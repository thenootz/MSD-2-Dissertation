package com.pavlova.ml

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.util.Log
import com.pavlova.data.model.FilterEvent
import com.pavlova.data.repository.FilterEventRepository
import com.pavlova.overlay.OverlayManager
import kotlinx.coroutines.*
import java.nio.ByteBuffer

class FrameProcessor(private val context: Context) {

    companion object {
        private const val TAG = "FrameProcessor"
        
        // Threshold for triggering filter on unsafe content
        private const val UNSAFE_THRESHOLD = 0.60f
        
        // Threshold for suggestive (sexy) content — more lenient
        private const val SUGGESTIVE_THRESHOLD = 0.80f
    }

    private val overlayManager = OverlayManager(context)
    private val processingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    // Repository for logging events (optional)
    private var repository: FilterEventRepository? = null

    /**
     * Process a captured frame
     */
    suspend fun processFrame(image: Image) = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()
        
        try {
            // Convert Image to byte array
            val imageData = imageToByteArray(image)
            val width = image.width
            val height = image.height
            
            // Call Rust ML inference
            val result = RustMLBridge.classifyFrame(imageData, width, height)
            
            val inferenceTime = System.currentTimeMillis() - startTime
            
            // Check if content should be filtered
            val shouldFilter = when {
                // Adult content (porn/hentai): filter if above threshold
                result.isAdult && result.confidence >= UNSAFE_THRESHOLD -> true
                // Suggestive content (sexy): higher threshold required
                result.isSuggestive && result.confidence >= SUGGESTIVE_THRESHOLD -> true
                // Safe content (neutral/drawing): don't filter
                result.isSafe -> false
                // Catch-all: filter if unsafe score is high
                !result.isSafe && result.confidence >= UNSAFE_THRESHOLD -> true
                else -> false
            }
            
            if (!shouldFilter) {
                // Hide overlay for safe content
                overlayManager.hideOverlay()
            } else {
                // Generate blur effect using Rust
                val blurRadius = calculateBlurRadius(result)
                val blurredData = RustMLBridge.generateBlur(
                    imageData,
                    width,
                    height,
                    blurRadius
                )
                
                // Convert to Bitmap and show overlay
                val blurredBitmap = byteArrayToBitmap(blurredData, width, height)
                overlayManager.showOverlay(blurredBitmap)
                
                // Log filter event (privacy-preserving)
                logFilterEvent(result.category, result.confidence)
            }
            
            val totalTime = System.currentTimeMillis() - startTime
            Log.v(TAG, "Frame processed in ${totalTime}ms (inference: ${inferenceTime}ms)")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing frame", e)
        }
    }

    /**
     * Convert Android Image to byte array (RGBA format)
     */
    private fun imageToByteArray(image: Image): ByteArray {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        buffer.rewind()
        return bytes
    }

    /**
     * Convert byte array back to Bitmap
     */
    private fun byteArrayToBitmap(data: ByteArray, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val buffer = ByteBuffer.wrap(data)
        bitmap.copyPixelsFromBuffer(buffer)
        return bitmap
    }

    /**
     * Calculate blur radius based on content category and confidence
     * Adult content gets stronger blur than suggestive content
     */
    private fun calculateBlurRadius(result: ClassificationResult): Float {
        val baseRadius = when {
            result.isAdult -> when {
                result.confidence > 0.95f -> 25f  // Very strong blur for confident adult
                result.confidence > 0.85f -> 20f
                result.confidence > 0.75f -> 15f
                else -> 12f
            }
            result.isSuggestive -> when {
                result.confidence > 0.95f -> 15f  // Moderate blur for suggestive
                result.confidence > 0.85f -> 10f
                else -> 8f
            }
            else -> 5f  // Light blur as fallback
        }
        return baseRadius
    }

    /**
     * Log filter event (privacy-preserving: no screenshots)
     */
    private suspend fun logFilterEvent(category: String, confidence: Float) {
        try {
            repository?.let { repo ->
                val event = FilterEvent(
                    timestamp = System.currentTimeMillis(),
                    category = category,
                    confidence = confidence,
                    action = "blur"
                )
                repo.insert(event)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log filter event", e)
        }
    }

    /**
     * Initialize repository for event logging
     */
    fun setRepository(repo: FilterEventRepository) {
        this.repository = repo
    }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        processingScope.cancel()
        overlayManager.cleanup()
    }
}
