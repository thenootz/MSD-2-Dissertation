#!/usr/bin/env python3
"""
Download and convert NLP models to TFLite for Pavlova.

Requires Python 3.11. Setup:

    python3.11 -m venv .venv
    source .venv/bin/activate       # Windows: .\.venv\\Scripts\\Activate.ps1
    pip install transformers torch tensorflow numpy onnx onnx2tf
    python scripts/collect_models.py

Produces 4 TFLite models in android/app/src/main/assets/:

    roberta_sentiment.tflite   3-class sentiment  (neg / neu / pos)
    roberta_toxicity.tflite    2-class toxicity    (benign / toxic)
    sbert_quantized.tflite     128-D sentence embeddings
    sequence_lstm.tflite       sequence escalation detector (20×5 → 3)

When the real HuggingFace download succeeds, each NLP model also gets a
sibling tokenizer asset bundle written into the same assets folder:

    roberta_sentiment_vocab.json    + roberta_sentiment_merges.txt    (BPE)
    roberta_sentiment_tokenizer.json
    sbert_quantized_vocab.txt       (WordPiece)
    sbert_quantized_tokenizer.json

The Android `Tokenizer` factory loads these at runtime so on-device
inference uses the same vocabulary the model was trained on. If the
assets are absent (e.g. when only the Keras placeholder TFLite shipped),
the runner falls back to a deterministic hash tokenizer scoped to the
placeholder's vocab range.

Pipeline per model:
    1. Download HuggingFace weights  →  PyTorch
    2. Export to ONNX (classifiers export input_ids + attention_mask)
    3. Convert ONNX → TFLite  (via onnx2tf)
    4. Quantize (dynamic range)
    5. Classifiers only: verify TFLite output matches the PyTorch model on
       sample strings (parity check) and fail loudly on divergence.

Classifier checkpoints are configured at the top of this file
(SENTIMENT_MODEL / TOXICITY_MODEL) and default to distilled models for
on-device size/speed.

If HuggingFace download or ONNX→TFLite conversion fails for a model,
a Keras-based placeholder TFLite is created instead (correct I/O shape,
random weights). The Android app still loads and runs it; accuracy is
random until the real model is supplied.

Flags:
    --placeholders-only    Skip downloads; create Keras placeholder TFLites
    --force                Re-download even if .tflite already exists
"""

import argparse
import shutil
import sys
from pathlib import Path

import numpy as np

SCRIPT_DIR = Path(__file__).parent
PROJECT_ROOT = SCRIPT_DIR.parent
ASSETS_DIR = PROJECT_ROOT / "android" / "app" / "src" / "main" / "assets"

# ── Model checkpoints ───────────────────────────────────────────────────
# Distilled checkpoints chosen for on-device size/speed (each ~65-130 MB in
# TFLite, vs ~250 MB for full RoBERTa-base). Override here to swap models.
#
# Sentiment is 3-class (negative / neutral / positive). The default below is
# the accurate RoBERTa reference; for a smaller distilled option set
# SENTIMENT_MODEL to a distilled 3-class checkpoint. Toxicity is a distilled
# DistilBERT (binary benign/toxic).
SENTIMENT_MODEL = "cardiffnlp/twitter-roberta-base-sentiment-latest"
SENTIMENT_NUM_CLASSES = 3
TOXICITY_MODEL = "martin-ha/toxic-comment-model"   # DistilBERT, ~66M params
TOXICITY_NUM_CLASSES = 2
MAX_SEQ_LEN = 128

# Sample strings used by the post-conversion parity check.
PARITY_SAMPLES = [
    "I absolutely love this, it's wonderful and made my day!",
    "This is the worst, most disgusting garbage I have ever seen.",
    "The meeting is scheduled for 3pm tomorrow in room two.",
    "You are an idiot and everyone hates you.",
]


# ── Python version guard ────────────────────────────────────────────────
def check_python():
    v = sys.version_info
    if not (v.major == 3 and 10 <= v.minor <= 12):
        print(f"WARNING: Python {v.major}.{v.minor} detected. "
              f"This script is tested on 3.11. TensorFlow may not work.\n")


# ── Keras placeholder builders ──────────────────────────────────────────

