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
     * Process a captured frame (handles Image object, extracts data immediately)
     */
    suspend fun processFrame(image: Image) {
        val imageData = imageToByteArray(image)
        val width = image.width
        val height = image.height
        processFrame(imageData, width, height)
    }

    /**
     * Process a captured frame from raw data
     */
    suspend fun processFrame(imageData: ByteArray, width: Int, height: Int) = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()
        
        try {
            // Call Kotlin TFLite ML inference
            val result = TFLiteMLBridge.classifyFrame(imageData, width, height)
            
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
                // Generate blur effect using TFLite bridge (Kotlin implementation)
                val blurRadius = calculateBlurRadius(result)
                val blurredData = TFLiteMLBridge.generateBlur(
                    imageData,
                    width,
                    height,
                    blurRadius.toFloat()
                )
                
                // Convert to Bitmap and show overlay
                val blurredBitmap = byteArrayToBitmap(blurredData, width, height)
                overlayManager.showOverlay(blurredBitmap)
                
                // Log filter event (privacy-preserving)
                logFilterEvent(result.category, result.confidence, "blur")
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
    fun imageToByteArray(image: Image): ByteArray {
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

    private fun calculateBlurRadius(result: ClassificationResult): Int {
        // Adult content gets stronger blur
        return if (result.isAdult) 25 else 15
    }

    private fun logFilterEvent(category: String, confidence: Float, action: String) {
        // Privacy-preserving: only log category and confidence, no pixel data
        processingScope.launch {
            try {
                repository?.insert(FilterEvent(
                    category = category,
                    confidence = confidence,
                    timestamp = System.currentTimeMillis(),
                    action = action
                ))
            } catch (e: Exception) {
                Log.e(TAG, "Error logging filter event", e)
            }
        }
    }
}
