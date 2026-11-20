package com.example.speech

enum class SpeechModelState {
    MODEL_NOT_READY,
    DOWNLOADING,
    LOADING,
    READY,
    ERROR
}

enum class SpeechStatus {
    IDLE,
    MODEL_NOT_READY,
    PREPARING,
    READY,
    SPEAKING,
    ERROR
}

enum class SpeechErrorType {
    NONE,
    MODEL_NOT_FOUND,
    MODEL_DOWNLOAD_FAILED,
    MODEL_LOAD_FAILED,
    LANGUAGE_NOT_SUPPORTED,
    SYNTHESIS_ERROR,
    AUDIO_PLAYBACK_ERROR
}

data class SpeechPlaybackMetrics(
    val firstAudioLatencyMs: Long?,
    val synthesisDurationMs: Long?,
    val audioPlaybackDurationMs: Long?
)

sealed class SpeechPreparationResult {
    data class Ready(val preparationTimeMs: Long) : SpeechPreparationResult()
    data class Failure(
        val errorType: SpeechErrorType,
        val reason: String
    ) : SpeechPreparationResult()
}

sealed class SpeechSpeakResult {
    data class Completed(val metrics: SpeechPlaybackMetrics) : SpeechSpeakResult()
    data class NotReady(
        val errorType: SpeechErrorType,
        val reason: String
    ) : SpeechSpeakResult()
    data class Failure(
        val errorType: SpeechErrorType,
        val reason: String
    ) : SpeechSpeakResult()
}

interface SpeechEngine {
    val languageCode: String
    val engineLabel: String

    suspend fun isModelDownloaded(): Boolean
    fun isEngineLoaded(): Boolean

    suspend fun prepare(
        onStateProgress: ((SpeechModelState, Int?) -> Unit)? = null
    ): SpeechPreparationResult

    suspend fun speak(
        text: String,
        onFirstAudio: ((firstAudioLatencyMs: Long) -> Unit)? = null
    ): SpeechSpeakResult

    fun stop()
    fun unloadModel()
    fun release()
}
