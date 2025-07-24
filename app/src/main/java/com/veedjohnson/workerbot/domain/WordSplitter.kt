package com.veedjohnson.workerbot.domain

import kotlin.math.max

class WordSlidingSplitter {
    companion object {
        /**
         * Chunk the text into pieces of up to maxChunkChars characters,
         * sliding forward by overlapWords words each time.
         */
        fun createChunks(
            docText: String,
            maxChunkChars: Int,
            overlapWords: Int
        ): List<String> {
            val chunks = mutableListOf<String>()
            // 1) Break into paragraphs (so we never join across blank lines)
            val paras = docText.split(Regex("\\n\\s*\\n"))
            for (para in paras) {
                // 2) Tokenize the paragraph
                val words = para.trim().split(Regex("\\s+"))
                var startWord = 0
                while (startWord < words.size) {
                    // 3) Grow an endWord until hitting maxChunkChars
                    var endWord = startWord
                    var charCount = 0
                    while (endWord < words.size) {
                        // +1 for the space
                        val nextLen = if (charCount == 0) words[endWord].length else charCount + 1 + words[endWord].length
                        if (nextLen > maxChunkChars) break
                        charCount = nextLen
                        endWord++
                    }
                    // 4) Emit that chunk
                    val chunk = words.subList(startWord, endWord).joinToString(" ")
                    chunks += chunk

                    if (endWord == words.size) break
                    // 5) Slide window back by overlapWords words
                    startWord = max(0, endWord - overlapWords)
                }
            }
            return chunks
        }
    }
}