"""
Konkani backend — Stage 1 + Stage 2 (open-source models on serverless GPU / Modal).

Stage 1: our own Konkani brain — IndicTrans2 (MT) + Indic-Parler-TTS (generic Konkani voice) +
         faster-whisper (English STT).
Stage 2: VOICE CLONING — IndicF5 (F5-TTS, zero-shot) speaks the Konkani in the ENROLLED user's voice.
         `/enroll` stores a short reference clip (+ its transcript, auto-made by Whisper); `/speak`
         with a `voice_id` renders Konkani in that voice, else falls back to the generic Parler voice.

  POST /speak        English text -> Konkani text + Konkani audio (in voice_id's voice if enrolled)
  POST /translate    text <-> text (IndicTrans2, en<->gom_Deva)
  POST /stt          English speech -> text (faster-whisper)
  POST /corrections  store a native correction (audio + text) into the data Volume (flywheel sink)
  POST /enroll       store a user's voice reference + transcript (for Stage 2 cloning)
  GET  /health       liveness + loaded model versions

NOTE (honesty): NOT run in this workspace — needs a GPU + Modal account and downloads several GB of
weights on first deploy. Pins are a known-good starting set; a first `modal deploy` may need minor
version tuning (transformers <-> parler-tts <-> IndicF5/F5-TTS <-> indictrans2).

Deploy:  modal deploy backend/modal_app.py    (see backend/README.md)
"""
import base64
import io
import json
import os
import subprocess
import tempfile
import time
import uuid

import modal
from pydantic import BaseModel

APP_NAME = "konkani-backend"
app = modal.App(APP_NAME)

weights = modal.Volume.from_name("konkani-weights", create_if_missing=True)
data = modal.Volume.from_name("konkani-data", create_if_missing=True)
WEIGHTS_DIR = "/weights"
DATA_DIR = "/data"
VOICES_DIR = f"{DATA_DIR}/voices"

image = (
    modal.Image.debian_slim(python_version="3.11")
    .apt_install("ffmpeg", "git")
    .pip_install(
        "torch==2.4.0",
        "torchaudio==2.4.0",
        "transformers>=4.44,<4.50",
        "accelerate",
        "sentencepiece",
        "sacremoses",
        "soundfile",
        "numpy",
        "librosa",
        "vocos",
        "faster-whisper==1.0.3",
        "parler-tts @ git+https://github.com/huggingface/parler-tts.git",
        "IndicTransToolkit @ git+https://github.com/VarunGumma/IndicTransToolkit.git",
        # Stage 2 zero-shot voice cloning (F5-TTS based); pulls f5-tts deps
        # Stage 2 zero-shot voice cloning: IndicF5 loads from HF hub (trust_remote_code);
        # its runtime dep is the f5-tts package (PyPI), not the GitHub repo.
        "f5-tts",
        "fastapi[standard]",
    )
    .env({"HF_HOME": f"{WEIGHTS_DIR}/hf"})
)

EN = "eng_Latn"
KOK = "gom_Deva"  # Goan Konkani, Devanagari (IndicTrans2 code)


class SpeakReq(BaseModel):
    english_text: str
    voice_id: str | None = None
    session_id: str | None = None


class TranslateReq(BaseModel):
    text: str
    source: str  # "en" | "kok"
    target: str  # "en" | "kok"


class SttReq(BaseModel):
    lang: str = "en"
    audio_b64: str


class CorrectionReq(BaseModel):
    english_source: str
    our_konkani_text: str | None = None
    native_confirmed_text: str | None = None
    native_audio_b64: str | None = None
    region: str = "Bardez"
    dialect: str = "Catholic"
    script: str = "Devanagari"
    consent: bool = False


class EnrollReq(BaseModel):
    user_id: str
    audio_b64: str
    consent: bool = False


