# Pavlova — Architecture

> **"Explainable AI Framework for Auditing Algorithmic Manipulation in Social Media Recommendation Systems"**

This document describes the **current** architecture of the Android app. The
original phased pivot plan that drove the rewrite from the NSFW-filter
prototype to the auditing tool is preserved in
[Appendix A — Pivot plan (historical)](#appendix-a--pivot-plan-historical)
for context.

---

## High-level overview

```
┌────────────────────────────────────────────────────────────────────┐
│                       PAVLOVA ANDROID APP                          │
│                                                                    │
│  UI (Compose, single-activity, 4 routes)                           │
│  ├─ DashboardScreen      audit start/stop, sessions, latest score │
│  ├─ SessionDetailScreen  per-item rows + (optional) thumbnails    │
│  ├─ SettingsScreen       verbose / demo + debug capture toggles   │
│  └─ DebugCapturesScreen  raw frame + OCR text log (developer)     │
│                                                                    │
│  Analysis engine                                                   │
│  ├─ DriftAnalyzer        entropy / Gini / escalation              │
│  ├─ MarkovChainAnalyzer  topic transition matrix + funnel finder  │
│  ├─ SequenceAnalyzer     LSTM (TFLite) or statistical fallback    │
│  ├─ IsolationForest      pure-Kotlin anomaly detector             │
│  ├─ ManipulationDetector 10-indicator weighted score              │
│  └─ ShapExplainer        permutation-importance human summaries   │
│                                                                    │
│  Content understanding                                             │
│  ├─ TextExtractor        ML Kit OCR                               │
│  ├─ NlpModelRunner       RoBERTa sentiment / toxicity (TFLite)    │
│  ├─ EmbeddingEngine      SBERT MiniLM embeddings (TFLite)         │
│  ├─ Tokenizer            BPE / WordPiece / hash fallback          │
│  └─ ContentAnalyzer      orchestrates everything above            │
│                                                                    │
│  Capture                                                           │
│  └─ ScreenCaptureService MediaProjection @ ~2 FPS, frame dedup    │
│                                                                    │
│  Data                                                              │
│  ├─ Room + SQLCipher     FeedSession, ContentItem, SessionMetrics │
│  ├─ ScreenshotStore      thumbnails (verbose mode only)           │
│  └─ AppSettings          SharedPreferences (verboseMode flag)     │
└────────────────────────────────────────────────────────────────────┘
```

No network calls. All inference runs on-device.

---

## Runtime pipeline

```
ScreenCaptureService (MediaProjection, ~2 FPS, dedupe by content hash)
  → FeedAnalyzer.processFrame(bytes, w, h)
      → TextExtractor.bitmapFromRgba / extractText  (ML Kit OCR)
      → ContentAnalyzer.analyzeText
            sentiment       (RoBERTa TFLite or keyword fallback)
            toxicity        (RoBERTa TFLite or keyword fallback)
            topics          (keyword)
            emotion         (keyword)
            persuasion      (keyword)
            embedding       (SBERT TFLite or hash fallback)
      → ContentItem persisted in Room
      → if verboseMode: ScreenshotStore.save() + ContentItem.update(path)
      → DebugCaptureStore.save()  (no-op when disabled)
      → every 10 items: ManipulationDetector.analyze() → SessionMetrics
  → Compose UI collects sessions + metrics via Flow
```

`PavlovaApplication.onCreate()` initialises singletons in this order so
later ones can read the earlier ones safely:

```
AppSettings → ScreenshotStore → DebugCaptureStore → ContentAnalyzer → ManipulationDetector
```

`ManipulationDetector.initialize` reuses the SBERT `EmbeddingEngine` owned
by `ContentAnalyzer` so the model file isn't memory-mapped twice.

---

## Package layout

`app/src/main/java/com/pavlova/`

| Package | Files | Role |
|---------|-------|------|
| `services/` | `ScreenCaptureService` | Foreground service. Owns `MediaProjection`/`VirtualDisplay`/`ImageReader`; hands raw RGBA bytes to `FeedAnalyzer`. |
| `ml/` | `TextExtractor`, `ContentAnalyzer`, `NlpModelRunner`, `EmbeddingEngine`, `Tokenizer`, `FeedAnalyzer`, `ContentAnalysis` | OCR + NLP pipeline. |
| `analysis/` | `DriftAnalyzer`, `MarkovChainAnalyzer`, `SequenceAnalyzer`, `IsolationForest`, `ManipulationDetector`, `ShapExplainer`, `Visualization` (`UmapProjector`, `ContentGraph`) | Session-level scoring + explanation. |
| `data/` | `database/PavlovaDatabase`, `dao/*`, `model/*`, `ScreenshotStore`, `AppSettings` | Encrypted Room + on-disk artefacts + prefs. |
| `debug/` | `DebugCaptureStore` | Developer-only frame + OCR text log. |
| `ui/` | `SessionDetailScreen`, `SettingsScreen`, `DebugCapturesScreen`, `components/Common.kt`, `theme/*` | Compose UI. |
| `overlay/` | `OverlayManager` | Unused today. Kept for a future annotation overlay (see "Reserved for future" below). |
| `permissions/` | `PermissionManager` | Permission helpers. The active flow only requests `POST_NOTIFICATIONS` and MediaProjection — `SYSTEM_ALERT_WINDOW` is no longer gated. |
| (root) | `MainActivity`, `PavlovaApplication` | NavHost (4 routes) + app-level init. |

---

## Data model

Three Room entities, encrypted at rest by SQLCipher:

- **`FeedSession`** — `id` (UUID), `platform`, `startTime`, `endTime`, `totalItems`, `avgWatchDurationMs`.
- **`ContentItem`** — per OCR'd feed item. FK → `FeedSession`. Fields: `position`, `timestamp`, `textContent`, `creatorId`, `screenshotPath?`, `topicLabels` (JSON array string), `sentimentScore`, `emotionLabel`, `toxicityScore`, `persuasionScore`, `watchDurationMs`, `userAction`.
- **`SessionMetrics`** — computed periodically. FK → `FeedSession`. Fields: topic/creator entropy, unique topic/creator counts, avg sentiment / toxicity / persuasion, sentiment variance, emotional escalation, creator concentration, top-topic share, `manipulationScore`, `indicatorBreakdown` (JSON object string).

Schema is at `version = 3` with `fallbackToDestructiveMigration` — schema
edits wipe local state.

JSON in fields like `topicLabels` is hand-rolled via small `parseJsonArray` /
`topicsAsJson` helpers (no `kotlinx.serialization` dependency).

---

## ML model stack

| Layer | Backend | File | Tokenizer | Fallback when model missing |
|-------|---------|------|-----------|-----------------------------|
| OCR | ML Kit (Google) | — | — | no fallback (required) |
| Sentiment | RoBERTa (TFLite) | `roberta_sentiment.tflite` | BPE (`*_vocab.json` + `*_merges.txt`) | keyword bag-of-words |
| Toxicity | RoBERTa (TFLite) | `roberta_toxicity.tflite` | BPE | keyword bag-of-words |
| Embeddings | SBERT MiniLM (TFLite) | `sbert_quantized.tflite` | WordPiece (`*_vocab.txt`) | deterministic hash bag |
| Sequence | LSTM (TFLite) | `sequence_lstm.tflite` | — (numeric input) | windowed linear regression |
| Markov | pure Kotlin | — | — | — |
| Anomaly | pure Kotlin Isolation Forest | — | — | neutral 0.5 score until trained |
| Explainability | pure Kotlin permutation importance | — | — | — |

All `.tflite` files plus their tokenizer asset bundles are gitignored and
generated locally by `../scripts/collect_models.py` (HuggingFace → ONNX →
onnx2tf). When the script can't reach HuggingFace it falls back to Keras
placeholder TFLites with `vocab_size = 32_000`; in that mode the runner
loads a `HashTokenizer` that clamps token ids into the safe range so the
GATHER op does not crash. The `Tokenizer.load(context, stem, ...)` factory
picks the right implementation per model.

---

## Privacy posture

Default configuration:

- Room database is SQLCipher-encrypted at rest.
- No screen content is written to disk — only OCR text, creator handles, and
  derived scores live in the database.
- The foreground capture service shows an Android system notification while
  active; users can revoke MediaProjection at any time.

User-opt-in toggles (in `SettingsScreen`):

- **Verbose / demo mode** (`AppSettings.verboseMode`, persisted in
  SharedPreferences). When ON, `ScreenshotStore` writes a downscaled
  (≤480 px, JPEG q=70) thumbnail per captured frame into
  `<filesDir>/captures/`, and `ContentItem.screenshotPath` is populated.
  Toggling OFF prompts the user to delete already-saved thumbnails
  (`ScreenshotStore.clearAll()`).
- **Debug capture** (`DebugCaptureStore`). Developer-only. Saves a
  full-resolution JPEG + raw OCR text per frame into
  `<getExternalFilesDir>/debug_captures/`. Independent of verbose mode;
  capped at 100 entries (oldest pruned).

Neither toggle changes any network behaviour — there is no network behaviour.

---

## Manipulation score

`ManipulationDetector.analyze(sessionId, items)` produces a single
`manipulationScore: Float` in `[0, 1]` from a weighted combination of ten
indicators:

| Indicator | Source | Weight |
|-----------|--------|-------:|
| `echo_chamber` | inverse-normalised topic entropy | 0.12 |
| `emotional_escalation` | slope of `\|sentiment\|` over the session | 0.12 |
| `content_steering` | share of items from the top topic | 0.10 |
| `creator_concentration` | Gini of creator IDs | 0.10 |
| `toxicity_level` | avg toxicity score | 0.10 |
| `persuasion_pressure` | avg persuasion score | 0.10 |
| `sequence_escalation` | `SequenceAnalyzer` LSTM/statistical | 0.12 |
| `embedding_homogeneity` | 1 − avg pairwise embedding distance | 0.08 |
| `anomaly_score` | `IsolationForest` over historical sessions | 0.12 |
| `funnel_detected` | `MarkovChainAnalyzer.detectFunnels` ≠ ∅ | 0.04 |

The breakdown is persisted as a JSON object in
`SessionMetrics.indicatorBreakdown` for downstream explainability.
`ShapExplainer.generateSummary(metrics)` renders the human-readable string
shown in the dashboard ("High risk — driven by: …").

---

## Reserved for future

- **`overlay/OverlayManager.kt`** — `WindowManager` overlay class kept for a
  planned annotation-overlay feature (e.g. labelling flagged items in
  real-time over the captured surface). Not invoked anywhere today, and the
  capture flow no longer requests `SYSTEM_ALERT_WINDOW`.
- **`analysis/Visualization.kt`** — `UmapProjector` (force-directed 2D
  projection of embeddings) and `ContentGraph` (topic co-occurrence
  graph). Defined and unit-testable but the dashboard does not yet render
  them; intended as inputs to a future "feed map" screen.
- Charting library (Vico) and `kotlinx.serialization` were listed in the
  pivot plan but never adopted — the dashboard renders without a chart
  library and JSON is hand-rolled. Keep new code consistent unless
  introducing a chart screen makes Vico worthwhile.

---

## Build & test

From `android/` on Windows:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat installDebug
.\gradlew.bat test                # JVM unit tests (TokenizerTest today)
.\gradlew.bat connectedAndroidTest
.\gradlew.bat lint
```

`minSdk 26`, `targetSdk = compileSdk = 35`, Java/Kotlin 17. Compose enabled
via the Kotlin Compose plugin (no manual compose-compiler version).

---

## Appendix A — Pivot plan (historical)

The remainder of this file is the original pivot plan from the
NSFW-filter prototype to the auditing tool. It is kept for context but is
no longer the authoritative description of the codebase — the sections
above are.

## Current State (after Rust removal)

Clean Kotlin-only Android app with:

```
android/app/src/main/java/com/pavlova/
├── MainActivity.kt              # Compose UI — needs full rewrite for new screens
├── PavlovaApplication.kt        # App init — reusable, swap ML init
├── data/
│   ├── dao/FilterEventDao.kt    # Room DAO — rename to AuditEventDao
│   ├── database/PavlovaDatabase.kt  # Encrypted Room DB — reusable
│   ├── model/FilterEvent.kt     # Entity — replace with AuditEvent
│   └── repository/FilterEventRepository.kt  # Repo — rename
├── ml/
│   ├── ClassificationResult.kt  # Data class — replace with ContentAnalysis
│   ├── FrameProcessor.kt        # Pipeline — rewrite for feed analysis
│   └── TFLiteMLBridge.kt        # TFLite inference — reusable, swap model
├── overlay/
│   └── OverlayManager.kt        # Overlay — repurpose for annotation
├── permissions/
│   └── PermissionManager.kt     # Permissions — reusable
├── services/
│   └── ScreenCaptureService.kt  # MediaProjection — reusable for feed capture
└── ui/theme/
    ├── Color.kt                 # Rebrand colors
    ├── Theme.kt                 # Reusable
    └── Type.kt                  # Reusable
```

**Reusable infra**: Room+SQLCipher, MediaProjection capture, TFLite runtime, Compose theme, permission handling.

**Needs rewrite**: ML models, data pipeline, UI screens, analysis logic.

---

## New Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        PAVLOVA ANDROID APP                      │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │                    UI LAYER (Compose)                     │   │
│  │                                                          │   │
│  │  Dashboard  │  Session  │  Analysis  │  Settings         │   │
│  │  Screen     │  Capture  │  Reports   │  Screen           │   │
│  └──────────────────────────────────────────────────────────┘   │
│                            │                                    │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │              ANALYSIS ENGINE (Module 2-4)                │   │
│  │                                                          │   │
│  │  ContentClassifier  │  DriftAnalyzer  │  BiasDetector    │   │
│  │  (NLP/TFLite)       │  (Markov/Stats) │  (Anomaly/Stats) │   │
│  └──────────────────────────────────────────────────────────┘   │
│                            │                                    │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │              CAPTURE LAYER (Module 1)                    │   │
│  │                                                          │   │
│  │  FeedCaptureService  │  OCR/TextExtract  │  Metadata     │   │
│  │  (MediaProjection)   │  (ML Kit / Tesseract)  │  Parser  │   │
│  └──────────────────────────────────────────────────────────┘   │
│                            │                                    │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │              DATA LAYER (Encrypted Room + DataStore)     │   │
│  │                                                          │   │
│  │  AuditEvent  │  FeedSession  │  ContentItem  │  Metrics  │   │
│  └──────────────────────────────────────────────────────────┘   │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │              EXPORT (Module 5)                           │   │
│  │  JSON/CSV export  │  Charts  │  Explainability scores    │   │
│  └──────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

---

## Implementation Phases

### Phase 1 — Feed Capture & Data Foundation (MVP)

**Goal**: Capture TikTok feed sessions and store structured metadata.

#### 1A. Data Model (replace FilterEvent)

```
AuditEvent
├── id: Long (auto)
├── sessionId: String (UUID)
├── timestamp: Long
├── contentType: String (video/image/text)
├── capturedText: String? (OCR'd captions/hashtags)
├── watchDurationMs: Long
├── userAction: String (scroll/like/skip/share/comment)
├── screenRegion: String? (feed/explore/search/profile)

FeedSession
├── id: String (UUID)
├── startTime: Long
├── endTime: Long?
├── platform: String (tiktok/instagram/youtube)
├── totalItems: Int
├── avgWatchDurationMs: Long

ContentItem
├── id: Long (auto)
├── sessionId: String
├── position: Int (order in feed)
├── textContent: String? (caption, hashtags)
├── creatorId: String? (hashed)
├── topicLabels: String? (JSON array)
├── sentimentScore: Float?
├── emotionLabel: String?
├── toxicityScore: Float?
├── persuasionScore: Float?
├── ideologyScore: Float? (-1 left, +1 right)
```

#### 1B. Feed Capture Service

Reuse `ScreenCaptureService` + `OverlayManager` to:
- Capture feed frames via MediaProjection
- Run OCR (ML Kit or Tesseract) to extract text from each feed item
- Detect feed scroll events (frame differencing)
- Log each item as a `ContentItem` with timestamp + position
- Track session boundaries (start/stop)

#### 1C. New Dependencies

```kotlin
// OCR — Google ML Kit (free, on-device)
implementation("com.google.mlkit:text-recognition:16.0.0")

// JSON serialization
implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

// Charts for visualization
implementation("com.patrykandpatrick.vico:compose-m3:1.13.1")
```

**Deliverable**: App that captures TikTok sessions, OCRs text from feed items, stores structured data locally.

---

### Phase 2 — Content Understanding (NLP Classification)

**Goal**: Classify each captured content item by topic, sentiment, emotion, toxicity, persuasion.

#### 2A. On-Device NLP Models (TFLite)

| Model | Task | Size | Source |
|-------|------|------|--------|
| MobileBERT | Sentiment classification | ~25 MB | TF Hub |
| Text toxicity | Toxicity scoring | ~5 MB | TF Hub |
| Emotion classifier | Emotion labels | ~10 MB | Custom fine-tune |
| Topic classifier | Topic/category labels | ~10 MB | Custom fine-tune |

All run through existing `TFLiteMLBridge` pattern — just swap the model file and output interpretation.

#### 2B. Content Analysis Pipeline

Replace `FrameProcessor` with `ContentAnalyzer`:

```
Captured Frame
    → OCR (ML Kit)
    → Text Extraction
    → Sentiment Model (MobileBERT)
    → Toxicity Model
    → Emotion Model
    → Topic Model
    → Store ContentItem with all scores
```

#### 2C. Embedding Generation (SBERT-lite)

Use a quantized sentence-transformer to generate embeddings for:
- Clustering similar content
- Measuring feed diversity (embedding space entropy)
- Detecting echo chambers (embedding convergence)

**Deliverable**: Each feed item gets topic, sentiment, emotion, toxicity, persuasion scores stored in Room.

---

### Phase 3 — Recommendation Drift Analysis

**Goal**: Detect how the feed changes over time — radicalization, emotional escalation, diversity collapse.

#### 3A. Markov Chain Analysis

Build transition probability matrix from content sequences:

```
Topic A → Topic B : P(B|A)
```

Detect:
- **Radicalization pathways**: fitness → extreme dieting → conspiracy wellness
- **Emotional escalation**: neutral → mildly emotional → outrage
- **Topic funneling**: diverse → narrowing → echo chamber

Implementation: Pure Kotlin, no ML model needed. Matrix operations on the `ContentItem` sequence.

#### 3B. Diversity Metrics (per session)

| Metric | Formula | Meaning |
|--------|---------|---------|
| Topic entropy | $H = -\sum p_i \log p_i$ | Higher = more diverse feed |
| Sentiment variance | $\sigma^2$ of sentiment scores | Low = monotone emotional tone |
| Embedding spread | Avg pairwise cosine distance | Low = echo chamber |
| Creator concentration | Gini coefficient of creator IDs | High = few creators dominate |

#### 3C. Temporal Analysis

Track metrics across sessions over days/weeks:
- Is diversity decreasing?
- Is emotional intensity increasing?
- Is topic range narrowing?

**Deliverable**: Per-session drift metrics, radicalization trajectory visualization.

---

### Phase 4 — Manipulation Detection & Explainability

**Goal**: Flag suspicious recommendation patterns and explain why.

#### 4A. Anomaly Detection

**Isolation Forest** (lightweight, runs on-device):
- Input: session-level feature vectors (diversity, emotion avg, topic concentration, etc.)
- Output: anomaly score (0-1)
- Flag sessions with abnormal content concentration or sudden ideology shifts

Implementation: Pure Kotlin or [smile-kotlin](https://haifengl.github.io/) library.

#### 4B. Manipulation Indicators

| Indicator | Measurement |
|-----------|------------|
| **Engagement trap** | High-emotion content ratio increasing per session |
| **Echo chamber** | Topic diversity dropping below threshold over N sessions |
| **Emotional escalation** | Monotonic increase in sentiment intensity |
| **Content steering** | Statistically significant shift toward one ideology/topic cluster |
| **Addiction pattern** | Session duration increasing + watch time per item increasing |

#### 4C. Explainability (SHAP-lite)

For each flagged session, compute feature importance:
- "Flagged because: 73% of content was from 2 creators (exposure concentration)"
- "Flagged because: emotional intensity increased 340% over 45 minutes"

Simple permutation-based importance — no heavy ML framework needed.

#### 4D. Dashboard

Compose screens:
1. **Session Timeline** — shows content items color-coded by category/emotion
2. **Drift Chart** — diversity/emotion/topic metrics over time (Vico charts)
3. **Manipulation Score** — aggregate risk score with breakdown
4. **Export** — JSON/CSV dump of all session data for external analysis

**Deliverable**: Working auditing tool with visual dashboard and explainable risk scores.

---

## Recommended Model Stack (Final)

| Layer | Tool | Runs On |
|-------|------|---------|
| Text extraction | Google ML Kit OCR | On-device |
| Sentiment | MobileBERT (TFLite) | On-device |
| Toxicity | TF Hub toxicity model | On-device |
| Embeddings | MiniLM-L6 quantized | On-device |
| Sequence analysis | Markov chains | Pure Kotlin |
| Feed drift | Statistical metrics | Pure Kotlin |
| Anomaly detection | Isolation Forest | Pure Kotlin |
| Explainability | Permutation importance | Pure Kotlin |
| Visualization | Vico + Compose | On-device |

**No server component needed** — entire pipeline runs on-device for privacy.

---

## File Changes Summary (This Commit)

### Deleted (30 files)
- `rust/` — entire Rust crate (Cargo.toml, 5 source files, config)
- `build-rust.ps1`, `build-rust.sh`, `check-env.ps1`, `clean.ps1` — Rust build scripts
- `scripts/convert_model.py` — ONNX model conversion
- `RustMLBridge.kt` — JNI bridge to Rust
- `accessibility_service_config.xml` — config for unimplemented service
- 11 `.md` documentation files describing old NSFW/Rust architecture

### Modified (4 files)
- `build.gradle.kts` — removed NDK ABI filters, jniLibs config, Rust build tasks
- `AndroidManifest.xml` — removed 2 unimplemented service declarations + their permissions
- `PermissionManager.kt` — removed accessibility/notification listener methods
- `.gitignore` — removed Rust/scripts artifact entries

### Kept (18 Kotlin files + resources)
- `TFLiteMLBridge.kt` — reusable for swapping models
- `FrameProcessor.kt` — pipeline pattern reusable
- `ClassificationResult.kt` — data pattern reusable
- `ScreenCaptureService.kt` — MediaProjection capture reusable
- `OverlayManager.kt` — repurpose for annotations
- `Room data layer` (4 files) — entity/DAO/DB/repo pattern reusable
- `MainActivity.kt` + `PavlovaApplication.kt`
- `UI theme` (3 files)
- `PermissionManager.kt`

---

## Next Steps

1. **Phase 1A**: Define new Room entities (`AuditEvent`, `FeedSession`, `ContentItem`)
2. **Phase 1B**: Add ML Kit OCR dependency and integrate with `ScreenCaptureService`
3. **Phase 1C**: Build basic capture flow — start session → capture frames → OCR → store
4. **Phase 1D**: Build minimal dashboard UI showing captured session data
