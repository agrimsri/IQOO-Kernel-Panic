package com.dpdpxray.core.dns

import com.dpdpxray.core.model.AnswerStatus
import com.dpdpxray.core.model.NetEvent

/** What a resolver reply said about one query. Only [attributable] results may be attached to that query's event. */
data class DnsAnswer(
    /** Never [AnswerStatus.NO_REPLY]: that is decided by the relay when no reply arrives at all. */
    val status: AnswerStatus,
    /** Lowercased question name of the reply, once it has been read. */
    val questionName: String? = null,
    val questionType: Int? = null,
    val rcode: Int? = null,
    /** Queried name first, then each alias followed. Empty when the reply had no CNAME for the queried name. */
    val cnameChain: List<String> = emptyList(),
    /** A/AAAA addresses owned by the final name of the chain, in answer order. */
    val answerIps: List<String> = emptyList(),
) {
    val attributable: Boolean get() = status == AnswerStatus.COMPLETE || status == AnswerStatus.PARTIAL
}

/**
 * Bounded reader for A, AAAA and CNAME records in a resolver reply. Never throws and never loops: compression
 * pointers must point backwards and are capped, the CNAME chain is capped at [MAX_CHAIN] and loops stop it. Only the
 * answer section is read, and only records owned by the queried name or an alias reached from it are attributed.
 */
object DnsAnswers {
    const val MAX_CHAIN = 8
    const val MAX_IPS = 16
    private const val MAX_RECORDS = 64
    private const val MAX_POINTER_JUMPS = 16
    private const val MAX_NAME_LENGTH = 255
    private const val TYPE_A = 1
    private const val TYPE_CNAME = 5
    private const val TYPE_AAAA = 28
    private const val CLASS_IN = 1

    /** [query] is the DNS message the app sent (the UDP payload), used to check the reply really answers it. */
    fun parse(response: ByteArray, query: ByteArray): DnsAnswer {
        val (expectedName, expectedType) = DnsPacketCodec.parseQuestion(query) ?: return DnsAnswer(AnswerStatus.MISMATCHED)
        val r = response
        if (r.size < 12) return DnsAnswer(AnswerStatus.MALFORMED)
        val flags = u16(r, 2)
        if (flags and 0x8000 == 0 || u16(r, 0) != u16(query, 0) || u16(r, 4) != 1) return DnsAnswer(AnswerStatus.MISMATCHED)
        val question = readName(r, 12) ?: return DnsAnswer(AnswerStatus.MALFORMED)
        if (question.end + 4 > r.size) return DnsAnswer(AnswerStatus.MALFORMED)
        val questionType = u16(r, question.end)
        if (question.name != expectedName || questionType != expectedType) {
            return DnsAnswer(AnswerStatus.MISMATCHED, question.name, questionType)
        }
        val rcode = flags and 0x000f
        var partial = flags and 0x0200 != 0 // TC: the resolver cut the reply short

        val aliases = HashMap<String, String>()
        val addresses = mutableListOf<Pair<String, String>>()
        val count = u16(r, 6)
        if (count > MAX_RECORDS) partial = true
        var pos = question.end + 4
        for (i in 0 until minOf(count, MAX_RECORDS)) {
            val owner = readName(r, pos)
            if (owner == null || owner.end + 10 > r.size) { partial = true; break }
            pos = owner.end
            val type = u16(r, pos)
            val rdStart = pos + 10
            val rdEnd = rdStart + u16(r, pos + 8)
            if (rdEnd > r.size) { partial = true; break }
            if (u16(r, pos + 2) == CLASS_IN) when (type) {
                TYPE_A -> if (rdEnd - rdStart == 4) addresses += owner.name to ipv4(r, rdStart) else partial = true
                TYPE_AAAA -> if (rdEnd - rdStart == 16) addresses += owner.name to ipv6(r, rdStart) else partial = true
                TYPE_CNAME -> {
                    val target = readName(r, rdStart)
                    when {
                        target == null || target.end > rdEnd -> partial = true
                        aliases.putIfAbsent(owner.name, target.name).let { it != null && it != target.name } -> partial = true
                    }
                }
            }
            pos = rdEnd
        }

        val chain = mutableListOf(expectedName)
        var resolved = true
        while (true) {
            val next = aliases[chain.last()] ?: break
            if (next in chain || chain.size > MAX_CHAIN) { resolved = false; partial = true; break }
            chain += next
        }
        val ips = if (!resolved) emptyList() else addresses.filter { it.first == chain.last() }.map { it.second }.distinct()
        if (ips.size > MAX_IPS) partial = true

        return DnsAnswer(
            status = if (partial) AnswerStatus.PARTIAL else AnswerStatus.COMPLETE,
            questionName = question.name,
            questionType = questionType,
            rcode = rcode,
            cnameChain = if (chain.size > 1) chain else emptyList(),
            answerIps = ips.take(MAX_IPS),
        )
    }

