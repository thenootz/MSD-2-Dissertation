# Pavlova — Architecture & Implementation Details

> **Explainable AI Framework for Auditing Algorithmic Manipulation in Social Media Recommendation Systems**

This document describes the **current** architecture of the Pavlova Android app, including the full runtime pipeline, package layout, key design patterns, and implementation status.

---

## High-Level Overview

Pavlova is a **single-activity Compose application** that runs a continuous background service to capture social media feed screens, analyze them for manipulation patterns, and alert the user in real-time — all without sending any data to the cloud.

```
┌─────────────────────────────────────────────────────────────────┐
│                       PAVLOVA APPLICATION                       │
│                                                                 │
│  ┌─ CAPTURE LAYER ───────────────────────────────────────────┐  │
│  │ ScreenCaptureService (MediaProjection ~2 FPS)             │  │
│  │ └─ Frame deduplication (contentHashCode)                  │  │
│  │ └─ Raw RGBA bytes → FeedAnalyzer                          │  │
│  └───────────────────────────────────────────────────────────┘  │
│                                ↓                                │
│  ┌─ ANALYSIS LAYER ──────────────────────────────────────────┐  │
│  │ FeedAnalyzer (orchestrator)                               │  │
│  │ ├─ TextExtractor (ML Kit OCR)                             │  │
│  │ ├─ ContentAnalyzer (NLP pipeline)                         │  │
│  │ │  ├─ NlpModelRunner (sentiment/toxicity TFLite)          │  │
│  │ │  ├─ EmbeddingEngine (SBERT embeddings TFLite)           │  │
│  │ │  └─ Tokenizer abstraction (BPE/WordPiece/Hash)          │  │
│  │ ├─ VideoSegmenter (scroll detection)                      │  │
│  │ ├─ CreatorDetector (per-video creator tracking)           │  │
│  │ └─ Room persistence (ContentItem)                         │  │
│  │                                                           │  │
│  │ ManipulationDetector (every ~10 items)                    │  │
│  │ ├─ DriftAnalyzer (entropy, Gini, escalation)              │  │
│  │ ├─ MarkovChainAnalyzer (topic transitions)                │  │
│  │ ├─ SequenceAnalyzer (LSTM or statistical)                 │  │
│  │ ├─ IsolationForest (anomaly scoring)                      │  │
│  │ └─ SessionMetrics → FeedAlerts → OverlayManager           │  │
│  └───────────────────────────────────────────────────────────┘  │
│                                ↓                                │
│  ┌─ UI & PERSISTENCE ────────────────────────────────────────┐  │
│  │ Compose Dashboard (4 routes)                              │  │
│  │ ├─ DashboardScreen (audit start, sessions, scores)        │  │
│  │ ├─ SessionDetailScreen (per-item rows, screenshots)       │  │
│  │ ├─ SettingsScreen (permissions, verbosity, alerts)        │  │
│  │ └─ DebugCapturesScreen (frame + OCR text log)             │  │
│  │                                                           │  │
│  │ Room + SQLCipher (encrypted DB)                           │  │
│  │ ├─ FeedSession, ContentItem, SessionMetrics               │  │
│  │ ├─ ScreenshotStore (opt-in verbose mode)                  │  │
│  │ └─ AppSettings (SharedPreferences)                        │  │
│  └───────────────────────────────────────────────────────────┘  │
│                                ↓                                │
│  ┌─ ALERTS ──────────────────────────────────────────────────┐  │
│  │ OverlayManager (TYPE_APPLICATION_OVERLAY)                 │  │
│  │ └─ Auto-dismissing wellbeing banners                      │  │
│  │ OR AlertNotifier (system notifications)                   │  │
│  └───────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

---

## Runtime Pipeline

### Initialization (PavlovaApplication.onCreate)

Singletons are initialized in strict dependency order:

```kotlin
AppSettings                    // Shared preferences
  ↓
ScreenshotStore               // Verbose mode screenshot storage
  ↓
DebugCaptureStore             // Pipeline debugging store
  ↓
