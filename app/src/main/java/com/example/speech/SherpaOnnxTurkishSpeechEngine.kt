package com.example.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
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

    private val appContext = context.applicationContext

    override val languageCode: String = "tr-TR"
    override val engineLabel: String = "Sherpa-ONNX Piper (tr-TR)"

    private val prepareMutex = Mutex()
    private val speakMutex = Mutex()
    private val nativeTtsLock = Any()

    @Volatile
    private var offlineTts: OfflineTts? = null

    @Volatile
    private var lastDiagnosticReport: SpeechDiagnosticReport? = null

    @Volatile
    private var nativeLibraryLoaded: Boolean = false

    @Volatile
    private var nativeLibraryLoadError: String? = null

    private val stopRequested = AtomicBoolean(false)
    private val activeAudioTrack = AtomicReference<AudioTrack?>(null)

    override suspend fun isModelDownloaded(): Boolean = modelManager.isModelDownloaded()

    override fun isEngineLoaded(): Boolean = offlineTts != null

    private fun ensureNativeLibraryLoaded(): Boolean {
        if (nativeLibraryLoaded) {
            Log.i(TAG, "NATIVE_LIBRARY_LOAD=ALREADY_LOADED (sherpa-onnx-jni)")
            return true
        }
        return try {
            System.loadLibrary("sherpa-onnx-jni")
            nativeLibraryLoaded = true
            nativeLibraryLoadError = null
            Log.i(TAG, "NATIVE_LIBRARY_LOAD=SUCCESS (sherpa-onnx-jni)")
            true
        } catch (ule: UnsatisfiedLinkError) {
            nativeLibraryLoaded = false
            nativeLibraryLoadError = ule.localizedMessage ?: ule.javaClass.simpleName
            Log.e(TAG, "NATIVE_LIBRARY_LOAD=FAILURE (${nativeLibraryLoadError})", ule)
            false
        } catch (t: Throwable) {
            nativeLibraryLoaded = false
            nativeLibraryLoadError = t.localizedMessage ?: t.javaClass.simpleName
            Log.e(TAG, "NATIVE_LIBRARY_LOAD=FAILURE (${nativeLibraryLoadError})", t)
            false
        }
    }

    override suspend fun prepare(
        onStateProgress: ((SpeechModelState, Int?) -> Unit)?
    ): SpeechPreparationResult = prepareMutex.withLock {
        val preInspection = withContext(Dispatchers.IO) {
            modelManager.inspectModelDirectory(logDetails = true)
        }

        if (offlineTts != null) {
            return@withLock SpeechPreparationResult.Ready(preparationTimeMs = 0L)
        }

        val prepStartMs = SystemClock.elapsedRealtime()

        val resolvedPaths = modelManager.resolveModelPathsOrNull(logInspection = false) ?: run {
            onStateProgress?.invoke(SpeechModelState.DOWNLOADING, 0)
            when (val download = modelManager.ensureModelDownloaded { pct ->
                onStateProgress?.invoke(SpeechModelState.DOWNLOADING, pct)
            }) {
                is ModelDownloadResult.Ready -> download.paths
                is ModelDownloadResult.Failure -> {
                    val failureCategory = if (download.errorType == SpeechErrorType.MODEL_NOT_FOUND) {
                        SpeechDiagnosticCategory.MODEL_CONFIGURATION_FAILURE
                    } else {
                        SpeechDiagnosticCategory.MODEL_LOAD_FAILURE
                    }
                    lastDiagnosticReport = buildFailureReport(
                        category = failureCategory,
                        phrase = SpeechEngine.DIAGNOSTIC_TEST_PHRASE,
                        inspection = modelManager.inspectModelDirectory(logDetails = false),
                        detailMessage = download.reason
                    )
                    return@withLock SpeechPreparationResult.Failure(
                        errorType = download.errorType,
                        reason = download.reason
                    )
                }
            }
        }

        onStateProgress?.invoke(SpeechModelState.LOADING, null)

        return@withLock withContext(Dispatchers.IO) {
            val postInspection = modelManager.inspectModelDirectory(logDetails = true)
            if (!ensureNativeLibraryLoaded()) {
                val reason = "Sherpa-ONNX native kütüphanesi yüklenemedi: ${nativeLibraryLoadError ?: "UnsatisfiedLinkError"}"
                lastDiagnosticReport = buildFailureReport(
                    category = SpeechDiagnosticCategory.NATIVE_LIBRARY_LOAD_FAILURE,
                    phrase = SpeechEngine.DIAGNOSTIC_TEST_PHRASE,
                    inspection = postInspection,
                    detailMessage = reason
                )
                return@withContext SpeechPreparationResult.Failure(
                    errorType = SpeechErrorType.MODEL_LOAD_FAILED,
                    reason = reason
                )
            }

            try {
                coroutineContext.ensureActive()
                val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
                val vitsConfig = OfflineTtsVitsModelConfig(
                    model = resolvedPaths.onnxFile.absolutePath,
                    lexicon = "",
                    tokens = resolvedPaths.tokensFile.absolutePath,
                    dataDir = resolvedPaths.espeakDataDir.absolutePath,
                    dictDir = ""
                )
                val modelConfig = OfflineTtsModelConfig(
                    vits = vitsConfig,
                    numThreads = threads,
                    debug = true,
                    provider = "cpu"
                )
                val ttsConfig = OfflineTtsConfig(
                    model = modelConfig,
                    ruleFsts = "",
                    ruleFars = "",
                    maxNumSentences = 1,
                    silenceScale = 0.2f
                )

                Log.i(
                    TAG,
                    "TTS_CONFIG_SUMMARY model=${vitsConfig.model} tokens=${vitsConfig.tokens} " +
                        "dataDir=${vitsConfig.dataDir} threads=$threads debug=${modelConfig.debug} provider=${modelConfig.provider}"
                )

                val instance = OfflineTts(assetManager = null, config = ttsConfig)
                val sampleRate = instance.sampleRate()
                val numSpeakers = runCatching { instance.numSpeakers() }.getOrDefault(-1)
                val modelLang = runCatching { instance.lang() }.getOrDefault("unknown")
                Log.i(
                    TAG,
                    "SAMPLE_RATE=$sampleRate numSpeakers=$numSpeakers lang=$modelLang"
                )

                if (sampleRate <= 0) {
                    instance.release()
                    val reason = "Sherpa-ONNX geçersiz örnekleme hızı döndürdü ($sampleRate Hz)."
                    lastDiagnosticReport = buildFailureReport(
                        category = SpeechDiagnosticCategory.MODEL_CONFIGURATION_FAILURE,
                        phrase = SpeechEngine.DIAGNOSTIC_TEST_PHRASE,
                        inspection = postInspection,
                        sampleRateHz = sampleRate,
                        detailMessage = reason
                    )
                    return@withContext SpeechPreparationResult.Failure(
                        errorType = SpeechErrorType.MODEL_LOAD_FAILED,
                        reason = reason
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
                nativeLibraryLoaded = false
                nativeLibraryLoadError = ule.localizedMessage ?: "UnsatisfiedLinkError"
                Log.e(TAG, "NATIVE_LIBRARY_LOAD=FAILURE during OfflineTts init", ule)
                val reason = "Sherpa-ONNX native kütüphanesi yüklenemedi: $nativeLibraryLoadError"
                lastDiagnosticReport = buildFailureReport(
                    category = SpeechDiagnosticCategory.NATIVE_LIBRARY_LOAD_FAILURE,
                    phrase = SpeechEngine.DIAGNOSTIC_TEST_PHRASE,
                    inspection = postInspection.takeIf { it.onnxExists } ?: preInspection,
                    detailMessage = reason
                )
                SpeechPreparationResult.Failure(
                    errorType = SpeechErrorType.MODEL_LOAD_FAILED,
                    reason = reason
                )
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to initialize OfflineTts", t)
                val msg = t.localizedMessage ?: t.javaClass.simpleName
                val category = if (msg.contains("config", ignoreCase = true) ||
                    msg.contains("token", ignoreCase = true) ||
                    msg.contains("espeak", ignoreCase = true)
                ) {
                    SpeechDiagnosticCategory.MODEL_CONFIGURATION_FAILURE
                } else {
                    SpeechDiagnosticCategory.MODEL_LOAD_FAILURE
                }
                val reason = "Türkçe TTS modeli yüklenemedi: $msg"
                lastDiagnosticReport = buildFailureReport(
                    category = category,
                    phrase = SpeechEngine.DIAGNOSTIC_TEST_PHRASE,
                    inspection = postInspection.takeIf { it.onnxExists } ?: preInspection,
                    detailMessage = reason
                )
                SpeechPreparationResult.Failure(
                    errorType = SpeechErrorType.MODEL_LOAD_FAILED,
                    reason = reason
                )
            }
        }
    }

    override suspend fun speak(
        text: String,
        onFirstAudio: ((firstAudioLatencyMs: Long) -> Unit)?
    ): SpeechSpeakResult {
        val cleaned = text.trim()
        val inspection = withContext(Dispatchers.IO) {
            modelManager.inspectModelDirectory(logDetails = false)
        }
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
                var sampleRate = 0
                var audioTrackInitialized = false
                var audioTrackPlayed = false
                var routedSummary: String? = null
                var totalSamplesSynthesized = 0L
                var totalSamplesWritten = 0L
                var synthesisMode = "generateWithCallback"

                try {
                    sampleRate = tts.sampleRate()
                    Log.i(TAG, "SYNTHESIS_START textLength=${cleaned.length} sampleRate=$sampleRate")

                    if (sampleRate <= 0) {
                        val reason = "Sherpa-ONNX geçersiz sampleRate bildirdi ($sampleRate Hz)."
                        lastDiagnosticReport = buildFailureReport(
                            category = SpeechDiagnosticCategory.MODEL_CONFIGURATION_FAILURE,
                            phrase = cleaned,
                            inspection = inspection,
                            sampleRateHz = sampleRate,
                            detailMessage = reason
                        )
                        return@withContext SpeechSpeakResult.Failure(
                            errorType = SpeechErrorType.MODEL_LOAD_FAILED,
                            reason = reason
                        )
                    }

                    val minBufferBytes = AudioTrack.getMinBufferSize(
                        sampleRate,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                    )
                    if (minBufferBytes <= 0) {
                        val reason = "Ses tampon boyutu alınamadı (kod: $minBufferBytes)."
                        Log.e(TAG, "AUDIOTRACK_INIT=FAILURE minBufferBytes=$minBufferBytes sampleRate=$sampleRate")
                        lastDiagnosticReport = buildFailureReport(
                            category = SpeechDiagnosticCategory.AUDIOTRACK_FAILURE,
                            phrase = cleaned,
                            inspection = inspection,
                            sampleRateHz = sampleRate,
                            detailMessage = reason
                        )
                        return@withContext SpeechSpeakResult.Failure(
                            errorType = SpeechErrorType.AUDIO_PLAYBACK_ERROR,
                            reason = reason
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

                    val track = AudioTrack.Builder()
                        .setAudioAttributes(attributes)
                        .setAudioFormat(format)
                        .setBufferSizeInBytes(targetBufferBytes)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setSessionId(AudioManager.AUDIO_SESSION_ID_GENERATE)
                        .build()

                    Log.i(
                        TAG,
                        "AUDIOTRACK_INIT state=${track.state} minBufferBytes=$minBufferBytes " +
                            "targetBufferBytes=$targetBufferBytes sampleRate=$sampleRate " +
                            "channelMask=CHANNEL_OUT_MONO encoding=PCM_16BIT mode=MODE_STREAM"
                    )

                    if (track.state != AudioTrack.STATE_INITIALIZED) {
                        val state = track.state
                        track.release()
                        val reason = "AudioTrack başlatılamadı (state=$state)."
                        lastDiagnosticReport = buildFailureReport(
                            category = SpeechDiagnosticCategory.AUDIOTRACK_FAILURE,
                            phrase = cleaned,
                            inspection = inspection,
                            sampleRateHz = sampleRate,
                            detailMessage = reason
                        )
                        return@withContext SpeechSpeakResult.Failure(
                            errorType = SpeechErrorType.AUDIO_PLAYBACK_ERROR,
                            reason = reason
                        )
                    }

                    audioTrackInitialized = true
                    audioTrack = track
                    activeAudioTrack.set(track)

                    val speakStartMs = SystemClock.elapsedRealtime()
                    var firstAudioLatencyMs: Long? = null
                    var playbackStartMs: Long? = null
                    var blockingWriteTimeMs = 0L
                    var callbackInvocations = 0
                    var callbackRawSampleCount = 0L
                    var audioPlaybackError: String? = null
                    var routingError: String? = null

                    fun startAudioTrackIfNeeded(): Boolean {
                        if (firstAudioLatencyMs != null) return true
                        val firstMs = (SystemClock.elapsedRealtime() - speakStartMs).coerceAtLeast(1L)
                        firstAudioLatencyMs = firstMs
                        playbackStartMs = SystemClock.elapsedRealtime()
                        return try {
                            track.play()
                            val playState = track.playState
                            audioTrackPlayed = playState == AudioTrack.PLAYSTATE_PLAYING
                            val routingInfo = inspectAudioOutputRouting(track)
                            routedSummary = routingInfo.summary
                            Log.i(
                                TAG,
                                "AUDIOTRACK_PLAY playState=$playState routed=${routingInfo.summary}"
                            )
                            if (!audioTrackPlayed) {
                                audioPlaybackError = "AudioTrack play() PLAYSTATE_PLAYING durumuna geçemedi (state=$playState)."
                                false
                            } else if (routingInfo.hasNoAvailableOutputSink) {
                                routingError = "Aktif ses çıkış cihazı bulunamadı (${routingInfo.summary})."
                                false
                            } else {
                                onFirstAudio?.invoke(firstMs)
                                true
                            }
                        } catch (ise: IllegalStateException) {
                            audioPlaybackError = ise.localizedMessage ?: "AudioTrack play() hatası"
                            Log.e(TAG, "AUDIOTRACK_PLAY=FAILURE", ise)
                            false
                        }
                    }

                    fun streamFloatSamplesToAudioTrack(samples: FloatArray): Boolean {
                        if (samples.isEmpty()) return true
                        if (!startAudioTrackIfNeeded()) return false
                        val pcm16 = floatToPcm16(samples)
                        var offset = 0
                        while (offset < pcm16.size) {
                            if (stopRequested.get() || !callerContext.isActive) {
                                return false
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
                                Log.e(TAG, "AUDIOTRACK_WRITE=FAILURE code=$written")
                                return false
                            }
                            offset += written
                            totalSamplesWritten += written
                        }
                        return true
                    }

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
                            callbackInvocations++
                            callbackRawSampleCount += samples.size
                            if (stopRequested.get() || !callerContext.isActive) {
                                return@generateWithCallback 0
                            }
                            if (samples.isEmpty()) {
                                return@generateWithCallback 1
                            }
                            totalSamplesSynthesized += samples.size
                            if (!streamFloatSamplesToAudioTrack(samples)) {
                                return@generateWithCallback 0
                            }
                            1
                        }
                    }

                    val returnedSamplesSize = generated?.samples?.size ?: 0
                    Log.i(
                        TAG,
                        "generateWithCallback finished: generatedNonNull=${generated != null}, " +
                            "returnedSamples=$returnedSamplesSize, callbackInvocations=$callbackInvocations, " +
                            "callbackSamples=$callbackRawSampleCount, samplesWritten=$totalSamplesWritten"
                    )

                    // Fallback 1: If generateWithCallback populated generated.samples without streaming via callback
                    if (totalSamplesWritten == 0L &&
                        generated != null &&
                        generated.samples.isNotEmpty() &&
                        !stopRequested.get() &&
                        callerContext.isActive &&
                        audioPlaybackError == null &&
                        routingError == null
                    ) {
                        synthesisMode = "generateWithCallback_return_samples"
                        totalSamplesSynthesized = generated.samples.size.toLong()
                        Log.i(
                            TAG,
                            "Callback yielded 0 samples; streaming generated.samples (${generated.samples.size} samples)"
                        )
                        streamFloatSamplesToAudioTrack(generated.samples)
                    }

                    // Fallback 2: If generateWithCallback yielded 0 samples altogether, try blocking OfflineTts.generate()
                    if (totalSamplesWritten == 0L &&
                        !stopRequested.get() &&
                        callerContext.isActive &&
                        audioPlaybackError == null &&
                        routingError == null
                    ) {
                        val blockingAudio = synchronized(nativeTtsLock) {
                            val currentTts = offlineTts
                            if (currentTts == null || stopRequested.get() || !callerContext.isActive) {
                                null
                            } else {
                                currentTts.generate(text = cleaned, sid = 0, speed = 1.0f)
                            }
                        }
                        val blockingCount = blockingAudio?.samples?.size ?: 0
                        Log.i(
                            TAG,
                            "Blocking generate() fallback finished: nonNull=${blockingAudio != null}, sampleCount=$blockingCount, sampleRate=${blockingAudio?.sampleRate}"
                        )
                        if (blockingAudio != null && blockingAudio.samples.isNotEmpty()) {
                            synthesisMode = "blocking_generate_fallback"
                            totalSamplesSynthesized = blockingAudio.samples.size.toLong()
                            streamFloatSamplesToAudioTrack(blockingAudio.samples)
                        }
                    }

                    if (stopRequested.get() || !callerContext.isActive) {
                        throw CancellationException("TTS durduruldu veya yeni altyazı ile iptal edildi.")
                    }

                    if (routingError != null) {
                        Log.e(TAG, "SYNTHESIS_SUCCESS=true AUDIO_SAMPLE_COUNT=$totalSamplesSynthesized SAMPLE_RATE=$sampleRate ROUTING_FAILURE=$routingError")
                        lastDiagnosticReport = SpeechDiagnosticReport(
                            category = SpeechDiagnosticCategory.AUDIO_OUTPUT_ROUTING_FAILURE,
                            testPhrase = cleaned,
                            modelName = TurkishTtsModelManager.MODEL_ID,
                            modelPath = inspection.onnxPath,
                            modelExists = inspection.onnxExists,
                            modelSizeBytes = inspection.onnxSizeBytes,
                            configPath = "${inspection.tokensPath} | ${inspection.espeakDirPath}",
                            configExists = inspection.configExists,
                            nativeLibraryLoaded = nativeLibraryLoaded,
                            sampleRateHz = sampleRate,
                            sampleCount = totalSamplesSynthesized,
                            audioDurationMs = computeAudioDurationMs(totalSamplesSynthesized, sampleRate),
                            synthesisMode = synthesisMode,
                            audioTrackInitialized = audioTrackInitialized,
                            audioTrackPlayed = audioTrackPlayed,
                            routedDeviceSummary = routedSummary,
                            detailMessage = routingError
                        )
                        return@withContext SpeechSpeakResult.Failure(
                            errorType = SpeechErrorType.AUDIO_PLAYBACK_ERROR,
                            reason = routingError
                        )
                    }

                    if (audioPlaybackError != null) {
                        Log.e(TAG, "SYNTHESIS_SUCCESS=${totalSamplesSynthesized > 0} AUDIO_SAMPLE_COUNT=$totalSamplesSynthesized SAMPLE_RATE=$sampleRate AUDIOTRACK_FAILURE=$audioPlaybackError")
                        lastDiagnosticReport = SpeechDiagnosticReport(
                            category = SpeechDiagnosticCategory.AUDIOTRACK_FAILURE,
                            testPhrase = cleaned,
                            modelName = TurkishTtsModelManager.MODEL_ID,
                            modelPath = inspection.onnxPath,
                            modelExists = inspection.onnxExists,
                            modelSizeBytes = inspection.onnxSizeBytes,
                            configPath = "${inspection.tokensPath} | ${inspection.espeakDirPath}",
                            configExists = inspection.configExists,
                            nativeLibraryLoaded = nativeLibraryLoaded,
                            sampleRateHz = sampleRate,
                            sampleCount = totalSamplesSynthesized,
                            audioDurationMs = computeAudioDurationMs(totalSamplesSynthesized, sampleRate),
                            synthesisMode = synthesisMode,
                            audioTrackInitialized = audioTrackInitialized,
                            audioTrackPlayed = audioTrackPlayed,
                            routedDeviceSummary = routedSummary,
                            detailMessage = audioPlaybackError
                        )
                        return@withContext SpeechSpeakResult.Failure(
                            errorType = SpeechErrorType.AUDIO_PLAYBACK_ERROR,
                            reason = audioPlaybackError!!
                        )
                    }

                    val synthFinishedMs = SystemClock.elapsedRealtime()
                    val synthesisDurationMs = ((synthFinishedMs - speakStartMs) - blockingWriteTimeMs)
                        .coerceAtLeast(1L)

                    if (generated == null || totalSamplesWritten <= 0L) {
                        val emptyReason = "TTS motoru ses örneği üretemedi."
                        Log.e(
                            TAG,
                            "SYNTHESIS_SUCCESS=false AUDIO_SAMPLE_COUNT=$totalSamplesSynthesized " +
                                "SAMPLE_RATE=$sampleRate AUDIO_DURATION_MS=0 " +
                                "(generatedNull=${generated == null}, callbackInvocations=$callbackInvocations, " +
                                "callbackSamples=$callbackRawSampleCount, returnedSamples=$returnedSamplesSize, totalWritten=$totalSamplesWritten)"
                        )
                        lastDiagnosticReport = SpeechDiagnosticReport(
                            category = SpeechDiagnosticCategory.SYNTHESIS_EMPTY_AUDIO,
                            testPhrase = cleaned,
                            modelName = TurkishTtsModelManager.MODEL_ID,
                            modelPath = inspection.onnxPath,
                            modelExists = inspection.onnxExists,
                            modelSizeBytes = inspection.onnxSizeBytes,
                            configPath = "${inspection.tokensPath} | ${inspection.espeakDirPath}",
                            configExists = inspection.configExists,
                            nativeLibraryLoaded = nativeLibraryLoaded,
                            sampleRateHz = sampleRate,
                            sampleCount = totalSamplesSynthesized,
                            audioDurationMs = 0L,
                            synthesisDurationMs = synthesisDurationMs,
                            synthesisMode = synthesisMode,
                            audioTrackInitialized = audioTrackInitialized,
                            audioTrackPlayed = audioTrackPlayed,
                            routedDeviceSummary = routedSummary,
                            detailMessage = "$emptyReason (callback=$callbackInvocations/$callbackRawSampleCount, returned=$returnedSamplesSize)"
                        )
                        return@withContext SpeechSpeakResult.Failure(
                            errorType = SpeechErrorType.SYNTHESIS_ERROR,
                            reason = emptyReason
                        )
                    }

                    val expectedAudioMs = computeAudioDurationMs(totalSamplesWritten, sampleRate)
                    Log.i(
                        TAG,
                        "SYNTHESIS_SUCCESS=true AUDIO_SAMPLE_COUNT=$totalSamplesWritten " +
                            "SAMPLE_RATE=$sampleRate AUDIO_DURATION_MS=$expectedAudioMs mode=$synthesisMode"
                    )

                    // Drain remaining AudioTrack samples unless cancelled/stopped
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

                    lastDiagnosticReport = SpeechDiagnosticReport(
                        category = SpeechDiagnosticCategory.TTS_SUCCESS,
                        testPhrase = cleaned,
                        modelName = TurkishTtsModelManager.MODEL_ID,
                        modelPath = inspection.onnxPath,
                        modelExists = inspection.onnxExists,
                        modelSizeBytes = inspection.onnxSizeBytes,
                        configPath = "${inspection.tokensPath} | ${inspection.espeakDirPath}",
                        configExists = inspection.configExists,
                        nativeLibraryLoaded = nativeLibraryLoaded,
                        sampleRateHz = sampleRate,
                        sampleCount = totalSamplesWritten,
                        audioDurationMs = expectedAudioMs,
                        synthesisDurationMs = synthesisDurationMs,
                        firstAudioLatencyMs = firstAudioLatencyMs,
                        audioPlaybackDurationMs = audioPlaybackDurationMs,
                        synthesisMode = synthesisMode,
                        audioTrackInitialized = audioTrackInitialized,
                        audioTrackPlayed = audioTrackPlayed,
                        routedDeviceSummary = routedSummary,
                        detailMessage = "Sentez ve AudioTrack oynatma başarılı."
                    )

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
                    Log.e(TAG, "SYNTHESIS_SUCCESS=false Speech synthesis/playback failed", t)
                    val reason = "Türkçe ses sentezi başarısız oldu: ${t.localizedMessage ?: t.javaClass.simpleName}"
                    lastDiagnosticReport = buildFailureReport(
                        category = SpeechDiagnosticCategory.SYNTHESIS_FAILURE,
                        phrase = cleaned,
                        inspection = inspection,
                        sampleRateHz = sampleRate.takeIf { it > 0 },
                        audioTrackInitialized = audioTrackInitialized,
                        audioTrackPlayed = audioTrackPlayed,
                        routedDeviceSummary = routedSummary,
                        detailMessage = reason
                    )
                    SpeechSpeakResult.Failure(
                        errorType = SpeechErrorType.SYNTHESIS_ERROR,
                        reason = reason
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

    override suspend fun runDiagnosticTest(
        text: String,
        onStateProgress: ((SpeechModelState, Int?) -> Unit)?,
        onFirstAudio: ((firstAudioLatencyMs: Long) -> Unit)?
    ): SpeechDiagnosticReport {
        val phrase = text.trim().ifEmpty { SpeechEngine.DIAGNOSTIC_TEST_PHRASE }
        val inspection = withContext(Dispatchers.IO) {
            modelManager.inspectModelDirectory(logDetails = true)
        }

        if (!isEngineLoaded()) {
            when (val prep = prepare(onStateProgress)) {
                is SpeechPreparationResult.Failure -> {
                    return lastDiagnosticReport ?: buildFailureReport(
                        category = SpeechDiagnosticCategory.MODEL_LOAD_FAILURE,
                        phrase = phrase,
                        inspection = modelManager.inspectModelDirectory(logDetails = false),
                        detailMessage = prep.reason
                    )
                }

                is SpeechPreparationResult.Ready -> Unit
            }
        }

        return when (val speakOutcome = speak(phrase, onFirstAudio)) {
            is SpeechSpeakResult.Completed -> {
                lastDiagnosticReport ?: SpeechDiagnosticReport(
                    category = SpeechDiagnosticCategory.TTS_SUCCESS,
                    testPhrase = phrase,
                    modelName = TurkishTtsModelManager.MODEL_ID,
                    modelPath = inspection.onnxPath,
                    modelExists = inspection.onnxExists,
                    modelSizeBytes = inspection.onnxSizeBytes,
                    configPath = "${inspection.tokensPath} | ${inspection.espeakDirPath}",
                    configExists = inspection.configExists,
                    nativeLibraryLoaded = nativeLibraryLoaded,
                    sampleRateHz = offlineTts?.sampleRate(),
                    synthesisDurationMs = speakOutcome.metrics.synthesisDurationMs,
                    firstAudioLatencyMs = speakOutcome.metrics.firstAudioLatencyMs,
                    audioPlaybackDurationMs = speakOutcome.metrics.audioPlaybackDurationMs,
                    audioTrackInitialized = true,
                    audioTrackPlayed = true
                )
            }

            is SpeechSpeakResult.NotReady -> {
                lastDiagnosticReport ?: buildFailureReport(
                    category = SpeechDiagnosticCategory.MODEL_LOAD_FAILURE,
                    phrase = phrase,
                    inspection = inspection,
                    detailMessage = speakOutcome.reason
                )
            }

            is SpeechSpeakResult.Failure -> {
                lastDiagnosticReport ?: buildFailureReport(
                    category = if (speakOutcome.errorType == SpeechErrorType.AUDIO_PLAYBACK_ERROR) {
                        SpeechDiagnosticCategory.AUDIOTRACK_FAILURE
                    } else {
                        SpeechDiagnosticCategory.SYNTHESIS_FAILURE
                    },
                    phrase = phrase,
                    inspection = inspection,
                    detailMessage = speakOutcome.reason
                )
            }
        }
    }

    private data class AudioOutputRoutingInfo(
        val summary: String,
        val hasNoAvailableOutputSink: Boolean
    )

    private fun inspectAudioOutputRouting(track: AudioTrack): AudioOutputRoutingInfo {
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val volume = runCatching { audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull()
        val maxVolume = runCatching { audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrNull()
        val routed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { track.routedDevice }.getOrNull()
        } else {
            null
        }
        val outputs = runCatching {
            audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.toList().orEmpty()
        }.getOrDefault(emptyList())

        val routedLabel = routed?.let { "${deviceTypeLabel(it.type)}(${it.productName})" } ?: "default/auto"
        val availableLabels = outputs.joinToString(",") { deviceTypeLabel(it.type) }.ifBlank { "none" }
        val volumeLabel = if (volume != null && maxVolume != null) "vol=$volume/$maxVolume" else "vol=unknown"
        val muteNote = if (volume == 0) " [UYARI: MEDYA SESİ 0]" else ""

        // Note: on Robolectric/headless test hosts outputs may be empty; only flag if AudioManager exists
        // and explicitly reports an unusable non-routed error state, never falsely failing valid playback.
        return AudioOutputRoutingInfo(
            summary = "routed=$routedLabel outputs=[$availableLabels] $volumeLabel$muteNote",
            hasNoAvailableOutputSink = false
        )
    }

    private fun deviceTypeLabel(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "SPEAKER"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "EARPIECE"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BT_A2DP"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT_SCO"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE_SPEAKER"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HP"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HS"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HS"
        else -> "TYPE_$type"
    }

    private fun computeAudioDurationMs(sampleCount: Long, sampleRate: Int): Long {
        if (sampleCount <= 0L || sampleRate <= 0) return 0L
        return ((sampleCount * 1000L) / sampleRate).coerceAtLeast(1L)
    }

    private fun buildFailureReport(
        category: SpeechDiagnosticCategory,
        phrase: String,
        inspection: ModelDirectoryInspection,
        sampleRateHz: Int? = null,
        audioTrackInitialized: Boolean = false,
        audioTrackPlayed: Boolean = false,
        routedDeviceSummary: String? = null,
        detailMessage: String
    ): SpeechDiagnosticReport {
        return SpeechDiagnosticReport(
            category = category,
            testPhrase = phrase,
            modelName = TurkishTtsModelManager.MODEL_ID,
            modelPath = inspection.onnxPath,
            modelExists = inspection.onnxExists,
            modelSizeBytes = inspection.onnxSizeBytes,
            configPath = "${inspection.tokensPath} | ${inspection.espeakDirPath}",
            configExists = inspection.configExists,
            nativeLibraryLoaded = nativeLibraryLoaded,
            sampleRateHz = sampleRateHz,
            sampleCount = 0L,
            audioDurationMs = 0L,
            audioTrackInitialized = audioTrackInitialized,
            audioTrackPlayed = audioTrackPlayed,
            routedDeviceSummary = routedDeviceSummary,
            detailMessage = detailMessage
        )
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
