package com.example.konkani

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

/** A corrected/saved phrase from phrase memory. */
data class Phrase(
    val english: String,
    val konkaniText: String,
    val nativeAudioPath: String?,
    val source: String
)

/**
 * On-device data plane (mirrors ARCHITECTURE.md §6 until the cloud backend is live).
 * v2: phrase_memory carries the display English (original casing) so the Phrasebook/drill can
 * show real sentences, not normalized keys. Upgrades preserve corrections (training data).
 */
class Store(context: Context) : SQLiteOpenHelper(context, "konkani.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE phrase_memory(" +
                "english_norm TEXT PRIMARY KEY, english_display TEXT, konkani_text TEXT NOT NULL, " +
                "konkani_audio_path TEXT, source TEXT NOT NULL, updated_at INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE corrections(" +
                "correction_id TEXT PRIMARY KEY, created_at INTEGER NOT NULL, " +
                "english_source TEXT NOT NULL, our_konkani_text TEXT, native_audio_path TEXT, " +
                "native_confirmed_text TEXT, region TEXT, dialect TEXT, script TEXT, " +
                "consent INTEGER NOT NULL, status TEXT NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        if (oldV < 2) {
            db.execSQL("ALTER TABLE phrase_memory ADD COLUMN english_display TEXT")
            db.execSQL("UPDATE phrase_memory SET english_display = english_norm WHERE english_display IS NULL")
        }
    }

    fun lookupPhrase(english: String): Phrase? {
        readableDatabase.query(
            "phrase_memory",
            arrayOf("english_display", "konkani_text", "konkani_audio_path", "source"),
            "english_norm=?", arrayOf(normalize(english)), null, null, null
        ).use { c ->
            return if (c.moveToFirst())
                Phrase(c.getString(0) ?: english, c.getString(1), c.getString(2), c.getString(3))
            else null
        }
    }

    fun upsertPhrase(english: String, konkaniText: String, audioPath: String?, source: String) {
        val cv = ContentValues().apply {
            put("english_norm", normalize(english))
            put("english_display", english.trim())
            put("konkani_text", konkaniText)
            put("konkani_audio_path", audioPath)
            put("source", source)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("phrase_memory", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Starter entries never overwrite anything the user or a native already saved. */
    fun seedStarter(pack: List<Pair<String, String>>) {
        val db = writableDatabase
        for ((en, kok) in pack) {
            val cv = ContentValues().apply {
                put("english_norm", normalize(en))
                put("english_display", en)
                put("konkani_text", kok)
                putNull("konkani_audio_path")
                put("source", "starter")
                put("updated_at", System.currentTimeMillis())
            }
            db.insertWithOnConflict("phrase_memory", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        }
    }

    fun listPhrases(): List<Phrase> = queryPhrases("SELECT english_display, konkani_text, konkani_audio_path, source FROM phrase_memory ORDER BY updated_at DESC")

    fun randomPhrases(n: Int): List<Phrase> = queryPhrases("SELECT english_display, konkani_text, konkani_audio_path, source FROM phrase_memory ORDER BY RANDOM() LIMIT $n")

    private fun queryPhrases(sql: String): List<Phrase> {
        val out = ArrayList<Phrase>()
        readableDatabase.rawQuery(sql, null).use { c ->
            while (c.moveToNext()) {
                out.add(Phrase(c.getString(0) ?: "", c.getString(1), c.getString(2), c.getString(3)))
            }
        }
        return out
    }

    /** Store a native correction AND update phrase memory so it replays instantly. */
    fun insertCorrection(
        englishSource: String, ourKonkani: String?, nativeAudioPath: String?,
        confirmedText: String?, region: String, dialect: String, script: String, consent: Boolean
    ): String {
        val id = "cor_" + UUID.randomUUID().toString().take(8)
        val cv = ContentValues().apply {
            put("correction_id", id)
            put("created_at", System.currentTimeMillis())
            put("english_source", englishSource)
            put("our_konkani_text", ourKonkani)
            put("native_audio_path", nativeAudioPath)
            put("native_confirmed_text", confirmedText)
            put("region", region)
            put("dialect", dialect)
            put("script", script)
            put("consent", if (consent) 1 else 0)
            put("status", "new")
        }
        writableDatabase.insert("corrections", null, cv)
        val text = confirmedText?.takeIf { it.isNotBlank() }
        if (text != null || nativeAudioPath != null) {
            upsertPhrase(englishSource, text ?: (ourKonkani ?: ""), nativeAudioPath, "native_correction")
        }
        return id
    }

    fun correctionCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM corrections", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    companion object {
        fun normalize(s: String): String =
            s.lowercase().trim().replace(Regex("\\p{Punct}"), "").replace(Regex("\\s+"), " ")
    }
}
