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
        private const val CONFIDENCE_THRESHOLD = 0.75f
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
            
            // Check if content is unsafe
            if (result.isSafe) {
                // Hide overlay for safe content
                overlayManager.hideOverlay()
            } else {
                // Generate blur effect using Rust
                val blurRadius = calculateBlurRadius(result.confidence)
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
     * Calculate blur radius based on confidence
     * Higher confidence = stronger blur
     */
    private fun calculateBlurRadius(confidence: Float): Float {
        return when {
            confidence > 0.95f -> 25f  // Very strong blur
            confidence > 0.85f -> 15f  // Strong blur
            confidence > 0.75f -> 10f  // Medium blur
            else -> 5f                 // Light blur
        }
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
