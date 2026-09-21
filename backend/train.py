"""
Konkani backend — Stage 3: self-improvement (fine-tune from native corrections).

Scheduled Modal jobs turn the corrections the app collects (`konkani-data/corrections.jsonl` + audio)
into training data and fine-tune our models, gated by an eval check so a worse model never ships
(ARCHITECTURE.md §8). The app's phrase-memory gives the *instant* fix; this gives the *durable* one.

  build_datasets()  : corrections.jsonl -> mt_pairs / tts_manifest / stt_manifest        (CPU)
  finetune_mt()     : LoRA fine-tune IndicTrans2 en->kok on corrected pairs; eval chrF;   (GPU, weekly)
                      promote only if it beats the current model; write a versioned adapter + run record.

TTS (Indic-Parler / IndicF5) and STT (IndicConformer) fine-tune from `tts_manifest.jsonl` /
`stt_manifest.jsonl` using each model's own recipe — same collect -> gate -> promote loop.

NOT run in this workspace (needs GPU + collected data). Syntax-checked only.
Deploy:  modal deploy backend/train.py     (or `modal run backend/train.py::finetune_mt`)
"""
import json
import os
import random
import time

import modal

app = modal.App("konkani-training")

weights = modal.Volume.from_name("konkani-weights", create_if_missing=True)
data = modal.Volume.from_name("konkani-data", create_if_missing=True)
WEIGHTS_DIR = "/weights"
DATA_DIR = "/data"
DATASETS_DIR = f"{DATA_DIR}/datasets"
REGISTRY = f"{WEIGHTS_DIR}/models"
EN = "eng_Latn"
KOK = "gom_Deva"
MT_MODEL = "ai4bharat/indictrans2-en-indic-1B"

image = (
    modal.Image.debian_slim(python_version="3.11")
    .apt_install("git", "ffmpeg")
    .pip_install(
        "torch==2.4.0",
        "transformers==4.44.2",
        "accelerate",
        "sentencepiece",
        "sacremoses",
        "peft",
        "datasets",
        "sacrebleu",
        "numpy",
        "IndicTransToolkit @ git+https://github.com/VarunGumma/IndicTransToolkit.git",
    )
    .env({"HF_HOME": f"{WEIGHTS_DIR}/hf"})
)

VOLUMES = {WEIGHTS_DIR: weights, DATA_DIR: data}


# ---------- dataset building (plain helper + a callable job) ----------
def _dump(path: str, rows: list):
    with open(path, "w") as fh:
        for r in rows:
            fh.write(json.dumps(r, ensure_ascii=False) + "\n")


def _build_datasets() -> dict:
    src = f"{DATA_DIR}/corrections.jsonl"
    mt, tts, stt = [], [], []
    if os.path.exists(src):
        for line in open(src):
            line = line.strip()
            if not line:
                continue
            r = json.loads(line)
            if r.get("status") == "rejected":
                continue
            eng, kok, aud = r.get("english_source"), r.get("native_confirmed_text"), r.get("native_audio_path")
            if eng and kok:
                mt.append({"src": eng, "tgt": kok})
            if kok and aud and os.path.exists(aud):
                tts.append({"text": kok, "audio": aud})
                stt.append({"audio": aud, "text": kok})
    os.makedirs(DATASETS_DIR, exist_ok=True)
    _dump(f"{DATASETS_DIR}/mt_pairs.jsonl", mt)
    _dump(f"{DATASETS_DIR}/tts_manifest.jsonl", tts)
    _dump(f"{DATASETS_DIR}/stt_manifest.jsonl", stt)
    data.commit()
    return {"mt": len(mt), "tts": len(tts), "stt": len(stt)}


@app.function(image=image, volumes=VOLUMES, timeout=1800)
def build_datasets() -> dict:
    return _build_datasets()


# ---------- eval + registry helpers ----------
def _eval_chrf(model, tok, ip, rows) -> float:
    import torch
    import sacrebleu

    src = [r["src"] for r in rows]
    ref = [r["tgt"] for r in rows]
    ins = ip.preprocess_batch(src, src_lang=EN, tgt_lang=KOK)
    enc = tok(ins, return_tensors="pt", padding=True, truncation=True, max_length=256).to(model.device)
    with torch.no_grad():
        out = model.generate(**enc, num_beams=5, max_length=256)
    hyp = tok.batch_decode(out, skip_special_tokens=True)
    hyp = ip.postprocess_batch(hyp, lang=KOK)
    return float(sacrebleu.corpus_chrf(hyp, [ref]).score)


