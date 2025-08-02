package com.veedjohnson.workerbot.domain

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.veedjohnson.workerbot.ui.screens.chat.AppLanguage
import org.koin.core.annotation.Single
import java.util.Locale

@Single
class SpeechToTextService(private val context: Context) {

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    interface SpeechRecognitionCallback {
        fun onSpeechResult(text: String)
        fun onSpeechError(error: String)
        fun onListeningStarted()
        fun onListeningStopped()
        fun onVolumeChanged(volume: Float) // For visual feedback
    }

    fun startListening(
        language: AppLanguage,
        callback: SpeechRecognitionCallback
    ) {
        if (isListening) {
            Log.w("SpeechToText", "Already listening")
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            callback.onSpeechError("Speech recognition not available on this device")
            return
        }

        try {
            // Clean up any existing recognizer
            speechRecognizer?.destroy()

            // Create new speech recognizer
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)

            // Store last partial result as fallback
            var lastPartialResult = ""

            val recognitionListener = object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Log.d("SpeechToText", "Ready for speech")
                    isListening = true
                    callback.onListeningStarted()
                }

                override fun onBeginningOfSpeech() {
                    Log.d("SpeechToText", "User started speaking")
                }

                override fun onRmsChanged(rmsdB: Float) {
                    // Volume level for visual feedback (0.0 to 10.0 typically)
                    callback.onVolumeChanged(rmsdB)
                }

                override fun onBufferReceived(buffer: ByteArray?) {
                    // Audio buffer received
                }

                override fun onEndOfSpeech() {
                    Log.d("SpeechToText", "User stopped speaking")
                    isListening = false
                    callback.onListeningStopped()
                }

                override fun onError(error: Int) {
                    isListening = false
                    val errorMessage = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                        SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                        SpeechRecognizer.ERROR_NETWORK -> "Network error"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                        SpeechRecognizer.ERROR_NO_MATCH -> "No speech match found. Please try again."
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition service busy"
                        SpeechRecognizer.ERROR_SERVER -> "Server error"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input detected"
                        else -> "Unknown error occurred"
                    }
                    Log.e("SpeechToText", "Speech recognition error: $errorMessage")
                    callback.onSpeechError(errorMessage)
                    callback.onListeningStopped()
                }

                override fun onResults(results: Bundle?) {
                    isListening = false
                    results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.let { resultList ->
                        if (resultList.isNotEmpty() && resultList[0].isNotBlank()) {
                            val recognizedText = resultList[0] // Best result
                            Log.d("SpeechToText", "Final speech result: $recognizedText")
                            callback.onSpeechResult(recognizedText)
                        } else {
                            // If no final results but we have partial results, use the last partial result
                            if (lastPartialResult.isNotBlank()) {
                                Log.d("SpeechToText", "Using last partial result: $lastPartialResult")
                                callback.onSpeechResult(lastPartialResult)
                            } else {
                                Log.w("SpeechToText", "No speech results received")
                                callback.onSpeechError("No speech recognized")
                            }
                        }
                    } ?: run {
                        // No results bundle received, use partial result if available
                        if (lastPartialResult.isNotBlank()) {
                            Log.d("SpeechToText", "No results bundle, using last partial result: $lastPartialResult")
                            callback.onSpeechResult(lastPartialResult)
                        } else {
                            Log.w("SpeechToText", "No results received and no partial results")
                            callback.onSpeechError("No results received")
                        }
                    }
                    callback.onListeningStopped()
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    // Store partial results as backup and for live transcription
                    partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.let { resultList ->
                        if (resultList.isNotEmpty() && resultList[0].isNotBlank()) {
                            lastPartialResult = resultList[0]
                            Log.d("SpeechToText", "Partial result: $lastPartialResult")
                            // You could show this as live transcription if needed
                        }
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {
                    // Additional events
                }
            }

            speechRecognizer?.setRecognitionListener(recognitionListener)

            // Create recognition intent
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, getLocaleForLanguage(language).toString())
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, getLocaleForLanguage(language).toString())
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true) // Enable partial results
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1) // We only need the best result
                // Improve recognition for longer speech
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000)
            }

            // Start listening
            speechRecognizer?.startListening(intent)
            Log.d("SpeechToText", "Started listening in ${language.displayName}")

        } catch (e: Exception) {
            Log.e("SpeechToText", "Error starting speech recognition", e)
            isListening = false
            callback.onSpeechError("Failed to start speech recognition: ${e.message}")
        }
    }

    fun stopListening() {
        if (isListening) {
            speechRecognizer?.stopListening()
            isListening = false
            Log.d("SpeechToText", "Stopped listening")
        }
    }

    fun cancelListening() {
        if (isListening) {
            speechRecognizer?.cancel()
            isListening = false
            Log.d("SpeechToText", "Cancelled listening")
        }
    }

    fun isCurrentlyListening(): Boolean = isListening

    private fun getLocaleForLanguage(language: AppLanguage): Locale {
        return when (language) {
            AppLanguage.ENGLISH -> Locale("en", "GB") // UK English
            AppLanguage.RUSSIAN -> Locale("ru", "RU") // Russian
        }
    }

    fun cleanup() {
        try {
            speechRecognizer?.destroy()
            speechRecognizer = null
            isListening = false
            Log.d("SpeechToText", "Speech recognizer cleaned up")
        } catch (e: Exception) {
            Log.e("SpeechToText", "Error during cleanup", e)
        }
    }
}