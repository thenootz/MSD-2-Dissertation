package com.pavlova.analysis

import com.pavlova.data.model.SessionMetrics
import com.pavlova.overlay.OverlayManager

/**
 * Translates a computed [SessionMetrics] (and optional live [SessionContext])
 * into user-facing wellbeing alerts.
 *
 * These are intentionally soft, non-clinical nudges shown as on-screen
 * banners while the user keeps scrolling — e.g. "your feed is getting
 * heavier" rather than a hard "manipulation detected" verdict.
 *
 * Two families of alert are produced:
 *  - **Metric-based** (toxicity, feed-shaping, isolation) from [SessionMetrics].
 *  - **Behaviour/time-based** (screen time, session length, creator repetition,
 *    binge volume) from the runtime [SessionContext].
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

    /**
     * Runtime session signals that aren't captured in [SessionMetrics] — used
     * for time/behaviour-based alerts. Supplied by
     * [com.pavlova.ml.FeedAnalyzer].
     */
    data class SessionContext(
        val elapsedMs: Long,
        val itemCount: Int,
        val videoCount: Int,
        /** Average duration of past completed sessions, or null if unknown. */
        val avgSessionDurationMs: Long?,
        /** Most-seen creator handle this session (without '@'), or null. */
        val topCreator: String?,
        /** Fraction (0..1) of items from [topCreator]. */
        val topCreatorShare: Float
    )

    // --- Metric thresholds — tuned to be informative, not alarmist. ---
    private const val TOXICITY_WARN = 0.45f
    private const val TOXICITY_CRIT = 0.65f
    private const val INFLUENCE_WARN = 0.55f
    private const val INFLUENCE_CRIT = 0.75f
    private const val ISOLATION_WARN = 0.60f
    private const val ISOLATION_CRIT = 0.80f

    // --- Behaviour/time thresholds ---
    private val SCREEN_TIME_MILESTONES = listOf(
        5 to OverlayManager.Level.INFO,
        15 to OverlayManager.Level.INFO,
        30 to OverlayManager.Level.WARNING,
        45 to OverlayManager.Level.WARNING,
        60 to OverlayManager.Level.CRITICAL
    )
    private const val LONG_SESSION_RATIO = 1.25f
    private const val MIN_AVG_FOR_COMPARISON_MS = 3 * 60_000L
    private const val REPEAT_CREATOR_WARN = 0.40f
    private const val REPEAT_CREATOR_CRIT = 0.60f
    private const val BINGE_WARN = 40
    private const val BINGE_CRIT = 80

    fun evaluate(metrics: SessionMetrics, context: SessionContext? = null): List<Alert> {
        val alerts = mutableListOf<Alert>()
        addMetricAlerts(metrics, alerts)
        if (context != null) addBehaviorAlerts(context, alerts)
        return alerts
    }

    private fun addMetricAlerts(metrics: SessionMetrics, alerts: MutableList<Alert>) {
        // --- Toxicity --------------------------------------------------
        val tox = metrics.avgToxicity
        if (tox >= TOXICITY_WARN) {
            alerts += Alert(
                key = "toxicity",
                title = "Heavy content in your feed",
                body = "About ${pct(tox)} of recent posts read as hostile or negative. Consider a break.",
                level = if (tox >= TOXICITY_CRIT) OverlayManager.Level.CRITICAL else OverlayManager.Level.WARNING
            )
        }

        // --- Feed shaping / influence ---------------------------------
        val infl = metrics.manipulationScore
        if (infl >= INFLUENCE_WARN) {
            alerts += Alert(
                key = "influence",
                title = "Your feed is being shaped",
                body = "Strong steering toward specific topics/emotions (${pct(infl)}).",
                level = if (infl >= INFLUENCE_CRIT) OverlayManager.Level.CRITICAL else OverlayManager.Level.WARNING
            )
        }

        // --- Isolation / echo chamber ---------------------------------
        // Derived from how concentrated the feed is on a few creators /
        // a single topic. Both range 0..1; we take the stronger signal.
        val isolation = maxOf(metrics.creatorConcentration, metrics.topTopicShare)
        if (isolation >= ISOLATION_WARN) {
            alerts += Alert(
                key = "isolation",
                title = "Your feed is narrowing",
                body = "It's clustering around a few creators/topics (${pct(isolation)}) — an echo-chamber pattern.",
                level = if (isolation >= ISOLATION_CRIT) OverlayManager.Level.CRITICAL else OverlayManager.Level.WARNING
            )
        }
    }

    private fun addBehaviorAlerts(ctx: SessionContext, alerts: MutableList<Alert>) {
        // --- Screen-time milestones -----------------------------------
        val minutes = (ctx.elapsedMs / 60_000L).toInt()
        SCREEN_TIME_MILESTONES.lastOrNull { minutes >= it.first }?.let { (mark, level) ->
            alerts += Alert(
                key = "screen_time_$mark",
                title = "Screen time: $mark minutes",
                body = "You've been scrolling for $mark+ minutes (${ctx.videoCount} videos). Time for a break?",
                level = level
            )
        }

        // --- Longer than your average ---------------------------------
        val avg = ctx.avgSessionDurationMs
        if (avg != null && avg >= MIN_AVG_FOR_COMPARISON_MS &&
            ctx.elapsedMs > avg * LONG_SESSION_RATIO
        ) {
            val pctLonger = ((ctx.elapsedMs.toFloat() / avg) - 1f) * 100f
            alerts += Alert(
                key = "long_session",
                title = "Longer than your usual",
                body = "This session is already ${pctLonger.toInt()}% longer than your average.",
                level = OverlayManager.Level.WARNING
            )
        }

        // --- Same creators on repeat ----------------------------------
        if (ctx.topCreatorShare >= REPEAT_CREATOR_WARN) {
            val who = ctx.topCreator?.let { "@$it" } ?: "one creator"
            alerts += Alert(
                key = "repeat_creator",
                title = "Seeing a lot of $who",
                body = "${pct(ctx.topCreatorShare)} of recent videos are from $who. Your feed may be narrowing.",
                level = if (ctx.topCreatorShare >= REPEAT_CREATOR_CRIT)
                    OverlayManager.Level.CRITICAL else OverlayManager.Level.WARNING
            )
        }

        // --- Binge volume ---------------------------------------------
        if (ctx.videoCount >= BINGE_WARN) {
            alerts += Alert(
                key = "binge_videos",
                title = "You've watched ${ctx.videoCount} videos",
                body = "That's a lot of short videos in one sitting. A good moment to pause?",
                level = if (ctx.videoCount >= BINGE_CRIT)
                    OverlayManager.Level.WARNING else OverlayManager.Level.INFO
            )
        }
    }

    private fun pct(value: Float): String = "${(value * 100).toInt()}%"
}
