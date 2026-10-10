package com.dpdpxray.app.ai

import kotlinx.coroutines.CancellationException

/**
 * What one model call actually produced. Descriptions name error classes, channel names and character counts only,
 * never prompt text and never the model's thinking text.
 */
sealed interface LlmOutcome {
    /**
     * A usable answer. [source] is [FROM_CONTENTS] for the normal visible reply, or `channel:<name>` when the visible
     * reply was empty and the text came from an answer channel. [hiddenThinkingChars] counts thinking text that was
     * removed; it is never part of [text].
     */
    data class Text(val text: String, val source: String = FROM_CONTENTS, val hiddenThinkingChars: Int = 0) : LlmOutcome

    /**
     * The model answered but put nothing usable in the reply. [channels] are the names of any side channels it filled;
     * [thinkingChars] counts thinking-only text, which is deliberately not used as an answer.
     */
    data class Empty(val channels: Set<String>, val thinkingChars: Int = 0) : LlmOutcome
    data class Failed(val errorClass: String, val message: String) : LlmOutcome

    /** The call was not made because the loaded model cannot take this input (for example an image for a text-only model). */
    data class Skipped(val reason: String) : LlmOutcome
    data object NotLoaded : LlmOutcome

    val textOrNull: String? get() = (this as? Text)?.text

    fun describe(): String = when (this) {
        is Text -> buildString {
            append("Text (${text.length} chars")
            if (source != FROM_CONTENTS) append(", from $source")
            append(")")
            if (hiddenThinkingChars > 0) append("; thinking text hidden ($hiddenThinkingChars chars)")
        }
        is Empty -> "Empty reply" +
            (if (channels.isEmpty()) "" else " (channels: ${channels.sorted().joinToString()})") +
            (if (thinkingChars > 0) "; only thinking text ($thinkingChars chars), not used as an answer" else "")
        is Failed -> "Failed: $errorClass: $message"
        is Skipped -> "Not sent: $reason"
        NotLoaded -> "Model not loaded"
    }

    companion object {
        const val MAX_MESSAGE = 300
        const val FROM_CONTENTS = "contents"

        /** Compatibility form: channel names only, without their text. */
        fun fromReply(textParts: List<String>, channelNames: Set<String>): LlmOutcome =
            fromReply(textParts, channelNames.associateWith { "" })

        /** [textParts] are the reply's `Content.Text` parts; [channels] is `Message.channels` (name to text). */
        fun fromReply(textParts: List<String>, channels: Map<String, String>): LlmOutcome =
            ReplyExtraction.extract(textParts, channels)

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
