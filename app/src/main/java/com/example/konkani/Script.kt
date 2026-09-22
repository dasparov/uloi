package com.example.konkani

import android.icu.text.Transliterator

/**
 * On-screen Konkani script toggle (decision #1: Devanagari + Roman).
 * Stage 0 uses ICU's mechanical transliteration both ways. This is a placeholder for
 * traditional Romi (Catholic) spelling, which local speakers' corrections will supply later
 * (ARCHITECTURE.md §12).
 *
 * ICU Transliterator.getInstance is slow (rule compilation) — never build it on the main
 * thread. Activities call [warm] from a background executor; until warmed, both directions
 * fall back to returning the input unchanged.
 */
object Script {
    @Volatile private var devToLatin: Transliterator? = null
    @Volatile private var latinToDev: Transliterator? = null
    @Volatile private var warmed = false

    /** Build the transliterators. Idempotent; call from a background thread. */
    fun warm() {
        if (warmed) return
        synchronized(this) {
            if (warmed) return
            devToLatin = try {
                Transliterator.getInstance("Devanagari-Latin")
            } catch (e: Exception) {
                null
            }
            latinToDev = try {
                Transliterator.getInstance("Latin-Devanagari")
            } catch (e: Exception) {
                null
            }
            warmed = true
        }
    }

    fun toRoman(devanagari: String): String =
        try {
            devToLatin?.transliterate(devanagari) ?: devanagari
        } catch (e: Exception) {
            devanagari
        }

    private fun hasDevanagari(s: String): Boolean = s.any { it in '\u0900'..'\u097F' }

    /**
     * Canonicalize typed Konkani: people type Romi (English letters), storage/display canonical
     * is Devanagari. Devanagari input passes through untouched.
     */
    fun toDeva(text: String): String {
        val t = text.trim()
        if (t.isEmpty() || hasDevanagari(t)) return t
        return try {
            latinToDev?.transliterate(t.lowercase()) ?: t
        } catch (e: Exception) {
            t
        }
    }
}
