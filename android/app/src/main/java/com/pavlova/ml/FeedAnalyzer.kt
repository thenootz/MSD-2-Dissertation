package com.pavlova.ml

import android.content.Context
import android.media.Image
import android.util.Log
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
    }

    private val db = PavlovaDatabase.getDatabase(context)
    private val sessionDao: FeedSessionDao = db.feedSessionDao()
    private val contentDao: ContentItemDao = db.contentItemDao()
    private val metricsDao: SessionMetricsDao = db.sessionMetricsDao()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var currentSession: FeedSession? = null
    private var itemPosition = 0
    private var lastFrameHash = 0
    private val creatorDetector = CreatorDetector()

    /** Row IDs of ContentItems we've inserted for the current video segment,
     *  so we can back-fill `creatorId` once the detector resolves it. */
    private val currentSegmentRowIds = mutableListOf<Long>()
    private var currentSegmentId: Long = -1L
    private var currentSegmentCreator: String? = null

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
        currentSegmentRowIds.clear()
        currentSegmentId = -1L
        currentSegmentCreator = null
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

            // Resolve creator using the per-segment tracker. Three cases:
            //   - new segmentId  → flush previous segment's rows; record this one.
            //   - same segment, locked creator    → use it.
            //   - same segment, not yet resolved  → store null; we'll back-fill
            //     onto this row once the detector locks in.
            val detection = creatorDetector.submit(ocr.blocks)
            if (detection.segmentId != currentSegmentId) {
                // Scrolled to a new video. The previous segment's rows are
                // already populated with whatever creator we knew at the time
                // of each insert — no further action needed for them.
                currentSegmentId = detection.segmentId
                currentSegmentRowIds.clear()
                currentSegmentCreator = detection.creator
            } else if (detection.creator != null && currentSegmentCreator == null) {
                // The detector just resolved the creator for this segment.
                // Back-fill every row we've already inserted for it.
                currentSegmentCreator = detection.creator
                if (currentSegmentRowIds.isNotEmpty()) {
                    contentDao.updateCreatorForIds(currentSegmentRowIds.toList(), detection.creator)
                    Log.d(TAG, "Back-filled creator='${detection.creator}' on ${currentSegmentRowIds.size} items (segment=${detection.segmentId})")
                }
            }
            val creator = currentSegmentCreator ?: analysis.creatorId

            // Store as ContentItem
            val item = ContentItem(
                sessionId = session.id,
                position = itemPosition++,
                textContent = analysis.extractedText,
                creatorId = creator,
                topicLabels = analysis.topicsAsJson(),
                sentimentScore = analysis.sentimentScore,
                emotionLabel = analysis.emotionLabel,
                toxicityScore = analysis.toxicityScore,
                persuasionScore = analysis.persuasionScore
            )
            val rowId = contentDao.insert(item)
            currentSegmentRowIds.add(rowId)

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
        } catch (e: Exception) {
            Log.e(TAG, "Error computing metrics", e)
        }
    }

    fun destroy() {
        scope.cancel()
    }
}
