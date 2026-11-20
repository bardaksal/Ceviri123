package com.example.speech

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

data class ResolvedTurkishModelPaths(
    val rootDir: File,
    val onnxFile: File,
    val tokensFile: File,
    val espeakDataDir: File,
    val jsonConfigFile: File? = null
)

data class ModelDirectoryInspection(
    val storageLocation: String,
    val modelDirPath: String,
    val modelDirExists: Boolean,
    val readyMarkerExists: Boolean,
    val onnxPath: String,
    val onnxExists: Boolean,
    val onnxSizeBytes: Long,
    val tokensPath: String,
    val tokensExists: Boolean,
    val tokensSizeBytes: Long,
    val espeakDirPath: String,
    val espeakDirExists: Boolean,
    val espeakPhontabExists: Boolean,
    val espeakPhondataExists: Boolean,
    val espeakPhonindexExists: Boolean,
    val espeakTurkishLangExists: Boolean,
    val espeakTurkishDictExists: Boolean,
    val jsonConfigPath: String?,
    val jsonConfigExists: Boolean,
    val fileCount: Int,
    val configExists: Boolean
)

sealed class ModelDownloadResult {
    data class Ready(val paths: ResolvedTurkishModelPaths) : ModelDownloadResult()
    data class Failure(val errorType: SpeechErrorType, val reason: String) : ModelDownloadResult()
}

/**
 * Manages the on-device Turkish Piper neural TTS model (vits-piper-tr_TR-fahrettin-medium).
 * Downloads the official Sherpa-ONNX tar.bz2 archive on first use into app-private internal
 * storage (context.filesDir/tts_models/vits-piper-tr_TR-fahrettin-medium), logs existing files
 * and sizes before modifying anything, verifies extracted files, and runs 100% offline once ready.
 */
