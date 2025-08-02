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
}

data class ChatScreenUIState(
    val question: String = "",
    val response: String = "",
    val isGeneratingResponse: Boolean = false,
    val isStreamingResponse: Boolean = false,
    val retrievedContextList: List<RetrievedContext> = emptyList(),
    val isInitializingKnowledgeBase: Boolean = false,
    val isInitializingLLM: Boolean = false,
    val isSystemReady: Boolean = false,
    val isDownloadingModel: Boolean = false,
    val downloadProgress: Int = 0,
    val downloadStatus: String = "",
    val conversationHistory: List<ChatMessage> = emptyList(),
    val selectedLanguage: AppLanguage = AppLanguage.ENGLISH,
    // Separate histories for each language
    val englishConversationHistory: List<ChatMessage> = emptyList(),
    val russianConversationHistory: List<ChatMessage> = emptyList(),
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

    fun initializeKnowledgeBase() {
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
            // Step 1: Initialize Knowledge Base
            withContext(Dispatchers.Main) {
                _chatScreenUIState.value = _chatScreenUIState.value.copy(
                    isInitializingKnowledgeBase = true
                )
            }

            Log.d("ChatViewModel", "Starting knowledge base initialization...")

            processKnowledgeBase()
            Log.d("ChatViewModel", "Knowledge base processed successfully")
            knowledgeBaseReady = true

            withContext(Dispatchers.Main) {
                _chatScreenUIState.value = _chatScreenUIState.value.copy(
                    isInitializingKnowledgeBase = false
                )
                Toast.makeText(
                    application,
                    "Knowledge base loaded successfully!",
                    Toast.LENGTH_SHORT
                ).show()
            }
            var isActuallyDownloading = false

            // Step 2: Initialize LLM Model
            withContext(Dispatchers.Main) {
                _chatScreenUIState.value = _chatScreenUIState.value.copy(
                    isInitializingLLM = true,
                )
            }

            Log.d("ChatViewModel", "Starting LLM initialization with download...")
            llmReady = mediaPipeLLM.initializeModel { progress, downloaded, total ->
                // Update download progress on main thread
                viewModelScope.launch(Dispatchers.Main) {
                    val downloadedMB = downloaded / (1024 * 1024)
                    val totalMB = total / (1024 * 1024)

                    val isDownloading = downloaded > 0 && total > 0 && downloaded < total

                    // Set downloading flag only when actual download is happening
                    if (isDownloading && !isActuallyDownloading) {
                        isActuallyDownloading = true
                        Log.d("ChatViewModel", "Actual download detected, switching to download mode")
                    }

                    val status = when {
                        // Model preparation phase (no download)
                        !isDownloading && progress < 100 -> "Preparing AI model..."

                        // Active download phase
                        isDownloading -> "Downloading AI model: ${downloadedMB}MB / ${totalMB}MB"

                        // Download completed, now preparing
                        downloadedMB > 0 && downloadedMB == totalMB -> "Setting up AI model..."

                        // Default preparation
                        else -> "Loading AI model..."
                    }

                    _chatScreenUIState.value = _chatScreenUIState.value.copy(
                        isDownloadingModel = isActuallyDownloading,
                        downloadProgress = progress,
                        downloadStatus = status,
                    )
                }
            }

            withContext(Dispatchers.Main) {
                _chatScreenUIState.value = _chatScreenUIState.value.copy(
                    downloadProgress = if (llmReady) 100 else 0,
                    isDownloadingModel = false,
                    downloadStatus = if (llmReady) "AI model ready!" else "Download failed"
                )
            }

            Log.d("ChatViewModel", "LLM initialization result: $llmReady")

            if (llmReady) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        application,
                        "AI model loaded successfully!",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } else {
                Log.e("ChatViewModel", "LLM initialization failed")
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        application,
                        "Failed to load AI model",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            // Step 3: Initialize Translation Service (NEW)
            Log.d("ChatViewModel", "Starting translation service initialization...")
            translationReady = translationService.initializeTranslators()
            Log.d("ChatViewModel", "Translation service result: $translationReady")


        } catch (e: Exception) {
            Log.e("ChatViewModel", "Initialization error", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    application,
                    "Initialization failed: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        } finally {
            withContext(Dispatchers.Main) {
                _chatScreenUIState.value = _chatScreenUIState.value.copy(
                    isInitializingKnowledgeBase = false,
                    isInitializingLLM = false,
                    isDownloadingModel = false,
                    isSystemReady = knowledgeBaseReady && llmReady && translationReady
                )

                Log.d("ChatViewModel", "Final state - KB: $knowledgeBaseReady, LLM: $llmReady, Ready: ${knowledgeBaseReady && llmReady}")

                if (knowledgeBaseReady && llmReady && translationReady) {
                    Toast.makeText(
                        application,
                        "System ready! You can start chatting.",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Log.e("ChatViewModel", "System initialization incomplete - KB: $knowledgeBaseReady, LLM: $llmReady")
                }
            }
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
                maxChunkSize = 400
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

                // Step 2: RAG retrieval using English query
                var jointContext = ""
                val retrievedContextList = ArrayList<RetrievedContext>()
                val queryEmbedding = sentenceEncoder.encodeText(query)

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

                Log.d("ChatViewModel", "Final context for query '$query':")
                Log.d("ChatViewModel", jointContext)
                Log.d("ChatViewModel", "Context length: ${jointContext.length} chars")
                Log.d(
                    "ChatViewModel",
                    "Conversation history size: ${conversationHistory.size} messages"
                )

                // Step 3: Generate response using English RAG prompt

                    try {
                        // Build enhanced RAG prompt
                        val ragPrompt = mediaPipeLLM.buildRagPrompt(englishQuery, jointContext)

                        var fullEnglishResponse = ""
                        mediaPipeLLM.generateResponseStreaming(
                            ragPrompt,
                            conversationHistory.dropLast(1) // Exclude current user message
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