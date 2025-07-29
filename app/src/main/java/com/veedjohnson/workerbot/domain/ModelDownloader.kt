package com.veedjohnson.workerbot.domain

import android.content.Context
import android.util.Log
import com.veedjohnson.workerbot.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

@Single
class ModelDownloaderService(private val context: Context) {

    companion object {
        private const val MODEL_URL = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.task"
        private const val MODEL_FILENAME = "gemma3-1b-it-int4.task"
        private val HF_TOKEN = BuildConfig.HF_TOKEN.takeIf { it.isNotEmpty() }
    }

    private val modelFile: File
        get() = File(context.filesDir, MODEL_FILENAME)

    suspend fun downloadModelIfNeeded(
        onProgress: (progress: Int, downloaded: Long, total: Long) -> Unit = { _, _, _ -> }
    ): ModelDownloadResult = withContext(Dispatchers.IO) {
        try {

            Log.i("ModelDownloader", "HF TOKEN $HF_TOKEN")

            // Check if model already exists
            if (modelFile.exists()) {
                Log.i("ModelDownloader", "Model already exists at: ${modelFile.absolutePath}")
                return@withContext ModelDownloadResult.Success(modelFile.absolutePath)
            }

            Log.i("ModelDownloader", "Starting model download from Hugging Face... $HF_TOKEN")

            // Create connection with authorization
            val connection = URL(MODEL_URL).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "Bearer $HF_TOKEN")
            connection.setRequestProperty("User-Agent", "WorkerBot-Android/1.0")
            connection.connectTimeout = 30000 // 30 seconds
            connection.readTimeout = 60000 // 60 seconds

            // Handle redirects (Hugging Face uses redirects)
            connection.instanceFollowRedirects = true

            connection.connect()

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.e("ModelDownloader", "Download failed with response code: $responseCode")
                return@withContext ModelDownloadResult.Error("Download failed: HTTP $responseCode")
            }

            val fileLength = connection.contentLength
            Log.i("ModelDownloader", "Model size: ${fileLength / (1024 * 1024)} MB")

            // Download the file
            val inputStream: InputStream = connection.inputStream
            val outputStream = FileOutputStream(modelFile)

            val buffer = ByteArray(8192) // 8KB buffer
            var downloaded: Long = 0
            var bytesRead: Int

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                downloaded += bytesRead

                // Report progress
                val progress = if (fileLength > 0) {
                    ((downloaded * 100) / fileLength).toInt()
                } else {
                    0
                }
                onProgress(progress, downloaded, fileLength.toLong())

                // Log progress every 10MB
                if (downloaded % (10 * 1024 * 1024) == 0L) {
                    Log.d("ModelDownloader", "Downloaded: ${downloaded / (1024 * 1024)} MB")
                }
            }

            outputStream.close()
            inputStream.close()
            connection.disconnect()

            Log.i("ModelDownloader", "Model downloaded successfully to: ${modelFile.absolutePath}")
            Log.i("ModelDownloader", "Final file size: ${modelFile.length() / (1024 * 1024)} MB")

            // Verify file integrity
            if (modelFile.length() < 100 * 1024 * 1024) { // Less than 100MB seems wrong
                Log.w("ModelDownloader", "Downloaded file seems too small, might be corrupted")
            }

            ModelDownloadResult.Success(modelFile.absolutePath)

        } catch (e: Exception) {
            Log.e("ModelDownloader", "Model download failed", e)

            // Clean up partial download
            if (modelFile.exists()) {
                modelFile.delete()
                Log.d("ModelDownloader", "Cleaned up partial download")
            }

            ModelDownloadResult.Error("Download failed: ${e.message}")
        }
    }

    fun getModelPath(): String? {
        return if (modelFile.exists()) {
            modelFile.absolutePath
        } else {
            null
        }
    }

    fun isModelDownloaded(): Boolean = modelFile.exists()

    fun getModelSize(): Long = if (modelFile.exists()) modelFile.length() else 0

    fun deleteModel(): Boolean {
        return try {
            if (modelFile.exists()) {
                val deleted = modelFile.delete()
                Log.i("ModelDownloader", "Model deleted: $deleted")
                deleted
            } else {
                true
            }
        } catch (e: Exception) {
            Log.e("ModelDownloader", "Failed to delete model", e)
            false
        }
    }
}

sealed class ModelDownloadResult {
    data class Success(val modelPath: String) : ModelDownloadResult()
    data class Error(val message: String) : ModelDownloadResult()
}