class TurkishTtsModelManager(
    context: Context,
    private val baseFilesDir: File = context.applicationContext.filesDir
) {

    private val modelsBaseDir = File(baseFilesDir, "tts_models")
    private val modelDir = File(modelsBaseDir, MODEL_ID)
    private val readyMarker = File(modelDir, ".model_ready_v1")

    fun inspectModelDirectory(logDetails: Boolean = true): ModelDirectoryInspection {
        val dirExists = modelDir.isDirectory
        val markerExists = readyMarker.isFile

        val existingFiles = if (dirExists) {
            modelDir.walkTopDown()
                .filter { it.isFile }
                .sortedBy { it.relativeTo(modelDir).path }
                .toList()
        } else {
            emptyList()
        }

        val onnx = findOnnxFile(modelDir) ?: File(modelDir, EXPECTED_ONNX_NAME)
        val tokens = findFileNamed(modelDir, "tokens.txt") ?: File(modelDir, "tokens.txt")
        val espeakDir = findDirNamed(modelDir, "espeak-ng-data") ?: File(modelDir, "espeak-ng-data")
        val jsonConfig = findOnnxJsonFile(modelDir)

        val phontabExists = File(espeakDir, "phontab").let { it.isFile && it.length() > 0L }
        val phondataExists = File(espeakDir, "phondata").let { it.isFile && it.length() > 0L }
        val phonindexExists = File(espeakDir, "phonindex").let { it.isFile && it.length() > 0L }
        val trLangExists = File(espeakDir, "lang/trk/tr").let { it.isFile && it.length() > 0L }
        val trDictExists = File(espeakDir, "tr_dict").let { it.isFile && it.length() > 0L }

        val onnxExists = onnx.isFile
        val onnxSize = if (onnxExists) onnx.length() else 0L
        val tokensExists = tokens.isFile
        val tokensSize = if (tokensExists) tokens.length() else 0L
        val espeakExists = espeakDir.isDirectory
        val jsonExists = jsonConfig?.isFile == true
        val configExists = tokensExists && tokensSize > 0L && espeakExists && phontabExists && phondataExists && phonindexExists

        val inspection = ModelDirectoryInspection(
            storageLocation = "APP_INTERNAL_STORAGE (filesDir/tts_models/$MODEL_ID)",
            modelDirPath = modelDir.absolutePath,
            modelDirExists = dirExists,
            readyMarkerExists = markerExists,
            onnxPath = onnx.absolutePath,
            onnxExists = onnxExists,
            onnxSizeBytes = onnxSize,
            tokensPath = tokens.absolutePath,
            tokensExists = tokensExists,
            tokensSizeBytes = tokensSize,
            espeakDirPath = espeakDir.absolutePath,
            espeakDirExists = espeakExists,
            espeakPhontabExists = phontabExists,
            espeakPhondataExists = phondataExists,
            espeakPhonindexExists = phonindexExists,
            espeakTurkishLangExists = trLangExists,
            espeakTurkishDictExists = trDictExists,
            jsonConfigPath = jsonConfig?.absolutePath,
            jsonConfigExists = jsonExists,
            fileCount = existingFiles.size,
            configExists = configExists
        )

        if (logDetails) {
            Log.i(TAG, "TTS_STORAGE_LOCATION=${inspection.storageLocation}")
            Log.i(TAG, "TTS_MODEL_DIR=${inspection.modelDirPath} exists=${inspection.modelDirExists} readyMarker=${inspection.readyMarkerExists} fileCount=${inspection.fileCount}")
            existingFiles.forEach { file ->
                val rel = file.relativeTo(modelDir).path
                Log.i(TAG, "TTS_EXISTING_FILE name=$rel sizeBytes=${file.length()}")
            }
            Log.i(TAG, "TTS_MODEL_PATH=${inspection.onnxPath}")
            Log.i(TAG, "TTS_MODEL_EXISTS=${inspection.onnxExists}")
            Log.i(TAG, "TTS_MODEL_SIZE=${inspection.onnxSizeBytes}")
            Log.i(
                TAG,
                "TTS_CONFIG_PATH=tokens:${inspection.tokensPath};espeak:${inspection.espeakDirPath}" +
                    (inspection.jsonConfigPath?.let { ";json:$it" } ?: "")
            )
            Log.i(
                TAG,
                "TTS_CONFIG_EXISTS=${inspection.configExists} " +
                    "(tokens=${inspection.tokensExists}:${inspection.tokensSizeBytes}B, " +
                    "espeakDir=${inspection.espeakDirExists}, phontab=${inspection.espeakPhontabExists}, " +
                    "phondata=${inspection.espeakPhondataExists}, phonindex=${inspection.espeakPhonindexExists}, " +
                    "lang/trk/tr=${inspection.espeakTurkishLangExists}, tr_dict=${inspection.espeakTurkishDictExists}, " +
                    "json=${inspection.jsonConfigExists})"
            )
        }

        return inspection
    }

    fun resolveModelPathsOrNull(logInspection: Boolean = false): ResolvedTurkishModelPaths? {
        if (logInspection) {
            inspectModelDirectory(logDetails = true)
        }
        if (!readyMarker.isFile || !modelDir.isDirectory) return null
        val onnx = findOnnxFile(modelDir) ?: return null
        val tokens = findFileNamed(modelDir, "tokens.txt") ?: return null
        val espeakDir = findDirNamed(modelDir, "espeak-ng-data") ?: return null
        if (onnx.length() < MIN_ONNX_SIZE_BYTES || tokens.length() <= 0L) return null
        if (!File(espeakDir, "phontab").let { it.isFile && it.length() > 0L }) return null
        if (!File(espeakDir, "phondata").let { it.isFile && it.length() > 0L }) return null
        if (!File(espeakDir, "phonindex").let { it.isFile && it.length() > 0L }) return null
        return ResolvedTurkishModelPaths(
            rootDir = modelDir,
            onnxFile = onnx,
            tokensFile = tokens,
            espeakDataDir = espeakDir,
            jsonConfigFile = findOnnxJsonFile(modelDir)
        )
    }

    suspend fun isModelDownloaded(): Boolean = withContext(Dispatchers.IO) {
        resolveModelPathsOrNull(logInspection = false) != null
    }

    suspend fun ensureModelDownloaded(
        onProgress: ((Int?) -> Unit)? = null
    ): ModelDownloadResult = downloadMutex.withLock {
        withContext(Dispatchers.IO) {
            // Mandatory pre-download inspection: log existing file names & sizes before any download/change
            inspectModelDirectory(logDetails = true)

            resolveModelPathsOrNull(logInspection = false)?.let { existing ->
                return@withContext ModelDownloadResult.Ready(existing)
            }

            modelsBaseDir.mkdirs()
            val archiveFile = File(modelsBaseDir, "$MODEL_ID.tar.bz2.part")
            val stagingDir = File(modelsBaseDir, "${MODEL_ID}_staging")

            try {
                if (archiveFile.exists()) archiveFile.delete()
                if (stagingDir.exists()) stagingDir.deleteRecursively()
                stagingDir.mkdirs()

                Log.i(TAG, "Downloading Turkish Piper model from $MODEL_ARCHIVE_URL")
                downloadFileWithRedirects(
                    urlString = MODEL_ARCHIVE_URL,
                    destination = archiveFile,
                    onProgress = onProgress
                )

                coroutineContext.ensureActive()
                onProgress?.invoke(100)

                Log.i(TAG, "Extracting Turkish Piper model archive (${archiveFile.length()} bytes)")
                extractTarBz2Safely(archiveFile, stagingDir)

                val stagedOnnx = findOnnxFile(stagingDir)
                val stagedTokens = findFileNamed(stagingDir, "tokens.txt")
                val stagedEspeak = findDirNamed(stagingDir, "espeak-ng-data")
                val espeakValid = stagedEspeak != null &&
                    File(stagedEspeak, "phontab").let { it.isFile && it.length() > 0L } &&
                    File(stagedEspeak, "phondata").let { it.isFile && it.length() > 0L } &&
                    File(stagedEspeak, "phonindex").let { it.isFile && it.length() > 0L }

                if (stagedOnnx == null || stagedOnnx.length() < MIN_ONNX_SIZE_BYTES ||
                    stagedTokens == null || stagedTokens.length() <= 0L || !espeakValid
                ) {
                    stagingDir.deleteRecursively()
                    return@withContext ModelDownloadResult.Failure(
                        errorType = SpeechErrorType.MODEL_NOT_FOUND,
                        reason = "İndirilen Türkçe TTS arşivinde beklenen model dosyaları bulunamadı."
                    )
                }

                if (modelDir.exists()) {
                    modelDir.deleteRecursively()
                }
                if (!stagingDir.renameTo(modelDir)) {
                    stagingDir.copyRecursively(modelDir, overwrite = true)
                    stagingDir.deleteRecursively()
                }

                File(modelDir, ".model_ready_v1").writeText("ok")
                archiveFile.delete()

                val resolved = resolveModelPathsOrNull(logInspection = true)
                if (resolved == null) {
                    ModelDownloadResult.Failure(
                        errorType = SpeechErrorType.MODEL_NOT_FOUND,
                        reason = "Türkçe TTS model dosyaları doğrulama kontrolünden geçemedi."
                    )
                } else {
                    Log.i(TAG, "Turkish Piper model ready at ${resolved.onnxFile.absolutePath}")
                    ModelDownloadResult.Ready(resolved)
                }
            } catch (ce: CancellationException) {
                archiveFile.delete()
                stagingDir.deleteRecursively()
                throw ce
            } catch (ioe: IOException) {
                archiveFile.delete()
                stagingDir.deleteRecursively()
                Log.e(TAG, "Model download IO failure", ioe)
                ModelDownloadResult.Failure(
                    errorType = SpeechErrorType.MODEL_DOWNLOAD_FAILED,
                    reason = "Türkçe TTS modeli indirilemedi: ${ioe.localizedMessage ?: "Ağ/G-Ç hatası"}"
                )
            } catch (t: Throwable) {
                archiveFile.delete()
                stagingDir.deleteRecursively()
                Log.e(TAG, "Unexpected model download failure", t)
                ModelDownloadResult.Failure(
                    errorType = SpeechErrorType.MODEL_DOWNLOAD_FAILED,
                    reason = "Türkçe TTS modeli hazırlanamadı: ${t.localizedMessage ?: t.javaClass.simpleName}"
                )
            }
        }
    }

    private suspend fun downloadFileWithRedirects(
        urlString: String,
        destination: File,
        onProgress: ((Int?) -> Unit)?
    ) {
        var currentUrl = urlString
        var redirects = 0
        var connection: HttpURLConnection? = null

        try {
            while (redirects < MAX_REDIRECTS) {
                coroutineContext.ensureActive()
                val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    setRequestProperty("User-Agent", "SubCastTR-Android/1.0")
                }
                val status = conn.responseCode
                if (status in 300..399) {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (location.isNullOrBlank()) {
                        throw IOException("HTTP yönlendirme hedefi boş (status=$status)")
                    }
                    currentUrl = URL(URL(currentUrl), location).toString()
                    redirects++
                    continue
                }
                if (status !in 200..299) {
                    conn.disconnect()
                    throw IOException("Sunucu HTTP $status yanıtı döndürdü.")
                }
                connection = conn
                break
            }

            val activeConn = connection ?: throw IOException("Çok fazla HTTP yönlendirmesi ($MAX_REDIRECTS).")
            val contentLength = activeConn.contentLengthLong
            var downloadedBytes = 0L
            var lastReportedPct = -1

            BufferedInputStream(activeConn.inputStream, BUFFER_SIZE).use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        if (contentLength > 0L) {
                            val pct = ((downloadedBytes * 100L) / contentLength).toInt().coerceIn(0, 99)
                            if (pct != lastReportedPct) {
                                lastReportedPct = pct
                                onProgress?.invoke(pct)
                            }
                        } else if (lastReportedPct == -1) {
                            lastReportedPct = 0
                            onProgress?.invoke(null)
                        }
                    }
                    output.fd.sync()
                }
            }
        } finally {
            connection?.disconnect()
        }
    }

    private suspend fun extractTarBz2Safely(archiveFile: File, targetDir: File) {
        val targetCanonicalPrefix = targetDir.canonicalPath + File.separator
        val deferredLinks = mutableListOf<Pair<File, String>>()

        BufferedInputStream(FileInputStream(archiveFile), BUFFER_SIZE).use { fileIn ->
            BZip2CompressorInputStream(fileIn).use { bzIn ->
                TarArchiveInputStream(bzIn).use { tarIn ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val entry = tarIn.nextEntry ?: break
                        val normalizedName = entry.name.trimStart('/', '.').trimStart('/')
                        if (normalizedName.isEmpty()) continue

                        // Strip top-level wrapper folder (e.g. vits-piper-tr_TR-fahrettin-medium/...)
                        val stripped = if (normalizedName.startsWith("$MODEL_ID/")) {
                            normalizedName.removePrefix("$MODEL_ID/")
                        } else {
                            normalizedName.substringAfter('/', normalizedName)
                        }
                        if (stripped.isEmpty()) continue

                        val outFile = File(targetDir, stripped)
                        val outCanonical = outFile.canonicalPath
                        if (!outCanonical.startsWith(targetCanonicalPrefix) && outCanonical != targetDir.canonicalPath) {
                            throw SecurityException("Geçersiz arşiv yolu: ${entry.name}")
                        }

                        when {
                            entry.isDirectory -> {
                                outFile.mkdirs()
                            }

                            entry.isSymbolicLink || entry.isLink -> {
                                val linkTarget = entry.linkName.orEmpty()
                                Log.w(TAG, "Tar archive link entry detected: $stripped -> $linkTarget (isSym=${entry.isSymbolicLink}, isHard=${entry.isLink})")
                                deferredLinks.add(outFile to linkTarget)
                            }

                            else -> {
                                outFile.parentFile?.mkdirs()
                                FileOutputStream(outFile).use { out ->
                                    while (true) {
                                        coroutineContext.ensureActive()
                                        val count = tarIn.read(buffer)
                                        if (count <= 0) break
                                        out.write(buffer, 0, count)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Resolve any tar hardlinks/symlinks safely inside targetDir by copying target file contents
        for ((destFile, rawLinkTarget) in deferredLinks) {
            val cleanedTarget = rawLinkTarget.trimStart('/', '.').trimStart('/')
                .let { if (it.startsWith("$MODEL_ID/")) it.removePrefix("$MODEL_ID/") else it }
            val candidates = listOfNotNull(
                destFile.parentFile?.let { File(it, rawLinkTarget) },
                File(targetDir, cleanedTarget)
            )
            val resolvedSource = candidates.firstOrNull { candidate ->
                val canon = runCatching { candidate.canonicalPath }.getOrNull() ?: return@firstOrNull false
                canon.startsWith(targetCanonicalPrefix) && candidate.isFile && candidate.length() > 0L
            }
            if (resolvedSource != null) {
                destFile.parentFile?.mkdirs()
                resolvedSource.copyTo(destFile, overwrite = true)
            }
        }
    }

    private fun findOnnxFile(dir: File): File? {
        if (!dir.isDirectory) return null
        return dir.walkTopDown()
            .firstOrNull { it.isFile && it.name.endsWith(".onnx", ignoreCase = true) }
    }

    private fun findOnnxJsonFile(dir: File): File? {
        if (!dir.isDirectory) return null
        return dir.walkTopDown()
            .firstOrNull { it.isFile && it.name.endsWith(".onnx.json", ignoreCase = true) }
    }

    private fun findFileNamed(dir: File, name: String): File? {
        if (!dir.isDirectory) return null
        return dir.walkTopDown()
            .firstOrNull { it.isFile && it.name == name }
    }

    private fun findDirNamed(dir: File, name: String): File? {
        if (!dir.isDirectory) return null
        return dir.walkTopDown()
            .firstOrNull { it.isDirectory && it.name == name }
    }

    companion object {
        private const val TAG = "TurkishTtsModelManager"
        const val MODEL_ID = "vits-piper-tr_TR-fahrettin-medium"
        const val EXPECTED_ONNX_NAME = "tr_TR-fahrettin-medium.onnx"
        const val MODEL_ARCHIVE_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-tr_TR-fahrettin-medium.tar.bz2"
        private const val MIN_ONNX_SIZE_BYTES = 1_000_000L
        private const val MAX_REDIRECTS = 6
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val BUFFER_SIZE = 32 * 1024

        private val downloadMutex = Mutex()
    }
}
