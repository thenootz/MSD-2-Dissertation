package com.pavlova.analysis

import com.pavlova.data.model.SessionMetrics
import com.pavlova.overlay.OverlayManager

/**
 * Translates a computed [SessionMetrics] into user-facing wellbeing alerts.
 *
 * These are intentionally soft, non-clinical nudges shown as on-screen
 * banners while the user keeps scrolling — e.g. "your feed is getting
 * heavier" rather than a hard "manipulation detected" verdict.
 *
 * Each [Alert] carries a stable [key] so the caller can apply a per-type
 * cooldown and avoid spamming the same banner.
 */
object FeedAlerts {

    data class Alert(
        val key: String,
        val title: String,
        val body: String,
        val level: OverlayManager.Level
    )

    // Thresholds — tuned to be informative, not alarmist.
    private const val TOXICITY_WARN = 0.45f
    private const val TOXICITY_CRIT = 0.65f
    private const val INFLUENCE_WARN = 0.55f
    private const val INFLUENCE_CRIT = 0.75f
    private const val ISOLATION_WARN = 0.60f
    private const val ISOLATION_CRIT = 0.80f

    fun evaluate(metrics: SessionMetrics): List<Alert> {
        val alerts = mutableListOf<Alert>()

        // --- Toxicity --------------------------------------------------
        val tox = metrics.avgToxicity
        if (tox >= TOXICITY_WARN) {
            val level = if (tox >= TOXICITY_CRIT) OverlayManager.Level.CRITICAL else OverlayManager.Level.WARNING
            alerts += Alert(
                key = "toxicity",
                title = "Heavy content in your feed",
                body = "About ${pct(tox)} of recent posts read as hostile or negative. Consider a break.",
                level = level
            )
        }

        // --- Feed shaping / influence ---------------------------------
        val infl = metrics.manipulationScore
        if (infl >= INFLUENCE_WARN) {
            val level = if (infl >= INFLUENCE_CRIT) OverlayManager.Level.CRITICAL else OverlayManager.Level.WARNING
            alerts += Alert(
                key = "influence",
                title = "Your feed is being shaped",
                body = "Strong steering toward specific topics/emotions (${pct(infl)}).",
                level = level
            )
        }

        // --- Isolation / echo chamber ---------------------------------
        // Derived from how concentrated the feed is on a few creators /
        // a single topic. Both range 0..1; we take the stronger signal.
        val isolation = maxOf(metrics.creatorConcentration, metrics.topTopicShare)
        if (isolation >= ISOLATION_WARN) {
            val level = if (isolation >= ISOLATION_CRIT) OverlayManager.Level.CRITICAL else OverlayManager.Level.WARNING
            alerts += Alert(
                key = "isolation",
                title = "Your feed is narrowing",
                body = "It's clustering around a few creators/topics (${pct(isolation)}) — an echo-chamber pattern.",
                level = level
            )
        }

        return alerts
    }

    private fun pct(value: Float): String = "${(value * 100).toInt()}%"
}
