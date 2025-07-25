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
import com.veedjohnson.workerbot.domain.KnowledgeBaseLoader
import com.veedjohnson.workerbot.domain.MediaPipeLLMAPI
import com.veedjohnson.workerbot.domain.SentenceEmbeddingProvider
import com.veedjohnson.workerbot.domain.Chunker
import com.veedjohnson.workerbot.domain.TranslationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.annotation.KoinViewModel

// Simplified events
sealed interface ChatScreenUIEvent {
    data class LanguageChanged(val language: AppLanguage) : ChatScreenUIEvent

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
    private val translationService: TranslationService
) : ViewModel() {

    private val _chatScreenUIState = MutableStateFlow(ChatScreenUIState())
    val chatScreenUIState: StateFlow<ChatScreenUIState> = _chatScreenUIState

    fun initializeKnowledgeBase() {
        viewModelScope.launch(Dispatchers.IO) {
            // Initialize both knowledge base and LLM
            initializeSystem()
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

            // Check if knowledge base is already loaded
//            if (documentsDB.getDocsCount() == 0L) {
//                Log.d("ChatViewModel", "Knowledge base not found, processing from assets...")
//                processKnowledgeBase()
//                Log.d("ChatViewModel", "Knowledge base processed successfully")
//            } else {
//                Log.d("ChatViewModel", "Knowledge base already exists, skipping...")
//            }

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

            // Step 2: Initialize LLM Model
            withContext(Dispatchers.Main) {
                _chatScreenUIState.value = _chatScreenUIState.value.copy(
                    isInitializingLLM = true
                )
            }

            Log.d("ChatViewModel", "Starting LLM initialization...")
            llmReady = mediaPipeLLM.initializeModel()
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

            if (translationReady) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(application, "Translation service ready!", Toast.LENGTH_SHORT).show()
                }
            }

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

            // Add document to DB
            Log.d("ChatViewModel", "Adding document to database...")
            val docId = documentsDB.addDocument(
                Document(
                    docText = knowledgeBaseText,
                    docFileName = "knowledge_base_eng.txt",
                    docAddedTime = System.currentTimeMillis(),
                )
            )
            Log.d("ChatViewModel", "Document added with ID: $docId")

            // Create chunks using structured chunking that respects document format
            Log.d("ChatViewModel", "Creating structured chunks...")
            val chunks = Chunker.createStructuredChunks(
                knowledgeBaseText,
                maxChunkSize = 450  // Slightly larger since we respect boundaries
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

     fun onChatScreenEvent(event: ChatScreenUIEvent) {
        when (event) {
            is ChatScreenUIEvent.LanguageChanged -> {
                val previousLanguage = _chatScreenUIState.value.selectedLanguage
                val newLanguage = event.language

                Log.d("ChatViewModel", "Language changed from ${previousLanguage.displayName} to ${newLanguage.displayName}")

                // First, save current conversation to the previous language's history
                val currentState = _chatScreenUIState.value

                // Then, load the conversation history for the new language
                val newConversationHistory = when (newLanguage) {
                    AppLanguage.ENGLISH -> currentState.englishConversationHistory
                    AppLanguage.RUSSIAN -> currentState.russianConversationHistory
                }

                Log.d("ChatViewModel", "Loading ${newLanguage.displayName} history with ${newConversationHistory.size} messages")

                _chatScreenUIState.value = _chatScreenUIState.value.copy(
                    selectedLanguage = newLanguage,
                    conversationHistory = newConversationHistory,
                    // Clear current response when switching languages
                    question = "",
                    response = "",
                    retrievedContextList = emptyList(),
                    isGeneratingResponse = false,
                    isStreamingResponse = false
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
                // Add assistant response to conversation
                val currentLanguage = _chatScreenUIState.value.selectedLanguage

                // Add assistant response to conversation
                val assistantMessage = ChatMessage(
                    content = event.response,
                    isFromUser = false
                )

                val currentHistory = _chatScreenUIState.value.conversationHistory
                val updatedHistory = currentHistory + assistantMessage

                // Update the appropriate language-specific history
                val updatedState = when (currentLanguage) {
                    AppLanguage.ENGLISH -> _chatScreenUIState.value.copy(
                        englishConversationHistory = updatedHistory,
                        conversationHistory = updatedHistory
                    )
                    AppLanguage.RUSSIAN -> _chatScreenUIState.value.copy(
                        russianConversationHistory = updatedHistory,
                        conversationHistory = updatedHistory
                    )
                }

                _chatScreenUIState.value = _chatScreenUIState.value.copy(
                    isGeneratingResponse = false,
                    isStreamingResponse = false,
                    response = event.response,
                    retrievedContextList = event.retrievedContextList,
                    conversationHistory = updatedHistory
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
                val allChunks = chunksDB.getSimilarChunks(queryEmbedding, n = 3)
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

                    if (!isDuplicate && retrievedContextList.size <= 3) {
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
                CoroutineScope(Dispatchers.IO).launch {
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
                                // Step 5: Translate final response if needed
                                if (isRussian) {
                                    Log.d(
                                        "ChatViewModel",
                                        "Translating final response to Russian..."
                                    )
                                    CoroutineScope(Dispatchers.IO).launch {
                                        try {
                                            val russianResponse =
                                                translationService.translateToRussian(
                                                    fullEnglishResponse
                                                )
                                            onChatScreenEvent(
                                                ChatScreenUIEvent.ResponseGeneration.StopWithSuccess(
                                                    russianResponse,
                                                    retrievedContextList,
                                                )
                                            )
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
                                    onChatScreenEvent(
                                        ChatScreenUIEvent.ResponseGeneration.StopWithSuccess(
                                            fullEnglishResponse,
                                            retrievedContextList,
                                        )
                                    )
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



//import android.app.Application
//import android.util.Log
//import android.widget.Toast
//import androidx.lifecycle.ViewModel
//import androidx.lifecycle.viewModelScope
//import kotlinx.coroutines.CoroutineScope
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.flow.MutableStateFlow
//import kotlinx.coroutines.flow.StateFlow
//import kotlinx.coroutines.flow.update
//import kotlinx.coroutines.launch
//import kotlinx.coroutines.withContext
//import org.koin.android.annotation.KoinViewModel
//
//// Simplified events - removed navigation events since we only have chat
//sealed interface ChatScreenUIEvent {
//    sealed class ResponseGeneration {
//        data class Start(
//            val query: String,
//            val prompt: String,
//        ) : ChatScreenUIEvent
//
//        data class StopWithSuccess(
//            val response: String,
//            val retrievedContextList: List<RetrievedContext>,
//        ) : ChatScreenUIEvent
//
//        data class StopWithError(
//            val errorMessage: String,
//        ) : ChatScreenUIEvent
//    }
//}
//
//data class ChatScreenUIState(
//    val question: String = "",
//    val response: String = "",
//    val isGeneratingResponse: Boolean = false,
//    val isStreamingResponse: Boolean = false,
//    val retrievedContextList: List<RetrievedContext> = emptyList(),
//    val isInitializingKnowledgeBase: Boolean = false,
//    val isInitializingLLM: Boolean = false,
//    val isSystemReady: Boolean = false,
//    val conversationHistory: List<ChatMessage> = emptyList(),
//)
//
//@KoinViewModel
//class ChatViewModel(
//    private val application: Application,
//    private val documentsDB: DocumentsDB,
//    private val chunksDB: ChunksDB,
//    private val sentenceEncoder: SentenceEmbeddingProvider,
//    private val knowledgeBaseLoader: KnowledgeBaseLoader,
//    private val mediaPipeLLM: MediaPipeLLMAPI
//) : ViewModel() {
//
//    private val _chatScreenUIState = MutableStateFlow(ChatScreenUIState())
//    val chatScreenUIState: StateFlow<ChatScreenUIState> = _chatScreenUIState
//
//    init {
//        viewModelScope.launch {
//            // 1) load KB
//            _chatScreenUIState.update { it.copy(isInitializingKnowledgeBase = true) }
//            runCatching { processKnowledgeBase() }
//                .onFailure { /* you can set an error flag here*/ }
//            _chatScreenUIState.update { it.copy(isInitializingKnowledgeBase = false) }
//
//            // 2) init LLM
//            _chatScreenUIState.update { it.copy(isInitializingLLM = true) }
//            val llmOk = mediaPipeLLM.initializeModel()
//            _chatScreenUIState.update {
//                it.copy(
//                    isInitializingLLM = false,
//                    isSystemReady      = llmOk
//                )
//            }
//        }
//    }
//
//    fun initializeKnowledgeBase() {
//        viewModelScope.launch(Dispatchers.IO) {
//            // Check if knowledge base is already loaded
//            if (documentsDB.getDocsCount() == 0L) {
//                withContext(Dispatchers.Main) {
//                    _chatScreenUIState.value = _chatScreenUIState.value.copy(
//                        isInitializingKnowledgeBase = true
//                    )
//                }
//
//                try {
//                    processKnowledgeBase()
//                    withContext(Dispatchers.Main) {
//                        Toast.makeText(
//                            application,
//                            "Knowledge base loaded successfully!",
//                            Toast.LENGTH_SHORT
//                        ).show()
//                    }
//                } catch (e: Exception) {
//                    withContext(Dispatchers.Main) {
//                        Toast.makeText(
//                            application,
//                            "Failed to load knowledge base: ${e.message}",
//                            Toast.LENGTH_LONG
//                        ).show()
//                    }
//                } finally {
//                    withContext(Dispatchers.Main) {
//                        _chatScreenUIState.value = _chatScreenUIState.value.copy(
//                            isInitializingKnowledgeBase = false
//                        )
//                    }
//                }
//            }
//        }
//    }
//
//    private suspend fun processKnowledgeBase() {
//        Log.i("ChatViewModel", "Starting process knowledge base")
//
//        try {
//            val knowledgeBaseText = knowledgeBaseLoader.loadKnowledgeBase()
//
//            // Add document to DB
//            val docId = documentsDB.addDocument(
//                Document(
//                    docText = knowledgeBaseText,
//                    docFileName = "knowledge_base_eng.txt",
//                    docAddedTime = System.currentTimeMillis(),
//                )
//            )
//
//            // Create chunks
////            val chunks = WhiteSpaceSplitter.createChunks(
////                knowledgeBaseText,
////                chunkSize = 500,
////                chunkOverlap = 50,
////            )
//            Log.d("ChatViewModel", "Creating structured chunks...")
//            val chunks = WordSlidingSplitter.createChunks(
//                knowledgeBaseText,
//                maxChunkChars = 500,
//                overlapWords   = 20    // e.g. overlap 20 words
//            )
//
//            Log.d("ChatViewModel", "Created ${chunks.size} structured chunks")
//
//            // Log first few chunks to verify quality
//            chunks.take(3).forEachIndexed { index, chunk ->
//                Log.d("ChatViewModel", "Chunk $index preview: ${chunk.take(100)}...")
//            }
//
//            // Add chunks with embeddings
//            chunks.forEach { chunk ->
//                val embedding = sentenceEncoder.encodeText(chunk)
//                chunksDB.addChunk(
//                    Chunk(
//                        docId = docId,
//                        docFileName = "knowledge_base_eng.txt",
//                        chunkData = chunk,
//                        chunkEmbedding = embedding,
//                    )
//                )
//            }
//        } catch (e: Exception) {
//            Log.e("ChatViewModel", "Error Processing KB: ${e.message}")
//        }
//
//    }
//
//    fun onChatScreenEvent(event: ChatScreenUIEvent) {
//        when (event) {
//            is ChatScreenUIEvent.ResponseGeneration.Start -> {
//                if (!checkNumDocuments()) {
//                    Toast.makeText(
//                        application,
//                        "Knowledge base is not loaded yet. Please wait...",
//                        Toast.LENGTH_LONG,
//                    ).show()
//                    return
//                }
//
//                if (event.query.trim().isEmpty()) {
//                    Toast.makeText(
//                        application,
//                        "Enter a query to execute",
//                        Toast.LENGTH_LONG
//                    ).show()
//                    return
//                }
//
//                _chatScreenUIState.value = _chatScreenUIState.value.copy(
//                    isGeneratingResponse = true,
//                    isStreamingResponse = true,
//                    question = event.query,
//                    response = "" // Clear previous response
//                )
//
//                getAnswer(event.query, event.prompt)
//            }
//
//            is ChatScreenUIEvent.ResponseGeneration.StopWithSuccess -> {
//                // Add assistant response to conversation
//                val assistantMessage = ChatMessage(
//                    content = event.response,
//                    isFromUser = false
//                )
//
//                val updatedHistory = _chatScreenUIState.value.conversationHistory + assistantMessage
//
//                _chatScreenUIState.value = _chatScreenUIState.value.copy(
//                    isGeneratingResponse = false,
//                    isStreamingResponse = false,
//                    response = event.response,
//                    retrievedContextList = event.retrievedContextList,
//                    conversationHistory = updatedHistory
//                )
//            }
//
//            is ChatScreenUIEvent.ResponseGeneration.StopWithError -> {
//                _chatScreenUIState.value = _chatScreenUIState.value.copy(
//                    isGeneratingResponse = false,
//                    isStreamingResponse = false,
//                    question = ""
//                )
//                Toast.makeText(
//                    application,
//                    "Error generating response: ${event.errorMessage}",
//                    Toast.LENGTH_LONG
//                ).show()
//            }
//        }
//    }
//
//    private fun getAnswer(query: String, prompt: String) {
//        try {
//            var jointContext = ""
//            val retrievedContextList = ArrayList<RetrievedContext>()
//            val queryEmbedding = sentenceEncoder.encodeText(query)
//
//            // Get similar chunks (same logic as before)
//            chunksDB.getSimilarChunks(queryEmbedding, n = 5).forEach {
//                jointContext += " " + it.second.chunkData
//                retrievedContextList.add(
//                    RetrievedContext(
//                        it.second.docFileName,
//                        it.second.chunkData,
//                    ),
//                )
//            }
//
//            val inputPrompt = prompt.replace("\$CONTEXT", jointContext).replace("\$QUERY", query)
//
//            CoroutineScope(Dispatchers.IO).launch {
//                try {
//                    // Initialize model if needed
//                    if (!mediaPipeLLM.initializeModel()) {
//                        onChatScreenEvent(
//                            ChatScreenUIEvent.ResponseGeneration.StopWithError(
//                                "Failed to initialize on-device model"
//                            )
//                        )
//                        return@launch
//                    }
//
//                    // Build enhanced RAG prompt
//                    val ragPrompt = mediaPipeLLM.buildRagPrompt(query, jointContext)
//
//                    // Generate streaming response with MediaPipe
//                    var fullResponse = ""
//                    mediaPipeLLM.generateResponseStreaming(ragPrompt) { partialResult: String, done: Boolean ->
//                        fullResponse += partialResult
//
//                        // Update UI with streaming response
//                        _chatScreenUIState.value = _chatScreenUIState.value.copy(
//                            response = fullResponse
//                        )
//
//                        if (done) {
//                            onChatScreenEvent(
//                                ChatScreenUIEvent.ResponseGeneration.StopWithSuccess(
//                                    fullResponse,
//                                    retrievedContextList,
//                                )
//                            )
//                        }
//                    }
//                } catch (e: Exception) {
//                    onChatScreenEvent(
//                        ChatScreenUIEvent.ResponseGeneration.StopWithError(
//                            e.message ?: "Unknown error occurred"
//                        )
//                    )
//                }
//            }
//        } catch (e: Exception) {
//            onChatScreenEvent(
//                ChatScreenUIEvent.ResponseGeneration.StopWithError(
//                    e.message ?: "Unknown error occurred"
//                )
//            )
//        }
//    }
//
//    private fun checkNumDocuments(): Boolean = documentsDB.getDocsCount() > 0
//}