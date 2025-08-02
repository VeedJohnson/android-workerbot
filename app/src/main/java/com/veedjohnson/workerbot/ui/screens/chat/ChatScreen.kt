package com.veedjohnson.workerbot.ui.screens.chat

import android.Manifest
import android.content.pm.PackageManager
import android.os.LocaleList
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.veedjohnson.workerbot.domain.SpeechToTextService
import com.veedjohnson.workerbot.ui.components.AppAlertDialog
import com.veedjohnson.workerbot.ui.theme.AppTheme
import dev.jeziellago.compose.markdowntext.MarkdownText
import java.util.Locale

// Chat message data class
data class ChatMessage(
    val id: String = System.currentTimeMillis().toString(),
    val content: String,
    val isFromUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false
)

enum class AppLanguage(val code: String, val displayName: String, val flag: String) {
    ENGLISH("en", "English", "🇬🇧"),
    RUSSIAN("ru", "Русский", "🇷🇺")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    screenUiState: ChatScreenUIState,
    onScreenEvent: (ChatScreenUIEvent) -> Unit,
) {
    // Use conversation history from state instead of deriving from current Q&A
    val chatMessages = screenUiState.conversationHistory

    AppTheme {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.SupportAgent,
                                contentDescription = null,
                                tint = Color.Blue,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "WorkerBot",
                                style = MaterialTheme.typography.headlineSmall,
                            )
                        }
                    },
                    actions = {
                        if (screenUiState.isSystemReady) {
                            ClearHistoryButton(
                                currentLanguage = screenUiState.selectedLanguage,
                                totalMessages = screenUiState.conversationHistory.size +
                                        screenUiState.englishConversationHistory.size +
                                        screenUiState.russianConversationHistory.size,
                                onClearHistory = { onScreenEvent(ChatScreenUIEvent.ClearAllChatHistory) }
                            )
                            // Language selector dropdown
                            LanguageSelector(
                                currentLanguage = screenUiState.selectedLanguage,
                                onLanguageSelected = { language ->
                                    onScreenEvent(ChatScreenUIEvent.LanguageChanged(language))
                                }
                            )
                        }
                    }
                )
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                // Chat messages area
                ChatMessagesArea(
                    messages = chatMessages,
                    isGenerating = screenUiState.isGeneratingResponse,
                    isInitializingKnowledgeBase = screenUiState.isInitializingKnowledgeBase,
                    isInitializingLLM = screenUiState.isInitializingLLM,
                    isDownloadingModel = screenUiState.isDownloadingModel,
                    isSystemReady = screenUiState.isSystemReady,
                    screenUiState,
                    modifier = Modifier.weight(1f)
                )



                // Input area
                ChatInputArea(
                    onSendMessage = { message ->
                        onScreenEvent(
                            ChatScreenUIEvent.ResponseGeneration.Start(
                                message,
                            )
                        )
                    },
                    enabled = screenUiState.isSystemReady && !screenUiState.isGeneratingResponse,
                    language = screenUiState.selectedLanguage
                )

                AIDisclaimerBar(screenUiState.selectedLanguage)
            }
            AppAlertDialog()
        }
    }
}