@app.cls(
    gpu="L4",
    image=image,
    volumes={WEIGHTS_DIR: weights, DATA_DIR: data},
    timeout=600,
    scaledown_window=120,
)
class Engine:
    @modal.enter()
    def load(self):
        import torch
        from transformers import AutoModel, AutoModelForSeq2SeqLM, AutoTokenizer
        from IndicTransToolkit import IndicProcessor
        from faster_whisper import WhisperModel
        from parler_tts import ParlerTTSForConditionalGeneration

        self.torch = torch
        self.device = "cuda" if torch.cuda.is_available() else "cpu"
        dtype = torch.float16 if self.device == "cuda" else torch.float32

        # MT (both directions), IndicTrans2 1B
        self.tok_en_indic = AutoTokenizer.from_pretrained(
            "ai4bharat/indictrans2-en-indic-1B", trust_remote_code=True
        )
        self.mt_en_indic = AutoModelForSeq2SeqLM.from_pretrained(
            "ai4bharat/indictrans2-en-indic-1B", trust_remote_code=True, torch_dtype=dtype
        ).to(self.device)
        self.tok_indic_en = AutoTokenizer.from_pretrained(
            "ai4bharat/indictrans2-indic-en-1B", trust_remote_code=True
        )
        self.mt_indic_en = AutoModelForSeq2SeqLM.from_pretrained(
            "ai4bharat/indictrans2-indic-en-1B", trust_remote_code=True, torch_dtype=dtype
        ).to(self.device)
        self.ip = IndicProcessor(inference=True)

        # Generic Konkani TTS (fallback voice)
        self.tts = ParlerTTSForConditionalGeneration.from_pretrained(
            "ai4bharat/indic-parler-tts"
        ).to(self.device)
        self.tts_prompt_tok = AutoTokenizer.from_pretrained("ai4bharat/indic-parler-tts")
        self.tts_desc_tok = AutoTokenizer.from_pretrained(
            self.tts.config.text_encoder._name_or_path
        )

        # Stage 2: zero-shot voice clone TTS (Konkani in the enrolled speaker's voice)
        self.f5 = AutoModel.from_pretrained("ai4bharat/IndicF5", trust_remote_code=True).to(self.device)

        # English STT (Konkani ASR = IndicConformer, Stage 3)
        self.whisper = WhisperModel(
            "small", device=self.device, compute_type="float16" if self.device == "cuda" else "int8"
        )

        os.makedirs(VOICES_DIR, exist_ok=True)
        self.versions = {
            "mt": "indictrans2-1B", "tts": "indic-parler-tts",
            "clone": "indic-f5", "stt": "whisper-small",
        }

    # ---- core ops ----
    def _translate(self, text: str, src: str, tgt: str) -> str:
        batch = self.ip.preprocess_batch([text], src_lang=src, tgt_lang=tgt)
        tok = self.tok_en_indic if src == EN else self.tok_indic_en
        mdl = self.mt_en_indic if src == EN else self.mt_indic_en
        enc = tok(batch, return_tensors="pt", padding=True, truncation=True, max_length=256).to(self.device)
        with self.torch.no_grad():
            out = mdl.generate(**enc, num_beams=5, max_length=256, num_return_sequences=1)
        dec = tok.batch_decode(out, skip_special_tokens=True, clean_up_tokenization_spaces=True)
        return self.ip.postprocess_batch(dec, lang=tgt)[0]

    def _tts(self, konkani_text: str) -> bytes:
        import soundfile as sf
        description = "A clear, natural Konkani voice, moderate pace, minimal background noise."
        desc = self.tts_desc_tok(description, return_tensors="pt").to(self.device)
        prompt = self.tts_prompt_tok(konkani_text, return_tensors="pt").to(self.device)
        with self.torch.no_grad():
            audio = self.tts.generate(
                input_ids=desc.input_ids,
                attention_mask=desc.attention_mask,
                prompt_input_ids=prompt.input_ids,
                prompt_attention_mask=prompt.attention_mask,
            )
        wav = audio.cpu().numpy().squeeze()
        buf = io.BytesIO()
        sf.write(buf, wav, self.tts.config.sampling_rate, format="WAV")
        return buf.getvalue()

    def _clone_tts(self, konkani_text: str, ref_path: str, ref_text: str) -> bytes:
        """Stage 2: Konkani in the reference speaker's voice (F5-TTS zero-shot)."""
        import numpy as np
        import soundfile as sf
        wav = self.f5(konkani_text, ref_audio_path=ref_path, ref_text=ref_text)
        wav = np.asarray(wav, dtype="float32")
        peak = float(np.max(np.abs(wav))) if wav.size else 1.0
        if peak > 1.0:
            wav = wav / peak
        buf = io.BytesIO()
        sf.write(buf, wav, 24000, format="WAV")
        return buf.getvalue()

    def _load_voice(self, voice_id: str):
        path = f"{VOICES_DIR}/{voice_id}.json"
        if not os.path.exists(path):
            return None
        with open(path) as fh:
            v = json.load(fh)
        return v if os.path.exists(v.get("ref_path", "")) else None

    @staticmethod
    def _to_wav(raw: bytes, out_path: str):
        """Transcode any recorded audio (m4a/aac/...) to 24k mono wav via ffmpeg."""
        with tempfile.NamedTemporaryFile(suffix=".bin", delete=False) as t:
            t.write(raw)
            src = t.name
        subprocess.run(
            ["ffmpeg", "-y", "-i", src, "-ar", "24000", "-ac", "1", out_path],
            check=True, capture_output=True,
        )
        os.unlink(src)

    # ---- HTTP endpoints ----
    @modal.fastapi_endpoint(method="POST")
    def speak(self, req: SpeakReq):
        kok = self._translate(req.english_text, EN, KOK)
        voice = self._load_voice(req.voice_id) if req.voice_id else None
        if voice:
            wav = self._clone_tts(kok, voice["ref_path"], voice["ref_text"])
            cloned = True
        else:
            wav = self._tts(kok)
            cloned = False
        return {
            "request_id": "utt_" + uuid.uuid4().hex[:8],
            "konkani_text": kok,
            "konkani_audio_b64": base64.b64encode(wav).decode(),
            "translation_source": "model",
            "voice_id": req.voice_id,
            "voice_cloned": cloned,
            "model_versions": self.versions,
        }

    @modal.fastapi_endpoint(method="POST")
    def translate(self, req: TranslateReq):
        src = EN if req.source == "en" else KOK
        tgt = KOK if req.target == "kok" else EN
        return {"translated": self._translate(req.text, src, tgt), "model_version": self.versions["mt"]}

    @modal.fastapi_endpoint(method="POST")
    def stt(self, req: SttReq):
        if req.lang != "en":
            return {"error": "Konkani ASR (IndicConformer) is added in Stage 3; use lang=en here."}
        raw = base64.b64decode(req.audio_b64)
        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as f:
            f.write(raw)
            path = f.name
        try:
            segments, _ = self.whisper.transcribe(path, language="en")
            text = " ".join(s.text for s in segments).strip()
        finally:
            os.unlink(path)
        return {"text": text, "lang": "en", "model_version": self.versions["stt"]}

    @modal.fastapi_endpoint(method="POST")
    def corrections(self, req: CorrectionReq):
        if not req.consent:
            return {"error": "consent required"}
        cid = "cor_" + uuid.uuid4().hex[:8]
        audio_path = None
        if req.native_audio_b64:
            os.makedirs(f"{DATA_DIR}/corrections", exist_ok=True)
            audio_path = f"{DATA_DIR}/corrections/{cid}.wav"
            self._to_wav(base64.b64decode(req.native_audio_b64), audio_path)
        record = {
            "correction_id": cid, "ts": time.time(),
            "english_source": req.english_source, "our_konkani_text": req.our_konkani_text,
            "native_confirmed_text": req.native_confirmed_text, "native_audio_path": audio_path,
            "region": req.region, "dialect": req.dialect, "script": req.script, "status": "new",
        }
        with open(f"{DATA_DIR}/corrections.jsonl", "a") as fh:
            fh.write(json.dumps(record, ensure_ascii=False) + "\n")
        data.commit()
        return {"correction_id": cid, "stored": True}

    @modal.fastapi_endpoint(method="POST")
    def enroll(self, req: EnrollReq):
        if not req.consent:
            return {"error": "consent required"}
        vid = "vp_" + uuid.uuid4().hex[:8]
        os.makedirs(VOICES_DIR, exist_ok=True)
        ref_path = f"{VOICES_DIR}/{vid}.wav"
        self._to_wav(base64.b64decode(req.audio_b64), ref_path)
        # F5 needs the reference transcript; the enrollment clip is English, so Whisper drafts it.
        segments, _ = self.whisper.transcribe(ref_path, language="en")
        ref_text = " ".join(s.text for s in segments).strip() or "Hello."
        with open(f"{VOICES_DIR}/{vid}.json", "w") as fh:
            json.dump({"voice_id": vid, "user_id": req.user_id, "ref_path": ref_path, "ref_text": ref_text}, fh)
        data.commit()
        return {"voice_id": vid, "ref_text": ref_text}

    @modal.fastapi_endpoint(method="GET")
    def health(self):
        return {"ok": True, "device": self.device, "versions": self.versions}