ContentAnalyzer               // Singleton holding TFLite interpreters
  ├─ TextExtractor
  ├─ NlpModelRunner (sentiment, toxicity models)
  └─ EmbeddingEngine (SBERT model, reused by ManipulationDetector)
  ↓
ManipulationDetector          // Reuses ContentAnalyzer's EmbeddingEngine
  ├─ DriftAnalyzer
  ├─ MarkovChainAnalyzer
  ├─ SequenceAnalyzer
  └─ IsolationForest
```

**Critical**: `ManipulationDetector` explicitly reuses the SBERT `EmbeddingEngine` owned by `ContentAnalyzer` to avoid loading `sbert_quantized.tflite` twice in memory.

Both `ContentAnalyzer` and `ManipulationDetector` are closed in `PavlovaApplication.onTerminate()` and `ScreenCaptureService.onDestroy()` to free TFLite interpreter handles.

### Per-Frame Processing

Each frame captured by `ScreenCaptureService` flows through this pipeline:

```
ScreenCaptureService.onImageAvailable(reader: ImageReader)
  ├─ Extract RGBA bytes (w × h × 4 bytes)
  ├─ Compute contentHashCode(); skip if == last frame hash
  ├─ FeedAnalyzer.processFrame(bytes, width, height, timestamp)
  │   │
  │   ├─ TextExtractor.bitmapFromRgba() + ML Kit OCR
  │   │   └─ Extract text + bounding boxes per line
  │   │
  │   ├─ ContentAnalyzer.analyzeText(ocrText, ocrBlocks)
  │   │   ├─ NlpModelRunner.sentiment (RoBERTa or keyword fallback)
  │   │   ├─ NlpModelRunner.toxicity (RoBERTa or keyword fallback)
  │   │   ├─ Keyword heuristics: topics, emotion, persuasion
  │   │   └─ EmbeddingEngine.embed() (SBERT or hash fallback)
  │   │   └─ Returns: ContentAnalysis (all scores + embedding)
  │   │
  │   ├─ VideoSegmenter.update(frameSignature, bottomBandText)
  │   │   ├─ Detect scroll: frame-diff luminance + vertical shift
  │   │   └─ If scroll || bottom-band text change ⇒ new videoIndex++
  │   │
  │   ├─ CreatorDetector.submit(ocrBlocks)
  │   │   ├─ Plausibility filter (@-prefix, length)
  │   │   └─ Lock creator per videoIndex
  │   │   └─ FeedAnalyzer back-fills creatorId onto earlier frames
  │   │
  │   ├─ Persist ContentItem to Room (FeedSession FK, videoIndex, scores, etc.)
  │   │
  │   ├─ ScreenshotStore.save() if AppSettings.verboseMode
  │   │   └─ Downscaled JPEG (~480px, quality 70) to <filesDir>/captures/
  │   │
  │   ├─ DebugCaptureStore.save() (no-op if disabled)
  │   │
  │   └─ Every 10 items: ManipulationDetector.analyze()
  │       ├─ Collect ContentItems for current session
  │       ├─ DriftAnalyzer.compute() (entropy, Gini, escalation slope)
  │       ├─ MarkovChainAnalyzer.analyzeTopic() (transitions, funnels)
  │       ├─ SequenceAnalyzer.detectEscalation() (LSTM or statistical)
  │       ├─ IsolationForest.score() (anomaly score)
  │       ├─ ManipulationDetector.computeManipulationScore()
  │       │   └─ 10 indicators: sentiment, toxicity, topic drift,
  │       │      embedding isolation, creator repetition, screen time, etc.
  │       ├─ Persist SessionMetrics to Room
  │       │
  │       ├─ FeedAlerts.evaluate(sessionContext)
  │       │   ├─ Metric-based: toxicity > 0.7, isolation > 0.6, etc.
  │       │   ├─ Behavior-based: screen time milestones (5/15/30/45/60 min),
  │       │   │   session > average, repeated creator, binge volume
  │       │   └─ Return Alert with severity + message
  │       │
  │       └─ OverlayManager.show(alert) or AlertNotifier.notify()
  │           └─ Display wellbeing warning (banner or notification)
  │
  └─ Return (async, non-blocking)
