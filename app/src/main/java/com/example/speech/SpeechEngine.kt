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

enum class SpeechDiagnosticCategory(val codeLabel: String) {
    MODEL_LOAD_FAILURE("A) MODEL_LOAD_FAILURE"),
    NATIVE_LIBRARY_LOAD_FAILURE("B) NATIVE_LIBRARY_LOAD_FAILURE"),
    MODEL_CONFIGURATION_FAILURE("C) MODEL_CONFIGURATION_FAILURE"),
    SYNTHESIS_FAILURE("D) SYNTHESIS_FAILURE"),
    SYNTHESIS_EMPTY_AUDIO("E) SYNTHESIS_EMPTY_AUDIO"),
    AUDIOTRACK_FAILURE("F) AUDIOTRACK_FAILURE"),
    AUDIO_OUTPUT_ROUTING_FAILURE("G) AUDIO_OUTPUT_ROUTING_FAILURE"),
    TTS_SUCCESS("H) TTS_SUCCESS")
}

data class SpeechDiagnosticReport(
    val category: SpeechDiagnosticCategory,
    val testPhrase: String,
    val modelName: String,
    val modelPath: String? = null,
    val modelExists: Boolean = false,
    val modelSizeBytes: Long = 0L,
    val configPath: String? = null,
    val configExists: Boolean = false,
    val nativeLibraryLoaded: Boolean = false,
    val sampleRateHz: Int? = null,
    val sampleCount: Long = 0L,
    val audioDurationMs: Long? = null,
    val synthesisDurationMs: Long? = null,
    val firstAudioLatencyMs: Long? = null,
    val audioPlaybackDurationMs: Long? = null,
    val synthesisMode: String? = null,
    val audioTrackInitialized: Boolean = false,
    val audioTrackPlayed: Boolean = false,
    val routedDeviceSummary: String? = null,
    val detailMessage: String? = null
)

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

    suspend fun runDiagnosticTest(
        text: String = DIAGNOSTIC_TEST_PHRASE,
        onStateProgress: ((SpeechModelState, Int?) -> Unit)? = null,
        onFirstAudio: ((firstAudioLatencyMs: Long) -> Unit)? = null
    ): SpeechDiagnosticReport

    fun stop()
    fun unloadModel()
    fun release()

    companion object {
        const val DIAGNOSTIC_TEST_PHRASE = "Merhaba, bu bir ses testidir."
    }
}
