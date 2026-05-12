package com.pavlova.analysis

import com.pavlova.data.model.ContentItem
import kotlin.math.ln

/**
 * Computes diversity and drift metrics for a sequence of content items.
 * Pure Kotlin — no ML models required.
 */
object DriftAnalyzer {

    /**
     * Shannon entropy of a categorical distribution.
     * H = -Σ p_i * ln(p_i)
     * Returns 0 if list is empty, higher values = more diverse.
     */
    fun shannonEntropy(labels: List<String>): Float {
        if (labels.isEmpty()) return 0f
        val counts = labels.groupingBy { it }.eachCount()
        val total = labels.size.toFloat()
        return -counts.values.sumOf { count ->
            val p = count / total.toDouble()
            if (p > 0) p * ln(p) else 0.0
        }.toFloat()
    }

    /**
     * Gini coefficient for concentration measurement.
     * 0 = perfectly equal distribution, 1 = one item dominates completely.
     */
    fun giniCoefficient(values: List<Int>): Float {
        if (values.isEmpty() || values.sum() == 0) return 0f
        val sorted = values.sorted()
        val n = sorted.size
        val total = sorted.sum().toFloat()
        var sumOfDifferences = 0f
        for (i in sorted.indices) {
            for (j in sorted.indices) {
                sumOfDifferences += kotlin.math.abs(sorted[i] - sorted[j])
            }
        }
        return sumOfDifferences / (2f * n * total)
    }

    /**
     * Detect emotional escalation: is sentiment intensity increasing over the sequence?
     * Returns slope of |sentiment| over time. Positive = escalating.
     */
    fun emotionalEscalation(items: List<ContentItem>): Float {
        val intensities = items.mapNotNull { it.sentimentScore?.let { s -> kotlin.math.abs(s) } }
        if (intensities.size < 2) return 0f
        return linearSlope(intensities)
    }

    /**
     * Compute topic entropy for a session's content items.
     */
    fun topicEntropy(items: List<ContentItem>): Float {
        val topics = items.mapNotNull { it.topicLabels }
            .flatMap { parseJsonArray(it) }
        return shannonEntropy(topics)
    }

    /**
     * Compute creator concentration (Gini) for a session.
     */
    fun creatorConcentration(items: List<ContentItem>): Float {
        val creatorCounts = items.mapNotNull { it.creatorId }
            .groupingBy { it }.eachCount().values.toList()
        return giniCoefficient(creatorCounts)
    }

    /**
     * Fraction of items belonging to the most common topic.
     */
    fun topTopicShare(items: List<ContentItem>): Float {
        val topics = items.mapNotNull { it.topicLabels }
            .flatMap { parseJsonArray(it) }
        if (topics.isEmpty()) return 0f
        val maxCount = topics.groupingBy { it }.eachCount().values.maxOrNull() ?: 0
        return maxCount / topics.size.toFloat()
    }

    /**
     * Simple linear regression slope for a list of values.
     * Treats index as x, value as y.
     */
    private fun linearSlope(values: List<Float>): Float {
        val n = values.size
        if (n < 2) return 0f
        val xMean = (n - 1) / 2f
        val yMean = values.average().toFloat()
        var numerator = 0f
        var denominator = 0f
        for (i in values.indices) {
            val dx = i - xMean
            val dy = values[i] - yMean
            numerator += dx * dy
            denominator += dx * dx
        }
        return if (denominator > 0) numerator / denominator else 0f
    }

    /**
     * Parse a simple JSON array string like ["a","b","c"] into a list.
     */
    private fun parseJsonArray(json: String): List<String> {
        return json.trim().removeSurrounding("[", "]")
            .split(",")
            .map { it.trim().removeSurrounding("\"") }
            .filter { it.isNotBlank() }
    }
}