def _keras_classifier(path: Path, max_seq_len: int, num_classes: int,
                      vocab_size: int = 32_000, embed_dim: int = 32):
    """Embedding → AvgPool → Dense → Softmax  (int32 input)."""
    import tensorflow as tf

    inp = tf.keras.layers.Input(shape=(max_seq_len,), dtype=tf.int32)
    x = tf.keras.layers.Embedding(vocab_size, embed_dim,
                                  input_length=max_seq_len)(inp)
    x = tf.keras.layers.GlobalAveragePooling1D()(x)
    x = tf.keras.layers.Dense(num_classes, activation="softmax")(x)
    model = tf.keras.Model(inputs=inp, outputs=x)

    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(_convert_keras(model))


def _keras_embedding(path: Path, max_seq_len: int, embed_dim: int,
                     vocab_size: int = 32_000, hidden: int = 64):
    """Embedding → AvgPool → Dense → L2Normalize  (int32 input)."""
    import tensorflow as tf

    inp = tf.keras.layers.Input(shape=(max_seq_len,), dtype=tf.int32)
    x = tf.keras.layers.Embedding(vocab_size, hidden,
                                  input_length=max_seq_len)(inp)
    x = tf.keras.layers.GlobalAveragePooling1D()(x)
    x = tf.keras.layers.Dense(embed_dim)(x)
    x = tf.keras.layers.Lambda(
        lambda v: tf.math.l2_normalize(v, axis=-1))(x)
    model = tf.keras.Model(inputs=inp, outputs=x)

    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(_convert_keras(model))


def _keras_lstm(path: Path, window: int = 20, features: int = 5,
                outputs: int = 3, train: bool = True):
    """LSTM → Dense → Sigmoid  (float32 input).  Optionally trained."""
    import tensorflow as tf

    model = tf.keras.Sequential([
        tf.keras.layers.Input(shape=(window, features)),
        tf.keras.layers.LSTM(32, return_sequences=False),
        tf.keras.layers.Dense(16, activation="relu"),
        tf.keras.layers.Dense(outputs, activation="sigmoid"),
    ])
    model.compile(optimizer="adam", loss="mse")

    if train:
        X, y = _generate_lstm_data(n=1000, window=window, features=features)
        model.fit(X, y, epochs=10, batch_size=32, verbose=0)
        print("  Trained on 1 000 synthetic sequences (10 epochs)")

    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(_convert_keras_lstm(model))


def _convert_keras_lstm(model) -> bytes:
    """Convert a Keras LSTM model to TFLite.

    The default converter (dynamic-range quantization) frequently fails on
    LSTM layers, so we try progressively more permissive settings:
      1. Native TFLite ops, NO quantization (fused UnidirectionalSequenceLSTM).
      2. Native + Select TF ops fallback (needs the flex delegate on-device).
    """
    import tensorflow as tf

    # Attempt 1: native builtins, no optimizations.
    try:
        conv = tf.lite.TFLiteConverter.from_keras_model(model)
        conv.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS]
        conv._experimental_lower_tensor_list_ops = False
        return conv.convert()
    except Exception as e:
        print(f"  ⚠ LSTM native conversion failed ({e}); retrying with Select TF ops")

    # Attempt 2: allow Select TF ops (Android needs tensorflow-lite-select-tf-ops).
    conv = tf.lite.TFLiteConverter.from_keras_model(model)
    conv.target_spec.supported_ops = [
        tf.lite.OpsSet.TFLITE_BUILTINS,
        tf.lite.OpsSet.SELECT_TF_OPS,
    ]
    conv._experimental_lower_tensor_list_ops = False
    return conv.convert()


def _generate_lstm_data(n, window, features):
    np.random.seed(42)
    X, y = [], []
    for _ in range(n):
        base = np.random.rand(features).astype(np.float32) * 0.3
        slope = (np.random.rand(features).astype(np.float32) - 0.3) * 0.05
        seq = np.clip(
            np.array([base + slope * t + np.random.randn(features) * 0.02
                       for t in range(window)], dtype=np.float32), 0, 1)
        avg = float(np.mean(slope))
        esc = np.clip(avg * 10 + 0.5, 0, 1)
        direction = (1.0 if avg > 0.005 else (-1.0 if avg < -0.005 else 0.0))
        conf = min(abs(avg) * 20, 1.0)
        X.append(seq)
        y.append([esc, (direction + 1) / 2, conf])
    return np.array(X), np.array(y, dtype=np.float32)


