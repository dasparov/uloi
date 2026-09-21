package com.example.konkani

import android.icu.text.Transliterator

/**
 * On-screen Konkani script toggle (decision #1: Devanagari + Roman).
 * Stage 0 uses ICU's mechanical Devanagari->Latin transliteration. This is a placeholder for
 * traditional Romi (Catholic) spelling, which native corrections will supply later (ARCHITECTURE.md §12).
 */
object Script {
    private val devToLatin: Transliterator? = try {
        Transliterator.getInstance("Devanagari-Latin")
    } catch (e: Exception) {
        null
    }

    fun toRoman(devanagari: String): String =
        try {
            devToLatin?.transliterate(devanagari) ?: devanagari
        } catch (e: Exception) {
            devanagari
        }
}
