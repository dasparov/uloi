package com.example.konkani

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * The flywheel data as a lesson book: every starter/saved/speaker-corrected phrase, browsable.
 * Tapping a row returns it to the main screen (filled in, ready to Hear / Practice / Fix).
 */
class PhrasebookActivity : AppCompatActivity() {

    private lateinit var phrases: List<Phrase>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_phrasebook)
        Thread { Script.warm() }.start()

        val store = Store(this)
        phrases = store.listPhrases()
        store.close()

        findViewById<TextView>(R.id.pbTitle).typeface = Fonts.latinMedium(this)
        val sub = findViewById<TextView>(R.id.pbCount)
        sub.typeface = Fonts.latin(this)
        val corrected = phrases.count { it.source == "native_correction" }
        sub.text = "${phrases.size} phrases \u00B7 $corrected speaker-corrected"

        val list = findViewById<ListView>(R.id.phraseList)
        list.adapter = object : BaseAdapter() {
            override fun getCount() = phrases.size
            override fun getItem(i: Int) = phrases[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, convert: View?, parent: ViewGroup): View {
                val v = convert ?: layoutInflater.inflate(R.layout.row_phrase, parent, false)
                val p = phrases[i]
                val en = v.findViewById<TextView>(R.id.rowEnglish)
                val tag = v.findViewById<TextView>(R.id.rowTag)
                val kokMain = v.findViewById<TextView>(R.id.rowKonkani)
                val kokAlt = v.findViewById<TextView>(R.id.rowKonkaniAlt)
                en.text = p.english
                en.typeface = Fonts.latin(this@PhrasebookActivity)
                tag.text = when (p.source) {
                    "native_correction" -> "\u2713 local"
                    "starter" -> "starter"
                    else -> "saved"
                }
                tag.typeface = Fonts.latin(this@PhrasebookActivity)
                val roman = Script.toRoman(p.konkaniText)
                kokMain.text = roman
                kokMain.typeface = Fonts.latin(this@PhrasebookActivity)
                if (roman != p.konkaniText) {
                    kokAlt.text = p.konkaniText
                    kokAlt.typeface = Fonts.devanagari(this@PhrasebookActivity)
                    kokAlt.visibility = View.VISIBLE
                } else {
                    kokAlt.visibility = View.GONE
                }
                return v
            }
        }
        list.setOnItemClickListener { _, _, pos, _ ->
            val p = phrases[pos]
            setResult(
                Activity.RESULT_OK,
                Intent()
                    .putExtra("english", p.english)
                    .putExtra("konkani", p.konkaniText)
                    .putExtra("audio", p.nativeAudioPath)
            )
            finish()
        }
    }
}