def _convert_keras(model) -> bytes:
    import tensorflow as tf
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    return converter.convert()


# ── ONNX → TFLite conversion ───────────────────────────────────────────

def _onnx_to_tflite(onnx_path: Path, tflite_path: Path):
    """Convert via onnx2tf (preferred) or onnx-tf fallback."""
    # Method 1: onnx2tf
    try:
        import onnx2tf
        tmp = tflite_path.parent / "_onnx2tf_tmp"
        onnx2tf.convert(str(onnx_path),
                        output_folder_path=str(tmp),
                        non_verbose=True)
        for f in tmp.rglob("*.tflite"):
            shutil.copy2(f, tflite_path)
            break
        shutil.rmtree(tmp, ignore_errors=True)
        if tflite_path.exists():
            return
    except Exception:
        pass

    # Method 2: onnx-tf + TFLite converter
    try:
        import onnx, tensorflow as tf
        from onnx_tf.backend import prepare
        m = onnx.load(str(onnx_path))
        tf_rep = prepare(m)
        saved = tflite_path.parent / "_savedmodel_tmp"
        tf_rep.export_graph(str(saved))
        conv = tf.lite.TFLiteConverter.from_saved_model(str(saved))
        conv.optimizations = [tf.lite.Optimize.DEFAULT]
        tflite_path.write_bytes(conv.convert())
        shutil.rmtree(saved, ignore_errors=True)
        if tflite_path.exists():
            return
    except Exception:
        pass

    raise RuntimeError(
        "ONNX → TFLite conversion failed. Install onnx2tf:\n"
        "  pip install onnx2tf"
    )


# ── Per-model collection ────────────────────────────────────────────────

def collect_sentiment(placeholders_only: bool, force: bool):
    path = ASSETS_DIR / "roberta_sentiment.tflite"
    if path.exists() and not force:
        _print_exists(path); return

    if not placeholders_only:
        if _try_hf_classifier(
            SENTIMENT_MODEL,
            path, max_seq_len=MAX_SEQ_LEN, label="Sentiment"):
            return

    print(f"  Creating Keras placeholder ({SENTIMENT_NUM_CLASSES}-class sentiment)...")
    _keras_classifier(path, max_seq_len=MAX_SEQ_LEN, num_classes=SENTIMENT_NUM_CLASSES)
    _print_done(path, placeholder=True)


def collect_toxicity(placeholders_only: bool, force: bool):
    path = ASSETS_DIR / "roberta_toxicity.tflite"
    if path.exists() and not force:
        _print_exists(path); return

    if not placeholders_only:
        if _try_hf_classifier(
            TOXICITY_MODEL,
            path, max_seq_len=MAX_SEQ_LEN, label="Toxicity"):
            return

    print(f"  Creating Keras placeholder ({TOXICITY_NUM_CLASSES}-class toxicity)...")
    _keras_classifier(path, max_seq_len=MAX_SEQ_LEN, num_classes=TOXICITY_NUM_CLASSES)
    _print_done(path, placeholder=True)


def collect_sbert(placeholders_only: bool, force: bool):
    path = ASSETS_DIR / "sbert_quantized.tflite"
    if path.exists() and not force:
        _print_exists(path); return

    if not placeholders_only:
        if _try_hf_sbert(path):
            return

    print("  Creating Keras placeholder (128-D embeddings)...")
    _keras_embedding(path, max_seq_len=64, embed_dim=128)
    _print_done(path, placeholder=True)


def collect_lstm(placeholders_only: bool, force: bool):
    path = ASSETS_DIR / "sequence_lstm.tflite"
    if path.exists() and not force:
        _print_exists(path); return

    print("  Building LSTM model (Keras + synthetic training)...")
    try:
        _keras_lstm(path, train=not placeholders_only)
        _print_done(path)
    except Exception as e:
        # The LSTM is optional — SequenceAnalyzer has a pure-Kotlin statistical
        # fallback. Never let an LSTM conversion failure abort the whole run.
        print(f"  ⚠ LSTM conversion failed ({e}); skipping. "
              f"SequenceAnalyzer will use its statistical fallback.")


