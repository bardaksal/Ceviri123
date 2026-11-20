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
    val espeakDataDir: File
)

sealed class ModelDownloadResult {
    data class Ready(val paths: ResolvedTurkishModelPaths) : ModelDownloadResult()
    data class Failure(val errorType: SpeechErrorType, val reason: String) : ModelDownloadResult()
}

/**
 * Manages the on-device Turkish Piper neural TTS model (vits-piper-tr_TR-fahrettin-medium).
 * Downloads the official Sherpa-ONNX tar.bz2 archive on first use into app-private filesDir,
 * verifies extracted files, and never hits the network again once ready.
 */
class TurkishTtsModelManager(context: Context) {

    private val appContext = context.applicationContext
    private val modelsBaseDir = File(appContext.filesDir, "tts_models")
    private val modelDir = File(modelsBaseDir, MODEL_ID)
    private val readyMarker = File(modelDir, ".model_ready_v1")

    fun resolveModelPathsOrNull(): ResolvedTurkishModelPaths? {
        if (!readyMarker.isFile || !modelDir.isDirectory) return null
        val onnx = findOnnxFile(modelDir) ?: return null
        val tokens = findFileNamed(modelDir, "tokens.txt") ?: return null
        val espeakDir = findDirNamed(modelDir, "espeak-ng-data") ?: return null
        if (onnx.length() < MIN_ONNX_SIZE_BYTES || tokens.length() <= 0L) return null
        if (!File(espeakDir, "phontab").isFile) return null
        return ResolvedTurkishModelPaths(
            rootDir = modelDir,
            onnxFile = onnx,
            tokensFile = tokens,
            espeakDataDir = espeakDir
        )
    }

    suspend fun isModelDownloaded(): Boolean = withContext(Dispatchers.IO) {
        resolveModelPathsOrNull() != null
    }

    suspend fun ensureModelDownloaded(
        onProgress: ((Int?) -> Unit)? = null
    ): ModelDownloadResult = downloadMutex.withLock {
        withContext(Dispatchers.IO) {
            resolveModelPathsOrNull()?.let { existing ->
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
                if (stagedOnnx == null || stagedOnnx.length() < MIN_ONNX_SIZE_BYTES ||
                    stagedTokens == null || stagedEspeak == null ||
                    !File(stagedEspeak, "phontab").isFile
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

                val resolved = resolveModelPathsOrNull()
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
        BufferedInputStream(FileInputStream(archiveFile), BUFFER_SIZE).use { fileIn ->
            BZip2CompressorInputStream(fileIn).use { bzIn ->
                TarArchiveInputStream(bzIn).use { tarIn ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val entry = tarIn.nextEntry ?: break
                        val normalizedName = entry.name.trimStart('/', '.')
                        if (normalizedName.isEmpty()) continue

                        // Strip top-level archive folder if present (e.g. vits-piper-tr_TR-fahrettin-medium/...)
                        val stripped = normalizedName.substringAfter('/', normalizedName)
                        if (stripped.isEmpty()) continue

                        val outFile = File(targetDir, stripped)
                        val outCanonical = outFile.canonicalPath
                        if (!outCanonical.startsWith(targetCanonicalPrefix) && outCanonical != targetDir.canonicalPath) {
                            throw SecurityException("Geçersiz arşiv yolu: ${entry.name}")
                        }

                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
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

    private fun findOnnxFile(dir: File): File? {
        return dir.walkTopDown()
            .firstOrNull { it.isFile && it.name.endsWith(".onnx", ignoreCase = true) }
    }

    private fun findFileNamed(dir: File, name: String): File? {
        return dir.walkTopDown()
            .firstOrNull { it.isFile && it.name == name }
    }

    private fun findDirNamed(dir: File, name: String): File? {
        return dir.walkTopDown()
            .firstOrNull { it.isDirectory && it.name == name }
    }

    companion object {
        private const val TAG = "TurkishTtsModelManager"
        const val MODEL_ID = "vits-piper-tr_TR-fahrettin-medium"
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
