package com.example.speech

import java.io.File
import kotlin.math.abs

data class VitsConfigValidationResult(
    val isValid: Boolean,
    val missingOrInvalidItems: List<String>
)

data class PcmSampleAnalysis(
    val returnNull: Boolean,
    val sampleCount: Long,
    val sampleRateHz: Int,
    val durationMs: Long,
    val minSample: Float?,
    val maxSample: Float?,
    val absSum: Double,
    val hasSamples: Boolean,
    val hasNonZeroSignal: Boolean
)

object SpeechDiagnosticSupport {

    fun validateVitsPaths(
        onnxPath: String,
        tokensPath: String,
        espeakDataDirPath: String,
        minOnnxBytes: Long = 1_000_000L
    ): VitsConfigValidationResult {
        val issues = mutableListOf<String>()

        if (onnxPath.isBlank()) {
            issues += "vits.model boş"
        } else {
            val onnx = File(onnxPath)
            if (!onnx.isFile) {
                issues += "onnx yok: $onnxPath"
            } else if (onnx.length() < minOnnxBytes) {
                issues += "onnx çok küçük (${onnx.length()} B < $minOnnxBytes B)"
            }
        }

        if (tokensPath.isBlank()) {
            issues += "vits.tokens boş"
        } else {
            val tokensFile = File(tokensPath)
            if (!tokensFile.isFile || tokensFile.length() <= 0L) {
                issues += "tokens.txt yok veya boş: $tokensPath"
            } else {
                val parsedTokens = runCatching {
                    tokensFile.readLines()
                        .filter { it.isNotBlank() }
                        .mapNotNull { line ->
                            if (line.startsWith(" ")) " "
                            else line.trimStart().substringBefore(' ', "").ifEmpty { null }
                        }
                        .toSet()
                }.getOrElse { emptySet() }

                if (!parsedTokens.contains("_") || !parsedTokens.contains("^") || !parsedTokens.contains("$")) {
                    issues += "tokens.txt Piper zorunlu sembollerini (_, ^, $) içermiyor"
                }
            }
        }

        if (espeakDataDirPath.isBlank()) {
            issues += "vits.dataDir boş"
        } else {
            val espeakDir = File(espeakDataDirPath)
            if (!espeakDir.isDirectory) {
                issues += "espeak-ng-data dizini yok: $espeakDataDirPath"
            } else {
                val requiredEspeakFiles = listOf(
                    "phontab",
                    "phondata",
                    "phonindex",
                    "lang/trk/tr",
                    "tr_dict"
                )
                for (rel in requiredEspeakFiles) {
                    val f = File(espeakDir, rel)
                    if (!f.isFile || f.length() <= 0L) {
                        issues += "espeak-ng-data/$rel eksik veya boş"
                    }
                }
            }
        }

        return VitsConfigValidationResult(
            isValid = issues.isEmpty(),
            missingOrInvalidItems = issues
        )
    }

    fun analyzePcmSamples(samples: FloatArray?, sampleRateHz: Int): PcmSampleAnalysis {
        if (samples == null) {
            return PcmSampleAnalysis(
                returnNull = true,
                sampleCount = 0L,
                sampleRateHz = sampleRateHz,
                durationMs = 0L,
                minSample = null,
                maxSample = null,
                absSum = 0.0,
                hasSamples = false,
                hasNonZeroSignal = false
            )
        }
        if (samples.isEmpty()) {
            return PcmSampleAnalysis(
                returnNull = false,
                sampleCount = 0L,
                sampleRateHz = sampleRateHz,
                durationMs = 0L,
                minSample = null,
                maxSample = null,
                absSum = 0.0,
                hasSamples = false,
                hasNonZeroSignal = false
            )
        }

        var minVal = Float.POSITIVE_INFINITY
        var maxVal = Float.NEGATIVE_INFINITY
        var absSum = 0.0
        for (sample in samples) {
            if (sample < minVal) minVal = sample
            if (sample > maxVal) maxVal = sample
            absSum += abs(sample.toDouble())
        }

        val durationMs = computeDurationMs(samples.size.toLong(), sampleRateHz)
        return PcmSampleAnalysis(
            returnNull = false,
            sampleCount = samples.size.toLong(),
            sampleRateHz = sampleRateHz,
            durationMs = durationMs,
            minSample = minVal,
            maxSample = maxVal,
            absSum = absSum,
            hasSamples = true,
            hasNonZeroSignal = absSum > 0.0
        )
    }

    fun computeDurationMs(sampleCount: Long, sampleRateHz: Int): Long {
        if (sampleCount <= 0L || sampleRateHz <= 0) return 0L
        return ((sampleCount * 1000L) / sampleRateHz).coerceAtLeast(1L)
    }

