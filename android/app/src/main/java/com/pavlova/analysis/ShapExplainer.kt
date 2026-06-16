package com.pavlova.analysis

import com.pavlova.data.model.SessionMetrics

/**
 * SHAP-inspired feature importance explainer for manipulation scores.
 *
 * Uses permutation-based importance: for each feature, shuffles its values
 * across the dataset and measures how much the manipulation score changes.
 * Features that cause the biggest score change when randomized are the
 * most important.
 *
 * Output: ordered list of (feature_name, importance_score, direction) tuples
 * that explain *why* a session was flagged.
 *
 * Example output:
 *   "Flagged because: echo_chamber (0.42, ↑) — topic diversity dropped 73%"
 *   "Flagged because: toxicity_level (0.31, ↑) — avg toxicity increased to 0.68"
 */
object ShapExplainer {

    /**
     * Feature definition: name + extractor from SessionMetrics.
     */
    private data class Feature(
        val name: String,
        val extract: (SessionMetrics) -> Float,
        val humanLabel: String
    )

    private val features = listOf(
        Feature("echo_chamber", { 1f - (it.topicEntropy / kotlin.math.ln(20f).toFloat()).coerceIn(0f, 1f) }, "Topic diversity"),
        Feature("emotional_escalation", { it.emotionalEscalation.coerceIn(0f, 1f) }, "Emotional intensity trend"),
        Feature("content_steering", { it.topTopicShare.coerceIn(0f, 1f) }, "Single-topic dominance"),
        Feature("creator_concentration", { it.creatorConcentration.coerceIn(0f, 1f) }, "Creator dominance"),
        Feature("toxicity_level", { it.avgToxicity.coerceIn(0f, 1f) }, "Average toxicity"),
        Feature("persuasion_pressure", { it.avgPersuasion.coerceIn(0f, 1f) }, "Persuasion language")
    )

    private val weights = mapOf(
        "echo_chamber" to 0.20f,
        "emotional_escalation" to 0.20f,
        "content_steering" to 0.15f,
        "creator_concentration" to 0.15f,
        "toxicity_level" to 0.15f,
        "persuasion_pressure" to 0.15f
    )

    /**
     * Explain a single session's manipulation score.
     * Returns feature contributions sorted by absolute importance.
     */
    fun explain(metrics: SessionMetrics): List<FeatureExplanation> {
        val explanations = mutableListOf<FeatureExplanation>()
        val baseScore = metrics.manipulationScore

        for (feature in features) {
            val featureValue = feature.extract(metrics)
            val weight = weights[feature.name] ?: 0.1f
            val contribution = featureValue * weight

            // Marginal contribution: score without this feature
            val scoreWithout = baseScore - contribution
            val importance = (baseScore - scoreWithout.coerceIn(0f, 1f))

            explanations.add(
                FeatureExplanation(
                    featureName = feature.name,
                    humanLabel = feature.humanLabel,
                    importance = importance,
                    featureValue = featureValue,
                    direction = if (featureValue > 0.5f) "high" else "normal",
                    contribution = contribution
                )
            )
        }

        return explanations.sortedByDescending { kotlin.math.abs(it.importance) }
    }

    /**
     * Permutation importance across multiple sessions.
     * For each feature, shuffle its values and measure prediction degradation.
     */
    fun permutationImportance(
        allMetrics: List<SessionMetrics>,
        numPermutations: Int = 10
    ): Map<String, Float> {
        if (allMetrics.isEmpty()) return emptyMap()

        val baseScores = allMetrics.map { it.manipulationScore }
        val baseMean = baseScores.average().toFloat()

        return features.associate { feature ->
            val importanceSum = (0 until numPermutations).sumOf {
                // Shuffle this feature's values
                val shuffledValues = allMetrics.map { feature.extract(it) }.shuffled()
                val permutedScores = allMetrics.indices.map { i ->
                    val original = feature.extract(allMetrics[i])
                    val shuffled = shuffledValues[i]
                    val weight = weights[feature.name] ?: 0.1f
                    allMetrics[i].manipulationScore - (original * weight) + (shuffled * weight)
                }
                val permutedMean = permutedScores.average().toFloat()
                kotlin.math.abs(baseMean - permutedMean).toDouble()
            }
            feature.name to (importanceSum / numPermutations).toFloat()
        }
    }

    /**
     * Generate a human-readable explanation string for a session.
     */
    fun generateSummary(metrics: SessionMetrics): String {
        val explanations = explain(metrics)
        if (metrics.manipulationScore < 0.3f) {
            return "No notable feed-shaping patterns detected."
        }

        val topFactors = explanations.take(3).filter { it.importance > 0.01f }
        if (topFactors.isEmpty()) return "Light feed-shaping detected."

        val lines = topFactors.map { exp ->
            val arrow = if (exp.direction == "high") "↑" else "→"
            "${exp.humanLabel} ($arrow ${String.format("%.0f%%", exp.featureValue * 100)})"
        }

        val severity = when {
            metrics.manipulationScore > 0.7f -> "Strong feed-shaping"
            metrics.manipulationScore > 0.4f -> "Moderate feed-shaping"
            else -> "Light feed-shaping"
        }

        return "$severity — shaped by: ${lines.joinToString(", ")}"
    }
}

/**
 * Explanation for a single feature's contribution to the manipulation score.
 */
data class FeatureExplanation(
    val featureName: String,
    val humanLabel: String,
    /** How much this feature affects the final score (signed) */
    val importance: Float,
    /** Raw feature value (0-1) */
    val featureValue: Float,
    /** "high" or "normal" */
    val direction: String,
    /** Weighted contribution to manipulation score */
    val contribution: Float
)
