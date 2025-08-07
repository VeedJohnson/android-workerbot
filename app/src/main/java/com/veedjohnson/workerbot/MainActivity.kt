package com.veedjohnson.workerbot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.veedjohnson.workerbot.ui.screens.chat.ChatScreen
import com.veedjohnson.workerbot.ui.screens.chat.ChatViewModel
import com.veedjohnson.workerbot.ui.screens.splash.SplashScreen
import org.koin.androidx.compose.koinViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: ChatViewModel = koinViewModel()
            val chatScreenUIState by viewModel.chatScreenUIState.collectAsState()
            var showSplash by remember { mutableStateOf(true) }

            if (showSplash) {
                SplashScreen(
                    onSplashFinished = {
                        showSplash = false
                    }
                )
            } else {
                // Initialize system when splash finishes and chat screen appears
                LaunchedEffect(Unit) {
                    viewModel.initializeSystemComponents()
                }

                ChatScreen(
                    screenUiState = chatScreenUIState,
                    onScreenEvent = { viewModel.onChatScreenEvent(it) },
                )
            }
        }
    }
}
