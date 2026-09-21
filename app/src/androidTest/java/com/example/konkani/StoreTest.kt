package com.example.konkani

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the Stage 0 acceptance criterion: a native correction is stored and REPLAYS INSTANTLY
 * via phrase memory for the same English (case/punctuation-insensitive) — the flywheel's fast path.
 */
@RunWith(AndroidJUnit4::class)
class StoreTest {

    private lateinit var store: Store

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.deleteDatabase("konkani.db")
        store = Store(ctx)
    }

    @Test
    fun correction_replays_instantly() {
        val english = "Where is the hospital?"
        val correct = "\u0939\u0949\u0938\u094D\u092A\u093F\u091F\u0932 \u0916\u0902\u092F \u0906\u0938\u093E?" // हॉस्पिटल खंय आसा?

        // 1) nothing in memory yet
        assertNull(store.lookupPhrase(english))

        // 2) a native fixes it
        store.insertCorrection(
            englishSource = english,
            ourKonkani = "wrong konkani",
            nativeAudioPath = null,
            confirmedText = correct,
            region = "Bardez",
            dialect = "Catholic",
            script = "Devanagari",
            consent = true
        )

        // 3) same English (different case + spacing/punctuation) replays the correction immediately
        val hit = store.lookupPhrase("  where is the hospital  ")
        assertNotNull(hit)
        assertEquals(correct, hit!!.konkaniText)
        assertEquals("native_correction", hit.source)
        assertEquals(1, store.correctionCount())
    }

    @Test
    fun normalize_is_case_and_punctuation_insensitive() {
        assertEquals(Store.normalize("Thank you!!"), Store.normalize("thank   you"))
    }
}
