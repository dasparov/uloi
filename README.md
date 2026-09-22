# Alfred 👓

**Konkani ⇄ English/Hindi voice translator + tutor**, built for Goan (Bardez, Catholic/Romi-leaning)
Konkani — a low-resource language the big platforms barely serve. Alfred is an interpreter, a teacher,
and a field recorder — and it **gets better as local Konkani speakers correct it**.

## What it does
- **Speak or type** in either direction; your side of the conversation can be **English or Hindi** (toggle).
- Konkani renders in **both scripts** (Romi + Devanagari, equal size, Romi-first toggle).
- **Hear** + **Slow** playback everywhere; Konkani audio prefers a **local speaker's recording**.
- **Fix it** — a Konkani speaker corrects any translation (typed and/or recorded with a **live mic meter**,
  consent-gated). Corrections replay instantly (phrase memory) and become training data (the flywheel).
- **Clips** — a soundboard *and* field recorder: record a local voice directly (no fixing required),
  caption it in English, **tag** it (*bazaar*, *petrol pump*…), tap to play it to anyone, hold to re-tag.
- **Phrasebook** — Bardez/Catholic starter pack + everything speakers have corrected.
- **Practice** — say-it-back pronunciation scoring; **Practice 5** drill with a learn pass and a
  **recap-from-memory** pass.
- **Enroll my voice** — records a reference clip; with the backend deployed, Konkani speaks **in your own
  cloned voice** (IndicF5).
- Luxe black/glass UI with rotating photo-gradient **atmospheres** that drift after every translation.

Speech in/out uses the phone's engine. Konkani ASR doesn't exist on-device, so Konkani listening runs
through the **Marathi model with answer biasing** (see `ARCHITECTURE.md`) until the backend's
**IndicConformer** takes over. The optional **backend** replaces Google with open models you own:
**IndicTrans2** (MT) · **Indic-Parler-TTS** / **IndicF5** (TTS + voice clone) · **IndicConformer** (kok STT) ·
**faster-whisper** (en STT) · weekly **fine-tuning from corrections** with an eval gate.

## Build (app)
Android Studio (or `./gradlew assembleDebug`), compileSdk 35, minSdk 24. No keys required — Stage 0 runs
on free on-device services + a public translate endpoint.

## Deploy (backend, optional but recommended)
```bash
pip install modal && modal token new
modal deploy backend/modal_app.py     # /speak /translate /stt /corrections /enroll /health
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
- `HANDOVER.md` — current state, next-session go-live steps, gotchas
- `ARCHITECTURE.md` — full system design (v1.0, decisions locked)
- `ROADMAP.md` — learning/gamification backlog + local-business sponsorship plan
- `backend/README.md` — endpoints, deploy, costs

## License
Code: MIT. Bundled Noto fonts: SIL OFL 1.1. Konkani models by [AI4Bharat](https://ai4bharat.iitm.ac.in/)
under their respective licenses. The community correction corpus will be released openly (CC). The
launcher artwork is an original, deliberately generic portrait.
