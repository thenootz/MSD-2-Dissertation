# Pavlova — Project Status Presentation

---

## Slide 1: Project Overview

### Pavlova — Explainable AI Framework for Auditing Social Media Recommendation Systems

**Dissertation**: *Detection of Behavioral and Ideological Steering in Short-Video Recommendation Platforms Using Explainable Machine Learning*

**What it does**: An Android app that captures social media feeds (TikTok, Instagram, YouTube), runs on-device NLP analysis, and detects algorithmic manipulation patterns — all without sending data to the cloud.

**Core question**: *Is the algorithm steering what you see?*

---

## Slide 2: Architecture & Model Stack

### On-Device Analysis Pipeline (no network calls)

| Layer | Technology | Purpose |
|-------|-----------|---------|
| **Screen capture** | MediaProjection (~2 FPS) | Capture feed frames, dedupe by content hash |
| **OCR** | Google ML Kit | Extract text + line boxes from feed frames |
| **NLP Classification** | RoBERTa (TFLite, BPE) | Sentiment & toxicity (keyword fallback) |
| **Keyword heuristics** | Pure Kotlin | Topic, emotion, persuasion scoring |
| **Semantic Clustering** | SBERT MiniLM (TFLite, WordPiece) | Echo-chamber detection via embedding similarity |
| **Video segmentation** | Frame-diff + accessibility scroll | Group frames per short video, track creators |
| **Sequence Analysis** | LSTM (TFLite) | Detect escalating content patterns over time |
| **Feed Drift** | Markov Chains (pure Kotlin) | Topic transition probabilities & funnels |
| **Anomaly Detection** | Isolation Forest (pure Kotlin) | Flag abnormal recommendation sessions |
| **Explainability** | SHAP permutation importance | Explain *why* a session was flagged |
| **Visualization** | UMAP + topic co-occurrence graph | 2D embedding map + topic graph |

**Key design**: Every neural model has a pure-Kotlin fallback — the app works without any `.tflite` files.

---

## Slide 3: What's Built (Current Status)

### Completed ✅

- **Feed capture engine** — MediaProjection screen capture at ~2 FPS with content-hash dedup
- **OCR pipeline** — ML Kit text extraction with positional line boxes
- **NLP analysis** — Sentiment, toxicity, topic, emotion, persuasion scoring (neural + heuristics)
- **SBERT embeddings** — Semantic similarity, k-means clustering & diversity measurement
- **Video segmentation** — Scroll-based boundary detection (visual + accessibility signal fusion)
- **Creator detection** — Per-video creator resolution with cross-frame stability + back-fill
- **Markov chain analyzer** — Topic transitions, radicalization funnel detection
- **LSTM sequence analyzer** — Temporal escalation pattern detection (statistical fallback)
- **Isolation Forest** — Anomaly scoring across sessions
- **Manipulation detector** — 10-indicator weighted risk score (0–1)
- **Session trend analyzer** — Cross-session behavior/addiction trend detection
- **SHAP explainer** — Per-session feature importance with human-readable summaries
- **UMAP visualization** — 2D projection of content embeddings + topic graph
- **Wellbeing alerts** — Real-time overlay banners with system-notification fallback
- **Encrypted Room database** — FeedSession, ContentItem, SessionMetrics (SQLCipher)
- **Compose UI** — Dashboard, session detail, settings, debug screens (4 nav routes)
- **Privacy controls** — Opt-in verbose/demo mode + developer debug-capture toggle
- **Model collection script** — Python 3.11 script to download & convert HuggingFace → TFLite

### Kotlin-only Android app | 4 TFLite model slots (all optional) | 3 Room entities | 0 cloud dependencies

---

## Slide 4: Manipulation Detection — How It Works

### 10 Indicators → Weighted Aggregate Risk Score

```
Session capture → Per-item NLP scores → Session-level metrics:

  echo_chamber          (12%)  ← low topic entropy
  emotional_escalation  (12%)  ← sentiment intensity increasing
  sequence_escalation   (12%)  ← LSTM detects feature ramps
  anomaly_score         (12%)  ← Isolation Forest outlier
  content_steering      (10%)  ← single topic dominates feed
  creator_concentration (10%)  ← few creators dominate
  toxicity_level        (10%)  ← elevated average toxicity
  persuasion_pressure   (10%)  ← manipulative language patterns
  embedding_homogeneity  (8%)  ← low SBERT diversity
  funnel_detected        (4%)  ← Markov chain convergence
  ─────────────────────────────────────────────────
  → manipulation_score   0.0 (safe) — 1.0 (high risk)
```

**Explainability output example**:
> *"High risk — driven by: Topic diversity ↑ 73%, Toxicity ↑ 68%, Emotional escalation ↑ 54%"*

---

## Slide 5: Real-Time Wellbeing Alerts

### Two Families of Alert, Delivered On-Screen

While an audit runs, `FeedAlerts` evaluates each fresh `SessionMetrics` plus a
runtime session context (elapsed time, video/item counts, average past-session
duration, top creator + share) and surfaces a heads-up banner over the feed.

**Metric-based** (from analysis):
- **Toxicity** — elevated average toxicity ("heavy content")
- **Feed shaping** — high manipulation score ("your feed is being shaped")
- **Isolation / echo chamber** — creator/topic concentration ("your feed is narrowing")

**Behaviour / time-based** (from session context):
- **Screen-time milestones** — 5 / 15 / 30 / 45 / 60 min (INFO → WARNING → CRITICAL)
- **Longer than average** — session exceeds 1.25× the user's usual duration
- **Repeated creator** — one creator dominates ≥40% of recent videos
- **Binge volume** — ≥40 videos watched in one session

**Delivery**: Overlay banner (`SYSTEM_ALERT_WINDOW`) preferred; falls back to a
high-importance **system notification** when the permission is missing. A
per-alert cooldown shows only the single most severe due alert.

---

## Slide 6: Next Steps

### Immediate Priorities

1. **Run model collection on Python 3.11 machine** — convert real RoBERTa/SBERT weights to TFLite
2. **End-to-end testing** — capture a live TikTok session and validate the full pipeline
3. **Dashboard polish** — add temporal charts (sentiment/toxicity over time), UMAP scatter plot
4. **Data export** — JSON/CSV export for external analysis in Python/R

### Research Deliverables

- Collect feed sessions from **multiple test accounts** with different engagement patterns
- Measure **diversity, emotional escalation, ideological drift** across sessions
- Compare manipulation scores between **curated vs. fresh accounts**
- Generate **explainable manipulation indicators** with SHAP visualizations

### Dissertation MVP Scope

> Collect feed sessions → Analyze content progression → Measure diversity + escalation + drift → Build explainable manipulation indicators

**This is already strong enough for MSc-level work.**
