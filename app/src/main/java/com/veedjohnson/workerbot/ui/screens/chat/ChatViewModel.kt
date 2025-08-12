package com.veedjohnson.workerbot.ui.screens.chat

import android.app.Application
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.veedjohnson.workerbot.data.ChunksDB
import com.veedjohnson.workerbot.data.Document
import com.veedjohnson.workerbot.data.Chunk
import com.veedjohnson.workerbot.data.DocumentsDB
import com.veedjohnson.workerbot.data.RetrievedContext
import com.veedjohnson.workerbot.domain.ChatHistoryStorage
import com.veedjohnson.workerbot.domain.KnowledgeBaseLoader
import com.veedjohnson.workerbot.domain.MediaPipeLLMAPI
import com.veedjohnson.workerbot.domain.SentenceEmbeddingProvider
import com.veedjohnson.workerbot.domain.Chunker
import com.veedjohnson.workerbot.domain.LLMErrorType
import com.veedjohnson.workerbot.domain.LLMInitializationResult
import com.veedjohnson.workerbot.domain.TranslationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.annotation.KoinViewModel

// Simplified events
sealed interface ChatScreenUIEvent {
    data class LanguageChanged(val language: AppLanguage) : ChatScreenUIEvent
    data object ClearAllChatHistory : ChatScreenUIEvent
    data object ToggleDebugMode : ChatScreenUIEvent

    sealed class ResponseGeneration {
        data class Start(
            val query: String,
        ) : ChatScreenUIEvent

        data class StopWithSuccess(
            val response: String,
            val retrievedContextList: List<RetrievedContext>,
        ) : ChatScreenUIEvent

        data class StopWithError(
            val errorMessage: String,
        ) : ChatScreenUIEvent
    }

    sealed class ErrorDialog {
        data class Show(
            val title: String,
            val message: String,
            val type: ErrorType = ErrorType.GENERAL
        ) : ChatScreenUIEvent
        data object Dismiss : ChatScreenUIEvent
        data object  Retry : ChatScreenUIEvent
        data object ExitApp : ChatScreenUIEvent
        }
    }


enum class ErrorType {
    GENERAL,
    KNOWLEDGE_BASE_FAILED,
    LLM_FAILED,
    DOWNLOAD_FAILED,
    SYSTEM_FAILED
}

data class ChatScreenUIState(
    val question: String = "",
    val response: String = "",
    val isGeneratingResponse: Boolean = false,
    val isStreamingResponse: Boolean = false,
    val retrievedContextList: List<RetrievedContext> = emptyList(),
    val isInitializingKnowledgeBase: Boolean = false,
    val isInitializingLLM: Boolean = false,
    val isInitializingTranslator: Boolean = false,
    val isSystemReady: Boolean = false,
    val isDownloadingModel: Boolean = false,
    val downloadProgress: Int = 0,
    val downloadStatus: String = "",
    val conversationHistory: List<ChatMessage> = emptyList(),
    val selectedLanguage: AppLanguage = AppLanguage.ENGLISH,
    // Separate histories for each language
    val englishConversationHistory: List<ChatMessage> = emptyList(),
    val russianConversationHistory: List<ChatMessage> = emptyList(),
    val translationAvailable: Boolean = true,

    //error dialog
    val showErrorDialog: Boolean = false,
    val errorDialogTitle: String = "",
    val errorDialogMessage: String = "",
    val errorDialogType: ErrorType = ErrorType.GENERAL,

    val debugLogs: List<String> = emptyList(),
    val showDebugLogs: Boolean = false,
)

