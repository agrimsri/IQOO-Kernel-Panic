package com.dpdpxray.core.assistant

import kotlin.math.max

/** An installed app that can be checked: its launcher label and package. */
data class AppCandidate(val label: String, val packageName: String)

sealed interface AppMatch {
    data class Found(val app: AppCandidate) : AppMatch

    /** The two best options, best first, when neither clearly wins. */
    data class Ambiguous(val options: List<AppCandidate>) : AppMatch
    data object NotFound : AppMatch
}

/**
 * Finds an installed app by a spoken name: case-insensitive, ignoring spaces ("whats app"), tolerant of small
 * misspellings (edit distance) and of words from a longer label ("play store"). Names heard in Devanagari
 * ("इंस्टाग्राम") are transliterated to Latin letters and compared by a rough phonetic key and consonant skeleton.
 *
 * A clear winner is returned; when two labels score within [MARGIN] of each other (and neither is an exact match),
 * both are returned so the user can choose. It never picks an app it has not scored at [MIN_SCORE] or better.
 */
object AppNameMatcher {
    const val MIN_SCORE = 0.72
    const val EXACT = 0.97
    const val MARGIN = 0.08

    fun match(query: String, apps: List<AppCandidate>): AppMatch {
        if (words(query).isEmpty()) return AppMatch.NotFound
        val scored = apps.map { it to score(query, it.label) }
            .filter { it.second >= MIN_SCORE }
            .sortedWith(compareByDescending<Pair<AppCandidate, Double>> { it.second }.thenBy { it.first.label.length }.thenBy { it.first.label })
        val top = scored.firstOrNull() ?: return AppMatch.NotFound
        val second = scored.getOrNull(1) ?: return AppMatch.Found(top.first)
        return when {
            top.second >= EXACT && second.second < EXACT -> AppMatch.Found(top.first)
            top.second - second.second < MARGIN -> AppMatch.Ambiguous(listOf(top.first, second.first))
            else -> AppMatch.Found(top.first)
        }
    }

    /** 0..1: how well [query] names an app with this [label]. */
    fun score(query: String, label: String): Double {
        val q = words(query)
        val l = words(label)
        if (q.isEmpty() || l.isEmpty()) return 0.0
        val qj = q.joinToString("")
        val lj = l.joinToString("")
        if (qj == lj) return 1.0
        val qp = phonetic(qj)
        val lp = phonetic(lj)
        if (qp == lp) return 0.98
        var best = similarity(qp, lp)
        if (qp.length >= 3 && lp.startsWith(qp)) best = max(best, 0.86 + 0.1 * qp.length / lp.length)
        val qs = skeleton(qp)
        if (qs.length >= 3 && qs == skeleton(lp) && qp.first() == lp.first()) best = max(best, 0.8)
        val perWord = q.map { qw -> l.maxOf { lw -> wordSimilarity(qw, lw) } }
        if (perWord.all { it >= 0.75 }) {
            val coverage = 0.75 + 0.25 * (q.size.toDouble() / l.size).coerceAtMost(1.0)
            best = max(best, 0.92 * perWord.average() * coverage)
        }
        return best
    }

    private fun wordSimilarity(a: String, b: String): Double {
        val pa = phonetic(a)
        val pb = phonetic(b)
        if (pa == pb) return 1.0
        val s = similarity(pa, pb)
        val k = skeleton(pa)
        return if (k.length >= 3 && k == skeleton(pb) && pa.first() == pb.first()) max(s, 0.8) else s
    }

    /** Lower-case Latin words; camel-case labels split ("LeakyShop" → leaky, shop); Devanagari transliterated. */
    fun words(text: String): List<String> {
        val split = text.replace(Regex("(?<=\\p{Ll})(?=\\p{Lu})"), " ")
        return latin(split).split(' ').filter { it.isNotEmpty() }
    }

    /** Latin letters and digits only, words separated by single spaces. */
    fun latin(text: String): String {
        val out = StringBuilder()
        val chars = text.lowercase()
        var i = 0
        while (i < chars.length) {
            val c = chars[i]
            when {
                c in 'a'..'z' || c in '0'..'9' -> out.append(c)
                c in 'ऀ'..'ॿ' -> i = devanagariWord(chars, i, out) - 1
                else -> out.append(' ')
            }
            i++
        }
        return out.toString().replace(Regex(" +"), " ").trim()
    }

