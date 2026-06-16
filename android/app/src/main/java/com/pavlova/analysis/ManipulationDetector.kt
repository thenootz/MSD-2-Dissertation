package com.pavlova.analysis

import android.content.Context
import android.util.Log
import com.pavlova.data.model.ContentItem
import com.pavlova.data.model.SessionMetrics
import com.pavlova.ml.ContentAnalyzer
import com.pavlova.ml.EmbeddingEngine

/**
 * Combines all analysis layers to detect manipulation:
 *   - Statistical indicators (DriftAnalyzer)
 *   - Markov chain feed drift
 *   - LSTM sequence escalation
 *   - Isolation Forest anomaly detection
 *   - SBERT embedding diversity
 *   - SHAP explainability
 */
object ManipulationDetector {

    private const val TAG = "ManipulationDetector"

    private var sequenceAnalyzer: SequenceAnalyzer? = null
    private var isolationForest: IsolationForest? = null
    private var embeddingEngine: EmbeddingEngine? = null
    private val markovAnalyzer = MarkovChainAnalyzer()

    // Historical feature vectors for Isolation Forest training
    private val historicalFeatures = mutableListOf<FloatArray>()

    fun initialize(context: Context) {
        sequenceAnalyzer = SequenceAnalyzer(context).also { it.load() }
        // Reuse the SBERT engine already loaded by ContentAnalyzer instead of
        // mapping the model file a second time. ContentAnalyzer is initialised
        // first in PavlovaApplication.onCreate so this is non-null in practice;
        // fall back to a fresh instance if the call order is ever reversed.
        embeddingEngine = ContentAnalyzer.embeddingEngine()
            ?: EmbeddingEngine(context).also { it.load() }
        isolationForest = IsolationForest(numTrees = 50, subsampleSize = 128)
    }

