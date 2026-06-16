# Pavlova

**Explainable AI Framework for Auditing Social Media Recommendation Systems**

[![Android](https://img.shields.io/badge/Platform-Android%208.0%2B-green.svg)](https://developer.android.com)
[![Python](https://img.shields.io/badge/Python-3.11-blue.svg)](https://python.org)
[![Master's Thesis](https://img.shields.io/badge/Type-Master's%20Thesis-purple.svg)](#)

---

## Overview

Pavlova is an on-device Android tool for **auditing social media recommendation algorithms** for behavioral manipulation and content steering. It captures feed sessions (e.g. TikTok), extracts text via OCR, classifies content through NLP, and computes drift/manipulation metrics — all locally, with no data leaving the device.

**Dissertation framing**: *"Detection of Behavioral and Ideological Steering in Short-Video Recommendation Platforms Using Explainable Machine Learning"*

---

## Architecture

```
Screen Capture (MediaProjection, 2 FPS)
        │
        ▼
  ML Kit OCR  →  Text Extraction
        │
        ▼
  NLP Pipeline (RoBERTa TFLite / keyword fallback)
  ├── Sentiment analysis
  ├── Toxicity scoring
  ├── Topic classification
  ├── Emotion detection
  ├── Persuasion scoring
  └── SBERT embeddings
        │
        ▼
  Analysis Engine
  ├── Markov chain feed drift
  ├── LSTM sequence escalation
  ├── Isolation Forest anomaly detection
  ├── SHAP explainability
  └── UMAP + graph visualization
        │
        ▼
  Encrypted Room DB  →  Dashboard UI
```

### Model Stack

| Layer                  | Implementation       | File                        |
|------------------------|----------------------|-----------------------------|
| NLP classification     | RoBERTa (TFLite)     | `NlpModelRunner.kt`        |
| Semantic clustering    | SBERT (TFLite)       | `EmbeddingEngine.kt`       |
| Sequence analysis      | LSTM (TFLite)        | `SequenceAnalyzer.kt`      |
| Feed drift             | Markov chains        | `MarkovChainAnalyzer.kt`   |
| Manipulation detection | Isolation Forest     | `IsolationForest.kt`       |
| Explainability         | SHAP                 | `ShapExplainer.kt`         |
| Visualization          | UMAP + ContentGraph  | `Visualization.kt`         |

Every TFLite model slot has a pure-Kotlin heuristic fallback. The app works without any `.tflite` files — drop models into `assets/` to upgrade from heuristics to neural inference.

---

## Quick Start

### Prerequisites

- Android Studio (Hedgehog 2023.1+)
- Java 17+
- Android SDK API 35, NDK not required
- Python 3.11 (for model collection only)

### 1. Clone

```bash
git clone https://github.com/thenootz/MSD-2-Dissertation.git
cd MSD-2-Dissertation
```

### 2. Collect ML Models

```bash
python3.11 -m venv .venv
source .venv/bin/activate       # Windows: .\.venv\Scripts\Activate.ps1
pip install transformers torch tensorflow numpy onnx onnx2tf
python scripts/collect_models.py
```

This downloads HuggingFace models, converts them to TFLite, and places them in `android/app/src/main/assets/`. If conversion fails, Keras placeholder models are created automatically.

| Model | Source | Size |
|-------|--------|------|
| `roberta_sentiment.tflite` | cardiffnlp/twitter-roberta-base-sentiment-latest | ~480 MB → quantized |
| `roberta_toxicity.tflite` | s-nlp/roberta_toxicity_classifier | ~480 MB → quantized |
| `sbert_quantized.tflite` | sentence-transformers/all-MiniLM-L6-v2 | ~90 MB → quantized |
| `sequence_lstm.tflite` | Trained on synthetic escalation data | ~50 KB |

Flags:
- `--placeholders-only` — skip downloads, create Keras stubs only
- `--force` — re-download even if files exist

### 3. Build & Run

1. Open `android/` in Android Studio
2. Sync Gradle
3. Connect a device or start an emulator
4. Run the app

### First Launch

1. Grant **Overlay Permission**
2. Grant **Notification Permission** (Android 13+)
3. Grant **MediaProjection** consent
4. Tap **Start Feed Audit**
5. Open TikTok / Instagram / YouTube and browse
6. Return to Pavlova to see analysis results

---

## Project Structure

```
pavlova/
├── android/app/src/main/java/com/pavlova/
│   ├── analysis/                  # Analysis engines
│   │   ├── DriftAnalyzer.kt       #   Shannon entropy, Gini, escalation
│   │   ├── IsolationForest.kt     #   Anomaly detection
│   │   ├── ManipulationDetector.kt#   Combines all layers → risk score
│   │   ├── MarkovChainAnalyzer.kt #   Topic transition matrices
│   │   ├── SequenceAnalyzer.kt    #   LSTM temporal analysis
│   │   ├── ShapExplainer.kt       #   Feature importance
│   │   └── Visualization.kt       #   UMAP projection + graph
│   ├── data/                      # Room database layer
│   │   ├── dao/                   #   DAOs (FeedSession, ContentItem, Metrics)
│   │   ├── database/              #   SQLCipher-encrypted Room DB
│   │   └── model/                 #   Entities
│   ├── ml/                        # ML pipeline
│   │   ├── ContentAnalyzer.kt     #   OCR → NLP orchestrator
│   │   ├── ContentAnalysis.kt     #   Analysis result data class
│   │   ├── EmbeddingEngine.kt     #   SBERT embeddings + clustering
│   │   ├── FeedAnalyzer.kt        #   Frame → analyze → store → metrics
│   │   ├── NlpModelRunner.kt      #   Generic TFLite NLP runner
│   │   └── TextExtractor.kt       #   ML Kit OCR
│   ├── services/                  # Android services
│   │   └── ScreenCaptureService.kt#   MediaProjection capture
│   ├── overlay/OverlayManager.kt  # Screen overlay
│   ├── permissions/               # Permission handling
│   ├── ui/theme/                  # Compose theme
│   ├── MainActivity.kt           # Dashboard UI
│   └── PavlovaApplication.kt     # App initialization
├── scripts/
│   └── collect_models.py          # Model download & conversion
└── ARCHITECTURE.md                # Detailed architecture plan
```

---

## Privacy

- All processing on-device — no cloud, no network calls
- No screenshots stored — only extracted text and NLP scores
- Database encrypted with SQLCipher
- Creator IDs hashed before storage
- Export data in JSON/CSV for external analysis

---

## Tech Stack

| Component | Technology |
|-----------|-----------|
| UI | Jetpack Compose + Material 3 |
| Database | Room 2.7 + SQLCipher |
| OCR | Google ML Kit (on-device) |
| NLP | TensorFlow Lite (RoBERTa, SBERT) |
| Sequence | TFLite LSTM + statistical fallback |
| Anomaly | Isolation Forest (pure Kotlin) |
| Explainability | SHAP permutation importance |
| Visualization | UMAP force-directed + graph |
| Coroutines | Kotlin Coroutines + Flow |
| Build | Gradle 8.13, AGP 8.13, KSP |

---

## License

This project is a Master's thesis prototype. License TBD.
