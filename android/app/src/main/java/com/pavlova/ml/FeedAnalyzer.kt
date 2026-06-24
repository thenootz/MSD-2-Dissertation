package com.pavlova.ml

import android.content.Context
import android.media.Image
import android.util.Log
import com.pavlova.analysis.FeedAlerts
import com.pavlova.analysis.ManipulationDetector
import com.pavlova.data.AppSettings
import com.pavlova.data.ScreenshotStore
import com.pavlova.data.dao.ContentItemDao
import com.pavlova.data.dao.FeedSessionDao
import com.pavlova.data.dao.SessionMetricsDao
import com.pavlova.data.database.PavlovaDatabase
import com.pavlova.data.model.ContentItem
import com.pavlova.data.model.FeedSession
import com.pavlova.debug.DebugCaptureStore
import com.pavlova.overlay.AlertNotifier
import com.pavlova.overlay.OverlayManager
import com.pavlova.services.ScrollSignal
import kotlinx.coroutines.*

/**
 * Orchestrates the feed auditing pipeline:
 *   Frame capture → OCR → NLP analysis → Store → Drift metrics
 *
 * Replaces the old FrameProcessor (NSFW filter).
 */
class FeedAnalyzer(context: Context) {

    companion object {
        private const val TAG = "FeedAnalyzer"
        private const val METRICS_INTERVAL = 10 // Recompute metrics every N items
        private const val ALERT_COOLDOWN_MS = 45_000L // Don't repeat the same alert too often
        private const val SCROLL_HINT_WINDOW_MS = 1_200L // Treat a11y scrolls within this window as recent
    }

    private val db = PavlovaDatabase.getDatabase(context)
    private val sessionDao: FeedSessionDao = db.feedSessionDao()
    private val contentDao: ContentItemDao = db.contentItemDao()
    private val metricsDao: SessionMetricsDao = db.sessionMetricsDao()

    private val overlay = OverlayManager(context)
    private val notifier = AlertNotifier(context)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var currentSession: FeedSession? = null
    private var itemPosition = 0
    private var lastFrameHash = 0
    private val creatorDetector = CreatorDetector()
    private val videoSegmenter = VideoSegmenter()

    /** Last time each alert type was shown, for per-type cooldown. */
    private val lastAlertAt = mutableMapOf<String, Long>()

    // --- Per-video grouping state ---------------------------------------
    /** 0-based index of the video currently being watched (−1 before first). */
    private var currentVideoIndex = -1
    /** Row IDs inserted for the current video, so we can back-fill creatorId. */
    private val currentVideoRowIds = mutableListOf<Long>()
    /** Resolved creator for the current video, once the detector locks in. */
    private var currentVideoCreator: String? = null

    /**
     * Start a new auditing session.
     */
    suspend fun startSession(platform: String = "tiktok"): FeedSession {
        val session = FeedSession(platform = platform)
        sessionDao.insert(session)
        currentSession = session
        itemPosition = 0
        lastFrameHash = 0
        creatorDetector.reset()
        videoSegmenter.reset()
        currentVideoIndex = -1
        currentVideoRowIds.clear()
        currentVideoCreator = null
        Log.d(TAG, "Session started: ${session.id}")
        return session
    }

    /**
     * End the current session and compute final metrics.
     */
    suspend fun endSession() {
        val session = currentSession ?: return
        val updatedSession = session.copy(
            endTime = System.currentTimeMillis(),
            totalItems = itemPosition
        )
        sessionDao.update(updatedSession)

        // Compute final metrics
        computeMetrics(session.id)

        overlay.hide()
        currentSession = null
        Log.d(TAG, "Session ended: ${session.id}, items=$itemPosition")
    }

