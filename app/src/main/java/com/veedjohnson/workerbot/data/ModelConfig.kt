package com.veedjohnson.workerbot.data

object ModelConfig {
    // Model URLs
    private const val GEMMA_MODEL_URL = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.task"
    private const val QWEN_MODEL_URL = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_seq128_q8_ekv4096.task"
    private const val HAMMER_MODEL_URL = "https://huggingface.co/litert-community/Hammer2.1-1.5b/resolve/main/hammer2.1_1.5b_q8_ekv4096.task"

    // Model filenames
    private const val GEMMA_FILENAME = "gemma3-1b-it-int4.task"
    private const val QWEN_FILENAME = "Qwen2.5-1.5B-Instruct_seq128_q8_ekv4096.task"
    private const val HAMMER_FILENAME = "hammer2.1_1.5b_q8_ekv4096.task"

    // Current active model
    const val CURRENT_MODEL_URL = GEMMA_MODEL_URL
    const val CURRENT_MODEL_FILENAME = GEMMA_FILENAME
}