    private class Name(val name: String, val end: Int)

    /** Reads a possibly compressed name at [start]; [Name.end] is where the record continues. Null if malformed. */
    private fun readName(b: ByteArray, start: Int): Name? {
        val name = StringBuilder()
        var pos = start
        var end = -1
        var jumps = 0
        while (true) {
            if (pos >= b.size) return null
            val len = u8(b, pos)
            when {
                len == 0 -> {
                    if (end < 0) end = pos + 1
                    return Name(name.toString().lowercase(), end)
                }
                len and 0xC0 == 0xC0 -> {
                    if (pos + 1 >= b.size || ++jumps > MAX_POINTER_JUMPS) return null
                    val target = ((len and 0x3f) shl 8) or u8(b, pos + 1)
                    if (target >= pos) return null // pointers must point backwards
                    if (end < 0) end = pos + 2
                    pos = target
                }
                len and 0xC0 != 0 -> return null
                else -> {
                    if (pos + 1 + len > b.size) return null
                    if (name.isNotEmpty()) name.append('.')
                    for (i in pos + 1..pos + len) {
                        val c = u8(b, i)
                        if (c <= 0x20 || c >= 0x7f || c == '.'.code) return null
                        name.append(c.toChar())
                    }
                    if (name.length > MAX_NAME_LENGTH) return null
                    pos += 1 + len
                }
            }
        }
    }

    private fun ipv4(b: ByteArray, at: Int) = (at until at + 4).joinToString(".") { u8(b, it).toString() }

    /** RFC 5952 text form: lowercase, no leading zeros, the longest run of two or more zero groups as "::". */
    private fun ipv6(b: ByteArray, at: Int): String {
        val groups = IntArray(8) { u16(b, at + 2 * it) }
        var bestStart = -1
        var bestLen = 1
        var i = 0
        while (i < 8) {
            if (groups[i] != 0) { i++; continue }
            var j = i
            while (j < 8 && groups[j] == 0) j++
            if (j - i > bestLen) { bestStart = i; bestLen = j - i }
            i = j
        }
        if (bestStart < 0) return groups.joinToString(":") { it.toString(16) }
        val head = groups.take(bestStart).joinToString(":") { it.toString(16) }
        val tail = groups.drop(bestStart + bestLen).joinToString(":") { it.toString(16) }
        return "$head::$tail"
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xff
    private fun u16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)
}

/**
 * Records the reply's outcome on the event for the same question. The status is always kept; the chain, addresses
 * and rcode only when the reply verifiably answers this event's question. A valid reply for another name counts as
 * [AnswerStatus.MISMATCHED] for this event.
 */
fun NetEvent.withAnswer(answer: DnsAnswer): NetEvent = when {
    !answer.attributable -> copy(answerStatus = answer.status, rcode = null)
    answer.questionName != hostname.lowercase().trimEnd('.') -> copy(answerStatus = AnswerStatus.MISMATCHED, rcode = null)
    else -> copy(answerStatus = answer.status, rcode = answer.rcode, cnameChain = answer.cnameChain, answerIps = answer.answerIps)
}
