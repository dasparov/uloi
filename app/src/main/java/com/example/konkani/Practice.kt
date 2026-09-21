package com.example.konkani

/** Pronunciation-practice scoring: compare what STT heard vs the target phrase. */
object Practice {

    /** Similarity 0..100, script-agnostic: both sides romanized + normalized, then Levenshtein. */
    fun score(heard: String, target: String): Int {
        val a = norm(Script.toRoman(heard))
        val b = norm(Script.toRoman(target))
        if (a.isEmpty() || b.isEmpty()) return 0
        val d = levenshtein(a, b)
        val sim = 1.0 - d.toDouble() / maxOf(a.length, b.length)
        return (sim * 100).toInt().coerceIn(0, 100)
    }

    private fun norm(s: String): String =
        s.lowercase().replace(Regex("\\p{Punct}"), "").replace(Regex("\\s+"), " ").trim()

    private fun levenshtein(a: String, b: String): Int {
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            prev.indices.forEach { prev[it] = cur[it] }
        }
        return prev[b.length]
    }
}
