package com.example.konkani

import android.app.AlertDialog
import android.media.MediaPlayer
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * Clips — the soundboard. Every phrase a native recorded (via Fix it) becomes a playable card:
 * English caption + Konkani, tap to PLAY the native's actual voice to someone, hold to file it
 * under a group ("bazaar", "petrol pump", ...). Grouped list, ungrouped last.
 */
class ClipsActivity : AppCompatActivity() {

    private lateinit var store: Store
    private var clips: List<Phrase> = emptyList()
    private var player: MediaPlayer? = null
    private lateinit var list: ListView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_clips)
        store = Store(this)

        findViewById<TextView>(R.id.clipsTitle).typeface = Fonts.latinMedium(this)
        findViewById<TextView>(R.id.clipsHint).typeface = Fonts.latin(this)
        list = findViewById(R.id.clipsList)
        reload()

        list.setOnItemClickListener { _, _, pos, _ -> play(clips[pos]) }
        list.setOnItemLongClickListener { _, _, pos, _ -> groupDialog(clips[pos]); true }
    }

    private fun reload() {
        clips = store.listClips()
        val sub = findViewById<TextView>(R.id.clipsCount)
        sub.typeface = Fonts.latin(this)
        sub.text = if (clips.isEmpty())
            "No clips yet \u2014 have a native record via Fix it \u2192 Record."
        else
            "${clips.size} native clips \u00B7 tap to play \u00B7 hold to group"
        list.adapter = object : BaseAdapter() {
            override fun getCount() = clips.size
            override fun getItem(i: Int) = clips[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, convert: View?, parent: ViewGroup): View {
                val v = convert ?: layoutInflater.inflate(R.layout.row_clip, parent, false)
                val p = clips[i]
                val grpTv = v.findViewById<TextView>(R.id.clipGroup)
                val showHeader = i == 0 || clips[i - 1].grp != p.grp
                grpTv.visibility = if (showHeader) View.VISIBLE else View.GONE
                grpTv.text = (p.grp ?: "Ungrouped").uppercase()
                grpTv.typeface = Fonts.latin(this@ClipsActivity)
                val en = v.findViewById<TextView>(R.id.clipEnglish)
                en.text = p.english
                en.typeface = Fonts.latin(this@ClipsActivity)
                val kok = v.findViewById<TextView>(R.id.clipKonkani)
                kok.text = Script.toRoman(p.konkaniText)
                kok.typeface = Fonts.latin(this@ClipsActivity)
                return v
            }
        }
    }

    private fun play(p: Phrase) {
        val path = p.nativeAudioPath ?: return
        if (!File(path).exists()) {
            Toast.makeText(this, "Recording file missing", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            player?.release()
            val mp = MediaPlayer()
            mp.setDataSource(path)
            mp.setOnCompletionListener { it.release(); if (player === it) player = null }
            mp.prepare()
            mp.start()
            player = mp
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't play: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun groupDialog(p: Phrase) {
        val input = EditText(this)
        input.setText(p.grp ?: "")
        input.hint = "e.g. bazaar, petrol pump, church"
        val existing = store.groups()
        val msg = if (existing.isEmpty()) "Name a group for this clip."
        else "Name a group for this clip.\nExisting: ${existing.joinToString(" \u00B7 ")}"
        AlertDialog.Builder(this)
            .setTitle("Group clip")
            .setMessage(msg)
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                store.setGroup(p.english, input.text.toString())
                reload()
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Ungroup") { _, _ ->
                store.setGroup(p.english, null)
                reload()
            }
            .show()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        if (::store.isInitialized) store.close()
        super.onDestroy()
    }
}
