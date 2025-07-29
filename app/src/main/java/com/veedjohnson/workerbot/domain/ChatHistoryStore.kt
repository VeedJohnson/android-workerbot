package com.veedjohnson.workerbot.domain

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.veedjohnson.workerbot.ui.screens.chat.AppLanguage
import com.veedjohnson.workerbot.ui.screens.chat.ChatMessage
import org.koin.core.annotation.Single

@Single
class ChatHistoryStorage(private val context: Context) {

    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences("chat_history", Context.MODE_PRIVATE)

    private val gson = Gson()

    companion object {
        private const val ENGLISH_HISTORY_KEY = "english_chat_history"
        private const val RUSSIAN_HISTORY_KEY = "russian_chat_history"
        private const val LAST_SELECTED_LANGUAGE_KEY = "last_selected_language"
    }

    /**
     * Save chat history for a specific language
     */
    fun saveChatHistory(language: AppLanguage, messages: List<ChatMessage>) {
        try {
            val key = when (language) {
                AppLanguage.ENGLISH -> ENGLISH_HISTORY_KEY
                AppLanguage.RUSSIAN -> RUSSIAN_HISTORY_KEY
            }

            val json = gson.toJson(messages)
            sharedPreferences.edit()
                .putString(key, json)
                .apply()

            Log.d("ChatHistoryStorage", "Saved ${messages.size} messages for ${language.displayName}")
        } catch (e: Exception) {
            Log.e("ChatHistoryStorage", "Error saving chat history for ${language.displayName}", e)
        }
    }

    /**
     * Load chat history for a specific language
     */
    fun loadChatHistory(language: AppLanguage): List<ChatMessage> {
        return try {
            val key = when (language) {
                AppLanguage.ENGLISH -> ENGLISH_HISTORY_KEY
                AppLanguage.RUSSIAN -> RUSSIAN_HISTORY_KEY
            }

            val json = sharedPreferences.getString(key, null)
            if (json != null) {
                val type = object : TypeToken<List<ChatMessage>>() {}.type
                val messages: List<ChatMessage> = gson.fromJson(json, type)
                Log.d("ChatHistoryStorage", "Loaded ${messages.size} messages for ${language.displayName}")
                messages
            } else {
                Log.d("ChatHistoryStorage", "No saved history found for ${language.displayName}")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e("ChatHistoryStorage", "Error loading chat history for ${language.displayName}", e)
            emptyList()
        }
    }

    /**
     * Save the last selected language
     */
    fun saveLastSelectedLanguage(language: AppLanguage) {
        sharedPreferences.edit()
            .putString(LAST_SELECTED_LANGUAGE_KEY, language.code)
            .apply()
        Log.d("ChatHistoryStorage", "Saved last selected language: ${language.displayName}")
    }

    /**
     * Load the last selected language (default to English)
     */
    fun loadLastSelectedLanguage(): AppLanguage {
        val languageCode = sharedPreferences.getString(LAST_SELECTED_LANGUAGE_KEY, AppLanguage.ENGLISH.code)
        return AppLanguage.entries.find { it.code == languageCode } ?: AppLanguage.ENGLISH
    }

    /**
     * Clear all chat history for both languages
     */
    fun clearAllChatHistory() {
        try {
            sharedPreferences.edit()
                .remove(ENGLISH_HISTORY_KEY)
                .remove(RUSSIAN_HISTORY_KEY)
                .apply()
            Log.d("ChatHistoryStorage", "Cleared all chat history")
        } catch (e: Exception) {
            Log.e("ChatHistoryStorage", "Error clearing chat history", e)
        }
    }

    /**
     * Clear chat history for a specific language
     */
    fun clearChatHistory(language: AppLanguage) {
        try {
            val key = when (language) {
                AppLanguage.ENGLISH -> ENGLISH_HISTORY_KEY
                AppLanguage.RUSSIAN -> RUSSIAN_HISTORY_KEY
            }

            sharedPreferences.edit()
                .remove(key)
                .apply()
            Log.d("ChatHistoryStorage", "Cleared chat history for ${language.displayName}")
        } catch (e: Exception) {
            Log.e("ChatHistoryStorage", "Error clearing chat history for ${language.displayName}", e)
        }
    }

    /**
     * Get total message count across all languages
     */
    fun getTotalMessageCount(): Int {
        val englishCount = loadChatHistory(AppLanguage.ENGLISH).size
        val russianCount = loadChatHistory(AppLanguage.RUSSIAN).size
        return englishCount + russianCount
    }
}