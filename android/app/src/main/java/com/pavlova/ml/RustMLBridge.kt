package com.pavlova.ml

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * JNI Bridge to Rust ML library
 */
object RustMLBridge {

    private const val TAG = "RustMLBridge"
    private const val LIBRARY_NAME = "pavlova_core"
    private const val MODEL_FILE = "nsfw_mobilenet_v2_140_224.tflite"

    // GantMan NSFW model class indices
    private const val CLASS_DRAWING = 0
    private const val CLASS_HENTAI = 1
    private const val CLASS_NEUTRAL = 2
    private const val CLASS_PORN = 3
    private const val CLASS_SEXY = 4

    private val CLASS_NAMES = arrayOf("drawing", "hentai", "neutral", "porn", "sexy")

    @Volatile
    var isInitialized = false
        private set

    init {
        try {
            System.loadLibrary(LIBRARY_NAME)
            Log.d(TAG, "Native library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native library", e)
        }
    }

    /**
     * Initialize ML engine with model
     */
    fun initialize(context: Context) {
        if (isInitialized) {
            Log.w(TAG, "Already initialized")
            return
        }

        try {
            // Extract model from assets to internal storage
            val modelFile = extractModelFromAssets(context)
            
            // Initialize native ML engine
            val success = nativeInit(modelFile.absolutePath)
            
            if (success) {
                isInitialized = true
                Log.d(TAG, "ML engine initialized successfully")
            } else {
                throw RuntimeException("Native initialization failed")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ML engine", e)
            throw e
        }
    }

    /**
     * Classify a frame
     * Returns ClassificationResult with 5-class scores from GantMan NSFW model:
     * [drawing, hentai, neutral, porn, sexy]
     */
    fun classifyFrame(imageData: ByteArray, width: Int, height: Int): ClassificationResult {
        if (!isInitialized) {
            Log.e(TAG, "RustMLBridge not initialized - returning default safe result")
            return ClassificationResult(
                isSafe = true,
                confidence = 0f,
                category = "uninitialized",
                scores = floatArrayOf(0f, 0f, 1f, 0f, 0f),
                topClass = "neutral"
            )
        }

        return try {
            // scores = [drawing, hentai, neutral, porn, sexy]
            val scores = nativeClassifyFrame(imageData, width, height)
            
            // Find top class
            val topIndex = scores.indices.maxByOrNull { scores[it] } ?: CLASS_NEUTRAL
            val topClass = CLASS_NAMES[topIndex]
            val topScore = scores[topIndex]
            
            // Safe classes: neutral + drawing
            // Unsafe classes: hentai + porn + sexy
            val safeScore = scores[CLASS_DRAWING] + scores[CLASS_NEUTRAL]
            val unsafeScore = scores[CLASS_HENTAI] + scores[CLASS_PORN] + scores[CLASS_SEXY]
            val isSafe = safeScore > unsafeScore
            
            // Determine category for filtering policy
            val category = when (topIndex) {
                CLASS_NEUTRAL -> "neutral"
                CLASS_DRAWING -> "drawing"
                CLASS_PORN -> "porn"
                CLASS_HENTAI -> "hentai"
                CLASS_SEXY -> "sexy"
                else -> "unknown"
            }
            
            val confidence = if (isSafe) safeScore else unsafeScore
            
            Log.d(TAG, "Classification: $category (${String.format("%.3f", topScore)}) " +
                "scores: d=${String.format("%.3f", scores[0])} h=${String.format("%.3f", scores[1])} " +
                "n=${String.format("%.3f", scores[2])} p=${String.format("%.3f", scores[3])} " +
                "s=${String.format("%.3f", scores[4])}")
            
            ClassificationResult(
                isSafe = isSafe,
                confidence = confidence,
                category = category,
                scores = scores,
                topClass = topClass
            )
        } catch (e: Exception) {
            Log.e(TAG, "Classification failed", e)
            // Fail-safe: assume safe
            ClassificationResult(
                isSafe = true,
                confidence = 0.0f,
                category = "error",
                scores = floatArrayOf(0f, 0f, 1f, 0f, 0f),
                topClass = "neutral"
            )
        }
    }

    /**
     * Generate blurred version of image
     */
    fun generateBlur(imageData: ByteArray, width: Int, height: Int, radius: Float): ByteArray {
        if (!isInitialized) {
            Log.e(TAG, "RustMLBridge not initialized - returning original image")
            return imageData
        }

        return try {
            nativeGenerateBlur(imageData, width, height, radius)
        } catch (e: Exception) {
            Log.e(TAG, "Blur generation failed", e)
            imageData // Return original on error
        }
    }

    /**
     * Generate pixelation version of image
     */
    fun generatePixelation(imageData: ByteArray, width: Int, height: Int, blockSize: Int): ByteArray {
        if (!isInitialized) {
            Log.e(TAG, "RustMLBridge not initialized - returning original image")
            return imageData
        }

        return try {
            nativeGeneratePixelation(imageData, width, height, blockSize)
        } catch (e: Exception) {
            Log.e(TAG, "Pixelation failed", e)
            imageData // Return original on error
        }
    }

    /**
     * Cleanup native resources
     */
    fun destroy() {
        if (isInitialized) {
            nativeDestroy()
            isInitialized = false
            Log.d(TAG, "ML engine destroyed")
        }
    }

    /**
     * Extract model from assets to internal storage
     */
    private fun extractModelFromAssets(context: Context): File {
        val modelFile = File(context.filesDir, MODEL_FILE)
        
        if (modelFile.exists()) {
            Log.d(TAG, "Model file already exists: ${modelFile.absolutePath}")
            return modelFile
        }

        context.assets.open(MODEL_FILE).use { input ->
            FileOutputStream(modelFile).use { output ->
                input.copyTo(output)
            }
        }
        
        Log.d(TAG, "Model extracted to: ${modelFile.absolutePath}")
        return modelFile
    }

    // Native method declarations
    private external fun nativeInit(modelPath: String): Boolean
    private external fun nativeClassifyFrame(imageData: ByteArray, width: Int, height: Int): FloatArray
    private external fun nativeGenerateBlur(imageData: ByteArray, width: Int, height: Int, radius: Float): ByteArray
    private external fun nativeGeneratePixelation(imageData: ByteArray, width: Int, height: Int, blockSize: Int): ByteArray
    private external fun nativeDestroy()
}
