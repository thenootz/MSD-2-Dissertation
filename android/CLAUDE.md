# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Pavlova — an Android app (Kotlin + Jetpack Compose) that audits social media feed recommendation algorithms (TikTok, Instagram, YouTube) for behavioral/ideological manipulation. It captures the screen via MediaProjection, OCRs feed text on-device, runs an NLP pipeline, and computes drift/manipulation metrics — entirely on-device, no network calls. This is a Master's dissertation project; this `android/` directory is the Gradle root for the app module. Sibling directories at the repo root (`../scripts`, `../rust`, `../ARCHITECTURE.md`, `../PRESENTATION.md`) contain the model-collection tooling and design docs — read `../ARCHITECTURE.md` for the original phased design rationale if working on analysis/ML code.

## Build & Run

From `android/` (PowerShell on Windows):

```powershell
.\gradlew.bat assembleDebug      # build debug APK
.\gradlew.bat installDebug       # build + install on connected device/emulator
.\gradlew.bat test                # run JVM unit tests (module: app)
.\gradlew.bat connectedAndroidTest # run instrumented tests on a device
.\gradlew.bat lint                # Android lint
```

There is one JVM unit test staged: `app/src/test/java/com/pavlova/ml/TokenizerTest.kt` (covers `HashTokenizer`, `WordPieceTokenizer`, `BpeTokenizer`). The test file is staged in `plan.md` and will be added once the `test/java/com/pavlova/ml/` directory is created on disk. The `androidTest` source set is not yet populated.

minSdk 26, targetSdk/compileSdk 35, Java/Kotlin target 17. Compose is enabled via the Kotlin Compose plugin (no separate compose-compiler version needed since Kotlin 2.x).

### ML model assets

`app/src/main/assets/*.tflite` and `*.onnx` are gitignored (large binaries) — they must be regenerated locally via `../scripts/collect_models.py` (Python 3.11, see `../README.md` for the venv/setup steps). The app is designed to run correctly even when these files are absent: every model-backed component checks `isAvailable`/`isModelLoaded` and falls back to a pure-Kotlin heuristic.

Expected model files and current status:
- `roberta_sentiment.tflite`, `roberta_toxicity.tflite` — sentiment/toxicity classifiers, loaded by `NlpModelRunner`
- `sbert_quantized.tflite` — sentence embeddings, loaded by `EmbeddingEngine`
- `sequence_lstm.tflite` — created (synthetic-trained Keras LSTM) when `collect_models.py` runs; `SequenceAnalyzer` falls back to a windowed-statistics analyser when missing
- `nsfw_mobilenet_v2_140_224.*` — leftover from a removed NSFW-filter feature, currently unused

When `collect_models.py` successfully downloads a HuggingFace checkpoint it also writes tokenizer assets next to each NLP `.tflite`:

- RoBERTa (BPE): `<stem>_vocab.json`, `<stem>_merges.txt`, `<stem>_tokenizer.json`
- SBERT MiniLM (WordPiece): `<stem>_vocab.txt`, `<stem>_tokenizer.json`