    /** Rough sound-alike key: c/k, ph/f, w/v, z/j, ou/oo/u, ee/i, a final y as i, doubled letters and a final silent e dropped. */
    fun phonetic(word: String): String {
        var s = word.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }
        s = s.replace("ph", "f").replace("ck", "k").replace("q", "k").replace("x", "ks").replace("w", "v").replace("z", "j")
        s = s.replace(Regex("c(?=[eiy])"), "s").replace("c", "k")
        s = s.replace("ou", "u").replace("oo", "u").replace("ee", "i")
        s = s.replace(Regex("(?<=[^aeiou])y$"), "i")
        s = s.replace(Regex("(.)\\1+"), "$1")
        if (s.length > 3 && s.endsWith("e")) s = s.dropLast(1)
        return s
    }

    /** The consonants of a phonetic key (vowels and a non-initial y removed). */
    fun skeleton(key: String): String = key.filterIndexed { i, c -> c !in "aeiou" && !(c == 'y' && i > 0) }

    private fun similarity(a: String, b: String): Double {
        val longest = max(a.length, b.length)
        return if (longest == 0) 1.0 else 1.0 - levenshtein(a, b).toDouble() / longest
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val t = prev
            prev = cur
            cur = t
        }
        return prev[b.length]
    }

    // --- Devanagari → Latin ----------------------------------------------------------------------------------------

    private const val VIRAMA = '्'
    private const val NUKTA = '़'

    private val CONSONANTS = mapOf(
        'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "n", 'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh", 'ञ' to "n",
        'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n", 'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n",
        'प' to "p", 'फ' to "f", 'ब' to "b", 'भ' to "bh", 'म' to "m", 'य' to "y", 'र' to "r", 'ल' to "l", 'व' to "v",
        'श' to "sh", 'ष' to "sh", 'स' to "s", 'ह' to "h",
    )
    private val NUKTA_FORMS = mapOf('क' to "k", 'ख' to "kh", 'ग' to "g", 'ज' to "z", 'ड' to "r", 'ढ' to "rh", 'फ' to "f")
    private val VOWELS = mapOf(
        'अ' to "a", 'आ' to "a", 'इ' to "i", 'ई' to "i", 'उ' to "u", 'ऊ' to "u", 'ए' to "e", 'ऐ' to "ai", 'ओ' to "o", 'औ' to "au",
        'ऋ' to "ri", 'ऑ' to "o", 'ऍ' to "e",
    )
    private val SIGNS = mapOf(
        'ा' to "a", 'ि' to "i", 'ी' to "i", 'ु' to "u", 'ू' to "u", 'ृ' to "ri", 'ॅ' to "e",
        'े' to "e", 'ै' to "ai", 'ॉ' to "o", 'ो' to "o", 'ौ' to "au",
    )
    private val NASALS = mapOf('ँ' to "n", 'ं' to "n", 'ः' to "h")

    private class Unit(val sound: String, val consonant: Boolean, var vowel: String?, var dead: Boolean = false)

    /**
     * Transliterates one run of Devanagari starting at [start] into [out] and returns the index after it. The inherent
     * "a" of a consonant is dropped at the end of a word, before an independent vowel, and between a vowel and a
     * consonant that carries its own vowel (the usual Hindi schwa deletion), so "फ़ेसबुक" reads "fesbuk".
     */
    private fun devanagariWord(text: String, start: Int, out: StringBuilder): Int {
        val units = mutableListOf<Unit>()
        var i = start
        while (i < text.length && text[i] in 'ऀ'..'ॿ') {
            val c = text[i]
            when {
                c in CONSONANTS -> {
                    val nukta = i + 1 < text.length && text[i + 1] == NUKTA
                    units += Unit(if (nukta) NUKTA_FORMS[c] ?: CONSONANTS.getValue(c) else CONSONANTS.getValue(c), consonant = true, vowel = null)
                    if (nukta) i++
                }
                c in SIGNS -> units.lastOrNull()?.takeIf { it.consonant }?.vowel = SIGNS.getValue(c)
                c == VIRAMA -> units.lastOrNull()?.dead = true
                c in VOWELS -> units += Unit(VOWELS.getValue(c), consonant = false, vowel = null)
                c in NASALS -> units += Unit(NASALS.getValue(c), consonant = false, vowel = null)
                else -> Unit // danda, digits in Devanagari and other signs are dropped
            }
            i++
        }
        units.forEachIndexed { k, u ->
            out.append(u.sound)
            if (!u.consonant) return@forEachIndexed
            u.vowel?.let { out.append(it); return@forEachIndexed }
            if (u.dead) return@forEachIndexed
            val next = units.getOrNull(k + 1)
            val inherent = when {
                next == null -> false // word end
                !next.consonant -> next.sound in NASALS.values // before anusvara: "kan"; before a vowel letter: none
                else -> {
                    val previousVowel = k > 0 && units[k - 1].let { p -> !p.consonant || p.vowel != null || (!p.dead && p.consonant) }
                    val nextHasVowel = next.vowel != null || (!next.dead && units.getOrNull(k + 2) != null)
                    !(previousVowel && nextHasVowel)
                }
            }
            if (inherent) out.append('a')
        }
        out.append(' ')
        return i
    }
}