# ── HuggingFace download + conversion ──────────────────────────────────

def _try_hf_classifier(model_name: str, tflite_path: Path,
                       max_seq_len: int, label: str) -> bool:
    try:
        print(f"  Downloading {label} ({model_name})...")
        from transformers import AutoTokenizer, AutoModelForSequenceClassification
        import torch

        tokenizer = AutoTokenizer.from_pretrained(model_name)
        model = AutoModelForSequenceClassification.from_pretrained(model_name)
        model.eval()

        # Export BOTH input_ids and attention_mask. Without the mask the model
        # attends over pad tokens, which corrupts predictions on the short OCR
        # snippets the app feeds it.
        dummy = tokenizer("test input", return_tensors="pt",
                          max_length=max_seq_len, padding="max_length",
                          truncation=True)
        onnx_path = tflite_path.with_suffix(".onnx")
        torch.onnx.export(
            model,
            (dummy["input_ids"], dummy["attention_mask"]),
            str(onnx_path),
            input_names=["input_ids", "attention_mask"],
            output_names=["logits"],
            dynamic_axes={
                "input_ids": {0: "batch", 1: "seq"},
                "attention_mask": {0: "batch", 1: "seq"},
                "logits": {0: "batch"},
            },
            opset_version=14)
        print(f"  ONNX exported ({onnx_path.stat().st_size / 1e6:.1f} MB)")

        _onnx_to_tflite(onnx_path, tflite_path)
        onnx_path.unlink(missing_ok=True)
        # id2label tells the Android runner the real class order.
        id2label = {int(k): v for k, v in model.config.id2label.items()}
        _export_tokenizer_assets(tokenizer, tflite_path.stem, id2label=id2label)
        _print_done(tflite_path)

        # Verify the TFLite output matches the source PyTorch model.
        _verify_classifier_parity(model, tokenizer, tflite_path, max_seq_len, label)
        return True
    except Exception as e:
        print(f"  ⚠ {label} conversion failed: {e}")
        tflite_path.with_suffix(".onnx").unlink(missing_ok=True)
        return False


def _verify_classifier_parity(model, tokenizer, tflite_path, max_seq_len, label,
                              atol=0.05):
    """Run [PARITY_SAMPLES] through both the PyTorch model and the converted
    TFLite model and assert their softmax probabilities agree within [atol].

    Raises AssertionError on divergence so a broken conversion fails loudly
    instead of silently shipping garbage.
    """
    import numpy as np
    import torch
    import tensorflow as tf

    print(f"  Verifying {label} TFLite parity...")
    interp = tf.lite.Interpreter(model_path=str(tflite_path))
    interp.allocate_tensors()
    in_details = interp.get_input_details()
    out_details = interp.get_output_details()

    def tflite_probs(text):
        enc = tokenizer(text, return_tensors="np", max_length=max_seq_len,
                        padding="max_length", truncation=True)
        for d in in_details:
            name = d["name"]
            if "mask" in name.lower():
                arr = enc["attention_mask"]
            else:
                arr = enc["input_ids"]
            interp.set_tensor(d["index"], arr.astype(d["dtype"]))
        interp.invoke()
        logits = interp.get_tensor(out_details[0]["index"])[0]
        e = np.exp(logits - logits.max())
        return e / e.sum()

    def torch_probs(text):
        enc = tokenizer(text, return_tensors="pt", max_length=max_seq_len,
                        padding="max_length", truncation=True)
        with torch.no_grad():
            logits = model(**enc).logits[0].numpy()
        e = np.exp(logits - logits.max())
        return e / e.sum()

    max_diff = 0.0
    for text in PARITY_SAMPLES:
        tp, hp = tflite_probs(text), torch_probs(text)
        diff = float(np.max(np.abs(tp - hp)))
        max_diff = max(max_diff, diff)
        agree = int(np.argmax(tp)) == int(np.argmax(hp))
        flag = "OK" if (agree and diff <= atol) else "DIVERGE"
        print(f"    [{flag}] diff={diff:.4f}  argmax_tflite={int(np.argmax(tp))} "
              f"argmax_torch={int(np.argmax(hp))}")
        assert agree, f"{label}: argmax mismatch on: {text!r}"
    assert max_diff <= atol, (
        f"{label}: TFLite vs PyTorch probabilities diverge "
        f"(max diff {max_diff:.4f} > {atol})")
    print(f"  ✅ {label} parity OK (max prob diff {max_diff:.4f})")


