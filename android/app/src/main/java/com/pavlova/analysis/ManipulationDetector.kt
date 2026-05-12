package com.pavlova.analysis

import com.pavlova.data.model.ContentItem
import com.pavlova.data.model.SessionMetrics

/**
 * Computes manipulation risk indicators for a feed session.
 * Each indicator is scored 0.0-1.0 and combined into an aggregate manipulation score.
 *
 * Indicators:
 * - Echo chamber: topic diversity dropping below threshold
 * - Emotional escalation: monotonic increase in sentiment intensity
 * - Content steering: significant shift toward one topic cluster
 * - Engagement trap: high-emotion content ratio increasing
 * - Addiction pattern: watch time per item increasing
 */
object ManipulationDetector {

    /**
     * Compute all manipulation indicators and return SessionMetrics.
     */
    fun analyze(sessionId: String, items: List<ContentItem>): SessionMetrics {
        if (items.isEmpty()) {
            return SessionMetrics(sessionId = sessionId)
        }

        // Diversity metrics
        val topicEnt = DriftAnalyzer.topicEntropy(items)
        val creatorEnt = DriftAnalyzer.shannonEntropy(
            items.mapNotNull { it.creatorId }
        )
        val uniqueTopicCount = items.mapNotNull { it.topicLabels }
            .flatMap { parseJsonArray(it) }.toSet().size
        val uniqueCreatorCount = items.mapNotNull { it.creatorId }.toSet().size

        // Emotional metrics
        val sentiments = items.mapNotNull { it.sentimentScore }
        val avgSent = if (sentiments.isNotEmpty()) sentiments.average().toFloat() else 0f
        val sentVar = if (sentiments.size > 1) {
            val mean = sentiments.average()
            sentiments.map { (it - mean) * (it - mean) }.average().toFloat()
        } else 0f

        val toxicities = items.mapNotNull { it.toxicityScore }
        val avgTox = if (toxicities.isNotEmpty()) toxicities.average().toFloat() else 0f

        val persuasions = items.mapNotNull { it.persuasionScore }
        val avgPers = if (persuasions.isNotEmpty()) persuasions.average().toFloat() else 0f

        val escalation = DriftAnalyzer.emotionalEscalation(items)

        // Concentration
        val creatorConc = DriftAnalyzer.creatorConcentration(items)
        val topShare = DriftAnalyzer.topTopicShare(items)

        // Compute individual manipulation indicators
        val indicators = mutableMapOf<String, Float>()

        // 1. Echo chamber: low topic entropy relative to item count
        val expectedEntropy = if (items.size > 1) kotlin.math.ln(items.size.toFloat().coerceAtMost(20f)) else 1f
        indicators["echo_chamber"] = (1f - (topicEnt / expectedEntropy).coerceIn(0f, 1f))

        // 2. Emotional escalation: positive slope of |sentiment| over time
        indicators["emotional_escalation"] = escalation.coerceIn(0f, 1f)

        // 3. Content steering: single topic dominates
        indicators["content_steering"] = topShare.coerceIn(0f, 1f)

        // 4. Creator concentration: few creators dominate feed
        indicators["creator_concentration"] = creatorConc.coerceIn(0f, 1f)

        // 5. High toxicity: average toxicity is elevated
        indicators["toxicity_level"] = avgTox.coerceIn(0f, 1f)

        // 6. Persuasion pressure: average persuasion is elevated
        indicators["persuasion_pressure"] = avgPers.coerceIn(0f, 1f)

        // Aggregate: weighted average of indicators
        val weights = mapOf(
            "echo_chamber" to 0.20f,
            "emotional_escalation" to 0.20f,
            "content_steering" to 0.15f,
            "creator_concentration" to 0.15f,
            "toxicity_level" to 0.15f,
            "persuasion_pressure" to 0.15f
        )
        val manipScore = indicators.entries.sumOf { (key, value) ->
            (value * (weights[key] ?: 0.1f)).toDouble()
        }.toFloat().coerceIn(0f, 1f)

        // Serialize indicators to JSON
        val breakdownJson = indicators.entries
            .joinToString(",", "{", "}") { (k, v) -> "\"$k\":${String.format("%.3f", v)}" }

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

    private fun parseJsonArray(json: String): List<String> {
        return json.trim().removeSurrounding("[", "]")
            .split(",")
            .map { it.trim().removeSurrounding("\"") }
            .filter { it.isNotBlank() }
    }
}
