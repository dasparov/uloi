# HANDOVER — Alfred (Konkani ⇄ English/Hindi translator + tutor)

**Repo:** https://github.com/dasparov/uloi (public, MIT) · **App name:** Alfred (was Uloi → Uzzo → Alfred)
**Owner:** Kapil · **Last session:** 2026-09-22 · All work committed + pushed.

## ⚠️ Read first
- **The working tree lives in `/tmp/konkani-translator` — macOS wipes `/tmp` on reboot.**
  Everything is pushed to GitHub, so recovery = `git clone` + the two local overlays below.
  (Better: move the folder to `~/Documents/` and re-point Android Studio.)
- **Local-only overlays (never commit):**
  1. **Saans fonts** (licensed): copy from `~/Documents/kodas/android/app/src/main/res/font/saans_{regular,medium}.ttf`
     over `app/src/main/res/font/app_sans_{regular,medium}.ttf`, then
     `git update-index --skip-worktree` both. Repo default is Noto (OFL) — builds fine without Saans.
  2. `local.properties` → `sdk.dir=/Users/kapil/Library/Android/sdk`.
- **Local toolchain (also in /tmp, volatile):** JDK17 `/tmp/jdk17/jdk-17.0.20.1+1/Contents/Home`,
  Gradle dist cached via wrapper. If gone: Android Studio builds everything (bundled JDK), or
  re-download Temurin 17 + `./gradlew assembleDebug`.

## What Alfred is (all shipped + verified on the Sony Xperia 1 V, serial QV7702YBMZ)
Turn-based Konkani ⇄ **English or Hindi** interpreter + tutor, luxe black/glass UI (cookit photo-gradient
atmospheres that rotate by time-of-day and drift after each translation), Saans/Noto typography:
- Speak or **type** both directions; editable fields fix mishears; ✕ clear buttons.
- Konkani in **both scripts** (Romi-first toggle, equal sizes); Hindi mode swaps mic/TTS/labels/hints.
- **Hear** + **Slow** everywhere; Konkani audio prefers a local speaker's recording.
- **Fix it** (consent-gated corrections: typed and/or recorded) → phrase memory replays instantly
  (on-device-proven by instrumented test) → training data for the backend.
- **Phrasebook** (35-phrase Bardez/Catholic starter pack, tagged `starter`, awaiting speaker gut-check).
- **Practice** (say-it-back scoring) + **Practice 5** drill with LEARN pass → shuffled **RECAP-from-memory**
  pass → remembered-count summary.
- **Clips**: standalone field recorder (Add clip = record with **live amplitude meter** + English caption +
  optional Konkani + **tag**; hold to re-tag; Slow playback) — decoupled from Fix-it.
- Launcher/splash: original "Goan gent" vector (glasses, mustache, warm light bg).

### Speech-engine reality (important)
- Phone **Konkani TTS exists** (Google `kok-IN` voice — installed on the Xperia; emulator lacks it).
- **No Konkani ASR exists on-device** (proven by scanning Google's voice-language list). Konkani listening
  runs through the **Marathi model (`mr-IN`)** + `EXTRA_LANGUAGE_PREFERENCE` + **answer biasing**
  (`EXTRA_BIASING_STRINGS`, API 33+) + English-fallback detection (offers Voice settings).
  **Marathi is enabled** in the phone's Google voice languages.
- Translation (Stage 0) = Google's free unofficial endpoint (`en/hi ↔ kok`); fine for pilot, replaced by
  the backend.

## Backend (code-complete, **NOT deployed** — blocked on one user step)
`backend/modal_app.py` (+ `train.py`), Modal serverless GPU, image already **built + cached** (7.1 GB):
- `/speak` en→kok text+audio (IndicF5 **voice clone** when a `voice_id` is enrolled; Parler-TTS otherwise)
- `/translate` (IndicTrans2 1B, `eng_Latn ↔ gom_Deva`), `/stt` (**kok = IndicConformer 600M wired**,
  en = faster-whisper), `/corrections`, `/enroll` (Whisper drafts the reference transcript), `/health`
- `train.py`: weekly `finetune_mt` (LoRA on speaker corrections, chrF eval-gate, versioned promote) +
  dataset builders for TTS/STT manifests.
- Modal CLI authed as workspace **kapil-das** (`~/.modal.toml`), `pip`'d into anaconda python.

### Next session — go live (todos are blocked on step 1)
1. **You:** add a payment method → modal.com/settings (workspace kapil-das). $30/mo free credit still applies.
2. `cd <repo> && /Users/kapil/anaconda3/bin/python3 -m modal deploy backend/modal_app.py` (fast; image cached)
   and `... deploy backend/train.py`.
3. Paste printed base URL (`https://kapil-das--konkani-backend-engine`) into `BackendConfig.BASE_URL`
   (`app/.../Backend.kt`), rebuild, install → app leaves Google; Enroll starts real cloning.
4. Verify: `/health`, `/speak` curl, Enroll→`voice_id`→`/speak` cloned, `/stt lang=kok`.
   Expect first-request cold start (weights download to the `konkani-weights` Volume once).
   Known risk: IndicF5/`f5-tts` + transformers pin may need a nudge at runtime (build resolved clean).
5. Note: app's cloud path currently routes **English only** through `/speak` (`cloud != null && userLang=="en"`);
   Hindi stays on Google until the backend gains a hi↔kok path (pivot or IndicTrans2 indic-indic).

## Data & licensing rules
- Wording: UI/docs say **"local speaker" / "local voice"** (never the n-word-for-locals). Internal names
  (`native_audio_path`, `native_confirmed_text`, source `native_correction`, API fields) are **frozen** —
  data/API contract; rename only with a v4 migration + API alias.
- DB `konkani.db` v3: `phrase_memory(english_norm PK, english_display, konkani_text, konkani_audio_path,
  source[starter|native_correction|clip|manual], grp, updated_at)`; `corrections(..., src_lang)`.
  `source='clip'` is **excluded** from translation lookups; tags survive re-correction.
- Consent checkboxes gate every recording; corpus to be **open-sourced (CC)** per founding decision.
- Fonts: Noto = OFL (text in `assets/fonts/OFL.txt`); Saans licensed/local-only; Nokia Pure rejected.
- Icon: original artwork, deliberately generic (no real person's likeness).

## Gotchas / notes
- Emulator ANR seen once under host load 9+ (uiautomator storms) — not an app bug; phone is clean.
- Package id still `com.example.konkani` — rename (e.g. `com.uloi.app`) **before** Play Store ($25).
- The Konkani starter translations are best-effort seeds — first flywheel task: speaker verification
  (also gut-check the Romi spelling of "Uloi"/branding).
- `KonkaniTranslator.apk` at repo root = latest debug build (gitignored).
- Verification pattern used throughout: `./gradlew assembleDebug` → `adb install` → launch → logcat FATAL
  check → screenshot; uiautomator dump + text-bounds tap for UI driving.

## File map (beyond the obvious)
`ARCHITECTURE.md` v1.0 (locked decisions §16) · `ROADMAP.md` (fun-learning backlog + sponsor plan +
next-session steps) · `backend/README.md` (deploy/test) · app: `MainActivity` (all main-screen logic),
`Store` (SQLite v3), `Backend` (Google + CloudBackend seam), `Practice` (Levenshtein scorer),
`Script` (ICU Deva↔Latin), `Fonts`, `RecordingMeter`, `StarterPack`, `PhrasebookActivity`,
`DrillActivity` (learn+recap), `ClipsActivity` (soundboard + field recorder).
