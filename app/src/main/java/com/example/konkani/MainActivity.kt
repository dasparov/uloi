package com.example.konkani

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Interpreter + tutor (Stage 0, luxe skin). Speak OR type either language; results render in both
 * Konkani scripts; Practice mode scores your pronunciation via Konkani STT; Phrasebook/Drill turn
 * the flywheel data (corrections + starter pack) into lessons. Konkani audio prefers a native's
 * recording, then TTS; Slow toggle stretches both. Cloud seam (BASE_URL) stays dormant until set.
 */
class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var konkaniTtsOk = false

    private lateinit var store: Store
    private val backend: TranslationBackend = GoogleBackend()
    private val cloud: CloudBackend? =
        BackendConfig.BASE_URL.takeIf { it.isNotBlank() }?.let { CloudBackend(it) }

    private lateinit var englishOutput: EditText
    private lateinit var konkaniOutput: EditText
    private lateinit var konkaniAlt: TextView
    private lateinit var status: TextView
    private lateinit var correctionsCount: TextView
    private lateinit var btnFixIt: Button
    private lateinit var btnPractice: Button
    private lateinit var btnSlow: Button
    private lateinit var btnScriptToggle: Button
    private lateinit var btnUserLang: Button
    private lateinit var labelUser: TextView
    private lateinit var atmoImage: ImageView
    // Cookit atmosphere washes (owner's photo-gradients): sunrise / sky / bloom / foliage
    private val atmospheres = intArrayOf(
        R.drawable.atmo_sunrise, R.drawable.atmo_sky, R.drawable.atmo_bloom, R.drawable.atmo_foliage
    )
    private var atmoIdx = 0

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private enum class Mode { KOK_TO_EN, EN_TO_KOK, PRACTICE }
    private var mode = Mode.KOK_TO_EN

    private var lastKonkani: String? = null
    private var lastKonkaniAudioPath: String? = null
    private var lastEnglish: String? = null
    private var showRoman = true              // Bardez/Catholic -> Romi-first
    private var slow = false
    private var userLang = "en"               // user's side: "en" or "hi" (both pair with Konkani)

    private lateinit var speechLauncher: ActivityResultLauncher<Intent>
    private lateinit var permLauncher: ActivityResultLauncher<String>
    private lateinit var phrasebookLauncher: ActivityResultLauncher<Intent>
    private var pendingRecordAction: (() -> Unit)? = null

    private var recorder: MediaRecorder? = null
    private var recordFile: File? = null
    private var onRecordSaved: ((String) -> Unit)? = null
    private var player: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = Store(this)
        io.execute { store.seedStarter(StarterPack.phrases) }
        tts = TextToSpeech(this, this)

        englishOutput = findViewById(R.id.englishOutput)
        konkaniOutput = findViewById(R.id.konkaniOutput)
        konkaniAlt = findViewById(R.id.konkaniAlt)
        status = findViewById(R.id.status)
        correctionsCount = findViewById(R.id.correctionsCount)
        btnFixIt = findViewById(R.id.btnFixIt)
        btnPractice = findViewById(R.id.btnPractice)
        btnSlow = findViewById(R.id.btnSlow)
        btnScriptToggle = findViewById(R.id.btnScriptToggle)

        applyFont(findViewById(R.id.rootScroll), Fonts.latin(this))
        findViewById<TextView>(R.id.title).typeface = Fonts.latinMedium(this)
        findViewById<Button>(R.id.btnKonkaniToEnglish).typeface = Fonts.latinMedium(this)
        findViewById<Button>(R.id.btnEnglishToKonkani).typeface = Fonts.latinMedium(this)
        btnFixIt.typeface = Fonts.latinMedium(this)

        // typed input: wrap nicely but keep the Go key
        listOf(englishOutput, konkaniOutput).forEach {
            it.setHorizontallyScrolling(false)
            it.maxLines = 4
        }
        englishOutput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                typedEnglish(); true
            } else false
        }
        konkaniOutput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                typedKonkani(); true
            } else false
        }
        installClear(englishOutput) {
            englishOutput.setText("")
            lastEnglish = null
            setActionsEnabled(fix = false, practice = !lastKonkani.isNullOrBlank())
        }
        installClear(konkaniOutput) {
            lastKonkani = null
            lastKonkaniAudioPath = null
            renderKonkani()
            setActionsEnabled(fix = false, practice = false)
        }

        speechLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val spoken = result.data
                    ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull()?.trim()
                if (!spoken.isNullOrEmpty()) handleRecognized(spoken)
                else setStatus("Didn't catch that \u2014 try again.")
            } else {
                setStatus("Listening cancelled.")
            }
        }

        permLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            val action = pendingRecordAction
            pendingRecordAction = null
            if (granted) action?.invoke()
            else Toast.makeText(this, "Microphone permission needed to record", Toast.LENGTH_SHORT).show()
        }

        phrasebookLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val en = result.data?.getStringExtra("english") ?: return@registerForActivityResult
                val kok = result.data?.getStringExtra("konkani") ?: return@registerForActivityResult
                lastEnglish = en
                englishOutput.setText(en)
                lastKonkani = kok
                lastKonkaniAudioPath = result.data?.getStringExtra("audio")
                renderKonkani()
                setActionsEnabled(fix = true, practice = true)
                setStatus("From phrasebook \u2014 Hear it, or Practice saying it.")
                playKonkani(manual = false)
            }
        }

        findViewById<Button>(R.id.btnKonkaniToEnglish).setOnClickListener {
            mode = Mode.KOK_TO_EN
            // Google ships no Konkani ASR; Marathi (closest relative, same script) hears Konkani best.
            startListening("mr-IN", "Listening in Konkani\u2026")
        }
        findViewById<Button>(R.id.btnEnglishToKonkani).setOnClickListener {
            mode = Mode.EN_TO_KOK
            startListening(if (userLang == "hi") "hi-IN" else "en-IN", "Listening in ${userName()}\u2026")
        }
        btnScriptToggle.setOnClickListener {
            showRoman = !showRoman
            renderKonkani()
            updateScriptToggleLabel()
        }
        btnFixIt.setOnClickListener { showFixItDialog() }
        btnPractice.setOnClickListener { startPractice() }
        btnSlow.setOnClickListener {
            slow = !slow
            btnSlow.text = if (slow) "Slow \u2713" else getString(R.string.btn_slow)
        }
        findViewById<Button>(R.id.btnPhrasebook).setOnClickListener {
            phrasebookLauncher.launch(Intent(this, PhrasebookActivity::class.java))
        }
        findViewById<Button>(R.id.btnDrill).setOnClickListener {
            startActivity(Intent(this, DrillActivity::class.java))
        }
        findViewById<Button>(R.id.btnClips).setOnClickListener {
            startActivity(Intent(this, ClipsActivity::class.java))
        }
        atmoImage = findViewById(R.id.atmoImage)
        atmoIdx = when (java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)) {
            in 5..10 -> 0    // morning: sunrise
            in 11..16 -> 1   // day: sky
            in 17..20 -> 2   // evening: bloom
            else -> 3        // night: foliage
        }
        atmoImage.setImageResource(atmospheres[atmoIdx])
        findViewById<Button>(R.id.btnEnroll).setOnClickListener { showEnrollDialog() }
        btnUserLang = findViewById(R.id.btnUserLang)
        labelUser = findViewById(R.id.labelUser)
        userLang = getSharedPreferences("konkani", Context.MODE_PRIVATE)
            .getString("user_lang", "en") ?: "en"
        btnUserLang.setOnClickListener {
            userLang = if (userLang == "en") "hi" else "en"
            getSharedPreferences("konkani", Context.MODE_PRIVATE).edit()
                .putString("user_lang", userLang).apply()
            updateUserLangUi()
        }
        findViewById<Button>(R.id.btnPlayEnglish).setOnClickListener {
            speak(englishOutput.text.toString(), userLocale(), manual = true)
        }
        findViewById<Button>(R.id.btnPlayKonkani).setOnClickListener { playKonkani(manual = true) }

        setActionsEnabled(fix = false, practice = false)
        updateScriptToggleLabel()
        updateUserLangUi()
        refreshCount()
    }

    private fun applyFont(v: View, tf: Typeface) {
        if (v is TextView) v.typeface = tf
        if (v is ViewGroup) for (i in 0 until v.childCount) applyFont(v.getChildAt(i), tf)
    }

    /** In-field \u2715 that appears when the box has text; tapping it clears the field + its state. */
    private fun installClear(et: EditText, onClear: () -> Unit) {
        val icon = ContextCompat.getDrawable(this, R.drawable.ic_clear)?.mutate()
        icon?.setTint(ContextCompat.getColor(this, R.color.white55))
        fun refresh() {
            et.setCompoundDrawablesRelativeWithIntrinsicBounds(
                null, null, if (et.text.isNotEmpty()) icon else null, null
            )
        }
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { refresh() }
        })
        et.setOnTouchListener { v, ev ->
            val d = et.compoundDrawablesRelative[2]
            if (ev.action == MotionEvent.ACTION_UP && d != null &&
                ev.x >= et.width - et.paddingEnd - d.intrinsicWidth - 24
            ) {
                onClear()
                v.performClick()
                return@setOnTouchListener true
            }
            false
        }
        refresh()
    }

    // ---------- input: speech + typed ----------
    private fun startListening(bcp47: String, hint: String, bias: String? = null) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, bcp47)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, bcp47)
            putExtra(RecognizerIntent.EXTRA_PROMPT, hint)
            if (bias != null && Build.VERSION.SDK_INT >= 33) {
                putExtra(
                    RecognizerIntent.EXTRA_BIASING_STRINGS,
                    arrayListOf(bias, Script.toRoman(bias))
                )
            }
        }
        setStatus(hint)
        try {
            speechLauncher.launch(intent)
        } catch (e: Exception) {
            setStatus("No speech recognizer found. Install/enable the Google app.")
        }
    }

    private fun handleRecognized(text: String) {
        when (mode) {
            Mode.KOK_TO_EN -> runKokToEn(text)
            Mode.EN_TO_KOK -> runEnToKok(text)
            Mode.PRACTICE -> scorePractice(text)
        }
    }

    private fun typedEnglish() {
        val t = englishOutput.text.toString().trim()
        if (t.isEmpty()) return
        hideKeyboard()
        runEnToKok(t)
    }

    private fun typedKonkani() {
        val t = konkaniOutput.text.toString().trim()
        if (t.isEmpty()) return
        hideKeyboard()
        runKokToEn(t)
    }

    private fun userLocale(): Locale = if (userLang == "hi") Locale("hi", "IN") else Locale.ENGLISH

    private fun userName(): String = if (userLang == "hi") "\u0939\u093f\u0902\u0926\u0940" else "English"

    /** Applies the chosen user-side language (English/Hindi) across labels, hints, and buttons. */
    private fun updateUserLangUi() {
        labelUser.text = if (userLang == "hi") "\u0939\u093f\u0902\u0926\u0940" else getString(R.string.label_english)
        labelUser.typeface = if (userLang == "hi") Fonts.devanagari(this) else Fonts.latin(this)
        btnUserLang.text = if (userLang == "hi") "English" else "\u0939\u093f\u0902\u0926\u0940"
        englishOutput.hint = if (userLang == "hi")
            "\u0939\u093f\u0902\u0926\u0940 \u092e\u0947\u0902 \u091f\u093e\u0907\u092a \u0915\u0930\u0947\u0902\u2026"
        else getString(R.string.hint_type_english)
        findViewById<Button>(R.id.btnKonkaniToEnglish).text = "Konkani   \u2192   ${userName()}"
        findViewById<Button>(R.id.btnEnglishToKonkani).text = "${userName()}   \u2192   Konkani"
    }

    // ---------- the two flows (mic, typed, phrasebook all share these) ----------
    private fun runEnToKok(text: String) {
        lastEnglish = text
        englishOutput.setText(text)
        val hit = if (userLang == "en") store.lookupPhrase(text) else null
        if (hit != null) {
            lastKonkani = hit.konkaniText
            lastKonkaniAudioPath = hit.nativeAudioPath
            renderKonkani()
            val heard = if (hit.nativeAudioPath != null) " \u00B7 native voice" else ""
            val src = if (hit.source == "starter") "phrasebook" else "a native correction"
            setStatus("From memory ($src)$heard \u2713")
            setActionsEnabled(fix = true, practice = true)
            playKonkani(manual = false)
        } else if (cloud != null && userLang == "en") {
            setStatus("Translating to Konkani (your models)\u2026")
            speakViaCloud(text)
        } else {
            setStatus("Translating to Konkani\u2026")
            translateAsync(text, userLang, "kok") { kok ->
                lastKonkani = kok
                lastKonkaniAudioPath = null
                renderKonkani()
                setStatus("Translated. Hear it, Practice it, or Fix it.")
                setActionsEnabled(fix = true, practice = true)
                playKonkani(manual = false)
            }
        }
    }

    private fun runKokToEn(text: String) {
        lastKonkani = text
        lastKonkaniAudioPath = null
        renderKonkani()
        setActionsEnabled(fix = false, practice = true)
        setStatus("Translating\u2026")
        translateAsync(text, "kok", userLang) { out ->
            englishOutput.setText(out)
            lastEnglish = out
            setStatus("Done. Tap Hear to replay.")
            speak(out, userLocale())
            advanceAtmosphere()
        }
    }

    /** Break the monotony: time-of-day wash at launch; each finished translation drifts onward. */
    private fun advanceAtmosphere() {
        atmoIdx = (atmoIdx + 1) % atmospheres.size
        atmoImage.animate().alpha(0f).setDuration(450).withEndAction {
            atmoImage.setImageResource(atmospheres[atmoIdx])
            atmoImage.animate().alpha(0.55f).setDuration(650).start()
        }.start()
    }

    private fun translateAsync(text: String, src: String, tgt: String, onResult: (String) -> Unit) {
        io.execute {
            val out = try {
                backend.translate(text, src, tgt)
            } catch (e: Exception) {
                "[translation failed: ${e.message}]"
            }
            main.post { onResult(out) }
        }
    }

    /** Stage 1/2 path: cloud translate + Konkani audio (in the enrolled voice if any). */
    private fun speakViaCloud(englishText: String) {
        val c = cloud ?: return
        setActionsEnabled(fix = false, practice = false)
        io.execute {
            val res = try { c.speak(englishText, voiceId()) } catch (e: Exception) { null }
            main.post {
                if (res == null) {
                    setStatus("Cloud unavailable \u2014 falling back.")
                    translateAsync(englishText, "en", "kok") { kok ->
                        lastKonkani = kok; lastKonkaniAudioPath = null; renderKonkani()
                        setActionsEnabled(fix = true, practice = true); playKonkani(false)
                    }
                    return@post
                }
                lastKonkani = res.konkaniText
                lastKonkaniAudioPath = null
                renderKonkani()
                setActionsEnabled(fix = true, practice = true)
                val audio = res.audio
                if (audio != null) {
                    val f = writeTempAudio(audio)
                    if (f != null) {
                        lastKonkaniAudioPath = f.absolutePath
                        playAudioFile(f.absolutePath)
                    }
                    setStatus("Konkani in your voice \u2713")
                } else {
                    setStatus("Translated (your models). Tap Hear.")
                    playKonkani(false)
                }
            }
        }
    }

    private fun writeTempAudio(bytes: ByteArray): File? = try {
        val f = File(cacheDir, "cloud_${System.currentTimeMillis()}.wav")
        f.outputStream().use { it.write(bytes) }
        f
    } catch (e: Exception) { null }

    private fun voiceId(): String? =
        getSharedPreferences("konkani", Context.MODE_PRIVATE).getString("voice_id", null)

    // ---------- practice ----------
    private fun startPractice() {
        if (lastKonkani.isNullOrBlank()) return
        mode = Mode.PRACTICE
        startListening("mr-IN", "Repeat the Konkani phrase\u2026", bias = lastKonkani)
    }

    private fun scorePractice(heard: String) {
        val target = lastKonkani ?: return
        val pct = Practice.score(heard, target)
        val targetDeva = target.any { it in '\u0900'..'\u097F' }
        val heardDeva = heard.any { it in '\u0900'..'\u097F' }
        val fellBackToEnglish = targetDeva && !heardDeva && pct < 60
        val shownHeard = if (showRoman) Script.toRoman(heard) else heard
        val shownTarget = if (showRoman) Script.toRoman(target) else target
        val b = AlertDialog.Builder(this)
        if (fellBackToEnglish) {
            setStatus("Recognizer replied in English \u2014 not scored.")
            b.setTitle("Heard English, not Konkani")
                .setMessage(
                    "The phone's recognizer fell back to English:\n\u201C$heard\u201D\n\n" +
                        "Target:\n$shownTarget\n\n" +
                        "Add Konkani in voice settings (Google \u2192 Voice \u2192 Languages), speak a little slower, then try again. Our own Konkani recognizer arrives with the cloud backend."
                )
                .setNeutralButton("Voice settings") { _, _ ->
                    try {
                        startActivity(Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS))
                    } catch (e: Exception) {
                        Toast.makeText(this, "Open: Settings \u2192 Google \u2192 Voice", Toast.LENGTH_LONG).show()
                    }
                }
        } else {
            setStatus("Practice: $pct% match")
            b.setTitle("Practice \u2014 $pct%")
                .setMessage("You said:\n$shownHeard\n\nTarget:\n$shownTarget")
        }
        b.setPositiveButton("Try again") { _, _ -> startPractice() }
            .setNegativeButton("Done", null)
            .show()
    }

    // ---------- rendering / script ----------
    private fun renderKonkani() {
        val k = lastKonkani ?: ""
        if (k.isBlank()) {
            konkaniOutput.setText("")
            konkaniAlt.visibility = View.GONE
            return
        }
        val roman = Script.toRoman(k)
        val mainText = if (showRoman) roman else k
        val altText = if (showRoman) k else roman
        konkaniOutput.setText(mainText)
        konkaniOutput.typeface = if (showRoman) Fonts.latin(this) else Fonts.devanagari(this)
        if (altText.isNotBlank() && altText != mainText) {
            konkaniAlt.text = altText
            konkaniAlt.typeface = if (showRoman) Fonts.devanagari(this) else Fonts.latin(this)
            konkaniAlt.visibility = View.VISIBLE
        } else {
            konkaniAlt.visibility = View.GONE
        }
    }

    private fun updateScriptToggleLabel() {
        btnScriptToggle.text = if (showRoman) "\u0926\u0947\u0935\u0928\u093E\u0917\u0930\u0940" else "Romi"
    }

    // ---------- Konkani audio: native recording first, else TTS ----------
    private fun playKonkani(manual: Boolean) {
        if (!manual) advanceAtmosphere()
        val path = lastKonkaniAudioPath
        if (path != null && File(path).exists()) {
            playAudioFile(path)
            return
        }
        speak(lastKonkani ?: konkaniOutput.text.toString(), Locale("kok", "IN"), manual)
    }

    private fun playAudioFile(path: String) {
        try {
            player?.release()
            val p = MediaPlayer()
            p.setDataSource(path)
            p.setOnCompletionListener { mp ->
                mp.release()
                if (player === mp) player = null
            }
            p.prepare()
            p.start()
            if (slow) {
                try { p.playbackParams = p.playbackParams.setSpeed(0.72f) } catch (_: Exception) {}
            }
            player = p
            setStatus("Playing the native recording\u2026")
        } catch (e: Exception) {
            setStatus("Couldn't play recording: ${e.message}")
        }
    }

    // ---------- text-to-speech ----------
    private fun speak(text: String, locale: Locale, manual: Boolean = false) {
        if (!ttsReady) {
            if (manual) Toast.makeText(this, "Text-to-speech not ready yet", Toast.LENGTH_SHORT).show()
            return
        }
        if (text.isBlank()) return
        tts.setSpeechRate(if (slow) 0.72f else 1.0f)
        when (tts.setLanguage(locale)) {
            TextToSpeech.LANG_MISSING_DATA -> {
                if (locale.language == "kok") {
                    setStatus("Konkani voice data missing.")
                    if (manual) promptInstallVoiceData()
                }
            }
            TextToSpeech.LANG_NOT_SUPPORTED -> {
                if (locale.language == "kok") {
                    setStatus("This phone has no Konkani TTS voice. Record it via Fix it (plays back), or wait for Stage 1.")
                } else if (manual) {
                    Toast.makeText(this, "No voice for ${locale.displayLanguage}", Toast.LENGTH_SHORT).show()
                }
            }
            else -> tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utt")
        }
    }

    private fun promptInstallVoiceData() {
        try {
            startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
        } catch (e: Exception) {
            try {
                startActivity(Intent("com.android.settings.TTS_SETTINGS"))
            } catch (_: Exception) {
                Toast.makeText(this, "Open Settings > System > Languages > Text-to-speech.", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---------- fix-it (the flywheel) ----------
    private fun setActionsEnabled(fix: Boolean, practice: Boolean) {
        btnFixIt.isEnabled = fix
        btnFixIt.alpha = if (fix) 1f else 0.4f
        btnPractice.isEnabled = practice
        btnPractice.alpha = if (practice) 1f else 0.4f
    }

    private fun showFixItDialog() {
        val eng = lastEnglish ?: return
        val ours = lastKonkani ?: ""
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_fixit, null)
        val tvContext = view.findViewById<TextView>(R.id.fixContext)
        val etCorrect = view.findViewById<EditText>(R.id.fixCorrect)
        val cbConsent = view.findViewById<CheckBox>(R.id.fixConsent)
        val btnRecord = view.findViewById<Button>(R.id.fixRecord)
        tvContext.text = "English: $eng\nOur Konkani: $ours"
        etCorrect.setText(ours)

        val audioPath = arrayOfNulls<String>(1)
        btnRecord.setOnClickListener { toggleRecording(btnRecord) { path -> audioPath[0] = path } }

        AlertDialog.Builder(this)
            .setTitle("Fix Konkani (Bardez \u00B7 Catholic)")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                stopRecordingIfAny()
                val confirmed = etCorrect.text.toString().trim()
                if (!cbConsent.isChecked) {
                    Toast.makeText(this, "Consent required to save", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (confirmed.isEmpty() && audioPath[0] == null) {
                    Toast.makeText(this, "Type the correct Konkani or record it", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                store.insertCorrection(
                    englishSource = eng,
                    ourKonkani = ours,
                    nativeAudioPath = audioPath[0],
                    confirmedText = confirmed.ifEmpty { null },
                    region = "Bardez",
                    dialect = "Catholic",
                    script = if (showRoman) "Roman" else "Devanagari",
                    consent = true,
                    srcLang = userLang
                )
                refreshCount()
                if (confirmed.isNotEmpty()) {
                    lastKonkani = confirmed
                    renderKonkani()
                }
                lastKonkaniAudioPath = audioPath[0]
                val extra = if (audioPath[0] != null) " Tap Hear to play it." else ""
                setStatus("Saved. Same English now replays this correction.$extra")
                Toast.makeText(this, "Correction saved \u2714", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel") { _, _ -> stopRecordingIfAny() }
            .setOnDismissListener { stopRecordingIfAny() }
            .show()
    }

    // ---------- enrollment (voice reference; cloud upload when backend live) ----------
    private fun showEnrollDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_enroll, null)
        val cbConsent = view.findViewById<CheckBox>(R.id.enrollConsent)
        val btnRecord = view.findViewById<Button>(R.id.enrollRecord)
        val audioPath = arrayOfNulls<String>(1)
        btnRecord.setOnClickListener { toggleRecording(btnRecord) { path -> audioPath[0] = path } }

        AlertDialog.Builder(this)
            .setTitle("Enroll your voice")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                stopRecordingIfAny()
                if (!cbConsent.isChecked) {
                    Toast.makeText(this, "Consent required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val c = cloud
                if (c != null && audioPath[0] != null) {
                    setStatus("Uploading your voice\u2026")
                    val path = audioPath[0]!!
                    io.execute {
                        val vid = try {
                            c.enroll("u_" + UUID.randomUUID().toString().take(6), File(path).readBytes())
                        } catch (e: Exception) { null }
                        main.post {
                            if (vid != null) {
                                getSharedPreferences("konkani", Context.MODE_PRIVATE).edit()
                                    .putString("voice_id", vid).putString("voice_audio", path).apply()
                                Toast.makeText(this, "Voice enrolled: $vid", Toast.LENGTH_LONG).show()
                                setStatus("Voice enrolled \u2014 Konkani will speak in your voice.")
                            } else {
                                Toast.makeText(this, "Enroll failed (backend).", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } else {
                    val voiceId = "vp_" + UUID.randomUUID().toString().take(8)
                    getSharedPreferences("konkani", Context.MODE_PRIVATE).edit()
                        .putString("voice_id", voiceId)
                        .putString("voice_audio", audioPath[0])
                        .apply()
                    Toast.makeText(this, "Saved $voiceId. Cloning activates once the backend URL is set.", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel") { _, _ -> stopRecordingIfAny() }
            .setOnDismissListener { stopRecordingIfAny() }
            .show()
    }

    // ---------- recording (best-effort) ----------
    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()

    private fun toggleRecording(btn: Button, onSaved: (String) -> Unit) {
        if (recorder == null) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                pendingRecordAction = { toggleRecording(btn, onSaved) }
                permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                return
            }
            val f = File(filesDir, "rec_${System.currentTimeMillis()}.m4a")
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
                btn.text = "Stop"
                setStatus("Recording\u2026")
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

    // ---------- misc ----------
    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow((currentFocus ?: englishOutput).windowToken, 0)
    }

    private fun setStatus(msg: String) { status.text = msg }

    private fun refreshCount() {
        correctionsCount.text = "Corrections collected: ${store.correctionCount()}"
    }

    override fun onInit(code: Int) {
        ttsReady = code == TextToSpeech.SUCCESS
        if (ttsReady) {
            val kok = try { tts.isLanguageAvailable(Locale("kok", "IN")) } catch (e: Exception) { -99 }
            val en = try { tts.isLanguageAvailable(Locale.ENGLISH) } catch (e: Exception) { -99 }
            konkaniTtsOk = kok >= TextToSpeech.LANG_AVAILABLE
            Log.i(TAG, "TTS ready. kok-IN=$kok en=$en engine=${tts.defaultEngine} (>=0 = installed)")
            if (!konkaniTtsOk) {
                main.post {
                    setStatus("No on-device Konkani voice yet \u2014 record corrections (Fix it) to hear real Konkani.")
                }
            }
        } else {
            Log.i(TAG, "TTS init FAILED code=$code")
            main.post { Toast.makeText(this, "Text-to-speech unavailable", Toast.LENGTH_LONG).show() }
        }
    }

    override fun onDestroy() {
        stopRecordingIfAny()
        player?.release()
        player = null
        tts.stop()
        tts.shutdown()
        io.shutdown()
        store.close()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "KonkaniTTS"
    }
}