@Composable
private fun AIDisclaimerBar(language: AppLanguage) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color.Gray.copy(alpha = 0.1f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = Color.Gray,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = when (language) {
                    AppLanguage.ENGLISH -> "Responses are AI-generated and may contain errors"
                    AppLanguage.RUSSIAN -> "Ответы генерируются ИИ и могут содержать ошибки"
                },
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ChatMessagesArea(
    messages: List<ChatMessage>,
    isGenerating: Boolean,
    isInitializingKnowledgeBase: Boolean,
    isInitializingLLM: Boolean,
    isDownloadingModel: Boolean,
    isSystemReady: Boolean,
    screenUiState: ChatScreenUIState,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (messages.isEmpty() && isSystemReady) {
            // Empty state - system is ready
            EmptyStateView(language = screenUiState.selectedLanguage)
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (isInitializingKnowledgeBase) {
                    item {
                        InitializingMessage("Loading knowledge base...")
                    }
                }

                if (isInitializingLLM || isDownloadingModel) {
                    item {
                        if (isDownloadingModel) {
                            ModelDownloadMessage(
                                progress = screenUiState.downloadProgress,
                                status = screenUiState.downloadStatus
                            )
                        } else {
                            InitializingMessage("Setting up AI model...")
                        }
                    }
                }

                if (!isSystemReady && !isInitializingKnowledgeBase && !isInitializingLLM) {
                    item {
                        InitializingMessage("System initialization failed. Please restart the app.")
                    }
                }

                items(messages) { message ->
                    ChatMessageItem(
                        message = message,
                        showTypingIndicator = !message.isFromUser &&
                                message == messages.lastOrNull() &&
                                isGenerating
                    )
                }

                // Show streaming response if generating
                if (isGenerating) {
                    item {
                        ChatMessageItem(
                                message = ChatMessage(
                                    content = screenUiState.response ?: "",
                                    isFromUser = false,
                                    isStreaming = true
                                ),
                                showTypingIndicator = screenUiState.isStreamingResponse
                            )
                    }
                }
            }
        }
    }
}

@Composable
fun LanguageSelector(
    currentLanguage: AppLanguage,
    onLanguageSelected: (AppLanguage) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        // Current language display
        Row(
            modifier = Modifier
                .clickable { expanded = true }
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = currentLanguage.flag,
                fontSize = 20.sp
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = currentLanguage.code.uppercase(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        }

        // Dropdown menu
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            AppLanguage.entries.forEach { language ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = language.flag,
                                fontSize = 18.sp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = language.displayName,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    },
                    onClick = {
                        onLanguageSelected(language)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun EmptyStateView(language: AppLanguage = AppLanguage.ENGLISH) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Language,
            contentDescription = null,
            tint = Color.LightGray,
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = when (language) {
                AppLanguage.RUSSIAN -> "Спросите меня о программе сезонных рабочих!"
                AppLanguage.ENGLISH -> "Ask me about the Seasonal Worker Scheme!"
            },
            style = MaterialTheme.typography.bodyLarge,
            color = Color.Gray,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = when (language) {
                AppLanguage.RUSSIAN -> "Я готов помочь вам найти информацию"
                AppLanguage.ENGLISH -> "I'm ready to help you find information"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = Color.LightGray,
            textAlign = TextAlign.Center
        )
    }
}


@Composable
private fun InitializingMessage(message: String = "Initializing...") {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        AssistantMessageBubble(
            content = message,
            showTypingIndicator = true
        )
    }
}

@Composable
private fun ChatMessageItem(
    message: ChatMessage,
    showTypingIndicator: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isFromUser) Arrangement.End else Arrangement.Start
    ) {
        if (message.isFromUser) {
            UserMessageBubble(content = message.content)
        } else {
            AssistantMessageBubble(
                content = message.content,
                showTypingIndicator = showTypingIndicator
            )
        }
    }
}