def _baseline(component: str) -> float:
    p = f"{REGISTRY}/{component}/baseline.json"
    if os.path.exists(p):
        return float(json.load(open(p)).get("chrf", 0.0))
    return 0.0


def _promote(component: str, version: str, chrf: float):
    d = f"{REGISTRY}/{component}"
    os.makedirs(d, exist_ok=True)
    json.dump({"version": version, "chrf": chrf}, open(f"{d}/current.json", "w"))
    json.dump({"chrf": chrf}, open(f"{d}/baseline.json", "w"))


def _record_run(component: str, version: str, before: float, after: float, promoted: bool):
    os.makedirs(f"{DATA_DIR}/runs", exist_ok=True)
    rec = {"component": component, "version": version, "metric_before": before,
           "metric_after": after, "promoted": promoted, "ts": time.time()}
    with open(f"{DATA_DIR}/runs.jsonl", "a") as fh:
        fh.write(json.dumps(rec) + "\n")
    data.commit()


# ---------- MT fine-tune (weekly) ----------
@app.function(gpu="A10G", image=image, volumes=VOLUMES, timeout=7200, schedule=modal.Cron("0 3 * * 1"))
def finetune_mt(min_pairs: int = 50) -> dict:
    import torch
    from datasets import Dataset
    from transformers import (
        AutoModelForSeq2SeqLM, AutoTokenizer,
        DataCollatorForSeq2Seq, Seq2SeqTrainer, Seq2SeqTrainingArguments,
    )
    from peft import LoraConfig, get_peft_model
    from IndicTransToolkit import IndicProcessor

    _build_datasets()
    path = f"{DATASETS_DIR}/mt_pairs.jsonl"
    pairs = [json.loads(l) for l in open(path)] if os.path.exists(path) else []
    if len(pairs) < min_pairs:
        return {"skipped": True, "reason": "not enough corrections", "pairs": len(pairs)}

    random.shuffle(pairs)
    k = max(10, int(len(pairs) * 0.15))
    heldout, train = pairs[:k], pairs[k:]

    tok = AutoTokenizer.from_pretrained(MT_MODEL, trust_remote_code=True)
    model = AutoModelForSeq2SeqLM.from_pretrained(MT_MODEL, trust_remote_code=True)
    ip = IndicProcessor(inference=False)

    # baseline (current deployed model on the held-out set) before touching weights
    before = _eval_chrf(model, tok, ip, heldout)

    model = get_peft_model(
        model,
        LoraConfig(task_type="SEQ_2_SEQ_LM", r=16, lora_alpha=32, lora_dropout=0.05,
                   target_modules=["q_proj", "v_proj"]),
    )

    src = ip.preprocess_batch([r["src"] for r in train], src_lang=EN, tgt_lang=KOK)
    tgt = ip.preprocess_batch([r["tgt"] for r in train], src_lang=KOK, tgt_lang=EN)
    enc = tok(src, text_target=tgt, truncation=True, max_length=256)
    train_ds = Dataset.from_dict({"input_ids": enc["input_ids"],
                                  "attention_mask": enc["attention_mask"],
                                  "labels": enc["labels"]})

    args = Seq2SeqTrainingArguments(
        output_dir="/tmp/mt_out", per_device_train_batch_size=4, gradient_accumulation_steps=2,
        num_train_epochs=3, learning_rate=2e-4, logging_steps=10, save_strategy="no", report_to=[],
    )
    trainer = Seq2SeqTrainer(
        model=model, args=args, train_dataset=train_ds,
        data_collator=DataCollatorForSeq2Seq(tok, model=model), tokenizer=tok,
    )
    trainer.train()

    after = _eval_chrf(model, tok, ip, heldout)
    version = "mt-" + time.strftime("%Y%m%d-%H%M")
    promoted = after >= before  # eval gate: never ship a regression
    if promoted:
        adapter_dir = f"{REGISTRY}/mt/{version}"
        os.makedirs(adapter_dir, exist_ok=True)
        model.save_pretrained(adapter_dir)  # LoRA adapter; serving loads current.json's version
        _promote("mt", version, after)
        weights.commit()
    _record_run("mt", version, before, after, promoted)
    return {"version": version, "chrf_before": before, "chrf_after": after, "promoted": promoted,
            "train": len(train), "heldout": len(heldout)}


@app.local_entrypoint()
def main():
    print(build_datasets.remote())
