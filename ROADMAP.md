# Uloi — Roadmap

## Now → next session: go live on our own models
1. Add a payment method on Modal (workspace `kapil-das`) — unlocks GPU. ($30/mo free credit still applies.)
2. `modal deploy backend/modal_app.py` (image already built/cached) + `backend/train.py`.
3. Paste the endpoint URL into `BackendConfig.BASE_URL`, rebuild, install.
4. Verify live: `/speak` (Konkani audio), Enroll → `/enroll` → cloned voice on `/speak`.
5. Wire **IndicConformer** (Konkani ASR) into `/stt` → proper Konkani hearing; retire the Marathi bridge
   (keep as offline fallback).
6. Activate Stage 3: weekly fine-tune once ≥50 corrections collected; watch the eval gate + correction rate.

## Making Konkani learning *fun* (scoped backlog)
Ordered by value ÷ effort. "No backend" = works fully offline today.

| # | Feature | Why it works | Size |
|---|---|---|---|
| 1 | **Streak + daily phrase notification** — one phrase/day from the Phrasebook (native audio when available), luxe-styled | Retention backbone; dead simple | S, no backend |
| 2 | **Listening quiz** — hear the Konkani (native recording first), pick the English from 4 options | Trains the *ear*; needs zero speech recognition, so it's reliable today | S, no backend |
| 3 | **Matching game** — 5 English↔Konkani tile pairs, timed, best time saved | Casual fun; kids + elders both play | S, no backend |
| 4 | **Script trainer** — Romi ↔ Devanagari flashcards (we already render both) | Unlocks reading for Romi-only users | S, no backend |
| 5 | **Spaced repetition** — per-phrase due dates (SM-2-lite over existing drill scores; `due_at`, `ease` columns) | Turns Practice 5 into a real memory system | M, no backend |
| 6 | **XP / levels / badges** — "Bardez Bhasha Level 3", monochrome luxe badges; XP from drills, corrections, streaks | Progression glue across all modes | M |
| 7 | **Scenario packs** — market / church / taxi / kitchen mini-dialogues, sequential play | Practical + sponsorable (see below) | M |
| 8 | **Duet mode** — native's recording and learner's attempt side by side; shareable clip | Social + emotional hook; uses existing recorder | M |
| 9 | **Pilot leaderboard** — weekly recap scores across the 10 pilots + 5 natives | Friendly pressure; needs backend | M, backend |
| 10 | **Mando / song-lyrics mode** — learn via Konkani hymns & mando verses | Deep culture hook (Catholic Bardez) | L |
| 11 | **Word-of-day widget** + kids picture mode | Nice-to-haves once above land | L |

Suggested first slice: **1 + 2 + 3** (all small, offline, and make the app feel alive daily).

## Local-business support (ads — later, after go-live)
Goal: cover GPU + contributor costs with **Goan businesses**, not ad networks.
- **Sponsor card** on the drill-complete and daily-phrase screens: "Supported by <business>, Mapusa" —
  static, luxe-styled, no tracking, no third-party SDKs.
- **Sponsored scenario packs**: "Ordering at <Café>" — the pack teaches real phrases used at that business.
- **Principles**: local businesses only · flat monthly sponsorship · clear "Sponsored" label · zero user
  data shared · revenue split toward native contributors + server costs.
- **Needs**: remote sponsor config (JSON), placement design, disclosure copy, simple invoicing.

## Platform / housekeeping (later)
- Package id rename `com.example.konkani` → `com.uloi.app` **before** Play Store; $25 developer account.
- Native gut-check of the "Uloi" Romi spelling + starter-pack phrases (first flywheel task).
- Romi authentic spellings from natives replacing ICU transliteration where corrected.
- Open-source the correction corpus (CC) once big enough to matter.
