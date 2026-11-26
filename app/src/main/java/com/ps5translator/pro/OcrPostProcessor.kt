package com.ps5translator.pro

import kotlin.math.max
import kotlin.math.min

object OcrPostProcessor {

    private val commonFixes = listOf(
        Regex("\\bquy\\b", RegexOption.IGNORE_CASE) to "guy",
        Regex("\\bquys\\b", RegexOption.IGNORE_CASE) to "guys",
        Regex("\\btne\\b", RegexOption.IGNORE_CASE) to "the",
        Regex("\\bwlth\\b", RegexOption.IGNORE_CASE) to "with",
        Regex("\\bcorne\\b", RegexOption.IGNORE_CASE) to "come",
        Regex("\\brny\\b", RegexOption.IGNORE_CASE) to "my",
        Regex("\\bl'm\\b") to "I'm",
        Regex("\\bl'll\\b") to "I'll",
        Regex("\\bl've\\b") to "I've",
        Regex("\\bl'd\\b") to "I'd",
        Regex("(?<=^|\\s)l(?=\\s)") to "I",
        Regex("\\|") to "I"
    )

    private val noiseOnlyRegex = Regex("^[0-9:./\\-\\s%$#@*+_=<>\\[\\]()]+$")

    fun cleanSubtitle(raw: String): String {
        if (raw.isBlank()) return ""
        var text = raw
            .replace("\r", " ")
            .replace("\n", " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        for ((pattern, replacement) in commonFixes) {
            text = pattern.replace(text, replacement)
        }

        if (text.length < 3 || noiseOnlyRegex.matches(text)) return ""
        val letterCount = text.count { it.isLetter() }
        if (letterCount < 2) return ""

        return text
    }

    fun normalizeForComparison(text: String): String {
        return text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun similarity(a: String, b: String): Double {
        val s1 = normalizeForComparison(a)
        val s2 = normalizeForComparison(b)
        if (s1 == s2) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0

        val maxLen = max(s1.length, s2.length)
        val dist = levenshtein(s1, s2)
        return 1.0 - (dist.toDouble() / maxLen.toDouble())
    }

    private fun levenshtein(a: String, b: String): Int {
        val prev = IntArray(b.length + 1) { it }
        val curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = min(min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            System.arraycopy(curr, 0, prev, 0, curr.size)
        }
        return prev[b.length]
    }
}