    fun buildSynthesisDiagnosticReport(
        phrase: String,
        inspection: ModelDirectoryInspection,
        nativeLibraryLoaded: Boolean,
        sampleRateHz: Int,
        numSpeakers: Int?,
        directAnalysis: PcmSampleAnalysis,
        callbackInvocations: Int,
        callbackSampleCount: Long,
        callbackReturnedSamples: Long,
        synthesisDurationMs: Long,
        firstAudioLatencyMs: Long? = null,
        audioPlaybackDurationMs: Long? = null,
        audioTrackInitialized: Boolean = false,
        audioTrackPlayed: Boolean = false,
        routedDeviceSummary: String? = null,
        audioTrackError: String? = null,
        routingError: String? = null,
        directGenerateError: String? = null,
        callbackGenerateError: String? = null
    ): SpeechDiagnosticReport {
        val d1 = when {
            !inspection.onnxExists -> "D1 MODEL_MISSING"
            !inspection.configExists -> "D1 CONFIG_INVALID"
            else -> "D1 MODEL_READY"
        }
        val d2 = if (nativeLibraryLoaded && sampleRateHz > 0) "D2 NATIVE_READY" else "D2 NATIVE_FAIL"

        val d3 = when {
            directGenerateError != null -> "D3 DIRECT_GENERATE_FAIL"
            directAnalysis.returnNull -> "D3 DIRECT_GENERATE_NULL"
            directAnalysis.sampleCount <= 0L -> "D3 DIRECT_GENERATE_EMPTY"
            else -> "D3 DIRECT_GENERATE_OK"
        }

        val effectiveCallbackSamples = maxOf(callbackSampleCount, callbackReturnedSamples)
        val d4 = when {
            callbackGenerateError != null -> "D4 CALLBACK_FAIL"
            callbackInvocations > 0 && callbackSampleCount > 0L -> "D4 CALLBACK_STREAM_OK"
            callbackReturnedSamples > 0L -> "D4 CALLBACK_RETURN_ONLY"
            else -> "D4 CALLBACK_EMPTY"
        }

        val totalUsableSamples = maxOf(directAnalysis.sampleCount, effectiveCallbackSamples)
        val synthesisMode = when {
            callbackSampleCount > 0L -> "generateWithCallback"
            callbackReturnedSamples > 0L -> "generateWithCallback_return"
            directAnalysis.sampleCount > 0L -> "direct_generate"
            else -> "none"
        }

        val d5 = when {
            totalUsableSamples <= 0L -> "D5 AUDIO_TRACK_NOT_TESTED"
            routingError != null || audioTrackError != null -> "D5 AUDIO_TRACK_FAIL"
            audioTrackInitialized && audioTrackPlayed -> "D5 AUDIO_TRACK_OK"
            else -> "D5 AUDIO_TRACK_NOT_TESTED"
        }

        val category = when {
            totalUsableSamples <= 0L -> {
                if (directGenerateError != null || callbackGenerateError != null) {
                    SpeechDiagnosticCategory.SYNTHESIS_FAILURE
                } else {
                    SpeechDiagnosticCategory.SYNTHESIS_EMPTY_AUDIO
                }
            }
            routingError != null -> SpeechDiagnosticCategory.AUDIO_OUTPUT_ROUTING_FAILURE
            audioTrackError != null -> SpeechDiagnosticCategory.AUDIOTRACK_FAILURE
            audioTrackInitialized && audioTrackPlayed -> SpeechDiagnosticCategory.TTS_SUCCESS
            else -> SpeechDiagnosticCategory.AUDIOTRACK_FAILURE
        }

        val effectiveDurationMs = computeDurationMs(totalUsableSamples, sampleRateHz)
        val detailMessage = when (category) {
            SpeechDiagnosticCategory.TTS_SUCCESS ->
                "Direct generate=${directAnalysis.sampleCount} örnek, callback=$callbackInvocations/$callbackSampleCount (ret=$callbackReturnedSamples), " +
                    "mod=$synthesisMode, AudioTrack oynatıldı."

            SpeechDiagnosticCategory.SYNTHESIS_EMPTY_AUDIO ->
                "Sherpa-ONNX 0 PCM örneği üretti (directNull=${directAnalysis.returnNull}, directSamples=${directAnalysis.sampleCount}, " +
                    "callback=$callbackInvocations/$callbackSampleCount, callbackRet=$callbackReturnedSamples)."

            SpeechDiagnosticCategory.SYNTHESIS_FAILURE ->
                directGenerateError ?: callbackGenerateError ?: "Sherpa-ONNX sentez sırasında hata fırlattı."

            SpeechDiagnosticCategory.AUDIO_OUTPUT_ROUTING_FAILURE ->
                routingError ?: "Ses çıkış cihazı yönlendirme hatası."

            SpeechDiagnosticCategory.AUDIOTRACK_FAILURE ->
                audioTrackError ?: "AudioTrack oynatılamadı."

            else -> "Tanı tamamlandı."
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
            sampleRateHz = sampleRateHz.takeIf { it > 0 },
            numSpeakers = numSpeakers,
            sampleCount = totalUsableSamples,
            audioDurationMs = effectiveDurationMs,
            synthesisDurationMs = synthesisDurationMs,
            firstAudioLatencyMs = firstAudioLatencyMs,
            audioPlaybackDurationMs = audioPlaybackDurationMs,
            synthesisMode = synthesisMode,
            directGenerateReturnNull = directAnalysis.returnNull,
            directGenerateSamples = directAnalysis.sampleCount,
            directGenerateMinSample = directAnalysis.minSample,
            directGenerateMaxSample = directAnalysis.maxSample,
            directGenerateAbsSum = directAnalysis.absSum,
            callbackInvocations = callbackInvocations,
            callbackSamples = callbackSampleCount,
            callbackGenerateReturnedSamples = callbackReturnedSamples,
            d1ModelStatus = d1,
            d2NativeStatus = d2,
            d3DirectGenerateStatus = d3,
            d4CallbackStatus = d4,
            d5AudioTrackStatus = d5,
            audioTrackInitialized = audioTrackInitialized,
            audioTrackPlayed = audioTrackPlayed,
            routedDeviceSummary = routedDeviceSummary,
            detailMessage = detailMessage
        )
    }
}
