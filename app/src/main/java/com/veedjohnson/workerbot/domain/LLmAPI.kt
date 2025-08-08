package com.veedjohnson.workerbot.domain

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.veedjohnson.workerbot.ui.screens.chat.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single


sealed class LLMInitializationResult {
    data class Success(val backendUsed: String) : LLMInitializationResult()
    data class Failure(val errorMessage: String, val errorType: LLMErrorType) : LLMInitializationResult()
}

enum class LLMErrorType {
    DOWNLOAD_FAILED,
    GPU_FAILED,
    CPU_FAILED,
    BOTH_BACKENDS_FAILED,
    WARMUP_FAILED,
    UNKNOWN_ERROR
}


@Single
class MediaPipeLLMAPI(
    private val context: Context,
    private val modelDownloader: ModelDownloaderService,
) {
    private var llmInference: LlmInference? = null
    private var isModelReady = false
    private var warmSession: LlmInferenceSession? = null
    private var isWarmSessionReady = false


    suspend fun initializeModel(  onDownloadProgress: (progress: Int, downloaded: Long, total: Long) -> Unit = { _, _, _ -> }): LLMInitializationResult = withContext(Dispatchers.IO) {
        if (isModelReady) return@withContext LLMInitializationResult.Success("Already Ready")

        Log.i("MediaPipeLLM", "Starting init")

        try {
            // Step 1: Download model if needed
            val modelPath: String = try {
                val downloadResult = modelDownloader.downloadModelIfNeeded(onDownloadProgress)

                when (downloadResult) {
                    is ModelDownloadResult.Success -> {
                        Log.i("MediaPipeLLM", "Model available at: ${downloadResult.modelPath}")

                        downloadResult.modelPath
                    }
                    is ModelDownloadResult.Error -> {
                        val errorMsg = "Model download failed: ${downloadResult.message}"

                        Log.e("MediaPipeLLM", "Model download failed: ${downloadResult.message}")


                        return@withContext LLMInitializationResult.Failure(errorMsg, LLMErrorType.DOWNLOAD_FAILED)
                    }
                }
            } catch (e: Exception) {
                val msg = "Model download failed: ${e.localizedMessage}"
                return@withContext LLMInitializationResult.Failure(msg, LLMErrorType.DOWNLOAD_FAILED)
            }


            var backendUsed = "Unknown"

            // Try GPU first
            try {

                Log.i("MediaPipeLLM", "Attempting to initialize with GPU backend...")

                val gpuOptions = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelPath)
                    .setMaxTokens(1024)
                    .setPreferredBackend(LlmInference.Backend.GPU)
                    .build()

                llmInference = LlmInference.createFromOptions(context, gpuOptions)
                backendUsed = "GPU"

                Log.i("MediaPipeLLM", "Successfully initialized with GPU backend")

            } catch (e: Exception) {
                Log.w("MediaPipeLLM", "GPU backend failed, falling back to CPU: ${e.message}")

                // Fallback to CPU
                try {
                    val cpuOptions = LlmInference.LlmInferenceOptions.builder()
                        .setModelPath(modelPath)
                        .setMaxTokens(1024)
                        .setPreferredBackend(LlmInference.Backend.CPU)
                        .build()

                    llmInference = LlmInference.createFromOptions(context, cpuOptions)
                    backendUsed = "CPU"
                    Log.i("MediaPipeLLM", "Successfully initialized with CPU backend")


                } catch (cpuException: Exception) {

                    Log.e("MediaPipeLLM", "Both GPU and CPU backends failed", cpuException)
                    return@withContext LLMInitializationResult.Failure(
                        "Both backends failed: ${e.localizedMessage}",
                        LLMErrorType.BOTH_BACKENDS_FAILED
                    )
                }
            }

            Log.i("MediaPipeLLM", "LLM initialized using $backendUsed backend")

            warmUpSession()

            isModelReady = true
            Log.i("MediaPipeLLM", "MediaPipe LLM initialized successfully")


            return@withContext LLMInitializationResult.Success(backendUsed)
        } catch (e: Exception) {
            Log.e("MediaPipeLLM", "MediaPipe LLM init failed", e)

            val errorMsg = "Critical LLM initialization error: ${e.message}"
            return@withContext LLMInitializationResult.Failure(errorMsg, LLMErrorType.UNKNOWN_ERROR)
        }
    }

    private fun createFreshSession(): LlmInferenceSession? {
        return try {
            LlmInferenceSession.createFromOptions(
                llmInference!!,
                LlmInferenceSession.LlmInferenceSessionOptions.builder()
                    .setTopK(15)
                    .setTopP(0.5f)
                    .setTemperature(0.1f)
                    .build()
            )
        } catch (e: Exception) {
            Log.e("MediaPipeLLM", "Failed to create fresh session", e)
            throw e
        }
    }

    private fun warmUpSession() {
        try {
            // Create and keep a warm session ready
            warmSession = createFreshSession()
            isWarmSessionReady = true

            // Optional: Run a tiny warm-up inference to prepare GPU/CPU caches
            warmSession?.let { session ->
                try {
                    // Use a minimal prompt to warm up the model without waiting for response
                    session.addQueryChunk("Hi")
                    Log.d("MediaPipeLLM", "Session warmed up successfully")
                } catch (e: Exception) {
                    Log.w("MediaPipeLLM", "Warm-up query failed, but session is ready", e)
                }
            }
        } catch (e: Exception) {
            Log.w("MediaPipeLLM", "Failed to create warm session", e)
            isWarmSessionReady = false
        }
    }

    suspend fun generateResponseStreaming(
        prompt: String,
        conversationHistory: List<ChatMessage> = emptyList(),
        onPartialResponse: StreamingCallback
    ) = withContext(Dispatchers.IO) {

            try {
                if (!isModelReady) {
                    onPartialResponse("Model not initialized", true)
                    return@withContext
                }

                // Create fresh session for each request
                val session = createFreshSession()
                if (session == null) {
                    onPartialResponse("Failed to create session", true)
                    return@withContext
                }


                // Build prompt with conversation history
                val fullPrompt = buildPromptWithHistory(prompt, conversationHistory)

                Log.d("MediaPipeLLM", "Fresh session created. Prompt length: ${fullPrompt.length}")
                Log.d("MediaPipeLLM", "Full prompt preview: ${fullPrompt.take(200)}...")

                session.addQueryChunk(fullPrompt)
                session.generateResponseAsync { partialResult, done ->
                    onPartialResponse(partialResult, done)
                    if (done) {
                        // Clean up session after use
                        try {
                            session.close()
                            Log.d("MediaPipeLLM", "Session closed after response completion")
                        } catch (e: Exception) {
                            Log.w("MediaPipeLLM", "Error closing session", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MediaPipeLLM", "Error in streaming response", e)
                onPartialResponse("I'm sorry, but I don't have that information. For more help, please contact FiftyEight at https://fiftyeight.io/contact/", true)
            }

    }

    private fun buildPromptWithHistory(
        currentPrompt: String,
        conversationHistory: List<ChatMessage>
    ): String {
        val recentHistory = conversationHistory.takeLast(2)

        return if (recentHistory.isNotEmpty()) {
            val historyText = recentHistory.joinToString("\n\n") { message ->
                if (message.isFromUser) {
                    "Human: ${message.content}"
                } else {
                    "Assistant: ${message.content}"
                }
            }

            """
            Previous conversation:
            $historyText

            Current request:
            $currentPrompt
            """.trimIndent()
        } else {
            currentPrompt
        }
    }

    // Enhanced prompt building for RAG
    fun buildRagPrompt(userQuery: String, context: String): String {
        return (
            """
          Your task is to act as WorkerBot, assisting users with questions about the UK Seasonal Worker Scheme.

Personality:
• Friendly and knowledgeable, like a helpful colleague
• Explain things clearly and focus on what matters most to the user

Response guidelines:
• For greetings: Warmly inquire how you can assist with the scheme
• For questions: Provide brief (1-2 sentences) and conversational answers under 100 words
• Use everyday language; avoid jargon
• Include URLs only if referenced in the context and directly helpful

If information is unavailable:
• Respond with "I don't have that specific information. For more details, contact FiftyEight at https://fiftyeight.io/contact/"

Context: $context
Query: $userQuery
Response:
          """.trimIndent())
    }

    fun cleanup() {
        try {
            llmInference?.close()
            isModelReady = false
            Log.i("MediaPipeLLM", "MediaPipe LLM cleaned up")
        } catch (e: Exception) {
            Log.e("MediaPipeLLM", "Error during cleanup", e)
        }
    }
}

// New streaming method
typealias StreamingCallback = (partialResult: String, done: Boolean) -> Unit