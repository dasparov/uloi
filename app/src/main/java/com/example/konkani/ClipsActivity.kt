package com.example.konkani

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Clips — the soundboard AND the field recorder. Collect a local speaker's voice directly
 * (Add clip: record with a live meter + English caption + optional Konkani + tag) or via
 * Fix-it corrections. Tap a card to play the voice to someone; hold to re-tag.
 */
class ClipsActivity : AppCompatActivity() {

    private lateinit var store: Store
    private var clips: List<Phrase> = emptyList()
    private var player: MediaPlayer? = null
    private lateinit var list: ListView

    private lateinit var permLauncher: ActivityResultLauncher<String>
    private var pendingRecordAction: (() -> Unit)? = null
    private var recorder: MediaRecorder? = null
    private var recordFile: File? = null
    private var onRecordSaved: ((String) -> Unit)? = null
    private var activeMeter: RecordingMeter? = null
    private var slow = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_clips)
        store = Store(this)
        Thread { Script.warm() }.start()

        findViewById<TextView>(R.id.clipsTitle).typeface = Fonts.latinMedium(this)
        findViewById<TextView>(R.id.clipsHint).typeface = Fonts.latin(this)
        val addBtn = findViewById<Button>(R.id.btnAddClip)
        addBtn.typeface = Fonts.latinMedium(this)
        list = findViewById(R.id.clipsList)

        permLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            val action = pendingRecordAction
            pendingRecordAction = null
            if (granted) action?.invoke()
            else Toast.makeText(this, "Microphone permission needed to record", Toast.LENGTH_SHORT).show()
        }

        addBtn.setOnClickListener { showAddDialog() }
        val slowBtn = findViewById<Button>(R.id.btnClipsSlow)
        slowBtn.setOnClickListener {
            slow = !slow
            slowBtn.text = if (slow) "Slow \u2713" else "Slow"
        }
        list.setOnItemClickListener { _, _, pos, _ -> play(clips[pos]) }
        list.setOnItemLongClickListener { _, _, pos, _ -> tagDialog(clips[pos]); true }
        reload()
    }

    private fun reload() {
        clips = store.listClips()
        val sub = findViewById<TextView>(R.id.clipsCount)
        sub.typeface = Fonts.latin(this)
        sub.text = if (clips.isEmpty())
            "No clips yet \u2014 record a local speaker with Add clip."
        else
            "${clips.size} local-voice clips \u00B7 tap to play \u00B7 hold to tag"
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
                grpTv.text = (p.grp ?: "Untagged").uppercase()
                grpTv.typeface = Fonts.latin(this@ClipsActivity)
                val en = v.findViewById<TextView>(R.id.clipEnglish)
                en.text = p.english
                en.typeface = Fonts.latin(this@ClipsActivity)
                val kok = v.findViewById<TextView>(R.id.clipKonkani)
                val roman = Script.toRoman(p.konkaniText)
                if (roman.isBlank()) {
                    kok.visibility = View.GONE
                } else {
                    kok.visibility = View.VISIBLE
                    kok.text = roman
                    kok.typeface = Fonts.latin(this@ClipsActivity)
                }
                return v
            }
        }
    }

    // ---------- add a clip directly (no fixing required) ----------
    private fun showAddDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_addclip, null)
        val etEnglish = view.findViewById<EditText>(R.id.acEnglish)
        val etKonkani = view.findViewById<EditText>(R.id.acKonkani)
        val etTag = view.findViewById<EditText>(R.id.acTag)
        val cbConsent = view.findViewById<CheckBox>(R.id.acConsent)
        val btnRecord = view.findViewById<Button>(R.id.acRecord)
        val meter = view.findViewById<RecordingMeter>(R.id.acMeter)
        val existing = store.groups()
        if (existing.isNotEmpty()) etTag.hint = "Tag \u2014 existing: ${existing.joinToString(" \u00B7 ")}"

        val audioPath = arrayOfNulls<String>(1)
        btnRecord.setOnClickListener { toggleRecording(btnRecord, meter) { path -> audioPath[0] = path } }

        AlertDialog.Builder(this)
            .setTitle("Add a local-voice clip")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                stopRecordingIfAny()
                val english = etEnglish.text.toString().trim()
                when {
                    english.isEmpty() ->
                        Toast.makeText(this, "Add the English meaning \u2014 it's the caption", Toast.LENGTH_SHORT).show()
                    audioPath[0] == null ->
                        Toast.makeText(this, "Record the speaker first", Toast.LENGTH_SHORT).show()
                    !cbConsent.isChecked ->
                        Toast.makeText(this, "Consent required to save", Toast.LENGTH_SHORT).show()
                    else -> {
                        store.addClip(
                            english = english,
                            konkani = Script.toDeva(etKonkani.text.toString()),
                            audioPath = audioPath[0]!!,
                            grp = etTag.text.toString()
                        )
                        reload()
                        Toast.makeText(this, "Clip saved \u2714", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel") { _, _ -> stopRecordingIfAny() }
            .setOnDismissListener { stopRecordingIfAny() }
            .show()
    }

    // ---------- recording with live meter ----------
    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()

    private fun toggleRecording(btn: Button, meter: RecordingMeter?, onSaved: (String) -> Unit) {
        if (recorder == null) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                pendingRecordAction = { toggleRecording(btn, meter, onSaved) }
                permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                return
            }
            val f = File(filesDir, "clip_${System.currentTimeMillis()}.m4a")
            try {
                val rec = newRecorder()
                rec.setAudioSource(MediaRecorder.AudioSource.MIC)
                rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                rec.setOutputFile(f.absolutePath)
                rec.prepare()
                rec.start()
                recorder = rec
                recordFile = f
                onRecordSaved = onSaved
                activeMeter = meter
                meter?.start { recorder?.maxAmplitude ?: 0 }
                btn.text = "Stop"
            } catch (e: Exception) {
                recorder = null
                Toast.makeText(this, "Recording unavailable: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            stopRecordingIfAny()
            btn.text = "Record"
        }
    }

    private fun stopRecordingIfAny() {
        activeMeter?.stop()
        activeMeter = null
        val rec = recorder ?: return
        recorder = null
        try { rec.stop() } catch (_: Exception) {}
        try { rec.release() } catch (_: Exception) {}
        val f = recordFile
        recordFile = null
        val cb = onRecordSaved
        onRecordSaved = null
        if (f != null && f.exists() && f.length() > 0) cb?.invoke(f.absolutePath)
    }

    // ---------- play + tag ----------
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
            if (slow) {
                try { mp.playbackParams = mp.playbackParams.setSpeed(0.72f) } catch (_: Exception) {}
            }
            player = mp
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't play: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun tagDialog(p: Phrase) {
        val input = EditText(this)
        input.setText(p.grp ?: "")
        input.hint = "e.g. bazaar, petrol pump, church"
        val existing = store.groups()
        val msg = if (existing.isEmpty()) "Tag this clip."
        else "Tag this clip.\nExisting: ${existing.joinToString(" \u00B7 ")}"
        AlertDialog.Builder(this)
            .setTitle("Tag clip")
            .setMessage(msg)
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                store.setGroup(p.english, input.text.toString())
                reload()
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Untag") { _, _ ->
                store.setGroup(p.english, null)
                reload()
            }
            .show()
    }

    override fun onDestroy() {
        stopRecordingIfAny()
        player?.release()
        player = null
        if (::store.isInitialized) store.close()
        super.onDestroy()
    }
}
