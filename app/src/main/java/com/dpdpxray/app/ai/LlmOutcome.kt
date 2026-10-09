package com.dpdpxray.app.ai

import kotlinx.coroutines.CancellationException

/** What one model call actually produced. Descriptions name error classes and channel names only, never prompt text. */
sealed interface LlmOutcome {
    data class Text(val text: String) : LlmOutcome
    /** The model answered but put nothing in the visible reply; [channels] are the names of any side channels it filled. */
    data class Empty(val channels: Set<String>) : LlmOutcome
    data class Failed(val errorClass: String, val message: String) : LlmOutcome
    data object NotLoaded : LlmOutcome

    val textOrNull: String? get() = (this as? Text)?.text

    fun describe(): String = when (this) {
        is Text -> "Text (${text.length} chars)"
        is Empty -> "Empty reply" + if (channels.isEmpty()) "" else " (channels: ${channels.sorted().joinToString()})"
        is Failed -> "Failed: $errorClass: $message"
        NotLoaded -> "Model not loaded"
    }

    companion object {
        const val MAX_MESSAGE = 300

        fun fromReply(textParts: List<String>, channelNames: Set<String>): LlmOutcome =
            textParts.joinToString("").trim().let { if (it.isEmpty()) Empty(channelNames) else Text(it) }

        fun failed(t: Throwable): LlmOutcome =
            Failed(t.javaClass.name, (t.message ?: "(no message)").take(MAX_MESSAGE))
    }
}

/** Like runCatching, but lets coroutine cancellation through instead of turning it into a failed answer. */
suspend fun llmCatching(block: suspend () -> LlmOutcome): LlmOutcome = try {
    block()
} catch (c: CancellationException) {
    throw c
} catch (t: Throwable) {
    LlmOutcome.failed(t)
}
