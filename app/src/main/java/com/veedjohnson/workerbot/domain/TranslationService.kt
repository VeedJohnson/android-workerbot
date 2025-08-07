package com.veedjohnson.workerbot.domain

import android.content.Context
import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single
import kotlin.coroutines.resume

@Single
class TranslationService(private val context: Context) {

    private var ruToEnTranslator: Translator? = null
    private var enToRuTranslator: Translator? = null
    private val languageIdentifier = LanguageIdentification.getClient()

    suspend fun initializeTranslators(onDebugLog: (String) -> Unit = {}): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d("TranslationService", "Initializing translator...")
            onDebugLog("TranslationService - Initializing translator...")
            // Create Russian to English translator
            val ruToEnOptions = TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.RUSSIAN)
                .setTargetLanguage(TranslateLanguage.ENGLISH)
                .build()
            ruToEnTranslator = Translation.getClient(ruToEnOptions)

            // Create English to Russian translator
            val enToRuOptions = TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.RUSSIAN)
                .build()
            enToRuTranslator = Translation.getClient(enToRuOptions)

            // Download models if needed
            val conditions = DownloadConditions.Builder()
                .build()

            val ruToEnReady = downloadModel(ruToEnTranslator!!, conditions, onDebugLog)
            val enToRuReady = downloadModel(enToRuTranslator!!, conditions, onDebugLog)

            val success = ruToEnReady && enToRuReady
            Log.d("TranslationService", "Translation models ready: $success")
            onDebugLog("TranslationService - Translation models ready: $success")
            success
        } catch (e: Exception) {
            onDebugLog("TranslationService - Failed to initialize translators: ${e.message}")
            Log.e("TranslationService", "Failed to initialize translators", e)
            false
        }
    }

    private suspend fun downloadModel(translator: Translator, conditions: DownloadConditions, onDebugLog: (String) -> Unit): Boolean {
        return suspendCancellableCoroutine { continuation ->
            translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener {
                    Log.d("TranslationService", "Model downloaded successfully")
                    onDebugLog("TranslationService - Model download successful")
                    continuation.resume(true)
                }
                .addOnFailureListener { exception ->
                    Log.e("TranslationService", "Model download failed", exception)
                    onDebugLog("TranslationService - Failed to download model: ${exception.message}")
                    continuation.resume(false)
                }
        }
    }

    suspend fun detectLanguage(text: String): String = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            languageIdentifier.identifyLanguage(text)
                .addOnSuccessListener { languageCode ->
                    Log.d("TranslationService", "Detected language: $languageCode")
                    continuation.resume(languageCode)
                }
                .addOnFailureListener {
                    Log.w("TranslationService", "Language detection failed, defaulting to English")
                    continuation.resume("en")
                }
        }
    }

    suspend fun translateToEnglish(text: String): String = withContext(Dispatchers.IO) {
        try {
            if (ruToEnTranslator == null) {
                Log.w("TranslationService", "Russian to English translator not ready")
                return@withContext text
            }

            suspendCancellableCoroutine { continuation ->
                ruToEnTranslator!!.translate(text)
                    .addOnSuccessListener { translatedText ->
                        Log.d("TranslationService", "Translated to English: ${translatedText.take(50)}...")
                        continuation.resume(translatedText)
                    }
                    .addOnFailureListener { exception ->
                        Log.e("TranslationService", "Translation to English failed", exception)
                        continuation.resume(text) // Return original on failure
                    }
            }
        } catch (e: Exception) {
            Log.e("TranslationService", "Error in translateToEnglish", e)
            text
        }
    }

    suspend fun translateToRussian(text: String): String = withContext(Dispatchers.IO) {
        try {
            if (enToRuTranslator == null) {
                Log.w("TranslationService", "English to Russian translator not ready")
                return@withContext text
            }

            suspendCancellableCoroutine { continuation ->
                enToRuTranslator!!.translate(text)
                    .addOnSuccessListener { translatedText ->
                        Log.d("TranslationService", "Translated to Russian: ${translatedText.take(50)}...")
                        continuation.resume(translatedText)
                    }
                    .addOnFailureListener { exception ->
                        Log.e("TranslationService", "Translation to Russian failed", exception)
                        continuation.resume(text) // Return original on failure
                    }
            }
        } catch (e: Exception) {
            Log.e("TranslationService", "Error in translateToRussian", e)
            text
        }
    }

    fun cleanup() {
        try {
            ruToEnTranslator?.close()
            enToRuTranslator?.close()
            languageIdentifier.close()
            Log.d("TranslationService", "Translation service cleaned up")
        } catch (e: Exception) {
            Log.e("TranslationService", "Error during cleanup", e)
        }
    }
}