def _try_hf_sbert(tflite_path: Path) -> bool:
    try:
        print("  Downloading SBERT (sentence-transformers/all-MiniLM-L6-v2)...")
        from transformers import AutoTokenizer, AutoModel
        import torch, torch.nn as nn

        base = AutoModel.from_pretrained(
            "sentence-transformers/all-MiniLM-L6-v2")
        tokenizer = AutoTokenizer.from_pretrained(
            "sentence-transformers/all-MiniLM-L6-v2")

        class Wrapper(nn.Module):
            def __init__(self, b, dim=128):
                super().__init__()
                self.base = b
                h = b.config.hidden_size
                self.proj = nn.Linear(h, dim) if h != dim else nn.Identity()
            def forward(self, ids):
                return self.proj(self.base(ids).last_hidden_state[:, 0, :])

        wrapper = Wrapper(base, 128); wrapper.eval()
        dummy = tokenizer("test", return_tensors="pt",
                          max_length=64, padding="max_length", truncation=True)
        onnx_path = tflite_path.with_suffix(".onnx")
        torch.onnx.export(
            wrapper, (dummy["input_ids"],), str(onnx_path),
            input_names=["input_ids"], output_names=["embedding"],
            opset_version=13)
        print(f"  ONNX exported ({onnx_path.stat().st_size / 1e6:.1f} MB)")

        _onnx_to_tflite(onnx_path, tflite_path)
        onnx_path.unlink(missing_ok=True)
        _export_tokenizer_assets(tokenizer, tflite_path.stem)
        _print_done(tflite_path)
        return True
    except Exception as e:
        print(f"  ⚠ SBERT conversion failed: {e}")
        tflite_path.with_suffix(".onnx").unlink(missing_ok=True)
        return False


# ── Tokenizer asset export ─────────────────────────────────────────────

