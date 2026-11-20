package com.example.translation

import android.os.SystemClock
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.MlKitException
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device English -> Turkish translator backed by Google ML Kit Translation.
 * Supports model download over either Wi-Fi or cellular data when not yet cached,
 * then executes all translations strictly on-device.
 */
class MlKitTranslationEngine : TranslationEngine {

    private val prepareMutex = Mutex()
    private val translatorLock = Any()

    @Volatile
    private var translator: Translator? = null

    @Volatile
    private var cachedModelReady: Boolean = false

    @Volatile
    private var lastErrorMessage: String? = null

    private val turkishModel: TranslateRemoteModel by lazy {
        TranslateRemoteModel.Builder(TranslateLanguage.TURKISH).build()
    }

    private fun getOrCreateTranslator(): Translator {
        synchronized(translatorLock) {
            return translator ?: Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(TranslateLanguage.ENGLISH)
                    .setTargetLanguage(TranslateLanguage.TURKISH)
                    .build()
            ).also { translator = it }
        }
    }

    override suspend fun isModelReady(): Boolean {
        if (cachedModelReady) return true
        return try {
            val downloaded = RemoteModelManager.getInstance()
                .isModelDownloaded(turkishModel)
                .awaitResult()
            cachedModelReady = downloaded
            downloaded
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            false
        }
    }

    override suspend fun prepare(): TranslationModelState = prepareMutex.withLock {
        if (isModelReady()) {
            lastErrorMessage = null
            return@withLock TranslationModelState.READY
        }

        return@withLock try {
            val client = getOrCreateTranslator()
            // Do not restrict to Wi-Fi; mobile data or Wi-Fi may be used for initial model download.
            val conditions = DownloadConditions.Builder().build()
            client.downloadModelIfNeeded(conditions).awaitResult()
            cachedModelReady = true
            lastErrorMessage = null
            TranslationModelState.READY
        } catch (ce: CancellationException) {
            throw ce
        } catch (mlEx: MlKitException) {
            cachedModelReady = false
            lastErrorMessage = mlEx.localizedMessage
                ?: "Çeviri modeli indirilemedi (kod: ${mlEx.errorCode})."
            TranslationModelState.ERROR
        } catch (t: Throwable) {
            cachedModelReady = false
            lastErrorMessage = t.localizedMessage ?: "Çeviri modeli hazırlanamadı."
            TranslationModelState.ERROR
        }
    }

    override suspend fun translate(text: String): TranslationResult {
        if (text.isBlank()) {
            return TranslationResult.Failure("Çevrilecek metin boş.")
        }

        if (!isModelReady()) {
            return TranslationResult.ModelNotReady(
                "English → Turkish çeviri modeli henüz cihazda hazır değil."
            )
        }

        val startNs = SystemClock.elapsedRealtimeNanos()
        return try {
            val client = getOrCreateTranslator()
            val translated = client.translate(text).awaitResult().trim()
            val processingMs = ((SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000L)
                .coerceAtLeast(0L)
            if (translated.isBlank()) {
                TranslationResult.Failure("Çeviri motoru boş sonuç döndürdü.")
            } else {
                TranslationResult.Success(
                    translatedText = translated,
                    processingTimeMs = processingMs
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (mlEx: MlKitException) {
            if (mlEx.errorCode == MlKitException.NOT_FOUND ||
                mlEx.errorCode == MlKitException.UNAVAILABLE
            ) {
                cachedModelReady = false
                TranslationResult.ModelNotReady(
                    mlEx.localizedMessage ?: "Çeviri modeli cihazda hazır değil."
                )
            } else {
                TranslationResult.Failure(
                    mlEx.localizedMessage ?: "Çeviri hatası (kod: ${mlEx.errorCode})."
                )
            }
        } catch (t: Throwable) {
            TranslationResult.Failure(
                t.localizedMessage ?: "Çeviri işlemi başarısız oldu."
            )
        }
    }

    override fun getLastError(): String? = lastErrorMessage

    override fun close() {
        synchronized(translatorLock) {
            translator?.close()
            translator = null
            cachedModelReady = false
        }
    }

    private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { result ->
            if (cont.isActive) cont.resume(result)
        }
        addOnFailureListener { exception ->
            if (cont.isActive) cont.resumeWithException(exception)
        }
        addOnCanceledListener {
            if (cont.isActive) cont.cancel()
        }
    }
}
