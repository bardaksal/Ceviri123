package com.example.speech

import android.graphics.Bitmap
import com.example.capture.SpeechState
import com.example.capture.TranslationState
import com.example.subtitle.OcrEngine
import com.example.subtitle.OcrEngineResult
import com.example.subtitle.OcrOutput
import com.example.subtitle.OcrPipelineWorker
import com.example.translation.TranslationEngine
import com.example.translation.TranslationModelState
import com.example.translation.TranslationPipelineWorker
import com.example.translation.TranslationResult
import com.example.translation.TranslationStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SpeechPipelineTest {

    private class FakeSpeechEngine(
        @Volatile var downloaded: Boolean = true,
        @Volatile var loaded: Boolean = false
    ) : SpeechEngine {
        override val languageCode: String = "tr-TR"
        override val engineLabel: String = "Sherpa-ONNX Piper (tr-TR)"

        val speakCallCount = AtomicInteger(0)
        val stopCallCount = AtomicInteger(0)
        val releaseCallCount = AtomicInteger(0)
        val spokenTexts = mutableListOf<String>()

        var prepareHandler: suspend (((SpeechModelState, Int?) -> Unit)?) -> SpeechPreparationResult = { onProgress ->
            onProgress?.invoke(SpeechModelState.LOADING, null)
            downloaded = true
            loaded = true
            SpeechPreparationResult.Ready(preparationTimeMs = 140L)
        }

        var speakHandler: suspend (String, ((Long) -> Unit)?) -> SpeechSpeakResult = { text, onFirstAudio ->
            onFirstAudio?.invoke(160L)
            SpeechSpeakResult.Completed(
                SpeechPlaybackMetrics(
                    firstAudioLatencyMs = 160L,
                    synthesisDurationMs = 210L,
                    audioPlaybackDurationMs = 820L
                )
            )
        }

        var diagnosticHandler: suspend (String, ((SpeechModelState, Int?) -> Unit)?, ((Long) -> Unit)?) -> SpeechDiagnosticReport =
            { text, onProgress, onFirstAudio ->
                if (!loaded) {
                    prepareHandler(onProgress)
                }
                when (val speakOutcome = speakHandler(text, onFirstAudio)) {
                    is SpeechSpeakResult.Completed -> SpeechDiagnosticReport(
                        category = SpeechDiagnosticCategory.TTS_SUCCESS,
                        testPhrase = text,
                        modelName = TurkishTtsModelManager.MODEL_ID,
                        modelExists = true,
                        modelSizeBytes = 63_200_000L,
                        configExists = true,
                        nativeLibraryLoaded = true,
                        sampleRateHz = 22050,
                        sampleCount = 18_081L,
                        audioDurationMs = 820L,
                        synthesisDurationMs = speakOutcome.metrics.synthesisDurationMs,
                        firstAudioLatencyMs = speakOutcome.metrics.firstAudioLatencyMs,
                        audioPlaybackDurationMs = speakOutcome.metrics.audioPlaybackDurationMs,
                        synthesisMode = "generateWithCallback",
                        audioTrackInitialized = true,
                        audioTrackPlayed = true,
                        routedDeviceSummary = "routed=SPEAKER"
                    )
                    is SpeechSpeakResult.NotReady -> SpeechDiagnosticReport(
                        category = SpeechDiagnosticCategory.MODEL_LOAD_FAILURE,
                        testPhrase = text,
                        modelName = TurkishTtsModelManager.MODEL_ID,
                        detailMessage = speakOutcome.reason
                    )
                    is SpeechSpeakResult.Failure -> SpeechDiagnosticReport(
                        category = if (speakOutcome.errorType == SpeechErrorType.AUDIO_PLAYBACK_ERROR) {
                            SpeechDiagnosticCategory.AUDIOTRACK_FAILURE
                        } else {
                            SpeechDiagnosticCategory.SYNTHESIS_EMPTY_AUDIO
                        },
                        testPhrase = text,
                        modelName = TurkishTtsModelManager.MODEL_ID,
                        nativeLibraryLoaded = true,
                        detailMessage = speakOutcome.reason
                    )
                }
            }

        override suspend fun isModelDownloaded(): Boolean = downloaded

        override fun isEngineLoaded(): Boolean = loaded

        override suspend fun prepare(
            onStateProgress: ((SpeechModelState, Int?) -> Unit)?
        ): SpeechPreparationResult = prepareHandler(onStateProgress)

        override suspend fun speak(
            text: String,
            onFirstAudio: ((firstAudioLatencyMs: Long) -> Unit)?
        ): SpeechSpeakResult {
            speakCallCount.incrementAndGet()
            synchronized(spokenTexts) { spokenTexts.add(text) }
            return speakHandler(text, onFirstAudio)
        }

        override suspend fun runDiagnosticTest(
            text: String,
            onStateProgress: ((SpeechModelState, Int?) -> Unit)?,
            onFirstAudio: ((firstAudioLatencyMs: Long) -> Unit)?
        ): SpeechDiagnosticReport {
            speakCallCount.incrementAndGet()
            synchronized(spokenTexts) { spokenTexts.add(text) }
            return diagnosticHandler(text, onStateProgress, onFirstAudio)
        }

        override fun stop() {
            stopCallCount.incrementAndGet()
        }

        override fun unloadModel() {
            loaded = false
        }

        override fun release() {
            releaseCallCount.incrementAndGet()
            loaded = false
        }
    }

    private suspend fun waitUntil(
        timeoutMs: Long = 2_000L,
        predicate: () -> Boolean
    ) {
        var elapsed = 0L
        val step = 15L
        while (!predicate() && elapsed < timeoutMs) {
            delay(step)
            elapsed += step
        }
        assertTrue("Condition was not satisfied within ${timeoutMs}ms", predicate())
    }

    @Test
    fun `new active translation triggers Turkish TTS and records telemetry`() = runBlocking {
        val fakeSpeech = FakeSpeechEngine(downloaded = true, loaded = false)
        val states = mutableListOf<SpeechState>()
        val worker = SpeechPipelineWorker(
            speechEngine = fakeSpeech,
            onStateChanged = { s -> synchronized(states) { states.add(s) } }
        )

        assertEquals(SpeechModelState.READY, worker.prepareModelAndAwait())

        worker.onTranslationState(
            TranslationState(
                modelState = TranslationModelState.READY,
                status = TranslationStatus.ACTIVE,
                sourceText = "Come on, let's go.",
                translatedText = "Hadi, gidelim.",
                sourceFrameIndex = 10L
            )
        )

        waitUntil {
            synchronized(states) {
                states.lastOrNull()?.status == SpeechStatus.READY &&
                    states.lastOrNull()?.audioPlaybackDurationMs == 820L
            }
        }

        val latest = synchronized(states) { states.last() }
        assertEquals(1, fakeSpeech.speakCallCount.get())
        assertEquals("Hadi, gidelim.", latest.currentText)
        assertEquals(160L, latest.firstAudioLatencyMs)
        assertEquals(210L, latest.synthesisDurationMs)
        assertEquals(820L, latest.audioPlaybackDurationMs)
        assertEquals(140L, latest.preparationTimeMs)
        assertEquals("tr-TR", latest.languageCode)

        worker.release()
    }

    @Test
    fun `identical active translation does not re-trigger TTS`() = runBlocking {
        val fakeSpeech = FakeSpeechEngine(downloaded = true, loaded = true)
        val states = mutableListOf<SpeechState>()
        val worker = SpeechPipelineWorker(
            speechEngine = fakeSpeech,
            onStateChanged = { s -> synchronized(states) { states.add(s) } }
        )
        worker.prepareModelAndAwait()

        val translation = TranslationState(
            modelState = TranslationModelState.READY,
            status = TranslationStatus.ACTIVE,
            sourceText = "Wait here",
            translatedText = "Burada bekle",
            sourceFrameIndex = 20L
        )
        worker.onTranslationState(translation)
        waitUntil { fakeSpeech.speakCallCount.get() == 1 && synchronized(states) { states.last().status == SpeechStatus.READY } }

        // Repeated identical translation state updates should not replay the active utterance
        worker.onTranslationState(translation.copy(sourceFrameIndex = 21L))
        worker.onTranslationState(translation.copy(sourceFrameIndex = 22L))
        delay(50L)

        assertEquals(1, fakeSpeech.speakCallCount.get())

        worker.release()
    }

    @Test
    fun `new subtitle cancels active speech job and latest subtitle wins`() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val fakeSpeech = FakeSpeechEngine(downloaded = true, loaded = true).apply {
            speakHandler = { text, onFirstAudio ->
                if (text == "Çabuk ol!") {
                    onFirstAudio?.invoke(150L)
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                    SpeechSpeakResult.Completed(
                        SpeechPlaybackMetrics(150L, 900L, 1_500L)
                    )
                } else {
                    onFirstAudio?.invoke(120L)
                    SpeechSpeakResult.Completed(
                        SpeechPlaybackMetrics(120L, 180L, 450L)
                    )
                }
            }
        }

        val states = mutableListOf<SpeechState>()
        val worker = SpeechPipelineWorker(
            speechEngine = fakeSpeech,
            onStateChanged = { s -> synchronized(states) { states.add(s) } }
        )
        worker.prepareModelAndAwait()

        // Slow utterance A starts
        worker.onTranslationState(
            TranslationState(
                modelState = TranslationModelState.READY,
                status = TranslationStatus.ACTIVE,
                sourceText = "Hurry up!",
                translatedText = "Çabuk ol!",
                sourceFrameIndex = 30L
            )
        )
        firstStarted.await()
        val stopsBeforeSecond = fakeSpeech.stopCallCount.get()

        // Newer utterance B arrives while A is still speaking
        worker.onTranslationState(
            TranslationState(
                modelState = TranslationModelState.READY,
                status = TranslationStatus.ACTIVE,
                sourceText = "Let's go!",
                translatedText = "Hadi gidelim!",
                sourceFrameIndex = 31L
            )
        )

        // stop() must be invoked immediately for utterance A
        assertTrue(fakeSpeech.stopCallCount.get() > stopsBeforeSecond)

        waitUntil {
            synchronized(states) {
                states.lastOrNull()?.currentText == "Hadi gidelim!" &&
                    states.lastOrNull()?.status == SpeechStatus.READY &&
                    states.lastOrNull()?.audioPlaybackDurationMs == 450L
            }
        }

        // Release A late and verify A's completion metrics/text NEVER overwrite B
        releaseFirst.complete(Unit)
        delay(50L)

        val finalState = synchronized(states) { states.last() }
        assertEquals("Hadi gidelim!", finalState.currentText)
        assertEquals(31L, finalState.sourceFrameIndex)
        assertEquals(120L, finalState.firstAudioLatencyMs)
        assertEquals(180L, finalState.synthesisDurationMs)
        assertEquals(450L, finalState.audioPlaybackDurationMs)

        worker.release()
    }

    @Test
    fun `subtitle cleared stops speech immediately and allows re-speaking if subtitle returns`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()
        val fakeSpeech = FakeSpeechEngine(downloaded = true, loaded = true).apply {
            speakHandler = { text, onFirstAudio ->
                if (fakeSpeechCountIsFirst(text)) {
                    onFirstAudio?.invoke(140L)
                    started.complete(Unit)
                    hold.await()
                }
                onFirstAudio?.invoke(110L)
                SpeechSpeakResult.Completed(SpeechPlaybackMetrics(110L, 170L, 400L))
            }
        }
        val states = mutableListOf<SpeechState>()
        val worker = SpeechPipelineWorker(
            speechEngine = fakeSpeech,
            onStateChanged = { s -> synchronized(states) { states.add(s) } }
        )
        worker.prepareModelAndAwait()

        worker.onTranslationState(
            TranslationState(
                modelState = TranslationModelState.READY,
                status = TranslationStatus.ACTIVE,
                sourceText = "Run!",
                translatedText = "Koş!",
                sourceFrameIndex = 40L
            )
        )
        started.await()
        val stopsBeforeClear = fakeSpeech.stopCallCount.get()

        // Subtitle disappears
        worker.onTranslationState(
            TranslationState(
                modelState = TranslationModelState.READY,
                status = TranslationStatus.READY,
                sourceText = null,
                translatedText = null,
                sourceFrameIndex = 41L
            )
        )

        assertTrue(fakeSpeech.stopCallCount.get() > stopsBeforeClear)
        val afterClear = synchronized(states) { states.last() }
        assertNull(afterClear.currentText)
        assertEquals(SpeechStatus.READY, afterClear.status)

        hold.complete(Unit)
        delay(40L)

        // Same text reappearing after disappearance should speak again
        worker.onTranslationState(
            TranslationState(
                modelState = TranslationModelState.READY,
                status = TranslationStatus.ACTIVE,
                sourceText = "Run!",
                translatedText = "Koş!",
                sourceFrameIndex = 42L
            )
        )

        waitUntil { fakeSpeech.speakCallCount.get() == 2 }
        assertEquals(2, fakeSpeech.speakCallCount.get())

        worker.release()
    }

    private fun FakeSpeechEngine.fakeSpeechCountIsFirst(text: String): Boolean {
        return synchronized(spokenTexts) { spokenTexts.count { it == text } == 1 }
    }

    @Test
    fun `stop cancels pending TTS jobs and release closes engine`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val neverFinishes = CompletableDeferred<Unit>()
        val fakeSpeech = FakeSpeechEngine(downloaded = true, loaded = true).apply {
            speakHandler = { _, onFirstAudio ->
                onFirstAudio?.invoke(130L)
                started.complete(Unit)
                neverFinishes.await()
                SpeechSpeakResult.Completed(SpeechPlaybackMetrics(130L, 190L, 500L))
            }
        }

        val states = mutableListOf<SpeechState>()
        val worker = SpeechPipelineWorker(
            speechEngine = fakeSpeech,
            onStateChanged = { s -> synchronized(states) { states.add(s) } }
        )
        worker.prepareModelAndAwait()

        worker.onTranslationState(
            TranslationState(
                modelState = TranslationModelState.READY,
                status = TranslationStatus.ACTIVE,
                sourceText = "Stay down",
                translatedText = "Eğil!",
                sourceFrameIndex = 50L
            )
        )
        started.await()

        worker.stop()
        val stoppedState = synchronized(states) { states.last() }
        assertNull(stoppedState.currentText)
        assertEquals(SpeechStatus.READY, stoppedState.status)
        assertTrue(fakeSpeech.stopCallCount.get() >= 2)

        assertEquals(0, fakeSpeech.releaseCallCount.get())
        worker.release()
        assertEquals(1, fakeSpeech.releaseCallCount.get())
    }

    @Test
    fun `when TTS model is NOT_READY speaking is never entered and speak is not called`() = runBlocking {
        val fakeSpeech = FakeSpeechEngine(downloaded = false, loaded = false)
        val states = mutableListOf<SpeechState>()
        val worker = SpeechPipelineWorker(
            speechEngine = fakeSpeech,
            onStateChanged = { s -> synchronized(states) { states.add(s) } }
        )

        worker.refreshOrPreloadIfDownloaded()
        worker.onTranslationState(
            TranslationState(
                modelState = TranslationModelState.READY,
                status = TranslationStatus.ACTIVE,
                sourceText = "Watch out!",
                translatedText = "Dikkat et!",
                sourceFrameIndex = 60L
            )
        )
        delay(50L)

        val latest = synchronized(states) { states.last() }
        assertEquals(0, fakeSpeech.speakCallCount.get())
        assertEquals(SpeechModelState.MODEL_NOT_READY, latest.modelState)
        assertEquals(SpeechStatus.MODEL_NOT_READY, latest.status)
        assertTrue(synchronized(states) { states.none { it.status == SpeechStatus.SPEAKING } })
        assertTrue(synchronized(states) { states.none { it.modelState == SpeechModelState.READY } })

        worker.release()
    }

    @Test
    fun `TTS download and playback errors map to ERROR state without fake READY or SPEAKING`() = runBlocking {
        val fakeSpeech = FakeSpeechEngine(downloaded = false, loaded = false).apply {
            prepareHandler = {
                SpeechPreparationResult.Failure(
                    errorType = SpeechErrorType.MODEL_DOWNLOAD_FAILED,
                    reason = "Sunucuya ulaşılamadı"
                )
            }
        }
        val states = mutableListOf<SpeechState>()
        val worker = SpeechPipelineWorker(
            speechEngine = fakeSpeech,
            onStateChanged = { s -> synchronized(states) { states.add(s) } }
        )

        assertEquals(SpeechModelState.ERROR, worker.prepareModelAndAwait())
        val prepError = synchronized(states) { states.last() }
        assertEquals(SpeechStatus.ERROR, prepError.status)
        assertEquals(SpeechErrorType.MODEL_DOWNLOAD_FAILED, prepError.errorType)
        assertEquals("Sunucuya ulaşılamadı", prepError.errorMessage)

        // Now preparation succeeds, but AudioTrack playback fails
        fakeSpeech.prepareHandler = {
            fakeSpeech.downloaded = true
            fakeSpeech.loaded = true
            SpeechPreparationResult.Ready(95L)
        }
        fakeSpeech.speakHandler = { _, _ ->
            SpeechSpeakResult.Failure(
                errorType = SpeechErrorType.AUDIO_PLAYBACK_ERROR,
                reason = "AudioTrack başlatılamadı"
            )
        }
        assertEquals(SpeechModelState.READY, worker.prepareModelAndAwait())

        worker.onTranslationState(
            TranslationState(
                modelState = TranslationModelState.READY,
                status = TranslationStatus.ACTIVE,
                sourceText = "Move!",
                translatedText = "Kımılda!",
                sourceFrameIndex = 70L
            )
        )

        waitUntil {
            synchronized(states) { states.lastOrNull()?.status == SpeechStatus.ERROR }
        }

        val playbackError = synchronized(states) { states.last() }
        assertEquals(SpeechStatus.ERROR, playbackError.status)
        assertEquals(SpeechErrorType.AUDIO_PLAYBACK_ERROR, playbackError.errorType)
        assertEquals("AudioTrack başlatılamadı", playbackError.errorMessage)

        worker.release()
    }

    @Test
    fun `end-to-end OCR to Translation to Speech pipeline triggers and stops accurately`() = runBlocking {
        val fakeSpeech = FakeSpeechEngine(downloaded = true, loaded = true)
        val speechStates = mutableListOf<SpeechState>()
        val speechWorker = SpeechPipelineWorker(
            speechEngine = fakeSpeech,
            onStateChanged = { s -> synchronized(speechStates) { speechStates.add(s) } }
        )
        speechWorker.prepareModelAndAwait()

        val fakeTranslationEngine = object : TranslationEngine {
            override suspend fun isModelReady(): Boolean = true
            override suspend fun prepare(): TranslationModelState = TranslationModelState.READY
            override suspend fun translate(text: String): TranslationResult {
                return if (text == "Come on, let's go.") {
                    TranslationResult.Success("Hadi, gidelim.", 20L)
                } else {
                    TranslationResult.Success("TR:$text", 18L)
                }
            }
            override fun getLastError(): String? = null
            override fun close() {}
        }

        val translationWorker = TranslationPipelineWorker(
            translationEngine = fakeTranslationEngine,
            onStateChanged = { tState -> speechWorker.onTranslationState(tState) }
        )
        translationWorker.prepareModelAndAwait()

        val ocrOutputs = ArrayDeque(
            listOf(
                OcrEngineResult.Success(OcrOutput("Come on, let's go.", 0.95f, 24L)),
                OcrEngineResult.Success(OcrOutput("Come on, let's go.", 0.96f, 22L)),
                OcrEngineResult.Success(OcrOutput("   ", null, 11L))
            )
        )
        val ocrDone = AtomicInteger(0)
        val ocrWorker = OcrPipelineWorker(
            ocrEngine = object : OcrEngine {
                override suspend fun recognize(roiBitmap: Bitmap): OcrEngineResult = ocrOutputs.removeFirst()
                override fun close() {}
            },
            onSubtitleEvent = { ev -> translationWorker.onSubtitleEvent(ev) },
            onStateChanged = { s ->
                if (s.status == com.example.capture.OcrStatus.READY) ocrDone.incrementAndGet()
            }
        )
        ocrWorker.start()

        // 1. Subtitle arrives -> OCR -> EN->TR -> TR Speech
        ocrWorker.submitRoiBitmap(Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888), 1L, 100L)
        waitUntil {
            fakeSpeech.speakCallCount.get() == 1 && synchronized(speechStates) {
                speechStates.lastOrNull()?.currentText == "Hadi, gidelim."
            }
        }

        // 2. Same subtitle frame arrives -> dedup prevents redundant translation & speech
        ocrWorker.submitRoiBitmap(Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888), 2L, 300L)
        waitUntil { ocrDone.get() >= 2 }
        delay(40L)
        assertEquals(1, fakeSpeech.speakCallCount.get())

        // 3. Subtitle disappears -> translation clears -> speech stops & clears currentText
        ocrWorker.submitRoiBitmap(Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888), 3L, 500L)
        waitUntil {
            ocrDone.get() >= 3 && synchronized(speechStates) {
                speechStates.lastOrNull()?.currentText == null
            }
        }
        assertNull(synchronized(speechStates) { speechStates.last().currentText })

        ocrWorker.release()
        translationWorker.release()
        speechWorker.release()
    }

    @Test
    fun `diagnostic TTS test runs directly on SpeechEngine bypassing OCR and translation`() = runBlocking {
        val fakeSpeech = FakeSpeechEngine(downloaded = true, loaded = false)
        val states = mutableListOf<SpeechState>()
        val worker = SpeechPipelineWorker(
            speechEngine = fakeSpeech,
            onStateChanged = { s -> synchronized(states) { states.add(s) } }
        )

        worker.runDiagnosticTest()

        waitUntil {
            synchronized(states) {
                states.lastOrNull()?.diagnosticReport != null
            }
        }

        val finalState = synchronized(states) { states.last() }
        val report = finalState.diagnosticReport!!
        assertEquals(SpeechDiagnosticCategory.TTS_SUCCESS, report.category)
        assertEquals(SpeechEngine.DIAGNOSTIC_TEST_PHRASE, report.testPhrase)
        assertEquals(22050, report.sampleRateHz)
        assertEquals(18_081L, report.sampleCount)
        assertEquals(820L, report.audioDurationMs)
        assertEquals(SpeechStatus.READY, finalState.status)
        assertEquals(1, fakeSpeech.speakCallCount.get())

        worker.release()
    }

    @Test
    fun `diagnostic TTS test reports SYNTHESIS_EMPTY_AUDIO when zero samples are produced`() = runBlocking {
        val fakeSpeech = FakeSpeechEngine(downloaded = true, loaded = true).apply {
            speakHandler = { _, _ ->
                SpeechSpeakResult.Failure(
                    errorType = SpeechErrorType.SYNTHESIS_ERROR,
                    reason = "TTS motoru ses örneği üretemedi."
                )
            }
        }
        val states = mutableListOf<SpeechState>()
        val worker = SpeechPipelineWorker(
            speechEngine = fakeSpeech,
            onStateChanged = { s -> synchronized(states) { states.add(s) } }
        )

        worker.runDiagnosticTest("Merhaba, bu bir ses testidir.")

        waitUntil {
            synchronized(states) {
                states.lastOrNull()?.diagnosticReport != null
            }
        }

        val finalState = synchronized(states) { states.last() }
        assertEquals(SpeechStatus.ERROR, finalState.status)
        assertEquals(SpeechDiagnosticCategory.SYNTHESIS_EMPTY_AUDIO, finalState.diagnosticReport?.category)
        assertEquals("TTS motoru ses örneği üretemedi.", finalState.errorMessage)

        worker.release()
    }

    @Test
    fun `model manager inspects internal storage paths and validates espeak-ng-data files`() {
        val tempRoot = kotlin.io.path.createTempDirectory("subcast_tts_test").toFile()
        try {
            val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            val manager = TurkishTtsModelManager(context = context, baseFilesDir = tempRoot)

            val missingInspection = manager.inspectModelDirectory(logDetails = false)
            assertEquals(false, missingInspection.onnxExists)
            assertEquals(false, missingInspection.configExists)
            assertEquals(null, manager.resolveModelPathsOrNull(logInspection = false))

            val modelDir = java.io.File(tempRoot, "tts_models/${TurkishTtsModelManager.MODEL_ID}")
            val espeakDir = java.io.File(modelDir, "espeak-ng-data")
            val trLangDir = java.io.File(espeakDir, "lang/trk")
            trLangDir.mkdirs()
            java.io.File(modelDir, TurkishTtsModelManager.EXPECTED_ONNX_NAME).writeBytes(ByteArray(1_200_000))
            java.io.File(modelDir, "tokens.txt").writeText("_ 0\n^ 1\n$ 2\na 3\n")
            java.io.File(espeakDir, "phontab").writeBytes(byteArrayOf(1, 2, 3))
            java.io.File(espeakDir, "phondata").writeBytes(byteArrayOf(1, 2, 3))
            java.io.File(espeakDir, "phonindex").writeBytes(byteArrayOf(1, 2, 3))
            java.io.File(trLangDir, "tr").writeBytes(byteArrayOf(1, 2, 3))
            java.io.File(espeakDir, "tr_dict").writeBytes(byteArrayOf(1, 2, 3))
            java.io.File(modelDir, ".model_ready_v2").writeText("ok")

            val readyInspection = manager.inspectModelDirectory(logDetails = false)
            assertEquals(true, readyInspection.onnxExists)
            assertEquals(1_200_000L, readyInspection.onnxSizeBytes)
            assertEquals(true, readyInspection.configExists)
            assertEquals(true, readyInspection.espeakPhontabExists)
            assertEquals(true, readyInspection.espeakPhondataExists)
            assertEquals(true, readyInspection.espeakPhonindexExists)
            assertEquals(true, readyInspection.espeakTurkishLangExists)
            assertEquals(true, readyInspection.espeakTurkishDictExists)
            val resolved = manager.resolveModelPathsOrNull(logInspection = false)
            assertTrue(resolved != null)
            assertEquals(TurkishTtsModelManager.EXPECTED_ONNX_NAME, resolved?.onnxFile?.name)

            // Incomplete installation (e.g., missing tr_dict) invalidates ready marker cleanly
            java.io.File(espeakDir, "tr_dict").delete()
            assertEquals(null, manager.resolveModelPathsOrNull(logInspection = false))
            assertEquals(false, java.io.File(modelDir, ".model_ready_v2").exists())
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `validateVitsPaths verifies onnx, Piper tokens markers, and espeak-ng-data directory`() {
        val tempRoot = kotlin.io.path.createTempDirectory("subcast_vits_cfg").toFile()
        try {
            val espeakDir = java.io.File(tempRoot, "espeak-ng-data")
            val trLangDir = java.io.File(espeakDir, "lang/trk")
            trLangDir.mkdirs()
            val onnx = java.io.File(tempRoot, "tr_TR-fahrettin-medium.onnx").apply {
                writeBytes(ByteArray(1_200_000))
            }
            val tokens = java.io.File(tempRoot, "tokens.txt").apply {
                writeText("_ 0\n^ 1\n$ 2\na 14\n")
            }
            java.io.File(espeakDir, "phontab").writeBytes(byteArrayOf(1, 2))
            java.io.File(espeakDir, "phondata").writeBytes(byteArrayOf(1, 2))
            java.io.File(espeakDir, "phonindex").writeBytes(byteArrayOf(1, 2))
            java.io.File(trLangDir, "tr").writeBytes(byteArrayOf(1, 2))
            java.io.File(espeakDir, "tr_dict").writeBytes(byteArrayOf(1, 2))

            val valid = SpeechDiagnosticSupport.validateVitsPaths(
                onnxPath = onnx.absolutePath,
                tokensPath = tokens.absolutePath,
                espeakDataDirPath = espeakDir.absolutePath
            )
            assertTrue(valid.isValid)
            assertTrue(valid.missingOrInvalidItems.isEmpty())

            // Corrupt tokens.txt missing '^' and '$'
            tokens.writeText("a 14\n")
            val invalidTokens = SpeechDiagnosticSupport.validateVitsPaths(
                onnxPath = onnx.absolutePath,
                tokensPath = tokens.absolutePath,
                espeakDataDirPath = espeakDir.absolutePath
            )
            assertEquals(false, invalidTokens.isValid)
            assertTrue(invalidTokens.missingOrInvalidItems.any { it.contains("tokens.txt") })
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `analyzePcmSamples distinguishes null, empty, and valid non-zero audio waveform`() {
        val nullStats = SpeechDiagnosticSupport.analyzePcmSamples(null, 22050)
        assertEquals(true, nullStats.returnNull)
        assertEquals(0L, nullStats.sampleCount)
        assertEquals(false, nullStats.hasSamples)

        val emptyStats = SpeechDiagnosticSupport.analyzePcmSamples(FloatArray(0), 22050)
        assertEquals(false, emptyStats.returnNull)
        assertEquals(0L, emptyStats.sampleCount)
        assertEquals(false, emptyStats.hasSamples)

        val samples = FloatArray(22050) { idx ->
            if (idx % 2 == 0) 0.25f else -0.5f
        }
        val validStats = SpeechDiagnosticSupport.analyzePcmSamples(samples, 22050)
        assertEquals(false, validStats.returnNull)
        assertEquals(22050L, validStats.sampleCount)
        assertEquals(1000L, validStats.durationMs)
        assertEquals(-0.5f, validStats.minSample)
        assertEquals(0.25f, validStats.maxSample)
        assertTrue(validStats.absSum > 8000.0)
        assertEquals(true, validStats.hasSamples)
        assertEquals(true, validStats.hasNonZeroSignal)
    }

    @Test
    fun `buildSynthesisDiagnosticReport handles zero-sample failure and direct non-zero success distinctly`() {
        val readyInspection = ModelDirectoryInspection(
            storageLocation = "APP_INTERNAL_STORAGE",
            modelDirPath = "/data/user/0/app/files/tts_models/vits-piper-tr_TR-fahrettin-medium",
            modelDirExists = true,
            readyMarkerExists = true,
            onnxPath = "/data/user/0/app/files/tts_models/vits-piper-tr_TR-fahrettin-medium/tr_TR-fahrettin-medium.onnx",
            onnxExists = true,
            onnxSizeBytes = 63_200_000L,
            tokensPath = "/data/user/0/app/files/tts_models/vits-piper-tr_TR-fahrettin-medium/tokens.txt",
            tokensExists = true,
            tokensSizeBytes = 1024L,
            espeakDirPath = "/data/user/0/app/files/tts_models/vits-piper-tr_TR-fahrettin-medium/espeak-ng-data",
            espeakDirExists = true,
            espeakPhontabExists = true,
            espeakPhondataExists = true,
            espeakPhonindexExists = true,
            espeakTurkishLangExists = true,
            espeakTurkishDictExists = true,
            jsonConfigPath = null,
            jsonConfigExists = false,
            fileCount = 50,
            configExists = true
        )

        val emptyDirect = SpeechDiagnosticSupport.analyzePcmSamples(FloatArray(0), 22050)
        val emptyReport = SpeechDiagnosticSupport.buildSynthesisDiagnosticReport(
            phrase = SpeechEngine.DIAGNOSTIC_TEST_PHRASE,
            inspection = readyInspection,
            nativeLibraryLoaded = true,
            sampleRateHz = 22050,
            numSpeakers = 1,
            directAnalysis = emptyDirect,
            callbackInvocations = 0,
            callbackSampleCount = 0L,
            callbackReturnedSamples = 0L,
            synthesisDurationMs = 18L
        )
        assertEquals(SpeechDiagnosticCategory.SYNTHESIS_EMPTY_AUDIO, emptyReport.category)
        assertEquals("D1 MODEL_READY", emptyReport.d1ModelStatus)
        assertEquals("D2 NATIVE_READY", emptyReport.d2NativeStatus)
        assertEquals("D3 DIRECT_GENERATE_EMPTY", emptyReport.d3DirectGenerateStatus)
        assertEquals("D4 CALLBACK_EMPTY", emptyReport.d4CallbackStatus)
        assertEquals("D5 AUDIO_TRACK_NOT_TESTED", emptyReport.d5AudioTrackStatus)
        assertEquals(0L, emptyReport.sampleCount)

        val pcm = FloatArray(11025) { if (it % 2 == 0) 0.3f else -0.3f }
        val nonZeroDirect = SpeechDiagnosticSupport.analyzePcmSamples(pcm, 22050)
        val directOnlySuccess = SpeechDiagnosticSupport.buildSynthesisDiagnosticReport(
            phrase = SpeechEngine.DIAGNOSTIC_TEST_PHRASE,
            inspection = readyInspection,
            nativeLibraryLoaded = true,
            sampleRateHz = 22050,
            numSpeakers = 1,
            directAnalysis = nonZeroDirect,
            callbackInvocations = 0,
            callbackSampleCount = 0L,
            callbackReturnedSamples = 0L,
            synthesisDurationMs = 95L,
            firstAudioLatencyMs = 100L,
            audioPlaybackDurationMs = 500L,
            audioTrackInitialized = true,
            audioTrackPlayed = true,
            routedDeviceSummary = "routed=SPEAKER"
        )
        assertEquals(SpeechDiagnosticCategory.TTS_SUCCESS, directOnlySuccess.category)
        assertEquals("D3 DIRECT_GENERATE_OK", directOnlySuccess.d3DirectGenerateStatus)
        assertEquals("D4 CALLBACK_EMPTY", directOnlySuccess.d4CallbackStatus)
        assertEquals("D5 AUDIO_TRACK_OK", directOnlySuccess.d5AudioTrackStatus)
        assertEquals("direct_generate", directOnlySuccess.synthesisMode)
        assertEquals(11025L, directOnlySuccess.sampleCount)
        assertEquals(500L, directOnlySuccess.audioDurationMs)
    }
}