`Tokenizer.load(context, stem, fallbackVocabSize)` picks the right implementation (`BpeTokenizer` / `WordPieceTokenizer`) when these assets are present, and falls back to a deterministic `HashTokenizer` (ids clamped into the placeholder's vocab range) when only a Keras-placeholder TFLite shipped.

## Architecture

### Pipeline (high level)

```
ScreenCaptureService (MediaProjection, ~2 FPS, frame dedup by content hash)
  → FeedAnalyzer.processFrame()
      → ContentAnalyzer.analyze()  [TextExtractor OCR → NLP scoring]
      → store ContentItem in Room
      → every 10 items: ManipulationDetector.analyze() → SessionMetrics
  → MainActivity / DashboardScreen reads sessions+metrics via Flow (Compose)
```

`PavlovaApplication.onCreate()` initialises the singletons in order: `AppSettings`, `ScreenshotStore`, `DebugCaptureStore`, `ContentAnalyzer`, `ManipulationDetector`. The two analysis singletons hold loaded TFLite interpreters and are closed in `onTerminate()`/service `onDestroy()`. `ManipulationDetector` reuses the SBERT `EmbeddingEngine` owned by `ContentAnalyzer` (do not load `sbert_quantized.tflite` twice).

### Package layout (`app/src/main/java/com/pavlova/`)

- **`services/ScreenCaptureService.kt`** — foreground service owning the `MediaProjection`/`VirtualDisplay`/`ImageReader`. Rate-limits frames to `TARGET_FPS`, deduplicates by `imageData.contentHashCode()`, hands raw RGBA bytes to `FeedAnalyzer`.
- **`ml/`** — content understanding pipeline:
  - `TextExtractor` — ML Kit OCR (bitmap/RGBA → text)
  - `ContentAnalyzer` (singleton) — orchestrates OCR → sentiment/toxicity (via `NlpModelRunner`) → topic/emotion/persuasion (keyword heuristics) → embedding (via `EmbeddingEngine`); returns a `ContentAnalysis`. Owns the single shared `EmbeddingEngine` instance.
  - `NlpModelRunner` — generic TFLite classifier wrapper (sentiment/toxicity models share this). Uses a [`Tokenizer`] picked by `Tokenizer.load(context, modelStem, fallbackVocabSize)`: real BPE/WordPiece when vocab assets ship, deterministic `HashTokenizer` otherwise. Always wraps `interpreter.run` in try/catch — on failure the runner self-disables and callers fall back to the keyword heuristic.
  - `EmbeddingEngine` — SBERT TFLite embeddings + cosine similarity / k-means / average pairwise distance. Uses the same `Tokenizer` abstraction; falls back to a deterministic hash-bag embedding when the model is absent.
  - `Tokenizer.kt` — pluggable tokenizer abstraction (`BpeTokenizer`, `WordPieceTokenizer`, `HashTokenizer`); see "ML model assets" above for the asset layout.
  - `FeedAnalyzer` — per-session orchestration: starts/ends `FeedSession`, persists `ContentItem`s, triggers periodic `ManipulationDetector` runs. Thumbnails via `ScreenshotStore.save(...)` are only written when `AppSettings.verboseMode` is on; `DebugCaptureStore.save(...)` is always called and is a no-op when that store is disabled.
- **`analysis/`** — session-level drift/manipulation analysis, all pure Kotlin except `SequenceAnalyzer`:
  - `DriftAnalyzer` — Shannon entropy, Gini coefficient, emotional escalation slope, topic share (stateless utility functions)
  - `MarkovChainAnalyzer` — topic-transition matrix, "funnel" (radicalization pathway) detection
  - `SequenceAnalyzer` — LSTM-based (or statistical-fallback) escalation scoring over a sliding window of item features
  - `IsolationForest` — from-scratch anomaly detector trained incrementally on session feature vectors (`ManipulationDetector.historicalFeatures`)
  - `ManipulationDetector` (singleton) — combines all of the above into a 10-indicator weighted `manipulationScore` (0-1) stored as `SessionMetrics`; weights are hardcoded in this file
  - `ShapExplainer` — permutation-importance style explanations for a `SessionMetrics` (human-readable "flagged because..." summaries)
  - `Visualization.kt` — `UmapProjector` (force-directed 2D projection of embeddings) and `ContentGraph` (topic co-occurrence graph) for dashboard charts
- **`data/`** — Room (SQLCipher-encrypted) persistence + on-disk artefacts:
  - `model/` entities: `FeedSession` (one per audit run), `ContentItem` (one per OCR'd feed item, FK → session), `SessionMetrics` (computed periodically, FK → session)
  - `database/PavlovaDatabase` — opens with a SQLCipher passphrase derived from the package name (`fallbackToDestructiveMigration` is enabled — schema changes wipe local data)
  - `dao/` — standard Room DAOs, exposed as `Flow` for Compose `collectAsState`
  - `ScreenshotStore` — downscaled JPEG thumbnails per captured frame, written to `<filesDir>/captures/`. Only writes when `AppSettings.verboseMode` is on; `clearAll()` wipes the directory.
  - `AppSettings` — SharedPreferences-backed object exposing `verboseMode: Boolean` + a `verboseModeFlow: StateFlow<Boolean>` for Compose. Toggled from `ui/SettingsScreen`.
- **`debug/DebugCaptureStore.kt`** + **`ui/DebugCapturesScreen.kt`** — optional on-disk JPEG + OCR text capture for pipeline debugging. Independent from `verboseMode`; toggleable via the same Settings screen. `FeedAnalyzer.processFrame` calls `DebugCaptureStore.save(bitmap, ocrText)` unconditionally and the store no-ops when disabled.
- **`ui/SettingsScreen.kt`** — Compose screen with two toggles (verbose/demo mode, debug capture) plus a "Clear stored screenshots" button. Reachable from the dashboard's top-right `Settings` action; navigates to `DebugCapturesScreen` when debug capture is on.
- **`overlay/OverlayManager.kt`** — `WindowManager` overlay that draws auto-dismissing **wellbeing alert banners** (`TYPE_APPLICATION_OVERLAY`, `FLAG_NOT_TOUCHABLE`) over the social-media app. Driven by `FeedAnalyzer` via `analysis/FeedAlerts.kt`, which evaluates per-session toxicity / feed-influence / isolation thresholds. Gated by `AppSettings.alertsEnabled` + the `SYSTEM_ALERT_WINDOW` permission (granted from `SettingsScreen`).
- **`permissions/PermissionManager.kt`** — overlay / POST_NOTIFICATIONS / MediaProjection permission helpers. MediaProjection is no longer gated on overlay; overlay is requested separately from Settings for the alerts feature.
- **`MainActivity.kt`** — single-activity Compose app with four nav routes: `dashboard`, `session/{sessionId}`, `settings`, `debug`. `DashboardScreen` shows audit start/stop, permission + verbose-mode status, latest `SessionMetrics`, and session history, all driven by Room `Flow`s.

### Key conventions

- Topic labels and similar list-valued fields are stored as hand-rolled JSON array strings (e.g. `["politics","news"]`) and parsed with `removeSurrounding`/`split` — there's no JSON library dependency for this; keep new code consistent with `parseJsonArray`/`topicsAsJson` helpers (duplicated in a few files) rather than introducing `kotlinx.serialization`.
- Every TFLite-backed class follows the same pattern: `load()` returns `Boolean` and sets `isAvailable`/`isModelLoaded`, `close()` releases the interpreter, and the calling code (`ContentAnalyzer`, `ManipulationDetector`) branches on availability to pick heuristic vs. model path. Preserve this fallback pattern for any new model integration.
- Privacy constraint (per `../README.md`): in the default (privacy-first) configuration the production pipeline persists only OCR text and computed scores — no raw screen content. Users can opt in to **verbose / demo mode** in Settings, which causes `ScreenshotStore` to write downscaled (≤480px, JPEG quality 70) thumbnails to app-private storage for the session detail screen. `DebugCaptureStore` is a separate developer toggle on the same Settings screen that saves full-resolution JPEGs + OCR text to `<getExternalFilesDir>/debug_captures/` for pipeline debugging.