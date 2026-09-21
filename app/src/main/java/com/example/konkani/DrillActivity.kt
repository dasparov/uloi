package com.example.konkani

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.Locale

/**
 * "Practice 5" — two passes over 5 phrasebook phrases:
 *   LEARN : see English -> reveal the Konkani (both scripts) -> hear it -> say it (scored).
 *   RECAP : same phrases, shuffled, English only — recall the Konkani FROM MEMORY and say it.
 *           "Show answer" is available but that item no longer counts as remembered.
 * Ends with: learn average + how many were truly remembered.
 */
class DrillActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private enum class Phase { LEARN, RECAP }

    private lateinit var items: List<Phrase>
    private lateinit var recapOrder: List<Int>
    private var phase = Phase.LEARN
    private var idx = 0
    private lateinit var learnScores: Array<Int?>
    private lateinit var recapScores: Array<Int?>
    private lateinit var answerShown: BooleanArray

    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var player: MediaPlayer? = null
    private lateinit var sayLauncher: ActivityResultLauncher<Intent>

    private lateinit var progress: TextView
    private lateinit var hint: TextView
    private lateinit var english: TextView
    private lateinit var revealBox: View
    private lateinit var actionRow: View
    private lateinit var kokMain: TextView
    private lateinit var kokAlt: TextView
    private lateinit var scoreTv: TextView
    private lateinit var btnReveal: Button
    private lateinit var btnHear: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_drill)

        val store = Store(this)
        items = store.randomPhrases(5)
        store.close()
        if (items.isEmpty()) {
            Toast.makeText(this, "No phrases yet \u2014 translate or save some first.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        recapOrder = items.indices.shuffled()
        learnScores = arrayOfNulls(items.size)
        recapScores = arrayOfNulls(items.size)
        answerShown = BooleanArray(items.size)

        tts = TextToSpeech(this, this)

        progress = findViewById(R.id.drillProgress)
        hint = findViewById(R.id.drillHint)
        english = findViewById(R.id.drillEnglish)
        revealBox = findViewById(R.id.drillReveal)
        actionRow = findViewById(R.id.drillActions)
        kokMain = findViewById(R.id.drillKonkani)
        kokAlt = findViewById(R.id.drillKonkaniAlt)
        scoreTv = findViewById(R.id.drillScore)
        btnReveal = findViewById(R.id.btnReveal)
        btnHear = findViewById(R.id.btnDrillHear)

        findViewById<TextView>(R.id.drillTitle).typeface = Fonts.latinMedium(this)
        english.typeface = Fonts.latin(this)
        progress.typeface = Fonts.latin(this)
        hint.typeface = Fonts.latin(this)
        scoreTv.typeface = Fonts.latin(this)
        btnReveal.typeface = Fonts.latinMedium(this)

        sayLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val heard = result.data
                    ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull()?.trim()
                if (!heard.isNullOrEmpty()) {
                    val target = cur().konkaniText
                    val pct = Practice.score(heard, target)
                    val fellBack = target.any { it in '\u0900'..'\u097F' } &&
                        heard.none { it in '\u0900'..'\u097F' } && pct < 60
                    if (fellBack) {
                        scoreTv.text = "Heard English (\u201C$heard\u201D) \u2014 try again."
                    } else {
                        recordScore(pct)
                        scoreTv.text = "$pct% match \u2014 you said: ${Script.toRoman(heard)}"
                    }
                } else {
                    scoreTv.text = "Didn't catch that \u2014 try Say it again."
                }
            }
        }

        btnReveal.setOnClickListener { reveal() }
        btnHear.setOnClickListener { playCurrent() }
        findViewById<Button>(R.id.btnDrillSay).setOnClickListener {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                // Google ships no Konkani ASR; the Marathi model hears Konkani best.
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "mr-IN")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "mr-IN")
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Say the Konkani phrase\u2026")
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    val t = cur().konkaniText
                    putExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, arrayListOf(t, Script.toRoman(t)))
                }
            }
            try { sayLauncher.launch(intent) } catch (e: Exception) {
                Toast.makeText(this, "No speech recognizer available", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.btnDrillNext).setOnClickListener { next() }

        showItem()
    }

    private fun cur(): Phrase =
        if (phase == Phase.LEARN) items[idx] else items[recapOrder[idx]]

    private fun curIndex(): Int =
        if (phase == Phase.LEARN) idx else recapOrder[idx]

    private fun recordScore(pct: Int) {
        val i = curIndex()
        when (phase) {
            Phase.LEARN -> learnScores[i] = maxOf(learnScores[i] ?: 0, pct)
            Phase.RECAP -> recapScores[i] = maxOf(recapScores[i] ?: 0, pct)
        }
    }

    private fun showItem() {
        english.text = cur().english
        revealBox.visibility = View.GONE
        scoreTv.text = ""
        when (phase) {
            Phase.LEARN -> {
                progress.text = "${idx + 1} / ${items.size}"
                hint.text = "Read it, hear it, say it."
                btnReveal.text = "Reveal the Konkani"
                btnReveal.visibility = View.VISIBLE
                actionRow.visibility = View.GONE
            }
            Phase.RECAP -> {
                progress.text = "Recap ${idx + 1} / ${items.size}"
                hint.text = "From memory \u2014 say it in Konkani."
                btnReveal.text = "Show answer"
                btnReveal.visibility = View.VISIBLE
                actionRow.visibility = View.VISIBLE
                btnHear.isEnabled = false
                btnHear.alpha = 0.4f
            }
        }
    }

    private fun reveal() {
        val p = cur()
        val roman = Script.toRoman(p.konkaniText)
        kokMain.text = roman
        kokMain.typeface = Fonts.latin(this)
        if (roman != p.konkaniText) {
            kokAlt.text = p.konkaniText
            kokAlt.typeface = Fonts.devanagari(this)
            kokAlt.visibility = View.VISIBLE
        } else {
            kokAlt.visibility = View.GONE
        }
        revealBox.visibility = View.VISIBLE
        btnReveal.visibility = View.GONE
        actionRow.visibility = View.VISIBLE
        btnHear.isEnabled = true
        btnHear.alpha = 1f
        if (phase == Phase.RECAP) answerShown[curIndex()] = true
        playCurrent()
    }

    private fun playCurrent() {
        val p = cur()
        val path = p.nativeAudioPath
        if (path != null && File(path).exists()) {
            try {
                player?.release()
                val mp = MediaPlayer()
                mp.setDataSource(path)
                mp.setOnCompletionListener { it.release(); if (player === it) player = null }
                mp.prepare()
                mp.start()
                player = mp
                return
            } catch (_: Exception) { /* fall through to TTS */ }
        }
        if (ttsReady) {
            tts.setSpeechRate(0.85f)
            if (tts.setLanguage(Locale("kok", "IN")) >= 0) {
                tts.speak(p.konkaniText, TextToSpeech.QUEUE_FLUSH, null, "drill")
            }
        }
    }

    private fun next() {
        if (idx + 1 < items.size) {
            idx++
            showItem()
            return
        }
        if (phase == Phase.LEARN) {
            phase = Phase.RECAP
            idx = 0
            Toast.makeText(this, "Recap \u2014 now from memory!", Toast.LENGTH_SHORT).show()
            showItem()
            return
        }
        // finished recap -> summary
        val learn = learnScores.filterNotNull()
        val learnAvg = if (learn.isEmpty()) null else learn.sum() / learn.size
        var remembered = 0
        for (i in items.indices) {
            val s = recapScores[i]
            if (s != null && s >= 60 && !answerShown[i]) remembered++
        }
        val shown = answerShown.count { it }
        val sb = StringBuilder()
        sb.append("Remembered from memory: $remembered / ${items.size}")
        if (shown > 0) sb.append("\nNeeded the answer: $shown")
        if (learnAvg != null) sb.append("\nLearn-pass average: $learnAvg%")
        val recap = recapScores.filterNotNull()
        if (recap.isNotEmpty()) sb.append("\nRecap average: ${recap.sum() / recap.size}%")
        AlertDialog.Builder(this)
            .setTitle("Practice complete")
            .setMessage(sb.toString())
            .setPositiveButton("Done") { _, _ -> finish() }
            .setOnDismissListener { finish() }
            .show()
    }

    override fun onInit(code: Int) { ttsReady = code == TextToSpeech.SUCCESS }

    override fun onDestroy() {
        player?.release()
        player = null
        if (::tts.isInitialized) { tts.stop(); tts.shutdown() }
        super.onDestroy()
    }
}