    /**
     * Process a captured frame from raw RGBA data.
     * Detects if the frame is new (content changed), then analyzes it.
     */
    suspend fun processFrame(imageData: ByteArray, width: Int, height: Int) = withContext(Dispatchers.Default) {
        val session = currentSession ?: return@withContext

        // Simple frame deduplication: skip if frame hash matches previous
        val frameHash = imageData.contentHashCode()
        if (frameHash == lastFrameHash) return@withContext
        lastFrameHash = frameHash

        val bitmap = TextExtractor.bitmapFromRgba(imageData, width, height)
        try {
            // OCR once, share the result between the NLP pipeline and the
            // creator detector so we don't pay for two passes per frame.
            val ocr = TextExtractor.extractStructured(bitmap)
            if (ocr.fullText.isBlank()) return@withContext

            val analysis = ContentAnalyzer.analyzeText(ocr.fullText)

            // --- Video segmentation from scroll behaviour -----------------
            // A scroll to the next video produces a large full-frame change,
            // while a tap/like/comment barely changes the frame. Combined with
            // a bottom-band text change (rejects in-video scene cuts), this
            // tells us when a new short video begins.
            val bottomKeys = ocr.blocks
                .filter { it.cy in 0.55f..0.97f }
                .map { it.text.lowercase().trim() }
                .filter { it.isNotEmpty() }
                .toSet()
            val signature = VideoSegmenter.signatureFromRgba(imageData, width, height)
            // Fuse the visual segmenter with the optional accessibility scroll
            // detector: if a real scroll was observed in the last ~1.2s, treat
            // this frame as scroll-like (the bottom-band guard + cooldown still
            // prevent a single fling from producing multiple boundaries).
            val a11yScrollRecent = ScrollSignal.isActive() &&
                ScrollSignal.millisSinceLastScroll() < SCROLL_HINT_WINDOW_MS
            val isNewVideo = videoSegmenter.update(signature, bottomKeys, a11yScrollRecent)
            if (isNewVideo) {
                currentVideoIndex += 1
                currentVideoRowIds.clear()
                currentVideoCreator = null
                creatorDetector.reset()
                Log.d(TAG, "New video #$currentVideoIndex (frameDiff=${"%.3f".format(videoSegmenter.lastFrameDiff)}, " +
                        "vShift=${videoSegmenter.lastVerticalShift}, a11y=$a11yScrollRecent)")
            }

            // Resolve the creator for the current video (stable across frames).
            val detectedCreator = creatorDetector.submit(ocr.blocks)
            if (detectedCreator != null && currentVideoCreator == null) {
                // Detector just locked in — back-fill earlier frames of this video.
                currentVideoCreator = detectedCreator
                if (currentVideoRowIds.isNotEmpty()) {
                    contentDao.updateCreatorForIds(currentVideoRowIds.toList(), detectedCreator)
                    Log.d(TAG, "Back-filled creator='$detectedCreator' on ${currentVideoRowIds.size} items (video #$currentVideoIndex)")
                }
            }
            val creator = currentVideoCreator ?: detectedCreator ?: analysis.creatorId

            // Store as ContentItem, tagged with its video index.
            val item = ContentItem(
                sessionId = session.id,
                position = itemPosition++,
                videoIndex = currentVideoIndex,
                textContent = analysis.extractedText,
                creatorId = creator,
                topicLabels = analysis.topicsAsJson(),
                sentimentScore = analysis.sentimentScore,
                emotionLabel = analysis.emotionLabel,
                toxicityScore = analysis.toxicityScore,
                persuasionScore = analysis.persuasionScore
            )
            val rowId = contentDao.insert(item)
            currentVideoRowIds.add(rowId)

            // Verbose/demo mode: persist a downscaled thumbnail of the frame so
            // the session detail screen can render previews. In default
            // (privacy-first) mode this branch is skipped and no raw screen
            // content is stored.
            if (AppSettings.verboseMode) {
                val screenshotPath = ScreenshotStore.save(session.id, rowId, bitmap)
                if (screenshotPath != null) {
                    contentDao.update(item.copy(id = rowId, screenshotPath = screenshotPath))
                }
            }

            // Optional developer debug capture (separate toggle, off in release by default)
            DebugCaptureStore.save(bitmap, analysis.extractedText)

            Log.d(TAG, "Item #$itemPosition: topics=${analysis.topics}, " +
                    "sentiment=${analysis.sentimentScore}, creator=$creator, ${analysis.processingTimeMs}ms")

            // Periodically compute metrics
            if (itemPosition % METRICS_INTERVAL == 0) {
                scope.launch { computeMetrics(session.id) }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error processing frame", e)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Process from an Android Image object (from ImageReader).
     */
    suspend fun processFrame(image: Image) {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        buffer.rewind()
        processFrame(bytes, image.width, image.height)
    }

    /**
     * Compute drift and manipulation metrics for a session.
     */
    private suspend fun computeMetrics(sessionId: String) {
        try {
            val items = contentDao.getItemsForSessionSync(sessionId)
            if (items.isEmpty()) return

            val metrics = ManipulationDetector.analyze(sessionId, items)
            metricsDao.insert(metrics)

            Log.d(TAG, "Metrics for $sessionId: manipulation=${metrics.manipulationScore}, " +
                    "topicEntropy=${metrics.topicEntropy}, escalation=${metrics.emotionalEscalation}")

            maybeShowAlerts(metrics, items)
        } catch (e: Exception) {
            Log.e(TAG, "Error computing metrics", e)
        }
    }

    /**
     * Evaluate wellbeing thresholds and surface an alert when crossed. Respects
     * the user's [AppSettings.alertsEnabled] toggle and a per-type cooldown.
     * Builds a [FeedAlerts.SessionContext] so time/behaviour-based alerts (screen
     * time, session length, creator repetition, binge volume) can fire alongside
     * the metric-based ones.
     *
     * Delivery: the on-screen [OverlayManager] banner is preferred, but when the
     * draw-over-other-apps permission is missing we fall back to a system
     * notification via [AlertNotifier] so alerts still reach the user.
     */
    private suspend fun maybeShowAlerts(
        metrics: com.pavlova.data.model.SessionMetrics,
        items: List<ContentItem>
    ) {
        if (!AppSettings.alertsEnabled) return

        val canOverlay = overlay.canDrawOverlays()
        val canNotify = notifier.canNotify()
        // No delivery channel available — nothing to do.
        if (!canOverlay && !canNotify) return

        val session = currentSession
        val context = if (session != null) {
            val creatorCounts = items
                .mapNotNull { it.creatorId?.trim()?.takeIf(String::isNotBlank) }
                .groupingBy { it }.eachCount()
            val top = creatorCounts.maxByOrNull { it.value }
            val topShare =
                if (items.isNotEmpty() && top != null) top.value.toFloat() / items.size else 0f
            val avgDuration = runCatching {
                sessionDao.getAverageCompletedDurationMs(session.id)
            }.getOrNull()?.toLong()

            FeedAlerts.SessionContext(
                elapsedMs = System.currentTimeMillis() - session.startTime,
                itemCount = items.size,
                videoCount = currentVideoIndex + 1,
                avgSessionDurationMs = avgDuration,
                topCreator = top?.key,
                topCreatorShare = topShare
            )
        } else null

        val now = System.currentTimeMillis()
        val due = FeedAlerts.evaluate(metrics, context).filter { alert ->
            now - (lastAlertAt[alert.key] ?: 0L) > ALERT_COOLDOWN_MS
        }
        if (due.isEmpty()) return

        // Show the single most severe due alert (avoid stacking).
        val top = due.maxByOrNull { it.level.ordinal } ?: return
        due.forEach { lastAlertAt[it.key] = now }

        if (canOverlay) {
            overlay.showAlert(top.title, top.body, top.level)
            Log.d(TAG, "Alert shown (overlay): ${top.key} (${top.level})")
        } else {
            // Overlay permission missing — fall back to a system notification.
            notifier.notify(top.key, top.title, top.body, top.level)
            Log.d(TAG, "Alert shown (notification): ${top.key} (${top.level})")
        }
    }

    fun destroy() {
        overlay.cleanup()
        scope.cancel()
    }
}
