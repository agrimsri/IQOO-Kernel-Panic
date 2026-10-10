package com.dpdpxray.app.ai

import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Message

/**
 * Turns a LiteRT-LM reply into an answer without ever presenting the model's thinking as the answer.
 *
 * Evidence (docs/AI_EMPTY_REPLY_DIAGNOSIS.md): LiteRT-LM 0.17.1 builds a reply `Message` from the native JSON with
 * `content` (list of `{type:"text"}` items) -> `Message.contents` and `channels` (name -> text) -> `Message.channels`.
 * The Gemma 4 E2B bundle declares one channel, `thought`, delimited by `<|channel>thought\n` and `<channel|>`.
 * The final answer is the visible contents. If a runtime ever leaves those markers in the visible text they are removed
 * here, using the same rule as the bundle's own chat template (`strip_thinking`).
 */
object ReplyExtraction {
    const val CHANNEL_OPEN = "<|channel>"
    const val CHANNEL_CLOSE = "<channel|>"
    private const val UNNAMED = "unnamed"

    /** Channel names that carry reasoning. Their text is counted, never shown. */
    private val THINKING = setOf("thought", "thoughts", "thinking", "think", "reasoning", "analysis")

    /** Channel names that may carry the final answer when the visible reply is empty. Anything else is never shown. */
    private val ANSWER = setOf("final", "answer", "response", "output")

    private val CHANNEL_NAME = Regex("[A-Za-z_][A-Za-z0-9_-]{0,31}")

    fun isThinkingChannel(name: String) = name.trim().lowercase() in THINKING
    fun isAnswerChannel(name: String) = name.trim().lowercase() in ANSWER

    /** Visible text with inline channel blocks removed; the removed blocks by channel name (unnamed blocks count as thinking). */
    data class Split(val visible: String, val blocks: Map<String, String>)

    fun splitInline(raw: String): Split {
        val visible = StringBuilder()
        val blocks = linkedMapOf<String, StringBuilder>()
        fun block(name: String, text: String) { blocks.getOrPut(name) { StringBuilder() }.append(text) }

        var rest = raw
        // A close marker before any open marker: the open marker was part of the prompt, so the text before it was thinking.
        val firstOpen = rest.indexOf(CHANNEL_OPEN)
        val firstClose = rest.indexOf(CHANNEL_CLOSE)
        if (firstClose >= 0 && (firstOpen < 0 || firstClose < firstOpen)) {
            block(UNNAMED, rest.substring(0, firstClose))
            rest = rest.substring(firstClose + CHANNEL_CLOSE.length)
        }
        while (true) {
            val open = rest.indexOf(CHANNEL_OPEN)
            if (open < 0) { visible.append(rest); break }
            visible.append(rest, 0, open)
            val afterOpen = rest.substring(open + CHANNEL_OPEN.length)
            val close = afterOpen.indexOf(CHANNEL_CLOSE)
            val body = if (close < 0) afterOpen else afterOpen.substring(0, close)
            val newline = body.indexOf('\n')
            val firstLine = (if (newline < 0) body else body.substring(0, newline)).trim()
            if (CHANNEL_NAME.matches(firstLine)) block(firstLine, if (newline < 0) "" else body.substring(newline + 1))
            else block(UNNAMED, body)
            // An unterminated block (the output budget ran out mid-thought) swallows the rest of the reply.
            if (close < 0) break
            rest = afterOpen.substring(close + CHANNEL_CLOSE.length)
        }
        return Split(visible.toString(), blocks.mapValues { it.value.toString() })
    }

    fun extract(textParts: List<String>, channels: Map<String, String>): LlmOutcome {
        val split = splitInline(textParts.joinToString(""))
        val merged = linkedMapOf<String, String>()
        (channels.entries + split.blocks.entries).forEach { (name, text) -> merged[name] = (merged[name] ?: "") + text }
        val thinkingChars = merged.filterKeys { isThinkingChannel(it) || it == UNNAMED }.values.sumOf { it.trim().length }
        val visible = split.visible.trim()
        if (visible.isNotEmpty()) return LlmOutcome.Text(visible, LlmOutcome.FROM_CONTENTS, thinkingChars)
        val answer = merged.entries.firstOrNull { isAnswerChannel(it.key) && it.value.isNotBlank() }
        if (answer != null) return LlmOutcome.Text(answer.value.trim(), "channel:${answer.key}", thinkingChars)
        return LlmOutcome.Empty(merged.keys.toSet(), thinkingChars)
    }
}

/** Joins `sendMessageAsync` chunks (each a `Message` with text parts and channel deltas) before extraction. */
class ReplyAccumulator {
    private val text = StringBuilder()
    private val channels = linkedMapOf<String, StringBuilder>()

    fun add(textParts: List<String>, channelDeltas: Map<String, String>) {
        textParts.forEach { text.append(it) }
        channelDeltas.forEach { (name, delta) -> channels.getOrPut(name) { StringBuilder() }.append(delta) }
    }

    fun outcome(): LlmOutcome = ReplyExtraction.extract(listOf(text.toString()), channels.mapValues { it.value.toString() })
}

fun Message.textParts(): List<String> = contents.contents.filterIsInstance<Content.Text>().map { it.text }

fun Message.toOutcome(): LlmOutcome = LlmOutcome.fromReply(textParts(), channels)
