# Pavlova — Project Status Presentation

---

## Slide 1: Project Overview

### Pavlova — Explainable AI Framework for Auditing Social Media Recommendation Systems

**Dissertation**: *Detection of Behavioral and Ideological Steering in Short-Video Recommendation Platforms Using Explainable Machine Learning*

**What it does**: An Android app that captures social media feeds (TikTok, Instagram, YouTube), runs on-device NLP analysis, and detects algorithmic manipulation patterns — all without sending data to the cloud.

**Core question**: *Is the algorithm steering what you see?*

---

## Slide 2: Architecture & Model Stack

### 7-Layer Analysis Pipeline (all on-device)

| Layer | Technology | Purpose |
|-------|-----------|---------|
| **OCR** | Google ML Kit | Extract text from feed screenshots |
| **NLP Classification** | RoBERTa (TFLite) | Sentiment, toxicity, emotion, persuasion |
| **Semantic Clustering** | SBERT (TFLite) | Detect echo chambers via embedding similarity |
| **Sequence Analysis** | LSTM (TFLite) | Detect escalating content patterns over time |
| **Feed Drift** | Markov Chains | Track topic transition probabilities & funnels |
| **Anomaly Detection** | Isolation Forest | Flag abnormal recommendation sessions |
| **Explainability** | SHAP + UMAP | Explain *why* a session was flagged |

**Key design**: Every neural model has a keyword-heuristic fallback — the app works without any `.tflite` files.

---

## Slide 3: What's Built (Current Status)

### Completed ✅

- **Feed capture engine** — MediaProjection screen capture at 2 FPS
- **OCR pipeline** — ML Kit text extraction from feed frames
- **NLP analysis** — Topic classification, sentiment, toxicity, emotion, persuasion scoring
- **SBERT embeddings** — Semantic similarity & diversity measurement
- **Markov chain analyzer** — Topic transitions, radicalization funnel detection
- **LSTM sequence analyzer** — Temporal escalation pattern detection
- **Isolation Forest** — Anomaly scoring across sessions
- **SHAP explainer** — Per-session feature importance with human-readable summaries
- **UMAP visualization** — 2D projection of content embeddings + topic graph
- **Manipulation detector** — 10-indicator weighted risk score (0–1)
- **Encrypted Room database** — FeedSession, ContentItem, SessionMetrics entities
- **Dashboard UI** — Compose-based with metrics cards, session history, risk scores
- **Model collection script** — Python 3.11 script to download & convert HuggingFace → TFLite

### 28 Kotlin source files | 4 TFLite model slots | 3 Room entities | 0 cloud dependencies

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

## Slide 5: Next Steps

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