@Composable
private fun UserMessageBubble(content: String) {
    Card(
        modifier = Modifier.widthIn(max = 280.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Blue),
        shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = content,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

// NEW: Clear History Button Component
@Composable
private fun ClearHistoryButton(
    currentLanguage: AppLanguage,
    totalMessages: Int,
    onClearHistory: () -> Unit
) {
    var showConfirmDialog by remember { mutableStateOf(false) }

    // Only show button if there are messages to clear
    if (totalMessages > 0) {
        IconButton(
            onClick = { showConfirmDialog = true }
        ) {
            Icon(
                imageVector = Icons.Default.DeleteSweep,
                contentDescription = when (currentLanguage) {
                    AppLanguage.ENGLISH -> "Clear chat history"
                    AppLanguage.RUSSIAN -> "Очистить историю чата"
                },
                tint = Color.Gray
            )
        }

        // Confirmation dialog
        if (showConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showConfirmDialog = false },
                title = {
                    Text(
                        text = when (currentLanguage) {
                            AppLanguage.ENGLISH -> "Clear Chat History"
                            AppLanguage.RUSSIAN -> "Очистить историю чата"
                        }
                    )
                },
                text = {
                    Text(
                        text = when (currentLanguage) {
                            AppLanguage.ENGLISH -> "This will permanently delete all chat history in both languages. This action cannot be undone."
                            AppLanguage.RUSSIAN -> "Это навсегда удалит всю историю чата на обоих языках. Это действие нельзя отменить."
                        }
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onClearHistory()
                            showConfirmDialog = false
                        }
                    ) {
                        Text(
                            text = when (currentLanguage) {
                                AppLanguage.ENGLISH -> "Clear"
                                AppLanguage.RUSSIAN -> "Очистить"
                            },
                            color = Color.Red
                        )
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showConfirmDialog = false }
                    ) {
                        Text(
                            text = when (currentLanguage) {
                                AppLanguage.ENGLISH -> "Cancel"
                                AppLanguage.RUSSIAN -> "Отмена"
                            }
                        )
                    }
                }
            )
        }
    }
}