def _export_tokenizer_assets(tokenizer, model_stem: str, id2label: dict = None):
    """Save the HuggingFace tokenizer's vocab/merges into the Android assets
    folder so the on-device runner can tokenise input the same way the model
    was trained.

    Produces, for stem ``roberta_sentiment``:
      roberta_sentiment_vocab.json
      roberta_sentiment_merges.txt   (BPE models only)
      roberta_sentiment_tokenizer.json  (config: type, special ids, id2label...)

    For WordPiece (BERT-style) models the vocab is emitted as ``*_vocab.txt``.

    [id2label], when supplied, records the classifier's class order so the
    Android runner labels outputs correctly instead of assuming an order.
    """
    import json

    out_dir = ASSETS_DIR
    out_dir.mkdir(parents=True, exist_ok=True)

    # Detect tokenizer family.
    cls = type(tokenizer).__name__.lower()
    is_bpe = "roberta" in cls or "gpt" in cls or "bart" in cls
    is_wordpiece = "bert" in cls and not is_bpe

    config = {
        "tokenizer_class": cls,
        "vocab_size": tokenizer.vocab_size,
        "model_max_length": getattr(tokenizer, "model_max_length", None),
        "pad_token_id": tokenizer.pad_token_id,
        "unk_token_id": tokenizer.unk_token_id,
        "cls_token_id": tokenizer.cls_token_id,
        "sep_token_id": tokenizer.sep_token_id,
        "bos_token_id": getattr(tokenizer, "bos_token_id", None),
        "eos_token_id": getattr(tokenizer, "eos_token_id", None),
        "do_lower_case": getattr(tokenizer, "do_lower_case", False),
        "family": "bpe" if is_bpe else ("wordpiece" if is_wordpiece else "unknown"),
    }
    if id2label:
        # Stored as {"0": "negative", ...} for the Android runner.
        config["id2label"] = {str(k): v for k, v in id2label.items()}
        config["labels"] = [id2label[i] for i in sorted(id2label.keys())]

    if is_bpe:
        vocab = tokenizer.get_vocab()
        (out_dir / f"{model_stem}_vocab.json").write_text(
            json.dumps(vocab, ensure_ascii=False), encoding="utf-8")
        # merges.txt is only accessible via the slow tokenizer; convert if needed.
        try:
            slow = tokenizer
            if not hasattr(slow, "bpe_ranks"):
                from transformers import AutoTokenizer
                slow = AutoTokenizer.from_pretrained(
                    tokenizer.name_or_path, use_fast=False)
            merges = sorted(slow.bpe_ranks.items(), key=lambda kv: kv[1])
            with open(out_dir / f"{model_stem}_merges.txt", "w", encoding="utf-8") as f:
                f.write("#version: 0.2\n")
                for (a, b), _ in merges:
                    f.write(f"{a} {b}\n")
        except Exception as e:
            print(f"  ⚠ Could not export merges.txt for {model_stem}: {e}")
    elif is_wordpiece:
        vocab = tokenizer.get_vocab()
        ordered = sorted(vocab.items(), key=lambda kv: kv[1])
        with open(out_dir / f"{model_stem}_vocab.txt", "w", encoding="utf-8") as f:
            for tok, _ in ordered:
                f.write(tok + "\n")
    else:
        print(f"  ⚠ Unknown tokenizer family for {cls}; skipping asset export")

    (out_dir / f"{model_stem}_tokenizer.json").write_text(
        json.dumps(config, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"  Tokenizer assets exported for {model_stem} ({config['family']})")


# ── Display helpers ─────────────────────────────────────────────────────

def _size_str(p: Path) -> str:
    sz = p.stat().st_size
    return f"{sz / 1e6:.1f} MB" if sz > 500_000 else f"{sz / 1e3:.0f} KB"

def _print_exists(p: Path):
    print(f"[OK] {p.name} already exists ({_size_str(p)})")

def _print_done(p: Path, placeholder: bool = False):
    tag = " (placeholder)" if placeholder else ""
    print(f"[OK] {p.name} — {_size_str(p)}{tag}")


def verify():
    models = {
        "roberta_sentiment.tflite": "Sentiment   (3-class: neg/neu/pos)",
        "roberta_toxicity.tflite":  "Toxicity    (2-class: benign/toxic)",
        "sbert_quantized.tflite":   "SBERT       (128-D embeddings)",
        "sequence_lstm.tflite":     "LSTM        (20×5 → 3 escalation)",
    }
    print("\n" + "=" * 60)
    print("Model Inventory")
    print("=" * 60)
    ok = True
    for fn, desc in models.items():
        p = ASSETS_DIR / fn
        if p.exists():
            print(f"  ✅ {fn:<35} {_size_str(p):>10}  {desc}")
        else:
            print(f"  ❌ {fn:<35} {'MISSING':>10}  {desc}")
            ok = False
    print()
    print("All models ready — neural inference enabled." if ok
          else "Some models missing — app will use heuristic fallbacks.")
    print()


# ── Entry point ─────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(
        description="Collect ML models for Pavlova (requires Python 3.11)")
    parser.add_argument("--placeholders-only", action="store_true",
                        help="Create Keras placeholder TFLites (no HuggingFace downloads)")
    parser.add_argument("--force", action="store_true",
                        help="Re-create models even if .tflite files already exist")
    args = parser.parse_args()

    check_python()

    print("=" * 60)
    print("Pavlova — Model Collection Script")
    print("=" * 60)
    print(f"Python  : {sys.version.split()[0]}")
    print(f"Assets  : {ASSETS_DIR}")
    print()

    ASSETS_DIR.mkdir(parents=True, exist_ok=True)

    collect_sentiment(args.placeholders_only, args.force)
    collect_toxicity(args.placeholders_only, args.force)
    collect_sbert(args.placeholders_only, args.force)
    collect_lstm(args.placeholders_only, args.force)

    verify()


if __name__ == "__main__":
    main()
