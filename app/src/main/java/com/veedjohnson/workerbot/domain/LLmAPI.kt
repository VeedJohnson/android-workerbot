package com.veedjohnson.workerbot.domain

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.veedjohnson.workerbot.ui.screens.chat.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single
import java.io.File

@Single
class MediaPipeLLMAPI(
    private val context: Context,
) {
    private var llmInference: LlmInference? = null
    private var isModelReady = false

    suspend fun initializeModel(): Boolean = withContext(Dispatchers.IO) {
        if (isModelReady) return@withContext true

        Log.i("MediaPipeLLM", "Starting init")

        try {
            // Model file pushed via ADB
            val modelFile = "/data/local/tmp/llm/gemma3-1b-it-int4.task"

            if (!File(modelFile).exists()) {
                Log.e("MediaPipeLLM", "Model $modelFile not found")
                return@withContext false
            }

            // 1. Configure LLM inference options
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile)
                .setMaxTokens(1024)
                .setPreferredBackend(LlmInference.Backend.GPU)
                .build()

            // 2. Create LLM inference engine (session created per request)
            llmInference = LlmInference.createFromOptions(context, options)

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
                    .setTopK(40)
                    .setTopP(0.4f)
                    .setTemperature(0.3f)
                    .build()
            )
        } catch (e: Exception) {
            Log.e("MediaPipeLLM", "Failed to create fresh session", e)
            null
        }
    }

    fun generateResponseStreaming(
        prompt: String,
        conversationHistory: List<ChatMessage> = emptyList(),
        onPartialResponse: StreamingCallback
    ) {
        try {
            if (!isModelReady) {
                onPartialResponse("Model not initialized", true)
                return
            }

            // Create fresh session for each request
            val session = createFreshSession()
            if (session == null) {
                onPartialResponse("Failed to create session", true)
                return
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
        // Take last 4 messages (2 Q&A pairs) to stay within token limits
        val recentHistory = conversationHistory.takeLast(4)

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
        return if (context.trim().isNotEmpty()) {
            """
            You are a UK Seasonal Worker Scheme expert. Answer the user's question using ONLY the context information provided below. Do not mention source documents. Speak directly and clearly.
            
            IMPORTANT: If the context contains the answer, provide it clearly. Only say you don't have the information if the context truly doesn't contain relevant details.
            
            Include any relevant URLs from the text.
            
            If you cannot find the answer in the context below, reply exactly: "I'm sorry, but I don't have that information. For more help, please contact FiftyEight at https://fiftyeight.io/contact/"
            
            ─── CONTEXT ───
            $context
            
            ─── USER QUESTION ───
            $userQuery
            
            Answer:
            """.trimIndent()
        } else {
            "I'm sorry, but I don't have that information. For more help, please contact FiftyEight at https://fiftyeight.io/contact/"
        }
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