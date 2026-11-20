package com.example.translation

import android.graphics.Bitmap
import com.example.capture.TranslationState
import com.example.subtitle.OcrEngine
import com.example.subtitle.OcrEngineResult
import com.example.subtitle.OcrOutput
import com.example.subtitle.OcrPipelineWorker
import com.example.subtitle.SubtitleRecognitionResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TranslationPipelineTest {

    private class FakeTranslationEngine(
        @Volatile var modelReady: Boolean = true
    ) : TranslationEngine {

        val translateCallCount = AtomicInteger(0)
        val prepareCallCount = AtomicInteger(0)
        @Volatile var closeCalled: Boolean = false
        @Volatile var errorDetail: String? = null

        var prepareHandler: suspend () -> TranslationModelState = {
            modelReady = true
            TranslationModelState.READY
        }

        var translateHandler: suspend (String) -> TranslationResult = { text ->
            TranslationResult.Success(
                translatedText = "TR:$text",
                processingTimeMs = 18L
            )
        }

        override suspend fun isModelReady(): Boolean = modelReady

        override suspend fun prepare(): TranslationModelState {
            prepareCallCount.incrementAndGet()
            return prepareHandler()
        }

        override suspend fun translate(text: String): TranslationResult {
            translateCallCount.incrementAndGet()
            return translateHandler(text)
        }

        override fun getLastError(): String? = errorDetail

        override fun close() {
            closeCalled = true
        }
    }

    private suspend fun waitUntil(
        timeoutMs: Long = 2_000L,
        predicate: () -> Boolean
    ) {
        val stepMs = 15L
        var elapsed = 0L
        while (!predicate() && elapsed < timeoutMs) {
            delay(stepMs)
            elapsed += stepMs
        }
        assertTrue("Condition was not met within ${timeoutMs}ms", predicate())
    }

    @Test
    fun `new OCR text triggers translation and updates TranslationState`() = runBlocking {
        val fakeEngine = FakeTranslationEngine(modelReady = true).apply {
            translateHandler = { text ->
                assertEquals("WE NEED TO GO", text)
                TranslationResult.Success("GITMEMIZ GEREK", processingTimeMs = 24L)
            }
        }
        val states = mutableListOf<TranslationState>()
        val worker = TranslationPipelineWorker(
            translationEngine = fakeEngine,
            onStateChanged = { state -> synchronized(states) { states.add(state) } }
        )

        assertEquals(TranslationModelState.READY, worker.prepareModelAndAwait())

        worker.onSubtitleRecognition(
            SubtitleRecognitionResult(
                text = "WE NEED TO GO",
                confidence = 0.94f,
                timestampElapsedMs = 1_000L,
                sourceFrameIndex = 12L,
                processingTimeMs = 30L,
                isNewText = true
            )
        )

        waitUntil {
            synchronized(states) {
                states.lastOrNull()?.status == TranslationStatus.ACTIVE &&
                    states.lastOrNull()?.translatedText == "GITMEMIZ GEREK"
            }
        }

        val latest = synchronized(states) { states.last() }
        assertEquals(1, fakeEngine.translateCallCount.get())
        assertEquals("WE NEED TO GO", latest.sourceText)
        assertEquals("GITMEMIZ GEREK", latest.translatedText)
        assertEquals(24L, latest.processingTimeMs)
        assertEquals(12L, latest.sourceFrameIndex)
        assertFalse(latest.isTranslating)
        assertNull(latest.errorMessage)

        worker.release()
    }

    @Test
    fun `repeated identical OCR text does not re-trigger translation`() = runBlocking {
        val fakeEngine = FakeTranslationEngine(modelReady = true)
        val translationStates = mutableListOf<TranslationState>()
        val translationWorker = TranslationPipelineWorker(
            translationEngine = fakeEngine,
            onStateChanged = { state -> synchronized(translationStates) { translationStates.add(state) } }
        )
        translationWorker.prepareModelAndAwait()

        val ocrOutputs = ArrayDeque(
            listOf(
                OcrEngineResult.Success(OcrOutput("HOLD ON", 0.9f, 20L)),
                OcrEngineResult.Success(OcrOutput("HOLD ON", 0.91f, 21L)),
                OcrEngineResult.Success(OcrOutput("HOLD ON", 0.92f, 19L))
            )
        )
        val ocrDoneCount = AtomicInteger(0)
        val ocrWorker = OcrPipelineWorker(
            ocrEngine = object : OcrEngine {
                override suspend fun recognize(roiBitmap: Bitmap): OcrEngineResult = ocrOutputs.removeFirst()
                override fun close() {}
            },
            onSubtitleEvent = { event -> translationWorker.onSubtitleEvent(event) },
            onStateChanged = { state ->
                if (state.status == com.example.capture.OcrStatus.READY) {
                    ocrDoneCount.incrementAndGet()
                }
            }
        )
        ocrWorker.start()

        for (i in 1..3) {
            ocrWorker.submitRoiBitmap(
                Bitmap.createBitmap(80, 32, Bitmap.Config.ARGB_8888),
                frameIndex = i.toLong(),
                capturedElapsedMs = i * 200L
            )
            waitUntil { ocrDoneCount.get() >= i }
        }

        waitUntil {
            synchronized(translationStates) {
                translationStates.lastOrNull()?.translatedText == "TR:HOLD ON"
            }
        }
        // Small settle delay to confirm no extra translation jobs were queued
        delay(50L)

        assertEquals(1, fakeEngine.translateCallCount.get())

        ocrWorker.release()
        translationWorker.release()
    }

    @Test
    fun `subtitle disappearance clears translatedText and reappearing subtitle translates again`() = runBlocking {
        val fakeEngine = FakeTranslationEngine(modelReady = true)
        val translationStates = mutableListOf<TranslationState>()
        val translationWorker = TranslationPipelineWorker(
            translationEngine = fakeEngine,
            onStateChanged = { state -> synchronized(translationStates) { translationStates.add(state) } }
        )
        translationWorker.prepareModelAndAwait()

        val ocrOutputs = ArrayDeque(
            listOf(
                OcrEngineResult.Success(OcrOutput("WE NEED TO GO", 0.9f, 20L)),
                OcrEngineResult.Success(OcrOutput("   ", null, 12L)),
                OcrEngineResult.Success(OcrOutput("WE NEED TO GO", 0.91f, 21L))
            )
        )
        val ocrDoneCount = AtomicInteger(0)
        val ocrWorker = OcrPipelineWorker(
            ocrEngine = object : OcrEngine {
                override suspend fun recognize(roiBitmap: Bitmap): OcrEngineResult = ocrOutputs.removeFirst()
                override fun close() {}
            },
            onSubtitleEvent = { event -> translationWorker.onSubtitleEvent(event) },
            onStateChanged = { state ->
                if (state.status == com.example.capture.OcrStatus.READY) {
                    ocrDoneCount.incrementAndGet()
                }
            }
        )
        ocrWorker.start()

        // 1. Subtitle appears -> translated
        ocrWorker.submitRoiBitmap(Bitmap.createBitmap(80, 32, Bitmap.Config.ARGB_8888), 1L, 200L)
        waitUntil {
            synchronized(translationStates) {
                translationStates.lastOrNull()?.translatedText == "TR:WE NEED TO GO"
            }
        }
        assertEquals(1, fakeEngine.translateCallCount.get())

        // 2. Subtitle disappears -> translatedText becomes null, model stays READY
        ocrWorker.submitRoiBitmap(Bitmap.createBitmap(80, 32, Bitmap.Config.ARGB_8888), 2L, 400L)
        waitUntil {
            ocrDoneCount.get() >= 2 && synchronized(translationStates) {
                val s = translationStates.last()
                s.translatedText == null && s.sourceText == null && s.status == TranslationStatus.READY
            }
        }
        val clearedState = synchronized(translationStates) { translationStates.last() }
        assertEquals(TranslationModelState.READY, clearedState.modelState)
        assertNull(clearedState.translatedText)

        // 3. Same subtitle reappears -> re-translated
        ocrWorker.submitRoiBitmap(Bitmap.createBitmap(80, 32, Bitmap.Config.ARGB_8888), 3L, 600L)
        waitUntil {
            fakeEngine.translateCallCount.get() == 2 && synchronized(translationStates) {
                translationStates.lastOrNull()?.translatedText == "TR:WE NEED TO GO"
            }
        }
        assertEquals(2, fakeEngine.translateCallCount.get())

        ocrWorker.release()
        translationWorker.release()
    }

    @Test
    fun `translation failure and prepare error are reported accurately`() = runBlocking {
        val fakeEngine = FakeTranslationEngine(modelReady = false).apply {
            errorDetail = "Ağ bağlantısı kurulamadı"
            prepareHandler = { TranslationModelState.ERROR }
        }
        val states = mutableListOf<TranslationState>()
        val worker = TranslationPipelineWorker(
            translationEngine = fakeEngine,
            onStateChanged = { state -> synchronized(states) { states.add(state) } }
        )

        val prepResult = worker.prepareModelAndAwait()
        assertEquals(TranslationModelState.ERROR, prepResult)
        val prepErrorState = synchronized(states) { states.last() }
        assertEquals(TranslationModelState.ERROR, prepErrorState.modelState)
        assertEquals(TranslationStatus.ERROR, prepErrorState.status)
        assertEquals("Ağ bağlantısı kurulamadı", prepErrorState.errorMessage)

        // Now prepare succeeds, but translate returns Failure
        fakeEngine.prepareHandler = {
            fakeEngine.modelReady = true
            TranslationModelState.READY
        }
        fakeEngine.translateHandler = {
            TranslationResult.Failure("On-device translator başarısız oldu")
        }
        assertEquals(TranslationModelState.READY, worker.prepareModelAndAwait())

        worker.onSubtitleRecognition(
            SubtitleRecognitionResult(
                text = "STAY HERE",
                confidence = 0.9f,
                timestampElapsedMs = 500L,
                isNewText = true,
                sourceFrameIndex = 4L,
                processingTimeMs = 20L
            )
        )

        waitUntil {
            synchronized(states) {
                states.lastOrNull()?.status == TranslationStatus.ERROR
            }
        }

        val failState = synchronized(states) { states.last() }
        assertEquals(TranslationStatus.ERROR, failState.status)
        assertNull(failState.translatedText)
        assertEquals("On-device translator başarısız oldu", failState.errorMessage)
        assertFalse(failState.isTranslating)

        worker.release()
    }

    @Test
    fun `when model is NOT_READY translate is not called and state never claims READY`() = runBlocking {
        val fakeEngine = FakeTranslationEngine(modelReady = false)
        val states = mutableListOf<TranslationState>()
        val worker = TranslationPipelineWorker(
            translationEngine = fakeEngine,
            onStateChanged = { state -> synchronized(states) { states.add(state) } }
        )

        worker.refreshModelState()
        worker.onSubtitleRecognition(
            SubtitleRecognitionResult(
                text = "DROP THE WEAPON",
                confidence = 0.93f,
                timestampElapsedMs = 700L,
                isNewText = true,
                sourceFrameIndex = 5L,
                processingTimeMs = 18L
            )
        )

        delay(40L)

        val latest = synchronized(states) { states.last() }
        assertEquals(0, fakeEngine.translateCallCount.get())
        assertEquals(TranslationModelState.NOT_READY, latest.modelState)
        assertEquals(TranslationStatus.MODEL_NOT_READY, latest.status)
        assertNull(latest.translatedText)
        assertTrue(synchronized(states) { states.none { it.modelState == TranslationModelState.READY } })

        worker.release()
    }

    @Test
    fun `model preparation transitions from NOT_READY through DOWNLOADING to READY`() = runBlocking {
        val downloadStarted = CompletableDeferred<Unit>()
        val releaseDownload = CompletableDeferred<Unit>()
        val fakeEngine = FakeTranslationEngine(modelReady = false).apply {
            prepareHandler = {
                downloadStarted.complete(Unit)
                releaseDownload.await()
                modelReady = true
                TranslationModelState.READY
            }
        }

        val observedModelStates = mutableListOf<TranslationModelState>()
        val worker = TranslationPipelineWorker(
            translationEngine = fakeEngine,
            onStateChanged = { state ->
                synchronized(observedModelStates) { observedModelStates.add(state.modelState) }
            }
        )

        worker.refreshModelState()
        val prepareDeferred = launch { worker.prepareModelAndAwait() }
        downloadStarted.await()

        val duringDownload = synchronized(observedModelStates) { observedModelStates.last() }
        assertEquals(TranslationModelState.DOWNLOADING, duringDownload)

        releaseDownload.complete(Unit)
        prepareDeferred.join()

        assertEquals(
            listOf(
                TranslationModelState.NOT_READY,
                TranslationModelState.DOWNLOADING,
                TranslationModelState.READY
            ),
            synchronized(observedModelStates) { observedModelStates.toList() }
        )

        // Calling prepareModelAndAwait again when already READY succeeds cleanly
        assertEquals(TranslationModelState.READY, worker.prepareModelAndAwait())

        worker.release()
    }

    @Test
    fun `latest subtitle wins when slow first translation completes after newer subtitle`() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()

        val fakeEngine = FakeTranslationEngine(modelReady = true).apply {
            translateHandler = { text ->
                if (text == "FIRST SLOW LINE") {
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                    TranslationResult.Success("ILK YAVAS SATIR", processingTimeMs = 450L)
                } else {
                    TranslationResult.Success("IKINCI HIZLI SATIR", processingTimeMs = 15L)
                }
            }
        }

        val states = mutableListOf<TranslationState>()
        val worker = TranslationPipelineWorker(
            translationEngine = fakeEngine,
            onStateChanged = { state -> synchronized(states) { states.add(state) } }
        )
        worker.prepareModelAndAwait()

        // Submit slow subtitle A
        worker.onSubtitleRecognition(
            SubtitleRecognitionResult(
                text = "FIRST SLOW LINE",
                confidence = 0.9f,
                timestampElapsedMs = 1_000L,
                isNewText = true,
                sourceFrameIndex = 1L,
                processingTimeMs = 20L
            )
        )
        firstStarted.await()

        // While A is suspended, newer subtitle B arrives
        worker.onSubtitleRecognition(
            SubtitleRecognitionResult(
                text = "SECOND FAST LINE",
                confidence = 0.92f,
                timestampElapsedMs = 1_100L,
                isNewText = true,
                sourceFrameIndex = 2L,
                processingTimeMs = 18L
            )
        )

        waitUntil {
            synchronized(states) {
                states.lastOrNull()?.translatedText == "IKINCI HIZLI SATIR"
            }
        }

        // Now release A even if it were somehow still finishing
        releaseFirst.complete(Unit)
        delay(50L)

        val finalState = synchronized(states) { states.last() }
        assertEquals("SECOND FAST LINE", finalState.sourceText)
        assertEquals("IKINCI HIZLI SATIR", finalState.translatedText)
        assertEquals(2L, finalState.sourceFrameIndex)
        assertTrue(synchronized(states) { states.none { it.translatedText == "ILK YAVAS SATIR" } })

        worker.release()
    }

    @Test
    fun `stop cancels pending translation and release invokes engine close`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val neverEnding = CompletableDeferred<Unit>()

        val fakeEngine = FakeTranslationEngine(modelReady = true).apply {
            translateHandler = {
                started.complete(Unit)
                neverEnding.await()
                TranslationResult.Success("SHOULD NOT APPEAR", 10L)
            }
        }

        val states = mutableListOf<TranslationState>()
        val worker = TranslationPipelineWorker(
            translationEngine = fakeEngine,
            onStateChanged = { state -> synchronized(states) { states.add(state) } }
        )
        worker.prepareModelAndAwait()

        worker.onSubtitleRecognition(
            SubtitleRecognitionResult(
                text = "RUN NOW",
                confidence = 0.9f,
                timestampElapsedMs = 1_000L,
                isNewText = true,
                sourceFrameIndex = 7L,
                processingTimeMs = 18L
            )
        )
        started.await()

        worker.stop()
        val afterStop = synchronized(states) { states.last() }
        assertFalse(afterStop.isTranslating)
        assertNull(afterStop.translatedText)
        assertNull(afterStop.sourceText)
        assertEquals(TranslationStatus.READY, afterStop.status)

        assertFalse(fakeEngine.closeCalled)
        worker.release()
        assertTrue(fakeEngine.closeCalled)
    }

    @Test
    fun `MlKitTranslationEngine close is idempotent and handles blank input cleanly`() = runBlocking {
        val engine = MlKitTranslationEngine()
        val blankResult = engine.translate("   ")
        assertTrue(blankResult is TranslationResult.Failure)

        engine.close()
        engine.close()
    }
}
