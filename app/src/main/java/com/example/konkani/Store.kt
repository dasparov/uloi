package com.example.konkani

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

/** A saved/corrected phrase from phrase memory. */
data class Phrase(
    val english: String,
    val konkaniText: String,
    val nativeAudioPath: String?,
    val source: String,
    val grp: String? = null
)

/**
 * On-device data plane (mirrors ARCHITECTURE.md §6 until the cloud backend is live).
 * v2: display English for the Phrasebook/drill.
 * v3: corrections.src_lang (user side may be English or Hindi) + phrase_memory.grp
 *     (soundboard groups like "bazaar", "petrol pump"). Upgrades preserve data.
 */
class Store(context: Context) : SQLiteOpenHelper(context, "konkani.db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE phrase_memory(" +
                "english_norm TEXT PRIMARY KEY, english_display TEXT, konkani_text TEXT NOT NULL, " +
                "konkani_audio_path TEXT, source TEXT NOT NULL, updated_at INTEGER NOT NULL, grp TEXT)"
        )
        db.execSQL(
            "CREATE TABLE corrections(" +
                "correction_id TEXT PRIMARY KEY, created_at INTEGER NOT NULL, " +
                "english_source TEXT NOT NULL, our_konkani_text TEXT, native_audio_path TEXT, " +
                "native_confirmed_text TEXT, region TEXT, dialect TEXT, script TEXT, " +
                "consent INTEGER NOT NULL, status TEXT NOT NULL, src_lang TEXT DEFAULT 'en')"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        if (oldV < 2) {
            db.execSQL("ALTER TABLE phrase_memory ADD COLUMN english_display TEXT")
            db.execSQL("UPDATE phrase_memory SET english_display = english_norm WHERE english_display IS NULL")
        }
        if (oldV < 3) {
            db.execSQL("ALTER TABLE corrections ADD COLUMN src_lang TEXT DEFAULT 'en'")
            db.execSQL("ALTER TABLE phrase_memory ADD COLUMN grp TEXT")
        }
    }

    fun lookupPhrase(english: String): Phrase? {
        readableDatabase.query(
            "phrase_memory",
            arrayOf("english_display", "konkani_text", "konkani_audio_path", "source", "grp"),
            "english_norm=? AND source != 'clip' AND konkani_text != ''",
            arrayOf(normalize(english)), null, null, null
        ).use { c ->
            return if (c.moveToFirst())
                Phrase(c.getString(0) ?: english, c.getString(1), c.getString(2), c.getString(3), c.getString(4))
            else null
        }
    }

    fun upsertPhrase(english: String, konkaniText: String, audioPath: String?, source: String) {
        val norm = normalize(english)
        val existingGrp = readableDatabase.rawQuery(
            "SELECT grp FROM phrase_memory WHERE english_norm=?", arrayOf(norm)
        ).use { if (it.moveToFirst()) it.getString(0) else null }
        val cv = ContentValues().apply {
            put("english_norm", norm)
            put("english_display", english.trim())
            put("konkani_text", konkaniText)
            put("konkani_audio_path", audioPath)
            put("source", source)
            put("updated_at", System.currentTimeMillis())
            if (existingGrp != null) put("grp", existingGrp)
        }
        writableDatabase.insertWithOnConflict("phrase_memory", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Starter entries never overwrite anything the user or a local speaker already saved. */
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

    fun listPhrases(): List<Phrase> = queryPhrases(
        "SELECT english_display, konkani_text, konkani_audio_path, source, grp FROM phrase_memory " +
            "WHERE konkani_text != '' ORDER BY updated_at DESC"
    )

    fun randomPhrases(n: Int): List<Phrase> = queryPhrases(
        "SELECT english_display, konkani_text, konkani_audio_path, source, grp FROM phrase_memory ORDER BY RANDOM() LIMIT $n"
    )

    /** Soundboard: phrases that carry a speaker's recording, grouped first, newest first within a group. */
    fun listClips(): List<Phrase> = queryPhrases(
        "SELECT english_display, konkani_text, konkani_audio_path, source, grp FROM phrase_memory " +
            "WHERE konkani_audio_path IS NOT NULL ORDER BY grp IS NULL, grp, updated_at DESC"
    )

    fun setGroup(english: String, grp: String?) {
        val cv = ContentValues().apply {
            if (grp.isNullOrBlank()) putNull("grp") else put("grp", grp.trim())
        }
        writableDatabase.update("phrase_memory", cv, "english_norm=?", arrayOf(normalize(english)))
    }

    /** Add a standalone local-voice clip (not a correction): recording + English caption (+ optional Konkani, tag). */
    fun addClip(english: String, konkani: String?, audioPath: String, grp: String?) {
        val cv = ContentValues().apply {
            put("english_norm", normalize(english))
            put("english_display", english.trim())
            put("konkani_text", konkani?.trim().orEmpty())
            put("konkani_audio_path", audioPath)
            put("source", "clip")
            put("updated_at", System.currentTimeMillis())
            if (grp.isNullOrBlank()) putNull("grp") else put("grp", grp.trim())
        }
        writableDatabase.insertWithOnConflict("phrase_memory", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun groups(): List<String> {
        val out = ArrayList<String>()
        readableDatabase.rawQuery(
            "SELECT DISTINCT grp FROM phrase_memory WHERE grp IS NOT NULL AND grp != '' ORDER BY grp", null
        ).use { c -> while (c.moveToNext()) out.add(c.getString(0)) }
        return out
    }

    private fun queryPhrases(sql: String): List<Phrase> {
        val out = ArrayList<Phrase>()
        readableDatabase.rawQuery(sql, null).use { c ->
            while (c.moveToNext()) {
                out.add(Phrase(c.getString(0) ?: "", c.getString(1), c.getString(2), c.getString(3), c.getString(4)))
            }
        }
        return out
    }

    /** Store a local speaker's correction AND update phrase memory so it replays instantly. */
    fun insertCorrection(
        englishSource: String, ourKonkani: String?, nativeAudioPath: String?,
        confirmedText: String?, region: String, dialect: String, script: String, consent: Boolean,
        srcLang: String = "en"
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
            put("src_lang", srcLang)
        }
        writableDatabase.insert("corrections", null, cv)
        val text = confirmedText?.takeIf { it.isNotBlank() }
        if (srcLang == "en" && (text != null || nativeAudioPath != null)) {
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
