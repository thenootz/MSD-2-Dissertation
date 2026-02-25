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
    private const val MODEL_FILE = "nsfw_mobilenet_v2_140_224_int8.tflite"

    @Volatile
    private var isInitialized = false

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
     */
    fun classifyFrame(imageData: ByteArray, width: Int, height: Int): ClassificationResult {
        if (!isInitialized) {
            throw IllegalStateException("RustMLBridge not initialized")
        }

        return try {
            val result = nativeClassifyFrame(imageData, width, height)
            ClassificationResult(
                isSafe = result[0] > 0.5f,
                confidence = result[0],
                category = if (result[0] > 0.5f) "safe" else "unsafe"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Classification failed", e)
            // Fail-safe: assume safe
            ClassificationResult(isSafe = true, confidence = 0.0f, category = "error")
        }
    }

    /**
     * Generate blurred version of image
     */
    fun generateBlur(imageData: ByteArray, width: Int, height: Int, radius: Float): ByteArray {
        if (!isInitialized) {
            throw IllegalStateException("RustMLBridge not initialized")
        }

        return try {
            nativeGenerateBlur(imageData, width, height, radius)
        } catch (e: Exception) {
            Log.e(TAG, "Blur generation failed", e)
            imageData // Return original on error
        }
    }

    /**
     * Generate pixelated version of image
     */
    fun generatePixelation(imageData: ByteArray, width: Int, height: Int, blockSize: Int): ByteArray {
        if (!isInitialized) {
            throw IllegalStateException("RustMLBridge not initialized")
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

/**
 * Classification result data class
 */
data class ClassificationResult(
    val isSafe: Boolean,
    val confidence: Float,
    val category: String
)