```

### UI Collection & Display

The Compose UI collects data via Room DAOs as `Flow`s:

```kotlin
// DashboardScreen.kt
val sessions = FeedSessionDao.getAllSessionsFlow()     // Flow<List<FeedSession>>
val latestMetrics = SessionMetricsDao.getLatestFlow()  // Flow<SessionMetrics?>
val captureActive = CaptureState.collectAsState()      // Flow<Boolean>

// SessionDetailScreen collects ContentItems for a session
val items = ContentItemDao.getBySessionIdFlow()        // Flow<List<ContentItem>>
```

These are rendered in real-time as the analysis engine emits new data.

---

## Package Layout

`android/app/src/main/java/com/pavlova/`

### `services/`

**ScreenCaptureService.kt**
- Foreground service owning `MediaProjection`, `VirtualDisplay`, `ImageReader`.
- Runs on a background coroutine; acquires the display every `1000/TARGET_FPS` ms (~500 ms for 2 FPS).
- Deduplicates frames by `imageData.contentHashCode()`.
- Publishes run state to `CaptureState` (a `StateFlow<Boolean>` observed by the dashboard).
- Handled: `MediaProjection.Callback.onStop()` when the user dismisses screen-share from system UI — tears down service and relaunches `MainActivity`.

**PavlovaAccessibilityService.kt**
- Optional, opt-in. Subscribes to `TYPE_VIEW_SCROLLED` events.
- Detects real scroll input (touch-based scrolling) that an overlay window can't observe.
- Publishes timestamp to `ScrollSignal` (a `@Volatile` bridge).
- Fused by `VideoSegmenter` with visual scroll detection; never required for operation.

**CaptureState & ScrollSignal**
- `CaptureState`: A `StateFlow<Boolean>` indicating whether screen capture is active. Observed by the dashboard to show "● Auditing in progress".
- `ScrollSignal`: A `@Volatile var timestamp: Long`. Updated by accessibility service; polled by `VideoSegmenter` to refine scroll detection.

### `ml/`

**TextExtractor.kt**
- Wraps Google ML Kit text recognition.
- Input: RGBA byte array (w × h × 4).
- Output: `StructuredText` (list of OCR blocks with text, bounding box, confidence).

**ContentAnalyzer.kt** (singleton)
- Orchestrates the content understanding pipeline.
- Owns the single shared `EmbeddingEngine` instance (reused by `ManipulationDetector`).
- `analyze(ocrText: String, ocrBlocks: List<OcrBlock>): ContentAnalysis`
  - Runs sentiment via `NlpModelRunner` (or keyword fallback if model unavailable).
  - Runs toxicity via `NlpModelRunner` (or keyword fallback).
  - Runs topic, emotion, persuasion via keyword heuristics.
  - Runs embedding via `EmbeddingEngine` (or hash fallback).
  - Returns: `ContentAnalysis` with all scores + 384-dim embedding vector.

**NlpModelRunner.kt**
- Generic TFLite classifier wrapper.
- Shared by sentiment and toxicity models (both use RoBERTa + BPE tokenizer).
- `load(context, modelPath, tokenizer): Boolean` — returns whether model was successfully loaded.
- `isAvailable: Boolean` — indicates whether inference is available.
- `classify(text: String, labels: List<String>): Map<String, Float>` — returns logit scores per label.
- On TFLite error: logs exception, sets `isAvailable = false`, caller falls back to keyword heuristic.

**EmbeddingEngine.kt** (singleton, reused by ManipulationDetector)
- Wraps SBERT MiniLM TFLite model for 384-dimensional embeddings.
- Shared tokenizer; uses same `Tokenizer` abstraction as NLP models.
- `embed(text: String): FloatArray` — returns 384-dim vector (or zeros if model unavailable).
- Provides utility methods:
  - `cosineSimilarity(a, b)` — similarity between two embeddings.
  - `kMeans(embeddings, k)` — clustering.
  - `averagePairwiseDistance(embeddings)` — embedding isolation / diversity metric.

**Tokenizer.kt** (abstraction + 3 implementations)
- Static factory: `Tokenizer.load(context, modelStem, fallbackVocabSize): Tokenizer`
- Looks for vocab assets:
  - RoBERTa (BPE): `<stem>_vocab.json`, `<stem>_merges.txt` → `BpeTokenizer`
  - SBERT (WordPiece): `<stem>_vocab.txt` → `WordPieceTokenizer`
  - Fallback: `HashTokenizer` (deterministic hash into vocab range)
- Every implementation has `tokenize(text): IntArray` and `encode(tokens): ByteArray`.

**VideoSegmenter.kt**
- Detects video boundaries (transitions between short videos in a feed).
- Visual signal: scroll produces large full-frame luminance diff (16×16 signature) + vertical row-shift; tap does not.
- Bottom-band OCR text change (caption, metadata) indicates a new video.
- Optional real scroll signal from `ScrollSignal` (accessibility service).
- Increments `videoIndex` when scroll detected or bottom-band changes.
- State: `currentVideoIndex: Int` (incremented, never reset per session).

**CreatorDetector.kt**
- Extracts creator handle from bottom-band OCR text.
- Plausibility filter: length check, @-prefix preference.
- Cross-frame stability: locks creator for the current `videoIndex`.
- `FeedAnalyzer` back-fills the locked `creatorId` onto earlier frames of the same video.
- Resets when `VideoSegmenter` detects a new video.

**FeedAnalyzer.kt**
- Per-session orchestrator.
- Manages `FeedSession` lifecycle (start on capture begin, end on capture stop).
- `processFrame(bytes, width, height, timestamp)`: runs the full per-frame pipeline above.
- Triggers `DebugCaptureStore` unconditionally (no-op when disabled).
- Triggers `ScreenshotStore` only when `AppSettings.verboseMode` is true.
- Batches every 10 items and calls `ManipulationDetector.analyze()`.

### `analysis/`

All components are pure Kotlin except `SequenceAnalyzer` (which can defer to heuristics).

**DriftAnalyzer.kt**
- Stateless utility functions for feed-level metrics.
- `computeEntropy(topics)` — Shannon entropy of topic distribution.
- `computeGini(scores)` — Gini coefficient (concentration inequality).
- `emotionalEscalationSlope(emotionScores, windowSize)` — linear regression slope of emotion trends.
- `contentVelocity(itemCount, elapsedMs)` — items per minute.

**MarkovChainAnalyzer.kt**
- Builds a topic transition matrix from session's content items.
- `analyzeTopic(items): TopicTransitionResult`
  - Returns: `transitionMatrix`, `entropyOfTransitions`, `trendingTopics`, `funnelPath`.
  - Funnel detection: identifies a pathological sequence (e.g., "news" → "politics" → "extremism") where each step increases escalation risk.

**SequenceAnalyzer.kt**
- Detects temporal escalation patterns (e.g., increasing toxicity or emotional intensity over time).
- If LSTM model is available: loads `sequence_lstm.tflite` and runs inference on sliding windows of 10-item feature sequences.
- Fallback: sliding-window linear regression + statistical thresholding.
- `detectEscalation(items, windowSize): EscalationScore` — returns 0–1 confidence.

**IsolationForest.kt**
- Pure-Kotlin anomaly detector trained incrementally on session feature vectors.
- `fit(historicalFeatures: List<FloatArray>)` — trains on past sessions' feature vectors.
- `score(features: FloatArray): Float` — returns 0–1 anomaly score for a new session.
- Used by `ManipulationDetector` to flag sessions with unusual feature combinations.

**ManipulationDetector.kt** (singleton)
- Combines all analysis engines into a 10-indicator manipulation score.
- `analyze(sessionId, items): SessionMetrics`
  - **Indicators**: sentiment spread, toxicity average, topic drift entropy, embedding isolation, creator repetition, screen time (flag if > 30 min), content velocity, behavioral escalation, anomaly score, feedback loops.
  - **Weights**: hardcoded per indicator (all sum to 1.0).
  - **Result**: `manipulationScore: Float` (0–1) stored in `SessionMetrics`.
- Publishes to Room; also fires `FeedAlerts` to trigger overlay/notification warnings.

**SessionTrendAnalyzer.kt**
- Cross-session trend detection (e.g., user increasingly exposed to toxicity over multiple sessions).
- `analyzeTrends(sessionsHistory): SessionTrendMetrics`
  - Returns: trend direction (up/flat/down), velocity, risk trajectory.

**ShapExplainer.kt**
- SHAP-style permutation importance for a `SessionMetrics`.
- For each indicator, permute its value and re-score the manipulation risk.
- Generate human-readable explanations: *"Flagged because: topic escalation detected (60% impact), high toxicity exposure (25% impact), …"*.
- Used by `SessionDetailScreen` to show explainability cards.

**Visualization.kt**
- `UmapProjector` — force-directed 2D projection of session embeddings (approximates UMAP).
- `ContentGraph` — topic co-occurrence graph for visualization.
- Renders as interactive charts in the dashboard.

### `data/`

**model/** — Room entities
- `FeedSession` — one per audit run (startTime, endTime, duration, sessionNotes).
- `ContentItem` — one per OCR'd frame (FK → session, videoIndex, ocrText, scores, embedding, creatorId, timestamp).
- `SessionMetrics` — computed periodically (FK → session, manipulationScore, driftIndicators, anomalyScore, trends).

**database/PavlovaDatabase.kt**
- `RoomDatabase` with SQLCipher encryption.
- Passphrase derived from app package name (for reproducibility).
- `fallbackToDestructiveMigration` enabled — schema changes wipe local data.

**dao/** — Room Data Access Objects
- `FeedSessionDao` — CRUD + `getAllSessionsFlow()`, `getByIdFlow(id)`, `getAverageCompletedDurationMs()`.
- `ContentItemDao` — CRUD + `getBySessionIdFlow(sessionId)`.
- `SessionMetricsDao` — CRUD + `getLatestFlow()`, `getBySessionIdFlow(sessionId)`.

**ScreenshotStore.kt**
- Downscaled (≤480px, JPEG quality 70) thumbnails per captured frame.
- Written to `<filesDir>/captures/<sessionId>/<frameNum>.jpg`.
- Only writes when `AppSettings.verboseMode` is true.
- `clearAll()` wipes the directory.

**AppSettings.kt**
- SharedPreferences-backed singleton.
- Exposes `verboseMode: Boolean` (toggleable from `SettingsScreen`).
- Exposes `verboseModeFlow: StateFlow<Boolean>` for Compose reactivity.
- Exposes `alertsEnabled: Boolean`, `debugCaptureEnabled: Boolean`.

### `debug/`

**DebugCaptureStore.kt**
- Optional pipeline debugging: saves full-resolution JPEG + OCR text per frame.
- Written to `<getExternalFilesDir>/debug_captures/<timestamp>/`.
- Independent from `verboseMode`; toggled via `SettingsScreen`.
- `FeedAnalyzer.processFrame` calls `save()` unconditionally; store is a no-op when disabled.

### `overlay/`

**OverlayManager.kt**
- WindowManager overlay that draws auto-dismissing wellbeing alert banners.
- Uses `TYPE_APPLICATION_OVERLAY` + `FLAG_NOT_TOUCHABLE` (pass-through).
- Shown for 3–5 seconds over the social-media app.
- Gated by `AppSettings.alertsEnabled` + `SYSTEM_ALERT_WINDOW` permission.
- Thread-safe (all operations dispatch to main thread).

**AlertNotifier.kt**
- Fallback when overlay permission is unavailable.
- Posts alerts as system notifications on a high-importance "Wellbeing alerts" channel.
- Stable notification ID per `Alert.key` (only one per key is shown at a time).
- Severity level maps to priority: `Critical` → `IMPORTANCE_MAX`, `Warning` → `IMPORTANCE_HIGH`.

**FeedAlerts.kt**
- Evaluates alerts in two families:
  - **Metric-based**: thresholds from `SessionMetrics` (toxicity > 0.7, isolation > 0.6, drift > 0.8).
  - **Behavior-based**: screen-time milestones (5, 15, 30, 45, 60 min), session > average duration, repeated creator (>50% of items), binge-volume (>5 items/min).
- Returns the single most severe due alert (per-key cooldown prevents spam).
- Alert template: `Alert(key, severity, message, actionUrl)`.

### `permissions/`

**PermissionManager.kt**
- Helper methods for runtime permission requests.
- `RECORD_AUDIO` (legacy, not used).
- `READ_PHONE_STATE` (legacy, not used).
- `SYSTEM_ALERT_WINDOW` — for overlay alerts.
- `POST_NOTIFICATIONS` — for notification alerts.
- `INTERNET` — not used (no network calls).

### `ui/`

**MainActivity.kt**
- Single-activity Compose root.
- Navigation graph with 4 routes: `dashboard`, `session/{sessionId}`, `settings`, `debug`.
- Initializes `PavlovaApplication` singletons on startup.

**DashboardScreen.kt**
- Shows audit start/stop button (observes `CaptureState`).
- Displays latest `SessionMetrics` in a card (risk color-coded).
- Shows session history (time, duration, risk score).
- First-run onboarding card if no sessions exist.
- Settings icon (top-right) navigates to `SettingsScreen`.
- Session cards are tappable → `SessionDetailScreen`.

**SessionDetailScreen.kt**
- Shows all `ContentItem`s for a session as a scrollable list.
- Per-item row displays: OCR text, scores (sentiment, toxicity, etc.), creator, optional thumbnail (if verbose mode).
- Expandable cards show explainability ("why was this session flagged?").
- Links to `ShapExplainer` summaries.

**SettingsScreen.kt**
- Toggles: `verboseMode` (enable screenshots), `debugCaptureEnabled`, `alertsEnabled`.
- Permission request buttons (overlay, notifications, accessibility service).
- "Clear stored screenshots" action.
- Navigates to `DebugCapturesScreen` if debug capture is on.

**DebugCapturesScreen.kt**
- List of debug captures (JPEG + OCR text log).
- Tappable to view full-resolution frame + extracted text.
- Developer-only; only visible when debug capture is enabled.

---

## Key Design Patterns

### 1. **Every Model Has a Fallback**

```kotlin
// Example: NlpModelRunner
if (nlpModelRunner.isAvailable) {
    // Use TFLite model
    scores = nlpModelRunner.classify(text, labels)
} else {
    // Use keyword heuristic
    scores = keywordFallback.classify(text, labels)
}
```

This ensures the app is **fully functional without any `.tflite` files**.

### 2. **Singleton Reuse for Expensive Resources**

`ManipulationDetector` reuses `ContentAnalyzer`'s `EmbeddingEngine`:

```kotlin
// In ManipulationDetector.initialize()
val embeddingEngine = contentAnalyzer.getEmbeddingEngine()  // Don't load SBERT twice!
```

### 3. **Privacy by Default, Opt-in Verbosity**

- **Production mode** (default): Only OCR text + scores stored.
- **Verbose mode** (opt-in): Downscaled (~480px) thumbnails added.
- **Debug mode** (opt-in): Full-res frames + OCR text for developers.

### 4. **Pluggable Tokenizers**

```kotlin
val tokenizer = Tokenizer.load(context, "roberta_sentiment", fallbackVocabSize = 50265)
// → BpeTokenizer if vocab assets exist
// → WordPieceTokenizer if SBERT vocab exists
// → HashTokenizer otherwise (deterministic)
```

### 5. **Room Flow for Reactive UI**

```kotlin
// DAO returns Flow for Compose reactivity
val sessions = sessionDao.getAllSessionsFlow()  // Flow<List<FeedSession>>
LaunchedEffect(sessions) {
    sessions.collect { newSessions ->
        // Recompose on data change
    }
}
```

---

## Data model

Three Room entities, encrypted at rest by SQLCipher:

- **`FeedSession`** — `id` (UUID), `platform`, `startTime`, `endTime`, `totalItems`, `avgWatchDurationMs`.
- **`ContentItem`** — per OCR'd feed item. FK → `FeedSession`. Fields: `position`, `videoIndex`, `timestamp`, `textContent`, `creatorId`, `screenshotPath?`, `topicLabels` (JSON array string), `sentimentScore`, `emotionLabel`, `toxicityScore`, `persuasionScore`, `watchDurationMs`, `userAction`. `videoIndex` groups consecutive frames belonging to the same short video (assigned by `VideoSegmenter`).
- **`SessionMetrics`** — computed periodically. FK → `FeedSession`. Fields: topic/creator entropy, unique topic/creator counts, avg sentiment / toxicity / persuasion, sentiment variance, emotional escalation, creator concentration, top-topic share, `manipulationScore`, `indicatorBreakdown` (JSON object string).

Schema is at `version = 4` with `fallbackToDestructiveMigration` — schema
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

## Cross-session behavior trends

`SessionTrendAnalyzer.analyze(completedSessions, itemsBySession)` computes
longitudinal metrics across recent completed sessions (currently the last 20),
rendered in the dashboard's **Behavior Trends (Across Sessions)** card.

It detects:

- **Session-duration trend** (minutes per session slope + first→last delta%)
- **Usage-frequency trend** (slope of inter-session start gaps; shrinking gaps
  indicate more frequent usage)
- **Creator concentration trend** and top creators whose session-share is
  increasing over time

From these signals it derives a 0–1 **addiction trend score** with
`Low/Moderate/High` bands. This is intentionally separate from
`manipulationScore` (which is per-session), so dashboards can show both
"what this session looked like" and "how behavior is changing across sessions."

---

## Video segmentation & per-video grouping

MediaProjection captures pixels but not touch events, and an overlay window
cannot read touches that pass through to the app below it (Android forbids
that — it's the tapjacking vector). So Pavlova uses two complementary signals:

**1. Visual segmentation (`VideoSegmenter`, always on).** Infers scrolls from
the frame:
- A **scroll** translates the whole screen — a large full-frame difference
  (16×16 downsampled luminance signature) *and* a coherent vertical row-shift
  (row-profile cross-correlation).
- A **tap / like / comment** barely changes the frame.
- In-video motion / scene cuts change the centre but not the bottom row.

A new video is declared when a *scroll-like* frame (big diff **or** clear
vertical translation) coincides with a **bottom-band OCR text change**, gated
by a short cooldown.

**2. Accessibility scroll detection (`PavlovaAccessibilityService`, optional).**
The only Android-sanctioned way to observe cross-app input. It subscribes to
`TYPE_VIEW_SCROLLED` events only (with `canRetrieveWindowContent=false` — no
text/coordinates/content), publishes a timestamp to `ScrollSignal`, and
`FeedAnalyzer` passes a recent-scroll hint into `VideoSegmenter.update(...)`.
This catches scrolls the visual heuristic might miss and raises confidence.
Opt-in via Settings → Accessibility; capture works visually without it.

Each boundary increments `FeedAnalyzer.currentVideoIndex`, written to every
`ContentItem.videoIndex`. `CreatorDetector` is reset at each boundary so its
per-video stability tracking and creator back-fill restart cleanly. The session
detail screen groups captured frames (and thumbnails) by `videoIndex`, one
header per video with the resolved creator and frame count.

---

## On-screen wellbeing alerts

While an audit session runs, `FeedAnalyzer` evaluates each freshly computed
`SessionMetrics` — plus a runtime `FeedAlerts.SessionContext` (elapsed time,
video/item counts, average past-session duration, and the session's top
creator + its share) — against soft wellbeing thresholds via
`FeedAlerts.evaluate(metrics, context)` and surfaces a heads-up banner over
the social-media app through `overlay/OverlayManager`.

Two families of alert are produced (each with WARNING/CRITICAL tiers unless
noted):

**Metric-based** (from `SessionMetrics`):

- **Toxicity** — `avgToxicity` above threshold ("heavy content").
- **Feed shaping** — `manipulationScore` above threshold ("your feed is being
  shaped").
- **Isolation / echo chamber** — `max(creatorConcentration, topTopicShare)`
  above threshold ("your feed is narrowing").

**Behaviour / time-based** (from `SessionContext`):

- **Screen-time milestones** — keyed per band (`screen_time_5/15/30/45/60`),
  escalating INFO → WARNING → CRITICAL as elapsed time crosses 5/15/30/45/60
  minutes ("Screen time: N minutes").
- **Longer than average** — elapsed time exceeds 1.25× the user's average past
  completed session (needs ≥1 prior session; "longer than your usual").
- **Repeated creator** — the most-seen creator makes up ≥40% (CRITICAL ≥60%)
  of recent videos, naming the handle ("seeing a lot of @creator").
- **Binge volume** — ≥40 (WARNING ≥80) videos watched in one session.

`OverlayManager` draws a `FLAG_NOT_TOUCHABLE` `TYPE_APPLICATION_OVERLAY`
banner that auto-dismisses after a few seconds, so it never intercepts touch
input. The feature is gated by the `AppSettings.alertsEnabled` toggle
(default on).

**Delivery & fallback.** The on-screen overlay banner is preferred, but it
needs the `SYSTEM_ALERT_WINDOW` (draw-over-other-apps) permission, which the
user grants from `SettingsScreen` (the screen detects the missing permission
and shows a "Grant permission" prompt). When that permission is **missing**,
`FeedAnalyzer` falls back to a **system notification** via
`overlay/AlertNotifier` so alerts still reach the user. The notification uses a
dedicated high-importance "Wellbeing alerts" channel (separate from the
low-importance foreground-capture notification), maps the alert `Level` to a
notification priority, and reuses a stable id per `Alert.key` so repeats update
in place. If neither the overlay nor notifications are available, the alert is
skipped.

A per-alert-type cooldown in `FeedAnalyzer` (keyed by `Alert.key`) prevents the
same alert from repeating too frequently; only the single most severe due alert
is shown at a time, through whichever delivery channel is available.

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

## Implementation status

Everything described above is implemented and runs on-device today:

- **Capture → analysis → scoring → alerts** pipeline is wired end-to-end
  (`ScreenCaptureService` → `FeedAnalyzer` → `ContentAnalyzer` →
  `ManipulationDetector` → `FeedAlerts` → `OverlayManager`/`AlertNotifier`).
- **All ML slots have working fallbacks**, so the app is fully functional with
  zero `.tflite` assets present; dropping the generated models in upgrades the
  heuristic paths to neural inference.
- **Room persistence** (SQLCipher) with `FeedSession` / `ContentItem` /
  `SessionMetrics` at schema `version = 4`.
- **Compose UI** with four routes (dashboard, session detail, settings, debug).
- **Cross-session behavior trends** and **on-screen wellbeing alerts** (overlay
  + notification fallback) are live.

Defined but **not yet surfaced in the UI**:

- `analysis/Visualization.kt` — `UmapProjector` (force-directed 2D embedding
  projection) and `ContentGraph` (topic co-occurrence graph) are implemented and
  unit-testable, but the dashboard does not yet render them. They are intended
  as inputs to a future "feed map" screen.

Deliberately **not adopted** (kept out to reduce dependencies):

- No charting library (Vico) — the dashboard renders metrics without one.
- No `kotlinx.serialization` — small list/object JSON is hand-rolled via
  `parseJsonArray` / `topicsAsJson` helpers.

---

## Next steps

Near-term work that builds directly on the current implementation:

1. **Run model collection on a Python 3.11 host** — execute
   `../scripts/collect_models.py` to download and convert the real
   RoBERTa / SBERT / LSTM weights into `app/src/main/assets/`, replacing the
   heuristic fallbacks with neural inference.
2. **End-to-end validation** — capture live feed sessions (TikTok / Instagram /
   YouTube Shorts) and verify OCR → NLP → segmentation → scoring → alerts
   against known content.
3. **Render the visualization layer** — wire `UmapProjector` and `ContentGraph`
   from `Visualization.kt` into a new dashboard "feed map" screen.
4. **Temporal charts** — plot per-session sentiment / toxicity / topic-share
   over time (currently only summarized as scores and trend bands).
5. **Data export** — add JSON/CSV export of sessions, items, and metrics for
   external analysis in Python/R.
6. **Tests** — populate `app/src/test/.../ml/TokenizerTest.kt` on disk and add
   `androidTest` coverage for the capture → persistence path.
