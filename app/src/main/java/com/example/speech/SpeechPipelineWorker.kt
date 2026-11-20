package com.example.speech

import android.util.Log
import com.example.capture.SpeechState
import com.example.capture.TranslationState
import com.example.translation.TranslationStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Latest-subtitle-wins TTS worker decoupled from TranslationPipelineWorker.
 *
 * - Never queues stale subtitles: arriving text cancels active speech immediately via
 *   generation token + SpeechEngine.stop().
 * - Speaks only when a new non-blank Turkish translation becomes ACTIVE.
 * - Stops immediately when translatedText becomes null (subtitle disappeared).
 */
class SpeechPipelineWorker(
    private val speechEngine: SpeechEngine,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val onStateChanged: (SpeechState) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val generation = AtomicLong(0L)

    private var activeSpeakJob: Job? = null
    private var prepareJob: Job? = null
    private var lastSpokenText: String? = null
    private var currentState: SpeechState = SpeechState(
        languageCode = speechEngine.languageCode,
        engineLabel = speechEngine.engineLabel
    )

    fun start() {
        scope.launch {
            refreshOrPreloadIfDownloaded()
        }
    }

    suspend fun refreshOrPreloadIfDownloaded() {
        if (currentState.modelState == SpeechModelState.DOWNLOADING ||
            currentState.modelState == SpeechModelState.LOADING
        ) {
            return
        }
        val downloaded = speechEngine.isModelDownloaded()
        if (!downloaded) {
            updateState {
                it.copy(
                    modelState = SpeechModelState.MODEL_NOT_READY,
                    status = SpeechStatus.MODEL_NOT_READY,
                    downloadProgressPercent = null,
                    errorType = SpeechErrorType.NONE,
                    errorMessage = null
                )
            }
            return
        }

        if (speechEngine.isEngineLoaded()) {
            updateState {
                it.copy(
                    modelState = SpeechModelState.READY,
                    status = if (activeSpeakJob?.isActive == true) SpeechStatus.SPEAKING else SpeechStatus.READY,
                    downloadProgressPercent = null,
                    errorType = SpeechErrorType.NONE,
                    errorMessage = null
                )
            }
        } else {
            // Model files already exist on device; load native session offline without internet
            prepareModelAndAwait()
        }
    }

    fun prepareModel() {
        val modelState = currentStateSnapshot().modelState
        if (prepareJob?.isActive == true ||
            modelState == SpeechModelState.DOWNLOADING ||
            modelState == SpeechModelState.LOADING
        ) {
            return
        }
        prepareJob = scope.launch {
            prepareModelAndAwait()
        }
    }

    suspend fun prepareModelAndAwait(): SpeechModelState {
        if (currentStateSnapshot().modelState == SpeechModelState.READY && speechEngine.isEngineLoaded()) {
            return SpeechModelState.READY
        }

        updateState {
            it.copy(
                modelState = SpeechModelState.LOADING,
                status = SpeechStatus.PREPARING,
                downloadProgressPercent = null,
                errorType = SpeechErrorType.NONE,
                errorMessage = null
            )
        }

        val outcome = try {
            speechEngine.prepare { stage, pct ->
                updateState {
                    it.copy(
                        modelState = stage,
                        status = SpeechStatus.PREPARING,
                        downloadProgressPercent = pct,
                        errorType = SpeechErrorType.NONE,
                        errorMessage = null
                    )
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            SpeechPreparationResult.Failure(
                errorType = SpeechErrorType.MODEL_LOAD_FAILED,
                reason = t.localizedMessage ?: "TTS hazırlama hatası"
            )
        }

        return when (outcome) {
            is SpeechPreparationResult.Ready -> {
                updateState {
                    it.copy(
                        modelState = SpeechModelState.READY,
                        status = SpeechStatus.READY,
                        preparationTimeMs = outcome.preparationTimeMs,
                        downloadProgressPercent = null,
                        errorType = SpeechErrorType.NONE,
                        errorMessage = null
                    )
                }
                Log.i(TAG, "status=READY prepMs=${outcome.preparationTimeMs}")
                SpeechModelState.READY
            }

            is SpeechPreparationResult.Failure -> {
                updateState {
                    it.copy(
                        modelState = SpeechModelState.ERROR,
                        status = SpeechStatus.ERROR,
                        downloadProgressPercent = null,
                        errorType = outcome.errorType,
                        errorMessage = outcome.reason
                    )
                }
                Log.w(TAG, "status=ERROR type=${outcome.errorType} detail=\"${outcome.reason}\"")
                SpeechModelState.ERROR
            }
        }
    }

    fun onTranslationState(translationState: TranslationState) {
        val text = translationState.translatedText?.trim()
        if (text.isNullOrEmpty()) {
            val snapshot = currentStateSnapshot()
            if (lastSpokenText != null || activeSpeakJob?.isActive == true || snapshot.currentText != null) {
                onSubtitleCleared()
            }
            return
        }

        if (translationState.status != TranslationStatus.ACTIVE) {
            return
        }

        if (text == lastSpokenText) {
            return
        }

        speakText(text = text, sourceFrameIndex = translationState.sourceFrameIndex)
    }

    fun speakTestPhrase(phrase: String = TEST_PHRASE) {
        scope.launch {
            val snapshot = currentStateSnapshot()
            if (snapshot.modelState != SpeechModelState.READY || !speechEngine.isEngineLoaded()) {
                val modelState = prepareModelAndAwait()
                if (modelState != SpeechModelState.READY) {
                    return@launch
                }
            }
            lastSpokenText = null
            speakText(text = phrase, sourceFrameIndex = currentStateSnapshot().sourceFrameIndex)
        }
    }

    @Synchronized
    private fun speakText(text: String, sourceFrameIndex: Long) {
        val requestGen = generation.incrementAndGet()
        activeSpeakJob?.cancel()
        speechEngine.stop()

        if (currentState.modelState != SpeechModelState.READY) {
            lastSpokenText = null
            updateState {
                it.copy(
                    status = when (it.modelState) {
                        SpeechModelState.MODEL_NOT_READY -> SpeechStatus.MODEL_NOT_READY
                        SpeechModelState.DOWNLOADING, SpeechModelState.LOADING -> SpeechStatus.PREPARING
                        SpeechModelState.ERROR -> SpeechStatus.ERROR
                        SpeechModelState.READY -> SpeechStatus.READY
                    },
                    currentText = text,
                    sourceFrameIndex = sourceFrameIndex
                )
            }
            return
        }

        lastSpokenText = text
        updateState {
            it.copy(
                status = SpeechStatus.SPEAKING,
                currentText = text,
                sourceFrameIndex = sourceFrameIndex,
                firstAudioLatencyMs = null,
                synthesisDurationMs = null,
                audioPlaybackDurationMs = null,
                errorType = SpeechErrorType.NONE,
                errorMessage = null
            )
        }

        activeSpeakJob = scope.launch {
            val result = try {
                speechEngine.speak(
                    text = text,
                    onFirstAudio = { firstMs ->
                        if (isActive && generation.get() == requestGen) {
                            updateState {
                                if (generation.get() != requestGen) it
                                else it.copy(
                                    status = SpeechStatus.SPEAKING,
                                    firstAudioLatencyMs = firstMs
                                )
                            }
                        }
                    }
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                SpeechSpeakResult.Failure(
                    errorType = SpeechErrorType.SYNTHESIS_ERROR,
                    reason = t.localizedMessage ?: "TTS oynatma hatası"
                )
            }

            if (!isActive || generation.get() != requestGen) {
                return@launch
            }

            when (result) {
                is SpeechSpeakResult.Completed -> {
                    updateState {
                        if (generation.get() != requestGen) it
                        else it.copy(
                            modelState = SpeechModelState.READY,
                            status = SpeechStatus.READY,
                            currentText = text,
                            sourceFrameIndex = sourceFrameIndex,
                            firstAudioLatencyMs = result.metrics.firstAudioLatencyMs
                                ?: it.firstAudioLatencyMs,
                            synthesisDurationMs = result.metrics.synthesisDurationMs,
                            audioPlaybackDurationMs = result.metrics.audioPlaybackDurationMs,
                            errorType = SpeechErrorType.NONE,
                            errorMessage = null
                        )
                    }
                    Log.i(
                        TAG,
                        "status=READY text=\"$text\" firstAudioMs=${result.metrics.firstAudioLatencyMs} " +
                            "synthMs=${result.metrics.synthesisDurationMs} playMs=${result.metrics.audioPlaybackDurationMs}"
                    )
                }

                is SpeechSpeakResult.NotReady -> {
                    lastSpokenText = null
                    updateState {
                        if (generation.get() != requestGen) it
                        else it.copy(
                            modelState = SpeechModelState.MODEL_NOT_READY,
                            status = SpeechStatus.MODEL_NOT_READY,
                            errorType = result.errorType,
                            errorMessage = result.reason
                        )
                    }
                }

                is SpeechSpeakResult.Failure -> {
                    val fatalModelError = result.errorType == SpeechErrorType.MODEL_NOT_FOUND ||
                        result.errorType == SpeechErrorType.MODEL_LOAD_FAILED ||
                        result.errorType == SpeechErrorType.MODEL_DOWNLOAD_FAILED ||
                        result.errorType == SpeechErrorType.LANGUAGE_NOT_SUPPORTED

                    updateState {
                        if (generation.get() != requestGen) it
                        else it.copy(
                            modelState = if (fatalModelError) SpeechModelState.ERROR else it.modelState,
                            status = SpeechStatus.ERROR,
                            errorType = result.errorType,
                            errorMessage = result.reason
                        )
                    }
                    Log.w(TAG, "status=ERROR type=${result.errorType} detail=\"${result.reason}\"")
                }
            }
        }
    }

    @Synchronized
    fun onSubtitleCleared() {
        generation.incrementAndGet()
        activeSpeakJob?.cancel()
        activeSpeakJob = null
        lastSpokenText = null
        speechEngine.stop()

        updateState {
            val nextStatus = when (it.modelState) {
                SpeechModelState.READY -> SpeechStatus.READY
                SpeechModelState.DOWNLOADING, SpeechModelState.LOADING -> SpeechStatus.PREPARING
                SpeechModelState.ERROR -> SpeechStatus.ERROR
                SpeechModelState.MODEL_NOT_READY -> SpeechStatus.MODEL_NOT_READY
            }
            it.copy(
                status = nextStatus,
                currentText = null,
                errorType = if (it.modelState == SpeechModelState.ERROR) it.errorType else SpeechErrorType.NONE,
                errorMessage = if (it.modelState == SpeechModelState.ERROR) it.errorMessage else null
            )
        }
    }

    @Synchronized
    fun stop() {
        generation.incrementAndGet()
        activeSpeakJob?.cancel()
        activeSpeakJob = null
        prepareJob?.cancel()
        prepareJob = null
        lastSpokenText = null
        speechEngine.stop()

        updateState {
            val nextModelState = if (it.modelState == SpeechModelState.DOWNLOADING ||
                it.modelState == SpeechModelState.LOADING
            ) {
                SpeechModelState.MODEL_NOT_READY
            } else {
                it.modelState
            }
            val nextStatus = when (nextModelState) {
                SpeechModelState.READY -> SpeechStatus.READY
                SpeechModelState.ERROR -> SpeechStatus.ERROR
                else -> SpeechStatus.IDLE
            }
            it.copy(
                modelState = nextModelState,
                status = nextStatus,
                currentText = null,
                downloadProgressPercent = null
            )
        }
    }

    @Synchronized
    fun unloadEngineForHandoff() {
        generation.incrementAndGet()
        activeSpeakJob?.cancel()
        activeSpeakJob = null
        prepareJob?.cancel()
        prepareJob = null
        lastSpokenText = null
        speechEngine.unloadModel()
    }

    fun release() {
        stop()
        scope.cancel()
        speechEngine.release()
    }

    @Synchronized
    private fun currentStateSnapshot(): SpeechState = currentState

    @Synchronized
    private fun updateState(transform: (SpeechState) -> SpeechState) {
        val next = transform(currentState)
        currentState = next
        onStateChanged(next)
    }

    companion object {
        private const val TAG = "SUBCAST_SPEECH"
        const val TEST_PHRASE = "Merhaba, SubCast TR hazır."
    }
}
