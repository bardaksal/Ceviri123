package com.example.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/**
 * Real on-device Turkish Neural TTS engine powered by Sherpa-ONNX + Piper VITS
 * (vits-piper-tr_TR-fahrettin-medium).
 *
 * - First preparation downloads the official MIT-licensed ONNX model archive once into app storage.
 * - All synthesis runs 100% on-device with streaming AudioTrack playback.
 * - Uses USAGE_MEDIA + CONTENT_TYPE_SPEECH so Android automatically routes to Bluetooth headsets
 *   when connected (A2DP/LE Audio) or device speaker otherwise, without disrupting game audio.
 */
class SherpaOnnxTurkishSpeechEngine(
    context: Context,
    private val modelManager: TurkishTtsModelManager = TurkishTtsModelManager(context)
) : SpeechEngine {

    override val languageCode: String = "tr-TR"
    override val engineLabel: String = "Sherpa-ONNX Piper (tr-TR)"

    private val prepareMutex = Mutex()
    private val speakMutex = Mutex()
    private val nativeTtsLock = Any()

    @Volatile
    private var offlineTts: OfflineTts? = null

    private val stopRequested = AtomicBoolean(false)
    private val activeAudioTrack = AtomicReference<AudioTrack?>(null)

    override suspend fun isModelDownloaded(): Boolean = modelManager.isModelDownloaded()

    override fun isEngineLoaded(): Boolean = offlineTts != null

    override suspend fun prepare(
        onStateProgress: ((SpeechModelState, Int?) -> Unit)?
    ): SpeechPreparationResult = prepareMutex.withLock {
        if (offlineTts != null) {
            return@withLock SpeechPreparationResult.Ready(preparationTimeMs = 0L)
        }

        val prepStartMs = SystemClock.elapsedRealtime()

        val resolvedPaths = modelManager.resolveModelPathsOrNull() ?: run {
            onStateProgress?.invoke(SpeechModelState.DOWNLOADING, 0)
            when (val download = modelManager.ensureModelDownloaded { pct ->
                onStateProgress?.invoke(SpeechModelState.DOWNLOADING, pct)
            }) {
                is ModelDownloadResult.Ready -> download.paths
                is ModelDownloadResult.Failure -> {
                    return@withLock SpeechPreparationResult.Failure(
                        errorType = download.errorType,
                        reason = download.reason
                    )
                }
            }
        }

        onStateProgress?.invoke(SpeechModelState.LOADING, null)

        return@withLock withContext(Dispatchers.IO) {
            try {
                coroutineContext.ensureActive()
                val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
                val vitsConfig = OfflineTtsVitsModelConfig(
                    model = resolvedPaths.onnxFile.absolutePath,
                    lexicon = "",
                    tokens = resolvedPaths.tokensFile.absolutePath,
                    dataDir = resolvedPaths.espeakDataDir.absolutePath
                )
                val modelConfig = OfflineTtsModelConfig(
                    vits = vitsConfig,
                    numThreads = threads,
                    debug = false,
                    provider = "cpu"
                )
                val ttsConfig = OfflineTtsConfig(
                    model = modelConfig,
                    maxNumSentences = 1,
                    silenceScale = 0.2f
                )

                val instance = OfflineTts(assetManager = null, config = ttsConfig)
                val sampleRate = instance.sampleRate()
                if (sampleRate <= 0) {
                    instance.release()
                    return@withContext SpeechPreparationResult.Failure(
                        errorType = SpeechErrorType.MODEL_LOAD_FAILED,
                        reason = "Sherpa-ONNX geçersiz örnekleme hızı döndürdü ($sampleRate Hz)."
                    )
                }

                synchronized(nativeTtsLock) {
                    offlineTts?.release()
                    offlineTts = instance
                }

                val elapsedMs = (SystemClock.elapsedRealtime() - prepStartMs).coerceAtLeast(1L)
                Log.i(TAG, "Sherpa-ONNX Turkish model loaded in ${elapsedMs}ms (${sampleRate}Hz)")
                SpeechPreparationResult.Ready(preparationTimeMs = elapsedMs)
            } catch (ce: CancellationException) {
                throw ce
            } catch (ule: UnsatisfiedLinkError) {
                Log.e(TAG, "Native Sherpa-ONNX library load failed", ule)
                SpeechPreparationResult.Failure(
                    errorType = SpeechErrorType.MODEL_LOAD_FAILED,
                    reason = "Sherpa-ONNX native kütüphanesi yüklenemedi: ${ule.localizedMessage ?: "UnsatisfiedLinkError"}"
                )
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to initialize OfflineTts", t)
                SpeechPreparationResult.Failure(
                    errorType = SpeechErrorType.MODEL_LOAD_FAILED,
                    reason = "Türkçe TTS modeli yüklenemedi: ${t.localizedMessage ?: t.javaClass.simpleName}"
                )
            }
        }
    }

    override suspend fun speak(
        text: String,
        onFirstAudio: ((firstAudioLatencyMs: Long) -> Unit)?
    ): SpeechSpeakResult {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) {
            return SpeechSpeakResult.Failure(
                errorType = SpeechErrorType.SYNTHESIS_ERROR,
                reason = "Seslendirilecek metin boş."
            )
        }

        if (offlineTts == null) {
            return SpeechSpeakResult.NotReady(
                errorType = SpeechErrorType.MODEL_NOT_FOUND,
                reason = "Türkçe TTS modeli henüz hazırlanmadı."
            )
        }

        return speakMutex.withLock {
            val callerContext = currentCoroutineContext()
            callerContext.ensureActive()

            val tts = synchronized(nativeTtsLock) { offlineTts }
                ?: return@withLock SpeechSpeakResult.NotReady(
                    errorType = SpeechErrorType.MODEL_NOT_FOUND,
                    reason = "Türkçe TTS motoru kapalı."
                )

            stopRequested.set(false)

            withContext(Dispatchers.IO) {
                var audioTrack: AudioTrack? = null
                try {
                    val sampleRate = tts.sampleRate().coerceAtLeast(16_000)
                    val minBufferBytes = AudioTrack.getMinBufferSize(
                        sampleRate,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                    )
                    if (minBufferBytes <= 0) {
                        return@withContext SpeechSpeakResult.Failure(
                            errorType = SpeechErrorType.AUDIO_PLAYBACK_ERROR,
                            reason = "Ses tampon boyutu alınamadı (kod: $minBufferBytes)."
                        )
                    }

                    // ~200 ms buffer for smooth A2DP Bluetooth + speaker streaming without excess lag
                    val targetBufferBytes = maxOf(minBufferBytes, (sampleRate / 5) * 2)
                    val attributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                    val format = AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()

                    val track = AudioTrack(
                        attributes,
                        format,
                        targetBufferBytes,
                        AudioTrack.MODE_STREAM,
                        AudioManager.AUDIO_SESSION_ID_GENERATE
                    )
                    if (track.state != AudioTrack.STATE_INITIALIZED) {
                        track.release()
                        return@withContext SpeechSpeakResult.Failure(
                            errorType = SpeechErrorType.AUDIO_PLAYBACK_ERROR,
                            reason = "AudioTrack başlatılamadı (state=${track.state})."
                        )
                    }

                    audioTrack = track
                    activeAudioTrack.set(track)

                    val speakStartMs = SystemClock.elapsedRealtime()
                    var firstAudioLatencyMs: Long? = null
                    var playbackStartMs: Long? = null
                    var blockingWriteTimeMs = 0L
                    var totalSamplesWritten = 0L
                    var audioPlaybackError: String? = null

                    val generated = synchronized(nativeTtsLock) {
                        val currentTts = offlineTts
                        if (currentTts == null || stopRequested.get() || !callerContext.isActive) {
                            return@synchronized null
                        }
                        currentTts.generateWithCallback(
                            text = cleaned,
                            sid = 0,
                            speed = 1.0f
                        ) { samples ->
                            if (stopRequested.get() || !callerContext.isActive) {
                                return@generateWithCallback 0
                            }
                            if (samples.isEmpty()) {
                                return@generateWithCallback 1
                            }

                            val pcm16 = floatToPcm16(samples)
                            if (firstAudioLatencyMs == null) {
                                val firstMs = (SystemClock.elapsedRealtime() - speakStartMs).coerceAtLeast(1L)
                                firstAudioLatencyMs = firstMs
                                playbackStartMs = SystemClock.elapsedRealtime()
                                try {
                                    track.play()
                                } catch (ise: IllegalStateException) {
                                    audioPlaybackError = ise.localizedMessage ?: "AudioTrack play() hatası"
                                    return@generateWithCallback 0
                                }
                                onFirstAudio?.invoke(firstMs)
                            }

                            var offset = 0
                            while (offset < pcm16.size) {
                                if (stopRequested.get() || !callerContext.isActive) {
                                    return@generateWithCallback 0
                                }
                                val chunkLen = minOf(PCM_WRITE_CHUNK_SAMPLES, pcm16.size - offset)
                                val writeStart = SystemClock.elapsedRealtime()
                                val written = track.write(
                                    pcm16,
                                    offset,
                                    chunkLen,
                                    AudioTrack.WRITE_BLOCKING
                                )
                                blockingWriteTimeMs += (SystemClock.elapsedRealtime() - writeStart).coerceAtLeast(0L)
                                if (written < 0) {
                                    audioPlaybackError = "AudioTrack write hatası (kod: $written)"
                                    return@generateWithCallback 0
                                }
                                offset += written
                                totalSamplesWritten += written
                            }
                            1
                        }
                    }

                    if (stopRequested.get() || !callerContext.isActive) {
                        throw CancellationException("TTS durduruldu veya yeni altyazı ile iptal edildi.")
                    }

                    if (audioPlaybackError != null) {
                        return@withContext SpeechSpeakResult.Failure(
                            errorType = SpeechErrorType.AUDIO_PLAYBACK_ERROR,
                            reason = audioPlaybackError!!
                        )
                    }

                    val synthFinishedMs = SystemClock.elapsedRealtime()
                    val synthesisDurationMs = ((synthFinishedMs - speakStartMs) - blockingWriteTimeMs)
                        .coerceAtLeast(1L)

                    if (generated == null || totalSamplesWritten <= 0L) {
                        return@withContext SpeechSpeakResult.Failure(
                            errorType = SpeechErrorType.SYNTHESIS_ERROR,
                            reason = "TTS motoru ses örneği üretemedi."
                        )
                    }

                    // Drain remaining AudioTrack samples unless cancelled/stopped
                    val expectedAudioMs = ((totalSamplesWritten * 1000L) / sampleRate).coerceAtLeast(0L)
                    val drainStartMs = SystemClock.elapsedRealtime()
                    val maxDrainWaitMs = expectedAudioMs + DRAIN_TIMEOUT_BUFFER_MS
                    while (!stopRequested.get() && callerContext.isActive) {
                        val head = runCatching { track.playbackHeadPosition.toLong() and 0xFFFFFFFFL }
                            .getOrDefault(totalSamplesWritten)
                        if (head >= totalSamplesWritten) break
                        if (SystemClock.elapsedRealtime() - drainStartMs > maxDrainWaitMs) break
                        delay(15L)
                    }

                    if (stopRequested.get() || !callerContext.isActive) {
                        throw CancellationException("TTS oynatma sırasında durduruldu.")
                    }

                    val playbackEndMs = SystemClock.elapsedRealtime()
                    val audioPlaybackDurationMs = playbackStartMs?.let { start ->
                        (playbackEndMs - start).coerceAtLeast(1L)
                    } ?: expectedAudioMs

                    SpeechSpeakResult.Completed(
                        SpeechPlaybackMetrics(
                            firstAudioLatencyMs = firstAudioLatencyMs,
                            synthesisDurationMs = synthesisDurationMs,
                            audioPlaybackDurationMs = audioPlaybackDurationMs
                        )
                    )
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    Log.e(TAG, "Speech synthesis/playback failed", t)
                    SpeechSpeakResult.Failure(
                        errorType = SpeechErrorType.SYNTHESIS_ERROR,
                        reason = "Türkçe ses sentezi başarısız oldu: ${t.localizedMessage ?: t.javaClass.simpleName}"
                    )
                } finally {
                    activeAudioTrack.compareAndSet(audioTrack, null)
                    audioTrack?.let { track ->
                        runCatching { track.pause() }
                        runCatching { track.flush() }
                        runCatching { track.stop() }
                        runCatching { track.release() }
                    }
                }
            }
        }
    }

    override fun stop() {
        stopRequested.set(true)
        activeAudioTrack.get()?.let { track ->
            runCatching { track.pause() }
            runCatching { track.flush() }
        }
    }

    override fun unloadModel() {
        stop()
        synchronized(nativeTtsLock) {
            runCatching { offlineTts?.release() }
            offlineTts = null
        }
    }

    override fun release() {
        unloadModel()
    }

    private fun floatToPcm16(samples: FloatArray): ShortArray {
        val out = ShortArray(samples.size)
        for (i in samples.indices) {
            val clamped = samples[i].coerceIn(-1.0f, 1.0f)
            out[i] = (clamped * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    companion object {
        private const val TAG = "SherpaOnnxSpeechEngine"
        private const val PCM_WRITE_CHUNK_SAMPLES = 1024
        private const val DRAIN_TIMEOUT_BUFFER_MS = 450L
    }
}
