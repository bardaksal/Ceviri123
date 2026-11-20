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
import kotlin.coroutines.CoroutineContext
import kotlin.math.roundToInt

/**
 * Real on-device Turkish Neural TTS engine powered by Sherpa-ONNX (v1.13.8 with built-in espeak-ng)
 * + Piper VITS (vits-piper-tr_TR-fahrettin-medium).
 *
 * - First preparation downloads the official MIT-licensed ONNX model archive once into app storage.
 * - All synthesis runs 100% on-device with AudioTrack PCM playback.
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
    private var cachedNumSpeakers: Int? = null

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
            Log.e(TAG, "NATIVE_LIBRARY_LOAD=FAILURE ($nativeLibraryLoadError)", ule)
            false
        } catch (t: Throwable) {
            nativeLibraryLoaded = false
            nativeLibraryLoadError = t.localizedMessage ?: t.javaClass.simpleName
            Log.e(TAG, "NATIVE_LIBRARY_LOAD=FAILURE ($nativeLibraryLoadError)", t)
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
            val validation = SpeechDiagnosticSupport.validateVitsPaths(
                onnxPath = resolvedPaths.onnxFile.absolutePath,
                tokensPath = resolvedPaths.tokensFile.absolutePath,
                espeakDataDirPath = resolvedPaths.espeakDataDir.absolutePath
            )
            if (!validation.isValid) {
                val reason = "Model yapılandırma doğrulaması başarısız: ${validation.missingOrInvalidItems.joinToString("; ")}"
                Log.e(TAG, "MODEL_CONFIG_VALIDATION=FAILURE issues=${validation.missingOrInvalidItems}")
                lastDiagnosticReport = buildFailureReport(
                    category = SpeechDiagnosticCategory.MODEL_CONFIGURATION_FAILURE,
                    phrase = SpeechEngine.DIAGNOSTIC_TEST_PHRASE,
                    inspection = postInspection,
                    detailMessage = reason
                )
                return@withContext SpeechPreparationResult.Failure(
                    errorType = SpeechErrorType.MODEL_LOAD_FAILED,
                    reason = reason
                )
            }

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
                        "dataDir=${vitsConfig.dataDir} lexicon=${vitsConfig.lexicon} threads=$threads " +
                        "debug=${modelConfig.debug} provider=${modelConfig.provider}"
                )

                val instance = OfflineTts(assetManager = null, config = ttsConfig)
                val sampleRate = instance.sampleRate()
                val numSpeakers = runCatching { instance.numSpeakers() }.getOrNull()
                Log.i(TAG, "OFFLINE_TTS_INIT sampleRate=$sampleRate numSpeakers=$numSpeakers")

                if (sampleRate <= 0) {
                    instance.release()
                    val reason = "Sherpa-ONNX geçersiz örnekleme hızı döndürdü ($sampleRate Hz)."
                    lastDiagnosticReport = buildFailureReport(
                        category = SpeechDiagnosticCategory.MODEL_CONFIGURATION_FAILURE,
                        phrase = SpeechEngine.DIAGNOSTIC_TEST_PHRASE,
                        inspection = postInspection,
                        sampleRateHz = sampleRate,
                        numSpeakers = numSpeakers,
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
                    cachedNumSpeakers = numSpeakers
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
                var totalSamplesWritten = 0L
                var callbackInvocations = 0
                var callbackRawSampleCount = 0L
                var returnedSamplesSize = 0L
                var directSampleCount = 0L
                var synthesisMode = "generateWithCallback"

                try {
                    sampleRate = tts.sampleRate()
                    if (sampleRate <= 0) {
                        val reason = "Sherpa-ONNX geçersiz sampleRate bildirdi ($sampleRate Hz)."
                        lastDiagnosticReport = buildFailureReport(
                            category = SpeechDiagnosticCategory.MODEL_CONFIGURATION_FAILURE,
                            phrase = cleaned,
                            inspection = inspection,
                            sampleRateHz = sampleRate,
                            numSpeakers = cachedNumSpeakers,
                            detailMessage = reason
                        )
                        return@withContext SpeechSpeakResult.Failure(
                            errorType = SpeechErrorType.MODEL_LOAD_FAILED,
                            reason = reason
                        )
                    }

                    val trackSetup = createStreamAudioTrack(sampleRate)
                    if (trackSetup.error != null || trackSetup.track == null) {
                        val err = trackSetup.error ?: "AudioTrack başlatılamadı."
                        lastDiagnosticReport = buildFailureReport(
                            category = SpeechDiagnosticCategory.AUDIOTRACK_FAILURE,
                            phrase = cleaned,
                            inspection = inspection,
                            sampleRateHz = sampleRate,
                            numSpeakers = cachedNumSpeakers,
                            detailMessage = err
                        )
                        return@withContext SpeechSpeakResult.Failure(
                            errorType = SpeechErrorType.AUDIO_PLAYBACK_ERROR,
                            reason = err
                        )
                    }

                    val track = trackSetup.track
                    audioTrack = track
                    audioTrackInitialized = true
                    activeAudioTrack.set(track)

                    val speakStartMs = SystemClock.elapsedRealtime()
                    var firstAudioLatencyMs: Long? = null
                    var playbackStartMs: Long? = null
                    var blockingWriteTimeMs = 0L
                    var audioPlaybackError: String? = null
                    var routingError: String? = null

                    fun startTrackIfNeeded(): Boolean {
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
                            Log.i(TAG, "AUDIOTRACK_PLAY playState=$playState routed=${routingInfo.summary}")
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

                    fun streamChunk(samples: FloatArray): Boolean {
                        if (samples.isEmpty()) return true
                        if (!startTrackIfNeeded()) return false
                        val writeResult = writeFloatSamplesToTrack(
                            track = track,
                            samples = samples,
                            callerContext = callerContext
                        )
                        blockingWriteTimeMs += writeResult.writeDurationMs
                        totalSamplesWritten += writeResult.samplesWritten
                        if (writeResult.error != null) {
                            audioPlaybackError = writeResult.error
                            return false
                        }
                        return !stopRequested.get() && callerContext.isActive
                    }

                    val generated = synchronized(nativeTtsLock) {
                        val currentTts = offlineTts
                        if (currentTts == null || stopRequested.get() || !callerContext.isActive) {
                            null
                        } else {
                            currentTts.generateWithCallback(
                                text = cleaned,
                                sid = 0,
                                speed = 1.0f
                            ) { chunk ->
                                callbackInvocations++
                                callbackRawSampleCount += chunk.size
                                if (stopRequested.get() || !callerContext.isActive) {
                                    return@generateWithCallback 0
                                }
                                if (chunk.isEmpty()) {
                                    return@generateWithCallback 1
                                }
                                if (!streamChunk(chunk)) {
                                    return@generateWithCallback 0
                                }
                                1
                            }
                        }
                    }

                    returnedSamplesSize = generated?.samples?.size?.toLong() ?: 0L
                    Log.i(
                        TAG,
                        "SPEAK_CALLBACK callbackInvocations=$callbackInvocations callbackSamples=$callbackRawSampleCount " +
                            "callbackReturnedSamples=$returnedSamplesSize written=$totalSamplesWritten"
                    )

                    if (totalSamplesWritten == 0L &&
                        generated != null &&
                        generated.samples.isNotEmpty() &&
                        !stopRequested.get() &&
                        callerContext.isActive &&
                        audioPlaybackError == null &&
                        routingError == null
                    ) {
                        synthesisMode = "generateWithCallback_return"
                        streamChunk(generated.samples)
                    }

                    if (totalSamplesWritten == 0L &&
                        !stopRequested.get() &&
                        callerContext.isActive &&
                        audioPlaybackError == null &&
                        routingError == null
                    ) {
                        val directAudio = synchronized(nativeTtsLock) {
                            val currentTts = offlineTts
                            if (currentTts == null || stopRequested.get() || !callerContext.isActive) {
                                null
                            } else {
                                currentTts.generate(text = cleaned, sid = 0, speed = 1.0f)
                            }
                        }
                        directSampleCount = directAudio?.samples?.size?.toLong() ?: 0L
                        Log.i(
                            TAG,
                            "SPEAK_DIRECT_FALLBACK directReturnNull=${directAudio == null} " +
                                "directSamples=$directSampleCount sampleRate=${directAudio?.sampleRate}"
                        )
                        if (directAudio != null && directAudio.samples.isNotEmpty()) {
                            synthesisMode = "direct_generate"
                            streamChunk(directAudio.samples)
                        }
                    }

                    if (stopRequested.get() || !callerContext.isActive) {
                        throw CancellationException("TTS durduruldu veya yeni altyazı ile iptal edildi.")
                    }

                    val activeRoutingError = routingError
                    if (activeRoutingError != null) {
                        return@withContext SpeechSpeakResult.Failure(
                            errorType = SpeechErrorType.AUDIO_PLAYBACK_ERROR,
                            reason = activeRoutingError
                        )
                    }

                    val activePlaybackError = audioPlaybackError
                    if (activePlaybackError != null) {
                        return@withContext SpeechSpeakResult.Failure(
                            errorType = SpeechErrorType.AUDIO_PLAYBACK_ERROR,
                            reason = activePlaybackError
                        )
                    }

                    val synthFinishedMs = SystemClock.elapsedRealtime()
                    val synthesisDurationMs = ((synthFinishedMs - speakStartMs) - blockingWriteTimeMs)
                        .coerceAtLeast(1L)

                    if (totalSamplesWritten <= 0L) {
                        val reason = "Sherpa-ONNX 0 PCM örneği üretti (direct=$directSampleCount, " +
                            "callback=$callbackInvocations/$callbackRawSampleCount, ret=$returnedSamplesSize)."
                        Log.e(TAG, "SYNTHESIS_EMPTY $reason")
                        return@withContext SpeechSpeakResult.Failure(
                            errorType = SpeechErrorType.SYNTHESIS_ERROR,
                            reason = reason
                        )
                    }

                    val expectedAudioMs = SpeechDiagnosticSupport.computeDurationMs(totalSamplesWritten, sampleRate)
                    drainAudioTrack(track, totalSamplesWritten, expectedAudioMs, callerContext)

                    if (stopRequested.get() || !callerContext.isActive) {
                        throw CancellationException("TTS oynatma sırasında durduruldu.")
                    }

                    val playbackEndMs = SystemClock.elapsedRealtime()
                    val audioPlaybackDurationMs = playbackStartMs?.let { start ->
                        (playbackEndMs - start).coerceAtLeast(1L)
                    } ?: expectedAudioMs

                    Log.i(
                        TAG,
                        "SYNTHESIS_SUCCESS sampleCount=$totalSamplesWritten sampleRate=$sampleRate " +
                            "durationMs=$expectedAudioMs mode=$synthesisMode"
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

    /**
     * Runs an isolated diagnostic directly against Sherpa-ONNX:
     * D1 Model files -> D2 Native/OfflineTts -> D3 Direct tts.generate() BEFORE any AudioTrack ->
     * D4 Separate tts.generateWithCallback() -> D5 AudioTrack playback of generated FloatArray.
     */
    override suspend fun runDiagnosticTest(
        text: String,
        onStateProgress: ((SpeechModelState, Int?) -> Unit)?,
        onFirstAudio: ((firstAudioLatencyMs: Long) -> Unit)?
    ): SpeechDiagnosticReport {
        val phrase = text.trim().ifEmpty { SpeechEngine.DIAGNOSTIC_TEST_PHRASE }

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

        val inspection = withContext(Dispatchers.IO) {
            modelManager.inspectModelDirectory(logDetails = true)
        }

        return speakMutex.withLock {
            val callerContext = currentCoroutineContext()
            callerContext.ensureActive()
            stopRequested.set(false)

            val tts = synchronized(nativeTtsLock) { offlineTts }
                ?: return@withLock buildFailureReport(
                    category = SpeechDiagnosticCategory.MODEL_LOAD_FAILURE,
                    phrase = phrase,
                    inspection = inspection,
                    detailMessage = "OfflineTts örneği başlatılamadı."
                )

            withContext(Dispatchers.IO) {
                val sampleRate = runCatching { tts.sampleRate() }.getOrDefault(0)
                val numSpeakers = runCatching { tts.numSpeakers() }.getOrDefault(cachedNumSpeakers ?: 0)
                val synthStartMs = SystemClock.elapsedRealtime()

                // STEP D3: Direct tts.generate() completely separated from AudioTrack & generateWithCallback
                var directSamples: FloatArray? = null
                var directReturnSampleRate = sampleRate
                var directGenerateError: String? = null
                try {
                    val directAudio = synchronized(nativeTtsLock) {
                        offlineTts?.generate(text = phrase, sid = 0, speed = 1.0f)
                    }
                    directSamples = directAudio?.samples
                    if (directAudio != null && directAudio.sampleRate > 0) {
                        directReturnSampleRate = directAudio.sampleRate
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    directGenerateError = t.localizedMessage ?: t.javaClass.simpleName
                    Log.e(TAG, "DIRECT_GENERATE_EXCEPTION", t)
                }

                val effectiveRate = if (directReturnSampleRate > 0) directReturnSampleRate else sampleRate
                val directAnalysis = SpeechDiagnosticSupport.analyzePcmSamples(directSamples, effectiveRate)

                Log.i(TAG, "GENERATE_RETURN_NULL=${directAnalysis.returnNull}")
                Log.i(TAG, "GENERATE_SAMPLE_COUNT=${directAnalysis.sampleCount}")
                Log.i(TAG, "GENERATE_SAMPLE_RATE=$effectiveRate")
                Log.i(TAG, "GENERATE_DURATION_MS=${directAnalysis.durationMs}")
                Log.i(TAG, "GENERATE_MIN_SAMPLE=${directAnalysis.minSample}")
                Log.i(TAG, "GENERATE_MAX_SAMPLE=${directAnalysis.maxSample}")
                Log.i(TAG, "GENERATE_ABS_SUM=${directAnalysis.absSum}")
                Log.i(TAG, "DIRECT_GENERATE_SAMPLES=${directAnalysis.sampleCount}")
                Log.i(
                    TAG,
                    if (directAnalysis.hasSamples) {
                        "SYNTHESIS_SUCCESS directSamples=${directAnalysis.sampleCount}"
                    } else {
                        "SYNTHESIS_EMPTY directSamples=0"
                    }
                )

                // STEP D4: Separate tts.generateWithCallback() test in-memory (independent of AudioTrack)
                var callbackInvocations = 0
                var callbackSampleCount = 0L
                var callbackReturnedSamples = 0L
                var callbackGenerateError: String? = null
                val callbackChunks = mutableListOf<FloatArray>()
                try {
                    val cbAudio = synchronized(nativeTtsLock) {
                        offlineTts?.generateWithCallback(
                            text = phrase,
                            sid = 0,
                            speed = 1.0f
                        ) { chunk ->
                            callbackInvocations++
                            if (chunk.isNotEmpty()) {
                                callbackSampleCount += chunk.size
                                callbackChunks.add(chunk.copyOf())
                            }
                            if (stopRequested.get() || !callerContext.isActive) 0 else 1
                        }
                    }
                    callbackReturnedSamples = cbAudio?.samples?.size?.toLong() ?: 0L
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    callbackGenerateError = t.localizedMessage ?: t.javaClass.simpleName
                    Log.e(TAG, "CALLBACK_GENERATE_EXCEPTION", t)
                }

                val synthDurationMs = (SystemClock.elapsedRealtime() - synthStartMs).coerceAtLeast(1L)
                Log.i(TAG, "CALLBACK_INVOCATIONS=$callbackInvocations")
                Log.i(TAG, "CALLBACK_SAMPLES=$callbackSampleCount")
                Log.i(TAG, "CALLBACK_GENERATE_RETURNED_SAMPLES=$callbackReturnedSamples")

                val pcmToPlay: FloatArray? = when {
                    directAnalysis.hasSamples -> directSamples
                    callbackSampleCount > 0L -> mergeChunks(callbackChunks, callbackSampleCount.toInt())
                    else -> null
                }

                // If synthesis itself failed/returned 0 samples, report BEFORE opening AudioTrack
                if (pcmToPlay == null || pcmToPlay.isEmpty()) {
                    val emptyReport = SpeechDiagnosticSupport.buildSynthesisDiagnosticReport(
                        phrase = phrase,
                        inspection = inspection,
                        nativeLibraryLoaded = nativeLibraryLoaded,
                        sampleRateHz = effectiveRate,
                        numSpeakers = numSpeakers,
                        directAnalysis = directAnalysis,
                        callbackInvocations = callbackInvocations,
                        callbackSampleCount = callbackSampleCount,
                        callbackReturnedSamples = callbackReturnedSamples,
                        synthesisDurationMs = synthDurationMs,
                        directGenerateError = directGenerateError,
                        callbackGenerateError = callbackGenerateError
                    )
                    lastDiagnosticReport = emptyReport
                    return@withContext emptyReport
                }

                // STEP D5: Play verified FloatArray PCM samples via AudioTrack
                val playbackOutcome = playFloatArrayOnAudioTrack(
                    samples = pcmToPlay,
                    sampleRate = effectiveRate,
                    speakStartMs = synthStartMs,
                    callerContext = callerContext,
                    onFirstAudio = onFirstAudio
                )

                val report = SpeechDiagnosticSupport.buildSynthesisDiagnosticReport(
                    phrase = phrase,
                    inspection = inspection,
                    nativeLibraryLoaded = nativeLibraryLoaded,
                    sampleRateHz = effectiveRate,
                    numSpeakers = numSpeakers,
                    directAnalysis = directAnalysis,
                    callbackInvocations = callbackInvocations,
                    callbackSampleCount = callbackSampleCount,
                    callbackReturnedSamples = callbackReturnedSamples,
                    synthesisDurationMs = synthDurationMs,
                    firstAudioLatencyMs = playbackOutcome.firstAudioLatencyMs,
                    audioPlaybackDurationMs = playbackOutcome.audioPlaybackDurationMs,
                    audioTrackInitialized = playbackOutcome.audioTrackInitialized,
                    audioTrackPlayed = playbackOutcome.audioTrackPlayed,
                    routedDeviceSummary = playbackOutcome.routedDeviceSummary,
                    audioTrackError = playbackOutcome.audioTrackError,
                    routingError = playbackOutcome.routingError,
                    directGenerateError = directGenerateError,
                    callbackGenerateError = callbackGenerateError
                )
                lastDiagnosticReport = report
                report
            }
        }
    }

    private data class AudioTrackSetup(
        val track: AudioTrack?,
        val error: String? = null
    )

    private data class TrackWriteOutcome(
        val samplesWritten: Long,
        val writeDurationMs: Long,
        val error: String? = null
    )

    private data class PlaybackOutcome(
        val audioTrackInitialized: Boolean,
        val audioTrackPlayed: Boolean,
        val firstAudioLatencyMs: Long? = null,
        val audioPlaybackDurationMs: Long? = null,
        val routedDeviceSummary: String? = null,
        val audioTrackError: String? = null,
        val routingError: String? = null
    )

    private fun createStreamAudioTrack(sampleRate: Int): AudioTrackSetup {
        val minBufferBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferBytes <= 0) {
            val err = "AudioTrack.getMinBufferSize geçersiz kod döndürdü ($minBufferBytes, sampleRate=$sampleRate)."
            Log.e(TAG, "AUDIOTRACK_INIT=FAILURE $err")
            return AudioTrackSetup(track = null, error = err)
        }

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
                "targetBufferBytes=$targetBufferBytes sampleRate=$sampleRate"
        )

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            val state = track.state
            track.release()
            return AudioTrackSetup(track = null, error = "AudioTrack başlatılamadı (state=$state).")
        }
        return AudioTrackSetup(track = track)
    }

    private fun writeFloatSamplesToTrack(
        track: AudioTrack,
        samples: FloatArray,
        callerContext: CoroutineContext
    ): TrackWriteOutcome {
        val pcm16 = floatToPcm16(samples)
        var offset = 0
        var writtenTotal = 0L
        var durationTotalMs = 0L
        while (offset < pcm16.size) {
            if (stopRequested.get() || !callerContext.isActive) {
                return TrackWriteOutcome(writtenTotal, durationTotalMs, error = null)
            }
            val chunkLen = minOf(PCM_WRITE_CHUNK_SAMPLES, pcm16.size - offset)
            val t0 = SystemClock.elapsedRealtime()
            val written = track.write(pcm16, offset, chunkLen, AudioTrack.WRITE_BLOCKING)
            durationTotalMs += (SystemClock.elapsedRealtime() - t0).coerceAtLeast(0L)
            if (written < 0) {
                Log.e(TAG, "AUDIOTRACK_WRITE=FAILURE code=$written")
                return TrackWriteOutcome(writtenTotal, durationTotalMs, error = "AudioTrack write hatası (kod: $written)")
            }
            offset += written
            writtenTotal += written
        }
        return TrackWriteOutcome(writtenTotal, durationTotalMs, error = null)
    }

    private suspend fun drainAudioTrack(
        track: AudioTrack,
        totalSamplesWritten: Long,
        expectedAudioMs: Long,
        callerContext: CoroutineContext
    ) {
        val drainStartMs = SystemClock.elapsedRealtime()
        val maxDrainWaitMs = expectedAudioMs + DRAIN_TIMEOUT_BUFFER_MS
        while (!stopRequested.get() && callerContext.isActive) {
            val head = runCatching { track.playbackHeadPosition.toLong() and 0xFFFFFFFFL }
                .getOrDefault(totalSamplesWritten)
            if (head >= totalSamplesWritten) break
            if (SystemClock.elapsedRealtime() - drainStartMs > maxDrainWaitMs) break
            delay(15L)
        }
    }

    private suspend fun playFloatArrayOnAudioTrack(
        samples: FloatArray,
        sampleRate: Int,
        speakStartMs: Long,
        callerContext: CoroutineContext,
        onFirstAudio: ((Long) -> Unit)?
    ): PlaybackOutcome {
        val setup = createStreamAudioTrack(sampleRate)
        val track = setup.track ?: return PlaybackOutcome(
            audioTrackInitialized = false,
            audioTrackPlayed = false,
            audioTrackError = setup.error ?: "AudioTrack başlatılamadı."
        )

        activeAudioTrack.set(track)
        var played = false
        var routedSummary: String? = null
        try {
            val firstMs = (SystemClock.elapsedRealtime() - speakStartMs).coerceAtLeast(1L)
            val playbackStartMs = SystemClock.elapsedRealtime()
            track.play()
            val playState = track.playState
            played = playState == AudioTrack.PLAYSTATE_PLAYING
            val routingInfo = inspectAudioOutputRouting(track)
            routedSummary = routingInfo.summary
            Log.i(TAG, "AUDIOTRACK_PLAY playState=$playState routed=${routingInfo.summary}")

            if (!played) {
                return PlaybackOutcome(
                    audioTrackInitialized = true,
                    audioTrackPlayed = false,
                    routedDeviceSummary = routedSummary,
                    audioTrackError = "AudioTrack play() PLAYSTATE_PLAYING durumuna geçemedi (state=$playState)."
                )
            }
            if (routingInfo.hasNoAvailableOutputSink) {
                return PlaybackOutcome(
                    audioTrackInitialized = true,
                    audioTrackPlayed = true,
                    routedDeviceSummary = routedSummary,
                    routingError = "Aktif ses çıkış cihazı bulunamadı (${routingInfo.summary})."
                )
            }

            onFirstAudio?.invoke(firstMs)
            val writeOutcome = writeFloatSamplesToTrack(track, samples, callerContext)
            if (writeOutcome.error != null) {
                return PlaybackOutcome(
                    audioTrackInitialized = true,
                    audioTrackPlayed = played,
                    firstAudioLatencyMs = firstMs,
                    routedDeviceSummary = routedSummary,
                    audioTrackError = writeOutcome.error
                )
            }

            val expectedAudioMs = SpeechDiagnosticSupport.computeDurationMs(samples.size.toLong(), sampleRate)
            drainAudioTrack(track, writeOutcome.samplesWritten, expectedAudioMs, callerContext)
            val playbackDurationMs = (SystemClock.elapsedRealtime() - playbackStartMs).coerceAtLeast(1L)

            return PlaybackOutcome(
                audioTrackInitialized = true,
                audioTrackPlayed = true,
                firstAudioLatencyMs = firstMs,
                audioPlaybackDurationMs = playbackDurationMs,
                routedDeviceSummary = routedSummary
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.e(TAG, "AUDIO_TRACK_FAIL", t)
            return PlaybackOutcome(
                audioTrackInitialized = true,
                audioTrackPlayed = played,
                routedDeviceSummary = routedSummary,
                audioTrackError = t.localizedMessage ?: "AudioTrack oynatma hatası"
            )
        } finally {
            activeAudioTrack.compareAndSet(track, null)
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    private fun mergeChunks(chunks: List<FloatArray>, totalSize: Int): FloatArray {
        val merged = FloatArray(totalSize)
        var offset = 0
        for (chunk in chunks) {
            System.arraycopy(chunk, 0, merged, offset, chunk.size)
            offset += chunk.size
        }
        return merged
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

    private fun buildFailureReport(
        category: SpeechDiagnosticCategory,
        phrase: String,
        inspection: ModelDirectoryInspection,
        sampleRateHz: Int? = null,
        numSpeakers: Int? = null,
        detailMessage: String
    ): SpeechDiagnosticReport {
        val d1 = when {
            !inspection.onnxExists -> "D1 MODEL_MISSING"
            !inspection.configExists -> "D1 CONFIG_INVALID"
            else -> "D1 MODEL_READY"
        }
        val d2 = if (nativeLibraryLoaded && category != SpeechDiagnosticCategory.NATIVE_LIBRARY_LOAD_FAILURE) {
            "D2 NATIVE_READY"
        } else {
            "D2 NATIVE_FAIL"
        }
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
            numSpeakers = numSpeakers,
            d1ModelStatus = d1,
            d2NativeStatus = d2,
            d3DirectGenerateStatus = "D3 DIRECT_GENERATE_NOT_TESTED",
            d4CallbackStatus = "D4 CALLBACK_NOT_TESTED",
            d5AudioTrackStatus = "D5 AUDIO_TRACK_NOT_TESTED",
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
            cachedNumSpeakers = null
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
