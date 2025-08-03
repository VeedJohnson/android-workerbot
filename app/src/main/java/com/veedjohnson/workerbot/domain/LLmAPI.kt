package com.veedjohnson.workerbot.domain

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.veedjohnson.workerbot.ui.screens.chat.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single

@Single
class MediaPipeLLMAPI(
    private val context: Context,
    private val modelDownloader: ModelDownloaderService,
) {
    private var llmInference: LlmInference? = null
    private var isModelReady = false
    private var warmSession: LlmInferenceSession? = null
    private var isWarmSessionReady = false


    suspend fun initializeModel(  onDownloadProgress: (progress: Int, downloaded: Long, total: Long) -> Unit = { _, _, _ -> }): Boolean = withContext(Dispatchers.IO) {
        if (isModelReady) return@withContext true

        Log.i("MediaPipeLLM", "Starting init")

        try {
            // Step 1: Download model if needed
            val downloadResult = modelDownloader.downloadModelIfNeeded(onDownloadProgress)

            val modelPath = when (downloadResult) {
                is ModelDownloadResult.Success -> {
                    Log.i("MediaPipeLLM", "Model available at: ${downloadResult.modelPath}")
                    downloadResult.modelPath
                }
                is ModelDownloadResult.Error -> {
                    Log.e("MediaPipeLLM", "Model download failed: ${downloadResult.message}")
                    return@withContext false
                }
            }

            // 1. Configure LLM inference options
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(1024)
                .setPreferredBackend(LlmInference.Backend.GPU)
                .build()

            // 2. Create LLM inference engine (session created per request)
            llmInference = LlmInference.createFromOptions(context, options)

            warmUpSession()

            isModelReady = true
            Log.i("MediaPipeLLM", "MediaPipe LLM initialized successfully")
            true
        } catch (e: Exception) {
            Log.e("MediaPipeLLM", "MediaPipe LLM init failed", e)
            false
        }
    }

    private fun createFreshSession(): LlmInferenceSession? {
        return try {
            LlmInferenceSession.createFromOptions(
                llmInference!!,
                LlmInferenceSession.LlmInferenceSessionOptions.builder()
                    .setTopK(15)
                    .setTopP(0.6f)
                    .setTemperature(0.1f)
                    .build()
            )
        } catch (e: Exception) {
            Log.e("MediaPipeLLM", "Failed to create fresh session", e)
            null
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
          You are WorkerBot, a friendly and knowledgeable assistant helping people understand the UK Seasonal Worker Scheme.

Your personality:
• Helpful and approachable, like a knowledgeable colleague
• You explain things clearly without overwhelming people
• You focus on what's most important for the person asking

How to respond:
• For greetings: Be warm and ask how you can help with the scheme
• For questions about the scheme: Give clear, CONCISE conversational answers supported by the available information below
• You must keep answers short (1-2 sentences) unless more detail is specifically needed
• Explain things in everyday language, not official jargon
• Only include URLs if they directly help with the person's question

If you can't answer from the available information: "I don't have that specific information. For more details, contact FiftyEight at https://fiftyeight.io/contact/"

Available information: $context

Person asks: $userQuery

Your helpful response:
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