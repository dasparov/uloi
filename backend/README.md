# Konkani backend — Stages 1-3 (open-source models on Modal)

Our own Konkani brain behind the ARCHITECTURE.md §5 API, on **serverless GPU** (scale-to-zero):
- **Stage 1** — MT (**IndicTrans2**) + generic TTS (**Indic-Parler-TTS**) + English STT (**faster-whisper**).
- **Stage 2** — **voice cloning** (**IndicF5**, zero-shot): Konkani in the enrolled user's voice.
- **Stage 3** — **self-improvement**: scheduled jobs fine-tune from native corrections behind an eval gate.

Replaces Google, speaks in the user's voice, and gets better as corrections come in.

> Not run in this workspace (needs a GPU + your Modal account). First deploy downloads several GB of
> weights to a Modal Volume. Version pins in `modal_app.py` are a known-good start; a first deploy may
> need minor tuning. Files are **syntax-checked** here, not executed.

## Deploy
```bash
pip install modal
modal token new                       # one-time auth to YOUR account
modal deploy backend/modal_app.py     # inference API (…/speak, …/enroll, …)
modal deploy backend/train.py         # Stage 3 jobs (weekly finetune_mt + build_datasets)
```
Cost: GPU only while serving/training (idle = $0); fits the ~$50/mo pilot cap.

## Test
```bash
BASE=https://<your-workspace>--konkani-backend-engine   # from `modal deploy` output

# generic Konkani voice
curl -X POST $BASE-speak.modal.run -H 'content-type: application/json' \
     -d '{"english_text":"Where is the hospital?"}'

# clone: enroll ~30s of the user (English is fine), then speak in that voice
VID=$(curl -s -X POST $BASE-enroll.modal.run -H 'content-type: application/json' \
     -d "{\"user_id\":\"u1\",\"consent\":true,\"audio_b64\":\"$(base64 -i me.m4a)\"}" | jq -r .voice_id)
curl -X POST $BASE-speak.modal.run -H 'content-type: application/json' \
     -d "{\"english_text\":\"Thank you very much\",\"voice_id\":\"$VID\"}"
# -> { konkani_text, konkani_audio_b64 (WAV, in the user's voice), voice_cloned: true }
```

## Endpoints (`modal_app.py`)
| Method | Path | Purpose |
|---|---|---|
| POST | `/speak` | English text → Konkani text + Konkani audio (in `voice_id`'s voice if enrolled) |
| POST | `/enroll` | store a voice clip; Whisper drafts the reference transcript → returns `voice_id` |
| POST | `/translate` | text ↔ text, `en`↔`kok` |
| POST | `/stt` | English speech → text |
| POST | `/corrections` | store a native correction (audio + text) → training sink |
| GET | `/health` | liveness + model versions |

## Stage 3 jobs (`train.py`)
- `build_datasets()` — `corrections.jsonl` → `mt_pairs / tts_manifest / stt_manifest`.
- `finetune_mt()` — weekly LoRA fine-tune of IndicTrans2 on corrected pairs; **eval chrF vs held-out**;
  promotes a versioned adapter **only if it beats** the current model; logs each run. TTS/STT fine-tune
  from their manifests with each model's recipe (same collect → gate → promote loop).

## Wire the Android app
`Backend.kt` → set `BackendConfig.BASE_URL = "https://<your>--konkani-backend-engine"`. The app then
routes English→Konkani through `/speak` (plays the returned voice audio) and `Enroll my voice` → `/enroll`.
Empty URL = Stage 0 (Google + on-device), which is the current default.
