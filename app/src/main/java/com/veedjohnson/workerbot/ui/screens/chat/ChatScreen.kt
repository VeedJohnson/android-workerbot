package com.veedjohnson.workerbot.ui.screens.chat

import android.content.Intent
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
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.veedjohnson.workerbot.ui.components.AppAlertDialog
import com.veedjohnson.workerbot.ui.theme.AppTheme
import dev.jeziellago.compose.markdowntext.MarkdownText

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
                        // Language selector dropdown
                        LanguageSelector(
                            currentLanguage = screenUiState.selectedLanguage,
                            onLanguageSelected = { language ->
                                onScreenEvent(ChatScreenUIEvent.LanguageChanged(language))
                            }
                        )
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
            }
            AppAlertDialog()
        }
    }
}

@Composable
private fun ChatMessagesArea(
    messages: List<ChatMessage>,
    isGenerating: Boolean,
    isInitializingKnowledgeBase: Boolean,
    isInitializingLLM: Boolean,
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
            EmptyStateView()
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

                if (isInitializingLLM) {
                    item {
                        InitializingMessage("Initializing AI model...")
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
                if (isGenerating && screenUiState.response.isNotEmpty()) {
                    item {
                        ChatMessageItem(
                            message = ChatMessage(
                                content = screenUiState.response,
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
                    if (content.isNotEmpty()) {
                        MarkdownText(
                            markdown = content,
                            style = TextStyle(
                                color = Color.Black,
                                fontSize = 14.sp
                            )
                        )
                    }

                    if (showTypingIndicator) {
                        Spacer(modifier = Modifier.height(4.dp))
                        TypingIndicator()
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

@Composable
private fun ChatInputArea(
    onSendMessage: (String) -> Unit,
    enabled: Boolean = true,
    language: AppLanguage = AppLanguage.ENGLISH
) {
    var messageText by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current

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
                onValueChange = { messageText = it },
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
                enabled = enabled,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
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

            IconButton(
                onClick = {
                    if (messageText.trim().isNotEmpty()) {
                        onSendMessage(messageText.trim())
                        messageText = ""
                        keyboardController?.hide()
                    }
                },
                enabled = enabled && messageText.trim().isNotEmpty(),
                modifier = Modifier
                    .background(
                        color = if (enabled && messageText.trim().isNotEmpty()) Color.Blue else Color.Gray,
                        shape = CircleShape
                    )
                    .size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = when (language) {
                        AppLanguage.RUSSIAN -> "Отправить сообщение"
                        AppLanguage.ENGLISH -> "Send message"
                    },
                    tint = Color.White
                )
            }
        }
    }
}

@Composable
@Preview
private fun ChatScreenPreview() {
    ChatScreen(
        screenUiState = ChatScreenUIState(
            question = "What is the impact of Mumbai?",
            response = "Mumbai is the financial capital of India and accounts for 25% of the nation's industrial output.",
            isGeneratingResponse = false
        ),
        onScreenEvent = { },
    )
}