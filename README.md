# Pavlova — An Explainable AI Framework for Auditing Social Media Recommendation Algorithms

[![Android](https://img.shields.io/badge/Platform-Android%2026%2B-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-purple.svg)](https://kotlinlang.org)
[![TensorFlow Lite](https://img.shields.io/badge/ML-TFLite-orange.svg)](https://www.tensorflow.org/lite)
[![Master's Thesis](https://img.shields.io/badge/Type-Master's%20Thesis-blue.svg)](.)

---

## What is Pavlova?

Pavlova is an **on-device Android audit tool** that analyzes social media feeds (TikTok, Instagram, YouTube Shorts, etc.) to detect algorithmic manipulation patterns and behavioral steering. It answers the question: *"Is the recommendation algorithm steering what you see?"*

### Key Features

- **100% on-device processing** — No data leaves your phone. All NLP inference runs locally on Android.
- **Screen capture & OCR** — Records feed sessions via `MediaProjection` and extracts text with ML Kit.
- **Content understanding** — Sentiment, toxicity, topic, emotion, and persuasion scoring via TFLite models (with pure-Kotlin fallbacks).
- **Drift detection** — Markov chains, LSTM sequences, and statistical entropy to spot topic escalation.
- **Anomaly scoring** — Isolation Forest detects abnormal recommendation patterns.
- **Explainability** — SHAP-style feature importance explains *why* a session was flagged.
- **Visual insights** — UMAP projections of embeddings + topic co-occurrence graphs.
- **Privacy-first** — Encrypted Room database; verbose mode (screenshots) is opt-in.
- **Wellbeing alerts** — Real-time overlay or notification warnings when manipulation thresholds are crossed.

**Dissertation framing**: *"Detection of Behavioral and Ideological Steering in Short-Video Recommendation Platforms Using Explainable Machine Learning"*

---

## Quick Start

### Prerequisites

- **Android Studio** 2023.1 (Hedgehog) or later
- **Java/Kotlin** target 17
- **Android SDK** API 26+, compile/target 35
- **Python 3.11** (for model collection only; optional if using pre-trained models)

### 1. Clone & Setup

```bash
git clone https://github.com/thenootz/MSD-2-Dissertation.git
cd MSD-2-Dissertation
```

### 2. Build & Run

From the `android/` directory:

```bash
# macOS/Linux
./gradlew assembleDebug
./gradlew installDebug

# Windows
.\gradlew.bat assembleDebug
.\gradlew.bat installDebug
```

The app will install on a connected device or emulator.

### 3. (Optional) Collect ML Models

To upgrade from keyword heuristics to TensorFlow Lite inference:

```bash
cd ..
python3.11 -m venv .venv
source .venv/bin/activate    # Windows: .\.venv\Scripts\Activate.ps1
pip install transformers torch tensorflow numpy onnx onnx2tf
python scripts/collect_models.py
```

Models will be downloaded, quantized, and placed in `android/app/src/main/assets/`. Restart the app to load them. If you skip this step, the app works perfectly fine — it falls back to keyword-based heuristics.

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                     PAVLOVA ANDROID APP                     │
│                                                             │
│  Screen Capture (MediaProjection, ~2 FPS, deduplicated)     │
│           ↓                                                 │
│  Text Extraction (ML Kit OCR)                               │
│           ↓                                                 │
│  Content Analysis Pipeline                                  │
│  ├─ Sentiment (RoBERTa / keyword fallback)                  │
│  ├─ Toxicity (RoBERTa / keyword fallback)                   │
│  ├─ Topics, Emotion, Persuasion (keyword heuristics)        │
│  └─ Embeddings (SBERT / hash fallback)                      │
│           ↓                                                 │
│  Manipulation Detection (10-indicator scoring)              │
│  ├─ Markov chain topic drift                                │
│  ├─ LSTM sequence escalation                                │
│  ├─ Isolation Forest anomalies                              │
│  ├─ Embedding isolation + echo chamber                      │
│  └─ Behavioral flags (screen time, repetition, velocity)    │
│           ↓                                                 │
│  Explainability & Visualization                             │
│  ├─ SHAP-style feature importance                           │
│  └─ UMAP embeddings + topic graph                           │
│           ↓                                                 │
│  Encrypted Room Database                                    │
│  ├─ FeedSession, ContentItem, SessionMetrics                │
│  └─ Optional: downscaled screenshot store (verbose mode)    │
│           ↓                                                 │
│  Compose Dashboard & Real-time Alerts                       │
│  ├─ Session list + risk scores                              │
│  ├─ Overlay or notification banners                         │
│  └─ Settings (permission, verbosity, debug toggles)         │
└─────────────────────────────────────────────────────────────┘
```

---

## Model Stack

| Component | Technology | File | Fallback |
|-----------|-----------|------|----------|
| **OCR** | Google ML Kit | — | None |
| **Sentiment** | RoBERTa (TFLite, BPE) | `NlpModelRunner.kt` | Keyword heuristic |
| **Toxicity** | RoBERTa (TFLite, BPE) | `NlpModelRunner.kt` | Keyword heuristic |
| **Embeddings** | SBERT MiniLM (TFLite, WordPiece) | `EmbeddingEngine.kt` | Deterministic hash |
| **Sequence** | LSTM (TFLite) | `SequenceAnalyzer.kt` | Windowed statistics |
| **Feed Drift** | Markov Chains (pure Kotlin) | `MarkovChainAnalyzer.kt` | — |
| **Anomalies** | Isolation Forest (pure Kotlin) | `IsolationForest.kt` | — |
| **Explainability** | SHAP permutation importance | `ShapExplainer.kt` | — |
| **Visualization** | UMAP + co-occurrence graphs | `Visualization.kt` | — |

**Key design**: Every neural model has a pure-Kotlin fallback. The app is fully functional without any `.tflite` files.

---

## Project Structure

```
android/
├── app/src/main/
│   ├── java/com/pavlova/
│   │   ├── services/          ScreenCaptureService, PavlovaAccessibilityService
│   │   ├── ml/                TextExtractor, NlpModelRunner, EmbeddingEngine, Tokenizer
│   │   ├── analysis/          DriftAnalyzer, MarkovChainAnalyzer, SequenceAnalyzer
│   │   │                      IsolationForest, ManipulationDetector, ShapExplainer
│   │   ├── data/              Room entities, database, DAOs, ScreenshotStore, AppSettings
│   │   ├── overlay/           OverlayManager, AlertNotifier, FeedAlerts
│   │   ├── permissions/       PermissionManager
│   │   ├── ui/                DashboardScreen, SessionDetailScreen, SettingsScreen, DebugCapturesScreen
│   │   ├── debug/             DebugCaptureStore
│   │   └── MainActivity.kt    Compose navigation root
│   ├── res/                   Layouts, strings, themes
│   └── AndroidManifest.xml
├── build.gradle.kts           Module-level build config
└── proguard-rules.pro

scripts/
└── collect_models.py          HuggingFace → TFLite conversion + quantization

ARCHITECTURE.md                 Detailed technical reference
PRESENTATION.md                 Project status slides
```

---

## Current Status

### ✅ Completed

- **Feed capture engine** — MediaProjection screen capture at ~2 FPS with content-hash deduplication.
- **OCR pipeline** — ML Kit text extraction with positional line boxes.
- **NLP analysis** — Sentiment, toxicity, topic, emotion, persuasion scoring (neural + heuristics).
- **Semantic embeddings** — SBERT MiniLM with cosine similarity and k-means clustering.
- **Feed drift detection** — Markov chain topic transitions, radicalization funnel identification.
- **Sequence analysis** — LSTM-based temporal escalation or statistical windowed fallback.
- **Anomaly detection** — Isolation Forest on session feature vectors.
- **Explainability** — SHAP-style permutation importance with human-readable summaries.
- **Visualization** — UMAP 2D projections + topic co-occurrence graphs.
- **Manipulation scoring** — 10-indicator weighted risk score (0–1) with thresholds.
- **Data persistence** — Encrypted Room DB (SQLCipher) with CRUD DAOs.
- **Compose UI** — Dashboard, session detail, settings, debug screens.
- **Real-time alerts** — Overlay banners or notifications based on metric/behavior thresholds.
- **Privacy controls** — Opt-in verbose mode for screenshots; debug capture toggle.

### 🛠️ Technical Highlights

- **Language**: Kotlin 2.x + Jetpack Compose
- **Minimum SDK**: 26
- **Target SDK**: 35
- **Database**: Room + SQLCipher encryption
- **ML Framework**: TensorFlow Lite (dynamic interpreter caching)
- **Concurrency**: Coroutines + StateFlow
- **Testing**: JVM unit tests for tokenizers (staged)

---

## Permissions & Privacy

Pavlova requests:

- `RECORD_AUDIO` — Not used currently; legacy from initial design.
- `READ_PHONE_STATE` — Not used currently; legacy from initial design.
- `SYSTEM_ALERT_WINDOW` — For overlay wellbeing alert banners (optional; falls back to notifications).
- `POST_NOTIFICATIONS` — For notification-based alerts.
- `INTERNET` — Not used; all processing is local.

**Privacy guarantee**: By default, only OCR text and computed scores are stored. Opt-in **verbose mode** saves downscaled (~480px, JPEG 70) thumbnails for the session detail screen. Raw video frames are never saved.
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
│   ├── overlay/                   # Wellbeing alert delivery
│   │   ├── OverlayManager.kt      #   On-screen banner over other apps
│   │   └── AlertNotifier.kt       #   System-notification fallback
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
