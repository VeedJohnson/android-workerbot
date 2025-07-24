package com.veedjohnson.workerbot.domain


object Chunker {

    fun createStructuredChunks(
        text: String,
        maxChunkSize: Int = 400
    ): List<String> {
        // Split by section separators first
        val sections = text.split(Regex("-{5,}")).filter { it.trim().isNotEmpty() }
        val chunks = mutableListOf<String>()

        for (section in sections) {
            val trimmedSection = section.trim()
            if (trimmedSection.isEmpty()) continue

            if (trimmedSection.length <= maxChunkSize) {
                // Section fits in one chunk
                chunks.add(trimmedSection)
            } else {
                // Split large section by paragraphs, then sentences if needed
                val sectionChunks = splitLargeSection(trimmedSection, maxChunkSize)
                chunks.addAll(sectionChunks)
            }
        }

        return chunks.filter { it.trim().isNotEmpty() }
    }

    private fun splitLargeSection(section: String, maxChunkSize: Int): List<String> {
        // Split by double line breaks (paragraphs)
        val paragraphs = section.split(Regex("\\n\\s*\\n+")).filter { it.trim().isNotEmpty() }
        val chunks = mutableListOf<String>()
        var currentChunk = StringBuilder()

        for (paragraph in paragraphs) {
            val trimmedParagraph = paragraph.trim().replace(Regex("\\s+"), " ")

            // If adding this paragraph would exceed max size, finalize current chunk
            if (currentChunk.isNotEmpty() &&
                (currentChunk.length + trimmedParagraph.length + 2) > maxChunkSize) {
                chunks.add(currentChunk.toString().trim())
                currentChunk = StringBuilder()
            }

            if (currentChunk.isNotEmpty()) {
                currentChunk.append("\n\n")
            }
            currentChunk.append(trimmedParagraph)

            // If single paragraph is too large, split by sentences
            if (currentChunk.length > maxChunkSize) {
                chunks.add(currentChunk.toString().trim())
                currentChunk = StringBuilder()
            }
        }

        if (currentChunk.isNotEmpty()) {
            chunks.add(currentChunk.toString().trim())
        }

        return chunks
    }

    fun createSmartChunks(
        text: String,
        targetChunkSize: Int = 250,
        maxChunkSize: Int = 350,
        overlapSentences: Int = 1
    ): List<String> {
        // First try structured chunking
        val structuredChunks = createStructuredChunks(text, maxChunkSize)

        // If chunks are still too big, further split them
        val finalChunks = mutableListOf<String>()

        for (chunk in structuredChunks) {
            if (chunk.length <= maxChunkSize) {
                finalChunks.add(chunk)
            } else {
                // Split by sentences as fallback
                val sentenceChunks = splitBySentences(chunk, targetChunkSize, maxChunkSize)
                finalChunks.addAll(sentenceChunks)
            }
        }

        return finalChunks.filter { it.trim().isNotEmpty() }
    }

    private fun splitBySentences(text: String, targetSize: Int, maxSize: Int): List<String> {
        val sentences = text.split(Regex("[.!?]+\\s+")).filter { it.trim().isNotEmpty() }
        val chunks = mutableListOf<String>()
        var currentChunk = StringBuilder()

        for (sentence in sentences) {
            val trimmedSentence = sentence.trim()
            if (trimmedSentence.isEmpty()) continue

            // If adding this sentence would exceed max size, finalize current chunk
            if (currentChunk.isNotEmpty() &&
                (currentChunk.length + trimmedSentence.length + 2) > maxSize) {
                chunks.add(currentChunk.toString().trim())
                currentChunk = StringBuilder()
            }

            if (currentChunk.isNotEmpty()) {
                currentChunk.append(". ")
            }
            currentChunk.append(trimmedSentence)
        }

        if (currentChunk.isNotEmpty()) {
            chunks.add(currentChunk.toString().trim())
        }

        return chunks
    }

    fun createParagraphBasedChunks(
        text: String,
        targetChunkSize: Int = 250,
        maxChunkSize: Int = 350
    ): List<String> {
        // Split by paragraphs first, then by sentences if needed
        val paragraphs = text.split(Regex("\\n\\s*\\n")).filter { it.trim().isNotEmpty() }
        val chunks = mutableListOf<String>()

        for (paragraph in paragraphs) {
            val trimmedParagraph = paragraph.trim()

            if (trimmedParagraph.length <= maxChunkSize) {
                // Paragraph fits in one chunk
                chunks.add(trimmedParagraph)
            } else {
                // Split large paragraph into sentence-based chunks
                val sentenceChunks = splitBySentences(trimmedParagraph, targetChunkSize, maxChunkSize)
                chunks.addAll(sentenceChunks)
            }
        }

        return chunks.filter { it.trim().isNotEmpty() }
    }
}