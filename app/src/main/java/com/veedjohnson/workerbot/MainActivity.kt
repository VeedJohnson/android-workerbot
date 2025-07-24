package com.veedjohnson.workerbot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.veedjohnson.workerbot.ui.screens.chat.ChatScreen
import com.veedjohnson.workerbot.ui.screens.chat.ChatViewModel
import org.koin.androidx.compose.koinViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: ChatViewModel = koinViewModel()
            val chatScreenUIState by viewModel.chatScreenUIState.collectAsState()

            // Initialize knowledge base on app start
            LaunchedEffect(Unit) {
                viewModel.initializeKnowledgeBase()
            }

            ChatScreen(
                screenUiState = chatScreenUIState,
                onScreenEvent = { viewModel.onChatScreenEvent(it) },
            )
        }
    }
}
