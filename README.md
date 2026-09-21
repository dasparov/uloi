# Uloi 🍾

**Konkani ⇄ English voice translator + tutor**, built for Goan (Bardez, Catholic/Romi-leaning) Konkani —
a low-resource language the big platforms barely serve. Uloi (*उलय* — "speak!") is both an interpreter
and a learning app, and it **gets better as native speakers correct it**.

## What it does
- **Speak or type** either language; Konkani renders in **both scripts** (Romi + Devanagari) at equal size.
- **Hear** buttons + **Slow** mode; Konkani audio prefers a **native speaker's recording** when one exists.
- **Fix it** — a native corrects any translation (typed and/or recorded, consent-gated). Corrections
  replay instantly (phrase memory) and become training data (the flywheel).
- **Phrasebook** — starter pack of everyday Bardez phrases + everything natives have corrected.
- **Practice** — say it back, scored by speech recognition; **Practice 5** drill with a learn pass and a
  **recap-from-memory** pass.
- **Enroll my voice** — records a reference clip; with the backend deployed, Konkani speaks **in your own
  cloned voice** (IndicF5).

Speech in/out uses the phone's engine (Konkani ASR doesn't exist there, so Konkani listening runs through
the Marathi model with answer biasing — see `ARCHITECTURE.md`). The optional **backend** replaces Google
with open models you own: **IndicTrans2** (MT) · **Indic-Parler-TTS** / **IndicF5** (TTS + voice clone) ·
**faster-whisper** (EN STT) · weekly **fine-tuning from corrections** with an eval gate.

## Build (app)
Android Studio (or `./gradlew assembleDebug`), compileSdk 35, minSdk 24. No keys required — Stage 0 runs
on free on-device services + a public translate endpoint.

## Deploy (backend, optional but recommended)
```bash
pip install modal && modal token new
modal deploy backend/modal_app.py     # inference: /speak /translate /stt /corrections /enroll /health
modal deploy backend/train.py         # weekly fine-tune from collected corrections
```
Paste the printed base URL into `BackendConfig.BASE_URL` (`app/.../Backend.kt`) and rebuild. Serverless
GPU, scale-to-zero; a small pilot fits in Modal's free monthly credit. Details: `backend/README.md`.

## Fonts
- UI sans ships as **Noto Sans** (SIL OFL) at `app/src/main/res/font/app_sans_{regular,medium}.ttf`;
  Devanagari is **Noto Sans Devanagari** (OFL) in `assets/fonts/` (license: `assets/fonts/OFL.txt`).
- The original design uses the licensed **Saans** family: overwrite the two `app_sans_*` files locally
  with your licensed TTFs and do **not** commit them.

## Docs
- `ARCHITECTURE.md` — full system design (v1.0, decisions locked)
- `ROADMAP.md` — learning/gamification backlog + local-business sponsorship plan
- `backend/README.md` — endpoints, deploy, costs

## License
Code: MIT. Bundled Noto fonts: SIL OFL 1.1. Konkani models by [AI4Bharat](https://ai4bharat.iitm.ac.in/)
under their respective licenses. The community correction corpus will be released openly (CC) per the
project's founding decision.
