package com.example.translation

import android.util.Log
import com.example.capture.TranslationState
import com.example.subtitle.SubtitleEvent
import com.example.subtitle.SubtitleRecognitionResult
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
 * Decoupled latest-subtitle-wins translation worker.
 * Only translates when [SubtitleRecognitionResult.isNewText] is true,
 * cancels obsolete pending translations when newer subtitles arrive,
 * and uses a monotonic generation token so stale completions never overwrite newer state.
 */
class TranslationPipelineWorker(
    private val translationEngine: TranslationEngine,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val onStateChanged: (TranslationState) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val generation = AtomicLong(0L)

    private var activeTranslationJob: Job? = null
    private var prepareJob: Job? = null
    private var currentState: TranslationState = TranslationState()

    fun start() {
        scope.launch {
            refreshModelState()
        }
    }

    suspend fun refreshModelState() {
        if (currentState.modelState == TranslationModelState.DOWNLOADING) return
        val ready = translationEngine.isModelReady()
        val newModelState = if (ready) TranslationModelState.READY else TranslationModelState.NOT_READY
        val newStatus = if (ready) {
            if (currentState.translatedText != null) TranslationStatus.ACTIVE else TranslationStatus.READY
        } else {
            TranslationStatus.MODEL_NOT_READY
        }
        publishState(
            currentState.copy(
                modelState = newModelState,
                status = newStatus,
                errorMessage = if (ready) null else currentState.errorMessage
            )
        )
    }

    fun prepareModel() {
        if (prepareJob?.isActive == true || currentState.modelState == TranslationModelState.DOWNLOADING) {
            return
        }
        prepareJob = scope.launch {
            prepareModelAndAwait()
        }
    }

    suspend fun prepareModelAndAwait(): TranslationModelState {
        if (currentState.modelState == TranslationModelState.READY && translationEngine.isModelReady()) {
            return TranslationModelState.READY
        }

        publishState(
            currentState.copy(
                modelState = TranslationModelState.DOWNLOADING,
                status = TranslationStatus.DOWNLOADING,
                errorMessage = null
            )
        )
        Log.i(TAG, "model=DOWNLOADING (EN -> TR)")

        val outcome = try {
            translationEngine.prepare()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            TranslationModelState.ERROR
        }

        when (outcome) {
            TranslationModelState.READY -> {
                publishState(
                    currentState.copy(
                        modelState = TranslationModelState.READY,
                        status = if (currentState.translatedText != null) {
                            TranslationStatus.ACTIVE
                        } else {
                            TranslationStatus.READY
                        },
                        errorMessage = null
                    )
                )
                Log.i(TAG, "model=READY (EN -> TR)")
            }

            TranslationModelState.ERROR,
            TranslationModelState.NOT_READY -> {
                val errMsg = translationEngine.getLastError()
                    ?: "Çeviri modeli hazırlanamadı."
                publishState(
                    currentState.copy(
                        modelState = TranslationModelState.ERROR,
                        status = TranslationStatus.ERROR,
                        errorMessage = errMsg
                    )
                )
                Log.w(TAG, "model=ERROR detail=\"$errMsg\"")
            }

            TranslationModelState.DOWNLOADING -> Unit
        }
        return outcome
    }

    fun onSubtitleEvent(event: SubtitleEvent) {
        when (event) {
            is SubtitleEvent.Recognized -> onSubtitleRecognition(event.result)
            SubtitleEvent.Cleared -> onSubtitleCleared()
        }
    }

    fun onSubtitleRecognition(result: SubtitleRecognitionResult) {
        if (!result.isNewText) {
            return
        }

        val requestGen = generation.incrementAndGet()
        activeTranslationJob?.cancel()

        if (currentState.modelState != TranslationModelState.READY) {
            publishState(
                currentState.copy(
                    status = when (currentState.modelState) {
                        TranslationModelState.DOWNLOADING -> TranslationStatus.DOWNLOADING
                        TranslationModelState.ERROR -> TranslationStatus.ERROR
                        TranslationModelState.NOT_READY -> TranslationStatus.MODEL_NOT_READY
                        TranslationModelState.READY -> TranslationStatus.READY
                    },
                    sourceText = result.text,
                    translatedText = null,
                    sourceFrameIndex = result.sourceFrameIndex,
                    processingTimeMs = null,
                    isTranslating = false
                )
            )
            return
        }

        publishState(
            currentState.copy(
                status = TranslationStatus.TRANSLATING,
                sourceText = result.text,
                translatedText = null,
                sourceFrameIndex = result.sourceFrameIndex,
                processingTimeMs = null,
                isTranslating = true,
                errorMessage = null
            )
        )

        activeTranslationJob = scope.launch {
            val outcome = try {
                translationEngine.translate(result.text)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                TranslationResult.Failure(t.localizedMessage ?: "Çeviri hatası")
            }

            // Strict latest-subtitle-wins guard: ignore stale completion
            if (!isActive || generation.get() != requestGen) {
                return@launch
            }

            when (outcome) {
                is TranslationResult.Success -> {
                    publishState(
                        currentState.copy(
                            modelState = TranslationModelState.READY,
                            status = TranslationStatus.ACTIVE,
                            sourceText = result.text,
                            translatedText = outcome.translatedText,
                            sourceFrameIndex = result.sourceFrameIndex,
                            processingTimeMs = outcome.processingTimeMs,
                            isTranslating = false,
                            errorMessage = null
                        )
                    )
                    Log.i(
                        TAG,
                        "status=ACTIVE en=\"${result.text}\" tr=\"${outcome.translatedText}\" " +
                            "processingMs=${outcome.processingTimeMs} frameIndex=${result.sourceFrameIndex}"
                    )
                }

                is TranslationResult.ModelNotReady -> {
                    publishState(
                        currentState.copy(
                            modelState = TranslationModelState.NOT_READY,
                            status = TranslationStatus.MODEL_NOT_READY,
                            sourceText = result.text,
                            translatedText = null,
                            sourceFrameIndex = result.sourceFrameIndex,
                            processingTimeMs = null,
                            isTranslating = false,
                            errorMessage = outcome.reason
                        )
                    )
                }

                is TranslationResult.Failure -> {
                    publishState(
                        currentState.copy(
                            status = TranslationStatus.ERROR,
                            sourceText = result.text,
                            translatedText = null,
                            sourceFrameIndex = result.sourceFrameIndex,
                            processingTimeMs = null,
                            isTranslating = false,
                            errorMessage = outcome.reason
                        )
                    )
                    Log.w(TAG, "status=ERROR detail=\"${outcome.reason}\"")
                }
            }
        }
    }

    fun onSubtitleCleared() {
        generation.incrementAndGet()
        activeTranslationJob?.cancel()
        activeTranslationJob = null

        val nextStatus = when (currentState.modelState) {
            TranslationModelState.READY -> TranslationStatus.READY
            TranslationModelState.DOWNLOADING -> TranslationStatus.DOWNLOADING
            TranslationModelState.ERROR -> TranslationStatus.ERROR
            TranslationModelState.NOT_READY -> TranslationStatus.MODEL_NOT_READY
        }

        publishState(
            currentState.copy(
                status = nextStatus,
                sourceText = null,
                translatedText = null,
                processingTimeMs = null,
                isTranslating = false,
                errorMessage = if (currentState.modelState == TranslationModelState.ERROR) {
                    currentState.errorMessage
                } else {
                    null
                }
            )
        )
    }

    fun stop() {
        generation.incrementAndGet()
        activeTranslationJob?.cancel()
        activeTranslationJob = null
        prepareJob?.cancel()
        prepareJob = null

        val nextStatus = when (currentState.modelState) {
            TranslationModelState.READY -> TranslationStatus.READY
            TranslationModelState.ERROR -> TranslationStatus.ERROR
            else -> TranslationStatus.MODEL_NOT_READY
        }
        val nextModelState = if (currentState.modelState == TranslationModelState.DOWNLOADING) {
            TranslationModelState.NOT_READY
        } else {
            currentState.modelState
        }

        publishState(
            currentState.copy(
                modelState = nextModelState,
                status = nextStatus,
                sourceText = null,
                translatedText = null,
                processingTimeMs = null,
                isTranslating = false
            )
        )
    }

    fun release() {
        stop()
        scope.cancel()
        translationEngine.close()
    }

    @Synchronized
    private fun publishState(newState: TranslationState) {
        currentState = newState
        onStateChanged(newState)
    }

    companion object {
        private const val TAG = "SUBCAST_TRANSLATION"
    }
}
