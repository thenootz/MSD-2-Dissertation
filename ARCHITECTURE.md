# Pavlova — Architecture Pivot Plan

> **"Explainable AI Framework for Auditing Algorithmic Manipulation in Social Media Recommendation Systems"**

---

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
