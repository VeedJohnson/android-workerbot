package com.veedjohnson.workerbot.data

object ModelConfig {
    // Model URLs
    private const val GEMMA_MODEL_URL = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.task"
    private const val GECKO_MODEL_URL = "https://huggingface.co/veedjohnson/gecko-512-quant/resolve/main/gecko-512-quant.task"

    // Model filenames
    private const val GEMMA_FILENAME = "gemma3-1b-it-int4.task"
    private const val GECKO_FILENAME = "gecko-512-quant.task"

    // Current active model (easy to switch)
    const val CURRENT_MODEL_URL = GEMMA_MODEL_URL
    const val CURRENT_MODEL_FILENAME = GEMMA_FILENAME
}