package com.pavlova.ml

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.util.Log
import com.pavlova.analysis.ManipulationDetector
import com.pavlova.data.dao.ContentItemDao
import com.pavlova.data.dao.FeedSessionDao
import com.pavlova.data.dao.SessionMetricsDao
import com.pavlova.data.database.PavlovaDatabase
import com.pavlova.data.model.ContentItem
import com.pavlova.data.model.FeedSession
import kotlinx.coroutines.*
import java.nio.ByteBuffer

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

    /**
     * Start a new auditing session.
     */
    suspend fun startSession(platform: String = "tiktok"): FeedSession {
        val session = FeedSession(platform = platform)
        sessionDao.insert(session)
        currentSession = session
        itemPosition = 0
        lastFrameHash = 0
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

        try {
            // Run content analysis pipeline
            val analysis = ContentAnalyzer.analyze(imageData, width, height)

            // Skip frames with no text content (transitions, loading screens)
            if (analysis.extractedText.isBlank()) return@withContext

            // Store as ContentItem
            val item = ContentItem(
                sessionId = session.id,
                position = itemPosition++,
                textContent = analysis.extractedText,
                creatorId = analysis.creatorId,
                topicLabels = analysis.topicsAsJson(),
                sentimentScore = analysis.sentimentScore,
                emotionLabel = analysis.emotionLabel,
                toxicityScore = analysis.toxicityScore,
                persuasionScore = analysis.persuasionScore
            )
            contentDao.insert(item)

            Log.d(TAG, "Item #$itemPosition: topics=${analysis.topics}, " +
                    "sentiment=${analysis.sentimentScore}, ${analysis.processingTimeMs}ms")

            // Periodically compute metrics
            if (itemPosition % METRICS_INTERVAL == 0) {
                scope.launch { computeMetrics(session.id) }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error processing frame", e)
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
