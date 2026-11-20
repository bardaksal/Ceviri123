package com.example.translation

enum class TranslationModelState {
    NOT_READY,
    DOWNLOADING,
    READY,
    ERROR
}

enum class TranslationStatus {
    MODEL_NOT_READY,
    DOWNLOADING,
    READY,
    TRANSLATING,
    ACTIVE,
    ERROR
}

sealed class TranslationResult {
    data class Success(
        val translatedText: String,
        val processingTimeMs: Long
    ) : TranslationResult()

    data class ModelNotReady(val reason: String) : TranslationResult()

    data class Failure(val reason: String) : TranslationResult()
}

interface TranslationEngine {
    suspend fun isModelReady(): Boolean
    suspend fun prepare(): TranslationModelState
    suspend fun translate(text: String): TranslationResult
    fun getLastError(): String?
    fun close()
}