    /**
     * Full-stack analysis for a session's content items.
     */
    fun analyze(sessionId: String, items: List<ContentItem>): SessionMetrics {
        if (items.isEmpty()) return SessionMetrics(sessionId = sessionId)

        // --- Layer 1: Statistical metrics (DriftAnalyzer) ---
        val topicEnt = DriftAnalyzer.topicEntropy(items)
        val creatorEnt = DriftAnalyzer.shannonEntropy(items.mapNotNull { it.creatorId })
        val uniqueTopicCount = items.mapNotNull { it.topicLabels }
            .flatMap { parseJsonArray(it) }.toSet().size
        val uniqueCreatorCount = items.mapNotNull { it.creatorId }.toSet().size

        val sentiments = items.mapNotNull { it.sentimentScore }
        val avgSent = if (sentiments.isNotEmpty()) sentiments.average().toFloat() else 0f
        val sentVar = if (sentiments.size > 1) {
            val mean = sentiments.average()
            sentiments.map { ((it - mean) * (it - mean)).toFloat() }.average().toFloat()
        } else 0f
        val avgTox = items.mapNotNull { it.toxicityScore }.averageOrZero()
        val avgPers = items.mapNotNull { it.persuasionScore }.averageOrZero()
        val escalation = DriftAnalyzer.emotionalEscalation(items)
        val creatorConc = DriftAnalyzer.creatorConcentration(items)
        val topShare = DriftAnalyzer.topTopicShare(items)

        // --- Layer 2: Markov chain feed drift ---
        markovAnalyzer.clear()
        markovAnalyzer.addSequence(items)
        val funnels = markovAnalyzer.detectFunnels()

        // --- Layer 3: LSTM sequence analysis ---
        val seqResult = sequenceAnalyzer?.analyze(items) ?: SequenceResult()

        // --- Layer 4: SBERT embedding diversity ---
        val embeddingDiversity = computeEmbeddingDiversity(items)

        // --- Build indicator vector ---
        val indicators = mutableMapOf<String, Float>()
        val expectedEntropy = if (items.size > 1) kotlin.math.ln(items.size.toFloat().coerceAtMost(20f)) else 1f
        indicators["echo_chamber"] = (1f - (topicEnt / expectedEntropy).coerceIn(0f, 1f))
        indicators["emotional_escalation"] = escalation.coerceIn(0f, 1f)
        indicators["content_steering"] = topShare.coerceIn(0f, 1f)
        indicators["creator_concentration"] = creatorConc.coerceIn(0f, 1f)
        indicators["toxicity_level"] = avgTox.coerceIn(0f, 1f)
        indicators["persuasion_pressure"] = avgPers.coerceIn(0f, 1f)
        // New indicators from advanced models
        indicators["sequence_escalation"] = seqResult.escalationScore
        indicators["embedding_homogeneity"] = (1f - embeddingDiversity).coerceIn(0f, 1f)
        indicators["funnel_detected"] = if (funnels.isNotEmpty()) 1f else 0f

        // --- Layer 5: Isolation Forest anomaly score ---
        val featureVector = floatArrayOf(
            topicEnt, creatorConc, avgTox, avgPers, avgSent,
            sentVar, escalation, topShare, seqResult.escalationScore, embeddingDiversity
        )
        val anomalyScore = computeAnomalyScore(featureVector)
        indicators["anomaly_score"] = anomalyScore

        // --- Aggregate: weighted combination ---
        val weights = mapOf(
            "echo_chamber" to 0.12f,
            "emotional_escalation" to 0.12f,
            "content_steering" to 0.10f,
            "creator_concentration" to 0.10f,
            "toxicity_level" to 0.10f,
            "persuasion_pressure" to 0.10f,
            "sequence_escalation" to 0.12f,
            "embedding_homogeneity" to 0.08f,
            "anomaly_score" to 0.12f,
            "funnel_detected" to 0.04f
        )
        val manipScore = indicators.entries.sumOf { (key, value) ->
            (value * (weights[key] ?: 0.05f)).toDouble()
        }.toFloat().coerceIn(0f, 1f)

        val breakdownJson = indicators.entries
            .joinToString(",", "{", "}") { (k, v) -> "\"$k\":${String.format("%.3f", v)}" }

        Log.d(TAG, "Session $sessionId: manip=${"%.3f".format(manipScore)}, " +
                "seq=${seqResult.escalationScore}, anomaly=${"%.3f".format(anomalyScore)}, " +
                "patterns=${seqResult.patterns}, funnels=${funnels.size}")

        return SessionMetrics(
            sessionId = sessionId,
            topicEntropy = topicEnt,
            creatorEntropy = creatorEnt,
            uniqueTopics = uniqueTopicCount,
            uniqueCreators = uniqueCreatorCount,
            avgSentiment = avgSent,
            sentimentVariance = sentVar,
            avgToxicity = avgTox,
            avgPersuasion = avgPers,
            emotionalEscalation = escalation,
            creatorConcentration = creatorConc,
            topTopicShare = topShare,
            manipulationScore = manipScore,
            indicatorBreakdown = breakdownJson
        )
    }

    /**
     * Compute semantic diversity of content using SBERT embeddings.
     * Returns average pairwise cosine distance (0=identical, 1=maximally diverse).
     */
    private fun computeEmbeddingDiversity(items: List<ContentItem>): Float {
        val engine = embeddingEngine ?: return 0.5f
        val texts = items.mapNotNull { it.textContent }.filter { it.isNotBlank() }
        if (texts.size < 2) return 0.5f

        // Sample at most 50 items for performance
        val sampled = if (texts.size > 50) texts.shuffled().take(50) else texts
        val embeddings = sampled.map { engine.embed(it) }
        return engine.averagePairwiseDistance(embeddings)
    }

    /**
     * Run Isolation Forest anomaly detection.
     * Trains on historical data if enough sessions exist.
     */
    private fun computeAnomalyScore(featureVector: FloatArray): Float {
        val forest = isolationForest ?: return 0.5f

        // Add to history
        historicalFeatures.add(featureVector.copyOf())

        // Train once we have enough data (retrain periodically)
        if (historicalFeatures.size >= 10 && !forest.isTrained) {
            forest.fit(historicalFeatures.toList())
        }

        return if (forest.isTrained) {
            forest.score(featureVector)
        } else {
            0.5f  // Neutral until trained
        }
    }

    fun destroy() {
        sequenceAnalyzer?.close()
        // Note: embeddingEngine is owned by ContentAnalyzer; do NOT close it here.
    }

    private fun parseJsonArray(json: String): List<String> {
        return json.trim().removeSurrounding("[", "]")
            .split(",")
            .map { it.trim().removeSurrounding("\"") }
            .filter { it.isNotBlank() }
    }

    private fun List<Float>.averageOrZero(): Float =
        if (isNotEmpty()) average().toFloat() else 0f
}
