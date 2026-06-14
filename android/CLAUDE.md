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

There are currently no unit/instrumented test source sets populated — `test`/`androidTest` directories don't exist yet.

minSdk 26, targetSdk/compileSdk 35, Java/Kotlin target 17. Compose is enabled via the Kotlin Compose plugin (no separate compose-compiler version needed since Kotlin 2.x).

### ML model assets

`app/src/main/assets/*.tflite` and `*.onnx` are gitignored (large binaries) — they must be regenerated locally via `../scripts/collect_models.py` (Python 3.11, see `../README.md` for the venv/setup steps). The app is designed to run correctly even when these files are absent: every model-backed component checks `isAvailable`/`isModelLoaded` and falls back to a pure-Kotlin heuristic.

Expected model files and current status:
- `roberta_sentiment.tflite`, `roberta_toxicity.tflite` — sentiment/toxicity classifiers, loaded by `NlpModelRunner`
- `sbert_quantized.tflite` — sentence embeddings, loaded by `EmbeddingEngine`
- `sequence_lstm.tflite` — **not present**; `SequenceAnalyzer` always runs its statistical fallback
- `nsfw_mobilenet_v2_140_224.*` — leftover from a removed NSFW-filter feature, currently unused

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

`PavlovaApplication.onCreate()` calls `ContentAnalyzer.initialize()` and `ManipulationDetector.initialize()` once at startup — both are singleton `object`s holding loaded TFLite interpreters, closed in `onTerminate()`/service `onDestroy()`.

### Package layout (`app/src/main/java/com/pavlova/`)

- **`services/ScreenCaptureService.kt`** — foreground service owning the `MediaProjection`/`VirtualDisplay`/`ImageReader`. Rate-limits frames to `TARGET_FPS`, deduplicates by `imageData.contentHashCode()`, hands raw RGBA bytes to `FeedAnalyzer`.
- **`ml/`** — content understanding pipeline:
  - `TextExtractor` — ML Kit OCR (bitmap/RGBA → text)
  - `ContentAnalyzer` (singleton) — orchestrates OCR → sentiment/toxicity (via `NlpModelRunner`) → topic/emotion/persuasion (keyword heuristics) → embedding (via `EmbeddingEngine`); returns a `ContentAnalysis`
  - `NlpModelRunner` — generic TFLite classifier wrapper (sentiment/toxicity models share this). **Note**: tokenization is a hash-based whitespace tokenizer, not real RoBERTa BPE/WordPiece — model outputs are placeholders until a real tokenizer is wired in
  - `EmbeddingEngine` — SBERT TFLite embeddings + cosine similarity / k-means / average pairwise distance; same hash-tokenizer caveat; falls back to a deterministic hash-bag embedding
  - `FeedAnalyzer` — per-session orchestration: starts/ends `FeedSession`, persists `ContentItem`s, triggers periodic `ManipulationDetector` runs
- **`analysis/`** — session-level drift/manipulation analysis, all pure Kotlin except `SequenceAnalyzer`:
  - `DriftAnalyzer` — Shannon entropy, Gini coefficient, emotional escalation slope, topic share (stateless utility functions)
  - `MarkovChainAnalyzer` — topic-transition matrix, "funnel" (radicalization pathway) detection
  - `SequenceAnalyzer` — LSTM-based (or statistical-fallback) escalation scoring over a sliding window of item features
  - `IsolationForest` — from-scratch anomaly detector trained incrementally on session feature vectors (`ManipulationDetector.historicalFeatures`)
  - `ManipulationDetector` (singleton) — combines all of the above into a 10-indicator weighted `manipulationScore` (0-1) stored as `SessionMetrics`; weights are hardcoded in this file
  - `ShapExplainer` — permutation-importance style explanations for a `SessionMetrics` (human-readable "flagged because..." summaries)
  - `Visualization.kt` — `UmapProjector` (force-directed 2D projection of embeddings) and `ContentGraph` (topic co-occurrence graph) for dashboard charts
- **`data/`** — Room (SQLCipher-encrypted) persistence:
  - `model/` entities: `FeedSession` (one per audit run), `ContentItem` (one per OCR'd feed item, FK → session), `SessionMetrics` (computed periodically, FK → session)
  - `database/PavlovaDatabase` — opens with a SQLCipher passphrase derived from the package name (`fallbackToDestructiveMigration` is enabled — schema changes wipe local data)
  - `dao/` — standard Room DAOs, exposed as `Flow` for Compose `collectAsState`
- **`debug/DebugCaptureStore.kt`** + **`ui/DebugCapturesScreen.kt`** — optional on-disk JPEG+OCR-text capture for debugging the pipeline. Note: as of the last commit this is **not yet wired up** — nothing calls `DebugCaptureStore.initialize()`/`save()`, and `DebugCapturesScreen` isn't reachable from `MainActivity`'s navigation. If extending this feature, both need to be connected.
- **`overlay/OverlayManager.kt`** — `WindowManager` overlay (currently unused by the active pipeline; was part of an earlier NSFW-blur feature).
- **`permissions/PermissionManager.kt`** — overlay / POST_NOTIFICATIONS / MediaProjection permission checks and request intents.
- **`MainActivity.kt`** — single-activity Compose app; `DashboardScreen` shows audit start/stop, permission status, latest `SessionMetrics`, and session history, all driven by Room `Flow`s.

### Key conventions

- Topic labels and similar list-valued fields are stored as hand-rolled JSON array strings (e.g. `["politics","news"]`) and parsed with `removeSurrounding`/`split` — there's no JSON library dependency for this; keep new code consistent with `parseJsonArray`/`topicsAsJson` helpers (duplicated in a few files) rather than introducing `kotlinx.serialization`.
- Every TFLite-backed class follows the same pattern: `load()` returns `Boolean` and sets `isAvailable`/`isModelLoaded`, `close()` releases the interpreter, and the calling code (`ContentAnalyzer`, `ManipulationDetector`) branches on availability to pick heuristic vs. model path. Preserve this fallback pattern for any new model integration.
- Privacy constraint (per `../README.md`): no raw screenshots are persisted by the production pipeline — only OCR text + scores. `DebugCaptureStore` is the deliberate exception, gated behind a debug toggle.