@Composable
private fun ModelDownloadMessage(
    progress: Int,
    status: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Card(
            modifier = Modifier.widthIn(max = 320.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = Icons.Default.CloudDownload,
                        contentDescription = null,
                        tint = Color.Blue,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = status,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Black
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        // Progress bar
                        LinearProgressIndicator(
                            progress = { progress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            color = Color.Blue,
                        )

                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "$progress%",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantMessageBubble(
    content: String,
    showTypingIndicator: Boolean = false
) {
    val context = LocalContext.current

    Card(
        modifier = Modifier.widthIn(max = 280.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    imageVector = Icons.Default.SupportAgent,
                    contentDescription = null,
                    tint = Color.Blue,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    // Show content if available
                    if (content.isNotEmpty()) {
                        MarkdownText(
                            markdown = content,
                            style = TextStyle(
                                color = Color.Black,
                                fontSize = 14.sp
                            )
                        )
                    }

                    // Show typing indicator
                    if (showTypingIndicator) {
                        // Add spacing only if there's content above
                        if (content.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        TypingIndicator()
                    }

                    // Ensure minimum height when showing only typing indicator
                    if (content.isEmpty() && showTypingIndicator) {
                        // This ensures the bubble has proper height even with just typing indicator
                        Spacer(modifier = Modifier.height(0.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun TypingIndicator() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        repeat(3) { index ->
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(Color.Gray)
            )
            if (index < 2) {
                Spacer(modifier = Modifier.width(4.dp))
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "typing...",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
        )
    }
}


enum class SpeechState {
    READY,      // Green mic icon - ready to listen
    LISTENING,  // Red stop icon - actively listening
    PROCESSING, // Loading spinner - processing speech
    ERROR       // Gray mic with error state
}

@Composable
private fun ChatInputArea(
    onSendMessage: (String) -> Unit,
    enabled: Boolean = true,
    language: AppLanguage = AppLanguage.ENGLISH
) {
    var messageText by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current
    val context = LocalContext.current

    // Enhanced speech state
    var speechState by remember { mutableStateOf(SpeechState.READY) }
    var speechError by remember { mutableStateOf<String?>(null) }
    var volumeLevel by remember { mutableFloatStateOf(0f) }
    var wasProcessing by remember { mutableStateOf(false) }

    val speechService = remember { SpeechToTextService(context) }

    // FIXED: Watch for text changes to clear processing state
    LaunchedEffect(messageText) {
        // If we were processing and now have text, speech recognition succeeded
        if (wasProcessing && messageText.isNotEmpty() && speechState == SpeechState.PROCESSING) {
            speechState = SpeechState.READY
            wasProcessing = false
        }
    }

    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startSpeechRecognition(speechService, language) { result ->
                when (result) {
                    is SpeechResult.Success -> {
                        messageText = result.text
                        speechState = SpeechState.READY
                        speechError = null
                        wasProcessing = false
                    }
                    is SpeechResult.Error -> {
                        speechState = SpeechState.ERROR
                        speechError = result.message
                        wasProcessing = false
                    }
                    is SpeechResult.ListeningStarted -> {
                        speechState = SpeechState.LISTENING
                        speechError = null
                        wasProcessing = false
                    }
                    is SpeechResult.ListeningStopped -> {
                        speechState = SpeechState.PROCESSING
                        wasProcessing = true
                    }
                    is SpeechResult.VolumeChanged -> {
                        volumeLevel = result.volume
                    }
                }
            }
        } else {
            speechState = SpeechState.ERROR
            speechError = when (language) {
                AppLanguage.ENGLISH -> "Microphone permission required"
                AppLanguage.RUSSIAN -> "Требуется разрешение микрофона"
            }
        }
    }

    // Cleanup on dispose
    DisposableEffect(Unit) {
        onDispose { speechService.cleanup() }
    }

    Column {
        // Error display
        speechError?.let { error ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Red.copy(alpha = 0.1f))
            ) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = Color.Red,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Red
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            speechError = null
                            speechState = SpeechState.READY
                            wasProcessing = false
                        }
                    ) {
                        Text("Dismiss", color = Color.Red)
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                OutlinedTextField(
                    value = messageText,
                    onValueChange = { newText ->
                        messageText = newText
                        speechError = null
                        // Reset state when user manually types (not from speech)
                        if (speechState == SpeechState.ERROR && newText.isNotEmpty()) {
                            speechState = SpeechState.READY
                        }
                    },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            text = when (language) {
                                AppLanguage.RUSSIAN -> "Спросите о программе сезонных рабочих..."
                                AppLanguage.ENGLISH -> "Ask about the Seasonal Worker Scheme..."
                            },
                            color = Color.Gray
                        )
                    },
                    enabled = enabled && speechState != SpeechState.LISTENING && speechState != SpeechState.PROCESSING,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        autoCorrectEnabled = true,
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Send
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (messageText.trim().isNotEmpty()) {
                                onSendMessage(messageText.trim())
                                messageText = ""
                                keyboardController?.hide()
                            }
                        }
                    ),
                    shape = RoundedCornerShape(24.dp)
                )

                Spacer(modifier = Modifier.width(8.dp))

                // Enhanced speech button with loading state
                EnhancedSpeechButton(
                    speechState = speechState,
                    volumeLevel = volumeLevel,
                    enabled = enabled,
                    language = language,
                    onClick = {
                        when (speechState) {
                            SpeechState.READY, SpeechState.ERROR -> {
                                // Start speech recognition
                                when (PackageManager.PERMISSION_GRANTED) {
                                    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) -> {
                                        startSpeechRecognition(speechService, language) { result ->
                                            when (result) {
                                                is SpeechResult.Success -> {
                                                    messageText = result.text
                                                    speechState = SpeechState.READY
                                                    speechError = null
                                                    wasProcessing = false
                                                }
                                                is SpeechResult.Error -> {
                                                    speechState = SpeechState.ERROR
                                                    speechError = result.message
                                                    wasProcessing = false
                                                }
                                                is SpeechResult.ListeningStarted -> {
                                                    speechState = SpeechState.LISTENING
                                                    speechError = null
                                                    wasProcessing = false
                                                }
                                                is SpeechResult.ListeningStopped -> {
                                                    speechState = SpeechState.PROCESSING
                                                    wasProcessing = true
                                                }
                                                is SpeechResult.VolumeChanged -> {
                                                    volumeLevel = result.volume
                                                }
                                            }
                                        }
                                    }
                                    else -> {
                                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                }
                            }
                            SpeechState.LISTENING -> {
                                // Stop listening
                                speechService.stopListening()
                            }
                            SpeechState.PROCESSING -> {
                                // Can't interact while processing
                            }
                        }
                    }
                )

                Spacer(modifier = Modifier.width(8.dp))

                // Send button
                IconButton(
                    onClick = {
                        if (messageText.trim().isNotEmpty()) {
                            onSendMessage(messageText.trim())
                            messageText = ""
                            keyboardController?.hide()
                        }
                    },
                    enabled = enabled && messageText.trim().isNotEmpty() &&
                            speechState != SpeechState.LISTENING && speechState != SpeechState.PROCESSING,
                    modifier = Modifier
                        .background(
                            color = if (enabled && messageText.trim().isNotEmpty() &&
                                speechState != SpeechState.LISTENING && speechState != SpeechState.PROCESSING)
                                Color.Blue else Color.Gray,
                            shape = CircleShape
                        )
                        .size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = when (language) {
                            AppLanguage.RUSSIAN -> "Отправить"
                            AppLanguage.ENGLISH -> "Send"
                        },
                        tint = Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun EnhancedSpeechButton(
    speechState: SpeechState,
    volumeLevel: Float,
    enabled: Boolean,
    language: AppLanguage,
    onClick: () -> Unit
) {
    // Animations for different states
    val infiniteTransition = rememberInfiniteTransition(label = "speech_animation")

    // Pulsing animation for listening
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (speechState == SpeechState.LISTENING) 1.15f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    // Rotation animation for processing
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = if (speechState == SpeechState.PROCESSING) 360f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    IconButton(
        onClick = onClick,
        enabled = enabled && speechState != SpeechState.PROCESSING,
        modifier = Modifier
            .background(
                color = when (speechState) {
                    SpeechState.READY -> Color.Green
                    SpeechState.LISTENING -> Color.Red
                    SpeechState.PROCESSING -> Color.Green
                    SpeechState.ERROR -> Color.Gray
                },
                shape = CircleShape
            )
            .size(48.dp)
            .scale(if (speechState == SpeechState.LISTENING) pulseScale else 1f)
            .graphicsLayer {
                rotationZ = if (speechState == SpeechState.PROCESSING) rotationAngle else 0f
            }
    ) {
        when (speechState) {
            SpeechState.READY, SpeechState.ERROR -> {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = when (language) {
                        AppLanguage.ENGLISH -> "Voice input"
                        AppLanguage.RUSSIAN -> "Голосовой ввод"
                    },
                    tint = Color.White
                )
            }
            SpeechState.LISTENING -> {
                Icon(
                    imageVector = Icons.Default.Stop,
                    contentDescription = when (language) {
                        AppLanguage.ENGLISH -> "Stop listening"
                        AppLanguage.RUSSIAN -> "Остановить"
                    },
                    tint = Color.White
                )
            }
            SpeechState.PROCESSING -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = Color.White,
                    strokeWidth = 3.dp
                )
            }
        }
    }
}

// Speech result sealed class
sealed class SpeechResult {
    data class Success(val text: String) : SpeechResult()
    data class Error(val message: String) : SpeechResult()
    data object ListeningStarted : SpeechResult()
    data object ListeningStopped : SpeechResult()
    data class VolumeChanged(val volume: Float) : SpeechResult()
}

// Helper function to start speech recognition
private fun startSpeechRecognition(
    speechService: SpeechToTextService,
    language: AppLanguage,
    onResult: (SpeechResult) -> Unit
) {
    speechService.startListening(
        language = language,
        callback = object : SpeechToTextService.SpeechRecognitionCallback {
            override fun onSpeechResult(text: String) {
                onResult(SpeechResult.Success(text))
            }

            override fun onSpeechError(error: String) {
                onResult(SpeechResult.Error(error))
            }

            override fun onListeningStarted() {
                onResult(SpeechResult.ListeningStarted)
            }

            override fun onListeningStopped() {
                onResult(SpeechResult.ListeningStopped)
            }

            override fun onVolumeChanged(volume: Float) {
                onResult(SpeechResult.VolumeChanged(volume))
            }
        }
    )
}


@Composable
@Preview
private fun ChatScreenPreview() {
    ChatScreen(
        screenUiState = ChatScreenUIState(
            question = "What kind of jobs can I do on seasonal work visa?",
            response = "You can do jobs such as picking fruits and vegetables",
            isGeneratingResponse = false
        ),
        onScreenEvent = { },
    )
}