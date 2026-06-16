package com.pavlova.analysis

import com.pavlova.data.model.ContentItem
import com.pavlova.data.model.FeedSession
import kotlin.math.abs

/**
 * Longitudinal analysis across completed sessions.
 *
 * Detects:
 *  - whether sessions are getting longer over time
 *  - whether usage frequency is increasing (session gaps shrinking)
 *  - whether creator concentration is increasing and which creators are growing
 */
object SessionTrendAnalyzer {

    data class CreatorGrowth(
        val creatorId: String,
        /** Share of items in the first session window where this creator appeared. */
        val firstShare: Float,
        /** Share in the latest session window. */
        val lastShare: Float,
        /** lastShare - firstShare (percentage points as 0..1 fraction). */
        val delta: Float
    )

    data class Report(
        val sessionCount: Int,
        val avgDurationMinutes: Float,
        /** + = sessions getting longer, - = shorter */
        val durationSlopeMinPerSession: Float,
        /** Relative change from first to last completed session duration. */
        val durationIncreasePct: Float,
        /**
         * + = gaps are increasing (less frequent use), - = gaps are shrinking
         * (more frequent use)
         */
        val gapSlopeHoursPerSession: Float,
        /** 0..1 composite score */
        val addictionScore: Float,
        val addictionLabel: String,
        val creatorConcentrationSlope: Float,
        val growingCreators: List<CreatorGrowth>
    )

    /**
     * Analyse completed sessions in chronological order.
     *
     * @param sessions Completed sessions sorted oldest -> newest
     * @param itemsBySession Preloaded content items per session id
     */
    fun analyze(
        sessions: List<FeedSession>,
        itemsBySession: Map<String, List<ContentItem>>
    ): Report? {
        if (sessions.size < 2) return null

        val durationsMin = sessions.map { session ->
            val end = session.endTime ?: session.startTime
            ((end - session.startTime).coerceAtLeast(0L) / 60000f)
        }
        val avgDuration = durationsMin.average().toFloat()
        val durationSlope = linearSlope(durationsMin)
        val firstDuration = durationsMin.first().coerceAtLeast(0.1f)
        val durationIncreasePct = ((durationsMin.last() - durationsMin.first()) / firstDuration) * 100f

        val gapsHours = sessions.zipWithNext { a, b ->
            ((b.startTime - a.startTime).coerceAtLeast(0L) / 3600000f)
        }
        val gapSlope = if (gapsHours.size >= 2) linearSlope(gapsHours) else 0f

        val creatorShareBySession = sessions.map { s ->
            creatorShares(itemsBySession[s.id].orEmpty())
        }
        val topCreatorShareSeries = creatorShareBySession.map { shares ->
            shares.maxByOrNull { it.value }?.value ?: 0f
        }
        val concentrationSlope = linearSlope(topCreatorShareSeries)

        val growingCreators = findGrowingCreators(creatorShareBySession, sessions.size)

        // Composite "addiction-like" signal:
        //  - longer sessions over time
        //  - sessions happening more frequently (gaps shrink => negative slope)
        //  - rising concentration on single creators
        val durationNorm = normalizePositive(durationSlope / 5f) // +5 min/session => strong
        val frequencyNorm = normalizePositive((-gapSlope) / 6f) // gaps shrinking by 6h/session => strong
        val concentrationNorm = normalizePositive(concentrationSlope / 0.08f) // +8pp/session => strong
        val score = (durationNorm * 0.45f + frequencyNorm * 0.25f + concentrationNorm * 0.30f)
            .coerceIn(0f, 1f)

        val label = when {
            score >= 0.7f -> "High"
            score >= 0.45f -> "Moderate"
            else -> "Low"
        }

        return Report(
            sessionCount = sessions.size,
            avgDurationMinutes = avgDuration,
            durationSlopeMinPerSession = durationSlope,
            durationIncreasePct = durationIncreasePct,
            gapSlopeHoursPerSession = gapSlope,
            addictionScore = score,
            addictionLabel = label,
            creatorConcentrationSlope = concentrationSlope,
            growingCreators = growingCreators
        )
    }

    private fun creatorShares(items: List<ContentItem>): Map<String, Float> {
        if (items.isEmpty()) return emptyMap()
        val total = items.size.toFloat()
        return items.mapNotNull { it.creatorId?.trim()?.takeIf(String::isNotBlank) }
            .groupingBy { it }
            .eachCount()
            .mapValues { (_, c) -> c / total }
    }

    private fun findGrowingCreators(
        creatorShareBySession: List<Map<String, Float>>,
        sessionCount: Int
    ): List<CreatorGrowth> {
        val creators = creatorShareBySession.flatMap { it.keys }.toSet()
        if (creators.isEmpty()) return emptyList()

        val growth = creators.mapNotNull { creator ->
            val series = FloatArray(sessionCount) { idx ->
                creatorShareBySession[idx][creator] ?: 0f
            }.toList()
            val slope = linearSlope(series)
            if (slope <= 0.005f) return@mapNotNull null // require at least 0.5pp/session trend

            val first = series.first { it > 0f }
            val last = series.last()
            CreatorGrowth(
                creatorId = creator,
                firstShare = first,
                lastShare = last,
                delta = (last - first).coerceAtLeast(0f)
            ) to slope
        }

        return growth
            .sortedByDescending { it.second }
            .take(3)
            .map { it.first }
    }

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
        return if (denominator > 0f) numerator / denominator else 0f
    }

    private fun normalizePositive(value: Float): Float {
        if (value <= 0f) return 0f
        // Soft clamp so one extreme metric doesn't fully dominate.
        return (1f - 1f / (1f + abs(value))).coerceIn(0f, 1f)
    }
}