@KoinViewModel
class ChatViewModel(
    private val application: Application,
    private val documentsDB: DocumentsDB,
    private val chunksDB: ChunksDB,
    private val sentenceEncoder: SentenceEmbeddingProvider,
    private val knowledgeBaseLoader: KnowledgeBaseLoader,
    private val mediaPipeLLM: MediaPipeLLMAPI,
    private val translationService: TranslationService,
    private val chatHistoryStorage: ChatHistoryStorage
) : ViewModel() {

    private val _chatScreenUIState = MutableStateFlow(ChatScreenUIState())
    val chatScreenUIState: StateFlow<ChatScreenUIState> = _chatScreenUIState

    fun initializeSystemComponents() {
        viewModelScope.launch(Dispatchers.IO) {
            // Initialize both knowledge base and LLM
            initializeSystem()

            loadPersistedChatHistory()
        }
    }

    private suspend fun initializeSystem() {
        var knowledgeBaseReady = false
        var llmReady = false
        var translationReady = false

        try {
            //addDebugLog("🔄 Starting system initialization...")

            // Step 1: Initialize Knowledge Base (CRITICAL - Stop if fails)
            if (!initializeKnowledgeBase()) {
                // Knowledge Base failed - stop everything
                //addDebugLog("💥 Knowledge Base initialization failed - stopping system initialization")
                onChatScreenEvent(ChatScreenUIEvent.ErrorDialog.Show(
                    title = "Knowledge Base Failed",
                    message = "Failed to load the knowledge base. The app cannot function without it. Please restart the app and try again.",
                    type = ErrorType.KNOWLEDGE_BASE_FAILED
                ))
                return // Exit early
            }
            knowledgeBaseReady = true
            //addDebugLog("✅ Knowledge base ready!")

            // Step 2: Initialize LLM Model (CRITICAL - Stop if fails)
            when (val llmResult = initializeLLMModel()) {
                is LLMInitializationResult.Success -> {
                    llmReady = true
                   // addDebugLog("✅ LLM ready using ${llmResult.backendUsed} backend!")
                }
                is LLMInitializationResult.Failure -> {
                    // LLM failed - stop everything
                   // addDebugLog("💥 LLM initialization failed - stopping system initialization")
                    val (title, message) = getErrorTitleAndMessage(llmResult)
                    onChatScreenEvent(ChatScreenUIEvent.ErrorDialog.Show(
                        title = title,
                        message = message,
                        type = ErrorType.LLM_FAILED
                    ))
                    return // Exit early
                }
            }

            // Step 3: Initialize Translation Service (NON-CRITICAL - Continue if fails)
            translationReady = initializeTranslationService()
            if (!translationReady) {
                // Translation failed, but system can still work with English only
               // addDebugLog("⚠️ Translation service failed - continuing with English only")
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        application,
                        "Translation service unavailable. Only English chat will be available.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                //addDebugLog("✅ Translation service ready!")
            }

        } catch (e: Exception) {

            onChatScreenEvent(ChatScreenUIEvent.ErrorDialog.Show(
                title = "System Error",
                message = "A critical error occurred during initialization: ${e.message ?: "Unknown error"}. Please restart the app.",
                type = ErrorType.SYSTEM_FAILED
            ))
            return
        } finally {
            updateFinalSystemState(knowledgeBaseReady, llmReady, translationReady)
        }
    }



    private fun processKnowledgeBase() {
        try {
            Log.d("ChatViewModel", "Loading knowledge base from assets...")
            val knowledgeBaseText = knowledgeBaseLoader.loadKnowledgeBase()
            Log.d("ChatViewModel", "Knowledge base text length: ${knowledgeBaseText.length}")

            val fileName = "knowledge_base_eng.txt"

            val existingDocId = documentsDB.getDocumentIdByFileName(fileName)
            if (existingDocId != null) {
                Log.d("ChatViewModel", "Found existing document with ID: $existingDocId, removing...")

                // Remove all chunks associated with this document
                chunksDB.removeChunksByDocId(existingDocId)
                Log.d("ChatViewModel", "Removed existing chunks for document ID: $existingDocId")

                // Remove the document
                documentsDB.removeDocument(existingDocId)
                Log.d("ChatViewModel", "Removed existing document with ID: $existingDocId")
            } else {
                Log.d("ChatViewModel", "No existing document found, proceeding with fresh installation")
            }

            // Add new document to DB
            Log.d("ChatViewModel", "Adding document to database...")
            val docId = documentsDB.addDocument(
                Document(
                    docText = knowledgeBaseText,
                    docFileName = fileName,
                    docAddedTime = System.currentTimeMillis(),
                )
            )
            Log.d("ChatViewModel", "Document added with ID: $docId")

            // Create chunks using structured chunking that respects document format
            Log.d("ChatViewModel", "Creating structured chunks...")
            val chunks = Chunker.createStructuredChunks(
                knowledgeBaseText,
                maxChunkSize = 300
            )

            Log.d("ChatViewModel", "Created ${chunks.size} structured chunks")

            // Log first few chunks to verify quality
            chunks.take(3).forEachIndexed { index, chunk ->
                Log.d("ChatViewModel", "Chunk $index preview: ${chunk.take(100)}...")
            }

            // Add chunks with embeddings
            Log.d("ChatViewModel", "Adding chunks with embeddings...")
            chunks.forEachIndexed { index, chunk ->
                Log.d("ChatViewModel", "Processing chunk ${index + 1}/${chunks.size}")
                val embedding = sentenceEncoder.encodeText(chunk)
                chunksDB.addChunk(
                    Chunk(
                        docId = docId,
                        docFileName = "knowledge_base_eng.txt",
                        chunkData = chunk,
                        chunkEmbedding = embedding,
                    )
                )
            }
            Log.d("ChatViewModel", "All chunks processed successfully")
        } catch (e: Exception) {
            Log.e("ChatViewModel", "Error processing knowledge base", e)
            throw e
        }
    }

    private suspend fun initializeKnowledgeBase(): Boolean {
        return try {
            withContext(Dispatchers.Main) {
                _chatScreenUIState.value = _chatScreenUIState.value.copy(
                    isInitializingKnowledgeBase = true
                )
            }

            //addDebugLog("📚 Initializing knowledge base...")
            processKnowledgeBase()
            //addDebugLog("✅ Knowledge base processed successfully")

            withContext(Dispatchers.Main) {
                _chatScreenUIState.value = _chatScreenUIState.value.copy(
                    isInitializingKnowledgeBase = false
                )
            }
            true
        } catch (e: Exception) {
            //addDebugLog("❌ Knowledge base initialization failed: ${e.message}")
            withContext(Dispatchers.Main) {
                _chatScreenUIState.value = _chatScreenUIState.value.copy(
                    isInitializingKnowledgeBase = false
                )
            }
            false
        }
    }

    private suspend fun initializeLLMModel(): LLMInitializationResult {
        return try {
            withContext(Dispatchers.Main) {
                _chatScreenUIState.value = _chatScreenUIState.value.copy(
                    isInitializingLLM = true
                )
            }

            var isActuallyDownloading = false

            val result = mediaPipeLLM.initializeModel(
                onDownloadProgress = { progress, downloaded, total ->
                    viewModelScope.launch(Dispatchers.Main) {
                        val downloadedMB = downloaded / (1024 * 1024)
                        val totalMB = total / (1024 * 1024)
                        val isDownloading = downloaded > 0 && total > 0 && downloaded < total

                        if (isDownloading && !isActuallyDownloading) {
                            isActuallyDownloading = true
                        }

                        val status = when {
                            !isDownloading && progress < 100 -> "Preparing AI model..."
                            isDownloading -> "Downloading AI model: ${downloadedMB}MB / ${totalMB}MB"
                            downloadedMB > 0 && downloadedMB == totalMB -> "Setting up AI model..."
                            else -> "Loading AI model..."
                        }

                        _chatScreenUIState.value = _chatScreenUIState.value.copy(
                            isDownloadingModel = isActuallyDownloading,
                            downloadProgress = progress,
                            downloadStatus = status,
                        )
                    }
                },
            )

            // Update UI with final download status
            withContext(Dispatchers.Main) {
                _chatScreenUIState.value = _chatScreenUIState.value.copy(
                    downloadProgress = if (result is LLMInitializationResult.Success) 100 else 0,
                    isDownloadingModel = false,
                    downloadStatus = if (result is LLMInitializationResult.Success) "AI model ready!" else "AI model failed",
                    isInitializingLLM = false
                )
            }

            result
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                _chatScreenUIState.value = _chatScreenUIState.value.copy(
                    isInitializingLLM = false,
                    isDownloadingModel = false
                )
            }
            LLMInitializationResult.Failure("Critical LLM error: ${e.message}", LLMErrorType.UNKNOWN_ERROR)
        }
    }

    private suspend fun initializeTranslationService(): Boolean {
        return try {
            withContext(Dispatchers.Main) {
                _chatScreenUIState.value = _chatScreenUIState.value.copy(
                    isInitializingTranslator = true
                )
            }

            val result = translationService.initializeTranslators()

            result
        } catch (e: Exception) {

            false
        } finally {
            withContext(Dispatchers.Main) {
                _chatScreenUIState.value = _chatScreenUIState.value.copy(
                    isInitializingTranslator = false
                )
            }
        }
    }

    private fun getErrorTitleAndMessage(result: LLMInitializationResult.Failure): Pair<String, String> {
        return when (result.errorType) {
            LLMErrorType.DOWNLOAD_FAILED ->
                "Download Failed" to "Failed to download the AI model. Please check your internet connection and try again."

            LLMErrorType.BOTH_BACKENDS_FAILED ->
                "Device Compatibility Issue" to "Your device doesn't support the AI model. Both GPU and CPU initialization failed.\n\nThis may be due to insufficient memory or unsupported hardware.\n\nTechnical details: ${result.errorMessage}"

            LLMErrorType.GPU_FAILED ->
                "GPU Issue" to "GPU acceleration failed, but CPU fallback should work. Please try restarting the app."

            LLMErrorType.CPU_FAILED ->
                "CPU Processing Error" to "CPU processing failed. This might be a device compatibility issue or insufficient memory."

            LLMErrorType.WARMUP_FAILED ->
                "Model Warmup Failed" to "The AI model loaded but failed to warm up properly. Try restarting the app."

            LLMErrorType.UNKNOWN_ERROR ->
                "AI Model Error" to "An unexpected error occurred while loading the AI model: ${result.errorMessage}"
        }
    }

    private suspend fun updateFinalSystemState(
        knowledgeBaseReady: Boolean,
        llmReady: Boolean,
        translationReady: Boolean
    ) {
        withContext(Dispatchers.Main) {
            _chatScreenUIState.value = _chatScreenUIState.value.copy(
                isInitializingKnowledgeBase = false,
                isInitializingLLM = false,
                isDownloadingModel = false,
                translationAvailable = translationReady,
                isSystemReady = knowledgeBaseReady && llmReady // Translation is optional
            )

            val finalStatus = when {
                !knowledgeBaseReady -> {

                    "Knowledge base failure"
                }

                // 2) LLM failed → even if KB was ok
                !llmReady -> {
                    "AI model failure"
                }

                // 3) Translation failed, but core systems are up
                !translationReady && llmReady && knowledgeBaseReady -> {

                    Toast.makeText(
                        application,
                        "System ready! (English only – translation unavailable)",
                        Toast.LENGTH_SHORT
                    ).show()
                    "English-only mode"
                }

                // 4) Everything is good
                else -> {

                    Toast.makeText(
                        application,
                        "System ready! You can start chatting!",
                        Toast.LENGTH_SHORT
                    ).show()
                    "All systems operational"
                }
            }

            Log.d("ChatViewModel", "Final state: $finalStatus")
        }
    }

    // NEW: Load persisted chat history
    private suspend fun loadPersistedChatHistory() {
        try {
            withContext(Dispatchers.Main) {
                Log.d("ChatViewModel", "Loading persisted chat history...")

                // Load chat histories for both languages
                val englishHistory = chatHistoryStorage.loadChatHistory(AppLanguage.ENGLISH)
                val russianHistory = chatHistoryStorage.loadChatHistory(AppLanguage.RUSSIAN)

                // Load last selected language
                val lastSelectedLanguage = chatHistoryStorage.loadLastSelectedLanguage()

                // Set the current conversation history based on selected language
                val currentHistory = when (lastSelectedLanguage) {
                    AppLanguage.ENGLISH -> englishHistory
                    AppLanguage.RUSSIAN -> russianHistory
                }

                Log.d("ChatViewModel", "Loaded ${englishHistory.size} English messages, ${russianHistory.size} Russian messages")
                Log.d("ChatViewModel", "Last selected language: ${lastSelectedLanguage.displayName}")

                _chatScreenUIState.value = _chatScreenUIState.value.copy(
                    selectedLanguage = lastSelectedLanguage,
                    conversationHistory = currentHistory,
                    englishConversationHistory = englishHistory,
                    russianConversationHistory = russianHistory
                )
            }
        } catch (e: Exception) {
            Log.e("ChatViewModel", "Error loading persisted chat history", e)
        }
    }

     fun onChatScreenEvent(event: ChatScreenUIEvent) {
        when (event) {

            is ChatScreenUIEvent.ToggleDebugMode -> {
                    _chatScreenUIState.value = _chatScreenUIState.value.copy(
                        showDebugLogs = !_chatScreenUIState.value.showDebugLogs
                    )
            }
            is ChatScreenUIEvent.LanguageChanged -> {
                val previousLanguage = _chatScreenUIState.value.selectedLanguage
                val newLanguage = event.language

                Log.d("ChatViewModel", "Language changed from ${previousLanguage.displayName} to ${newLanguage.displayName}")

                val currentState = _chatScreenUIState.value
                val currentConversationHistory = currentState.conversationHistory

                // Save current conversation to storage
                chatHistoryStorage.saveChatHistory(previousLanguage, currentConversationHistory)

                // Save selected language preference
                chatHistoryStorage.saveLastSelectedLanguage(newLanguage)

                // STEP 1: Save current conversation to the previous language's history
                val updatedPreviousLanguageState = when (previousLanguage) {
                    AppLanguage.ENGLISH -> currentState.copy(
                        englishConversationHistory = currentConversationHistory
                    )
                    AppLanguage.RUSSIAN -> currentState.copy(
                        russianConversationHistory = currentConversationHistory
                    )
                }

                // STEP 2: Load the conversation history for the new language
                val newConversationHistory = when (newLanguage) {
                    AppLanguage.ENGLISH -> updatedPreviousLanguageState.englishConversationHistory
                    AppLanguage.RUSSIAN -> updatedPreviousLanguageState.russianConversationHistory
                }

                Log.d("ChatViewModel", "Saving ${currentConversationHistory.size} messages to ${previousLanguage.displayName}")
                Log.d("ChatViewModel", "Loading ${newConversationHistory.size} messages for ${newLanguage.displayName}")

                // STEP 3: Update state with new language and its history
                _chatScreenUIState.value = updatedPreviousLanguageState.copy(
                    selectedLanguage = newLanguage,
                    conversationHistory = newConversationHistory,
                    // Clear current response when switching languages
                    question = "",
                    response = "",
                    retrievedContextList = emptyList(),
                    isGeneratingResponse = false,
                    isStreamingResponse = false,
                    // Update both language histories
                    englishConversationHistory = when (newLanguage) {
                        AppLanguage.ENGLISH -> newConversationHistory
                        AppLanguage.RUSSIAN -> updatedPreviousLanguageState.englishConversationHistory
                    },
                    russianConversationHistory = when (newLanguage) {
                        AppLanguage.ENGLISH -> updatedPreviousLanguageState.russianConversationHistory
                        AppLanguage.RUSSIAN -> newConversationHistory
                    }
                )

                // Show language switch message
                Toast.makeText(
                    application,
                    when (newLanguage) {
                        AppLanguage.ENGLISH -> "Switched to English"
                        AppLanguage.RUSSIAN -> "Переключено на русский"
                    },
                    Toast.LENGTH_SHORT
                ).show()
            }

            // NEW: Clear all chat history event handler
            is ChatScreenUIEvent.ClearAllChatHistory -> {
                Log.d("ChatViewModel", "Clearing all chat history...")

                // Clear from storage
                chatHistoryStorage.clearAllChatHistory()

                // Clear from current state
                _chatScreenUIState.value = _chatScreenUIState.value.copy(
                    conversationHistory = emptyList(),
                    englishConversationHistory = emptyList(),
                    russianConversationHistory = emptyList(),
                    question = "",
                    response = "",
                    retrievedContextList = emptyList()
                )

                Toast.makeText(
                    application,
                    when (_chatScreenUIState.value.selectedLanguage) {
                        AppLanguage.ENGLISH -> "Chat history cleared"
                        AppLanguage.RUSSIAN -> "История чата очищена"
                    },
                    Toast.LENGTH_SHORT
                ).show()
            }

            is ChatScreenUIEvent.ResponseGeneration.Start -> {
                if (!_chatScreenUIState.value.isSystemReady) {
                    Toast.makeText(
                        application,
                        "System is still initializing. Please wait...",
                        Toast.LENGTH_LONG,
                    ).show()
                    return
                }

                if (event.query.trim().isEmpty()) {
                    Toast.makeText(
                        application,
                        "Enter a query to execute",
                        Toast.LENGTH_LONG
                    ).show()
                    return
                }

                // Add user message to conversation
                val userMessage = ChatMessage(
                    content = event.query,
                    isFromUser = true
                )

                val updatedHistory = _chatScreenUIState.value.conversationHistory + userMessage

                _chatScreenUIState.value = _chatScreenUIState.value.copy(
                    isGeneratingResponse = true,
                    isStreamingResponse = true,
                    question = event.query,
                    response = "", // Clear for new response
                    conversationHistory = updatedHistory
                )

                getAnswer(event.query, updatedHistory)
            }

            is ChatScreenUIEvent.ResponseGeneration.StopWithSuccess -> {
                Log.i(
                    "ChatViewModel",
                    "SEEING SUCCESS",
                )
                // Add assistant response to conversation
                val currentLanguage = _chatScreenUIState.value.selectedLanguage

                // Add assistant response to conversation
                val assistantMessage = ChatMessage(
                    content = event.response,
                    isFromUser = false
                )

                val currentHistory = _chatScreenUIState.value.conversationHistory
                val updatedHistory = currentHistory + assistantMessage

                chatHistoryStorage.saveChatHistory(currentLanguage, updatedHistory)


                Log.d("ChatViewModel", "STOP SUCCESS...")

                _chatScreenUIState.value = _chatScreenUIState.value.copy(
                    isGeneratingResponse = false,
                    isStreamingResponse = false,
                    response = event.response,
                    retrievedContextList = event.retrievedContextList,
                    conversationHistory = updatedHistory,
// CRITICAL: Update the correct language-specific history
                    englishConversationHistory = if (currentLanguage == AppLanguage.ENGLISH) {
                        updatedHistory
                    } else {
                        _chatScreenUIState.value.englishConversationHistory
                    },
                    russianConversationHistory = if (currentLanguage == AppLanguage.RUSSIAN) {
                        updatedHistory
                    } else {
                        _chatScreenUIState.value.russianConversationHistory
                    }
                )
            }

            is ChatScreenUIEvent.ResponseGeneration.StopWithError -> {
                _chatScreenUIState.value = _chatScreenUIState.value.copy(
                    isGeneratingResponse = false,
                    isStreamingResponse = false,
                    question = ""
                )
                Toast.makeText(
                    application,
                    "Error generating response: ${event.errorMessage}",
                    Toast.LENGTH_LONG
                ).show()
            }

            is ChatScreenUIEvent.ErrorDialog.Dismiss -> {
                _chatScreenUIState.value = _chatScreenUIState.value.copy(
                    showErrorDialog = false
                )
            }

            is ChatScreenUIEvent.ErrorDialog.Retry -> {
                onChatScreenEvent(ChatScreenUIEvent.ErrorDialog.Dismiss)
                _chatScreenUIState.value = _chatScreenUIState.value.copy(
                    debugLogs = emptyList(),
                    isSystemReady = false
                )
                initializeSystemComponents()
            }

            is ChatScreenUIEvent.ErrorDialog.Show -> {
                _chatScreenUIState.value = _chatScreenUIState.value.copy(
                    showErrorDialog = true,
                    errorDialogTitle = event.title,
                    errorDialogMessage = event.message,
                    errorDialogType = event.type
                )
            }

            is ChatScreenUIEvent.ErrorDialog.ExitApp -> {
                android.os.Process.killProcess(android.os.Process.myPid())
            }

        }
    }

    private fun getAnswer(
        query: String,
        conversationHistory: List<ChatMessage>
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val currentLanguage = _chatScreenUIState.value.selectedLanguage
                val isRussian = currentLanguage == AppLanguage.RUSSIAN
                Log.d("ChatViewModel", "Processing query in ${currentLanguage.displayName}: $query")

                // Step 1: Translate query to English if needed
                val englishQuery = if (isRussian) {
                    Log.d("ChatViewModel", "Translating Russian query to English...")
                    translationService.translateToEnglish(query)
                } else {
                    query
                }

                Log.d("ChatViewModel", "English query: $englishQuery")

                // Step 2: Rewrite query to standalone format for better context retrieval
                val fused = fusedQueryForRetrieval(englishQuery, conversationHistory)

                // Step 3: RAG retrieval using Rewritten query
                var jointContext = ""
                val retrievedContextList = ArrayList<RetrievedContext>()
                val queryEmbedding = sentenceEncoder.encodeText(fused)

                // Get similar chunks and deduplicate more aggressively
                val allChunks = chunksDB.getSimilarChunks(queryEmbedding, n = 2)
                val seenContent = mutableSetOf<String>()

                for ((score, chunk) in allChunks) {
                    // More aggressive deduplication - check for exact substring matches
                    val chunkText = chunk.chunkData.trim()
                    val isDuplicate = seenContent.any { existing ->
                        // Check if this chunk is a substring of existing or vice versa
                        existing.contains(chunkText) || chunkText.contains(existing) ||
                                calculateSimpleSimilarity(
                                    existing,
                                    chunkText
                                ) > 0.5  // Lower threshold
                    }

                    if (!isDuplicate && retrievedContextList.size <= 2) {
                        seenContent.add(chunkText)
                        retrievedContextList.add(
                            RetrievedContext(
                                chunk.docFileName,
                                chunkText
                            )
                        )
                        Log.d("ChatViewModel", "Added unique chunk with score: $score")
                    }
                }

                jointContext = retrievedContextList.joinToString("\n----------\n") { it.context }

                Log.d("ChatViewModel", "Final context for query '$fused':")
                Log.d("ChatViewModel", jointContext)
                Log.d("ChatViewModel", "Context length: ${jointContext.length} chars")
                Log.d(
                    "ChatViewModel",
                    "Conversation history size: ${conversationHistory.size} messages"
                )

                // Step 4: Generate response using English RAG prompt

                    try {
                        // Build enhanced RAG prompt
                        val ragPrompt = mediaPipeLLM.buildPromptWithHistory(englishQuery, jointContext, conversationHistory)

                        var fullEnglishResponse = ""
                        mediaPipeLLM.generateResponseStreaming(
                            ragPrompt,
                        ) { partialResult: String, done: Boolean ->
                            fullEnglishResponse += partialResult

                            // Step 4: Handle streaming based on language
                            if (isRussian) {
                                // Show English with translation indicator for streaming
                                _chatScreenUIState.value = _chatScreenUIState.value.copy(
                                    response = "$fullEnglishResponse\n\n(переводится...)"
                                )
                            } else {
                                // Show English directly
                                _chatScreenUIState.value = _chatScreenUIState.value.copy(
                                    response = fullEnglishResponse
                                )
                            }

                            if (done) {
                                // OPTIMIZATION 7: Handle final translation efficiently
                                if (isRussian) {
                                    Log.d(
                                        "ChatViewModel",
                                        "Translating final response to Russian..."
                                    )

                                    // Use IO dispatcher for translation, Main for UI update
                                    viewModelScope.launch(Dispatchers.IO) {
                                        try {
                                            val russianResponse =
                                                translationService.translateToRussian(
                                                    fullEnglishResponse
                                                )

                                            withContext(Dispatchers.Main) {
                                                Log.d("ChatViewModel", "Updating state with Russian response")
                                                onChatScreenEvent(
                                                    ChatScreenUIEvent.ResponseGeneration.StopWithSuccess(
                                                        russianResponse,
                                                        retrievedContextList,
                                                    )
                                                )

                                            }


                                        } catch (e: Exception) {
                                            Log.e(
                                                "ChatViewModel",
                                                "Translation failed, using English",
                                                e
                                            )
                                            onChatScreenEvent(
                                                ChatScreenUIEvent.ResponseGeneration.StopWithSuccess(
                                                    fullEnglishResponse,
                                                    retrievedContextList,
                                                )
                                            )
                                        }
                                    }
                                } else {

                                    viewModelScope.launch(Dispatchers.Main) {
                                        onChatScreenEvent(
                                            ChatScreenUIEvent.ResponseGeneration.StopWithSuccess(
                                                fullEnglishResponse,
                                                retrievedContextList,
                                            )
                                        )
                                    }
                                }
                            }
                        }

                    } catch (e: Exception) {
                        onChatScreenEvent(
                            ChatScreenUIEvent.ResponseGeneration.StopWithError(
                                e.message ?: "Unknown error occurred"
                            )
                        )
                    }

            } catch (e: Exception) {
                onChatScreenEvent(
                    ChatScreenUIEvent.ResponseGeneration.StopWithError(
                        e.message ?: "Unknown error occurred"
                    )
                )
            }
        }
    }

    private fun fusedQueryForRetrieval(
        current: String,
        history: List<ChatMessage>
    ): String {
        val lastTurns = history.takeLast(2).joinToString("\n") {
            if (it.isFromUser) "Human: ${it.content}" else "Assistant: ${it.content}"
        }
        return """
    Context:
    $lastTurns

    Question:
    $current
    """.trimIndent()
    }

    private fun calculateSimpleSimilarity(text1: String, text2: String): Double {
        // Remove section numbers and common words for better comparison
        val cleanText1 = text1.replace(Regex("\\d+\\.\\d+\\s+"), "").lowercase()
        val cleanText2 = text2.replace(Regex("\\d+\\.\\d+\\s+"), "").lowercase()

        val words1 = cleanText1.split(Regex("\\W+")).filter { it.length > 3 }.toSet()
        val words2 = cleanText2.split(Regex("\\W+")).filter { it.length > 3 }.toSet()

        val intersection = words1.intersect(words2).size
        val union = words1.union(words2).size
        return if (union == 0) 0.0 else intersection.toDouble() / union.toDouble()
    }
}

