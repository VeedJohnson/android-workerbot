package com.veedjohnson.workerbot.domain

import android.content.Context
import org.koin.core.annotation.Single
import java.io.IOException


@Single
class KnowledgeBaseLoader(private val context: Context) {

    @Throws(IOException::class)
    fun loadKnowledgeBase(): String {
        return try {
            context.assets.open("documents/knowledge_base_eng.txt").bufferedReader().use { reader ->
                reader.readText()
            }
        } catch (e: IOException) {
            throw IOException("Failed to load knowledge_base_eng.txt from assets: ${e.message}", e)
        }
    }
}