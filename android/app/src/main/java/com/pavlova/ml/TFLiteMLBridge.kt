package com.pavlova.ml

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer

/**
 * Kotlin-native TensorFlow Lite implementation for ML inference.
 * Optimized to reuse buffers and prevent memory churn.
 */
object TFLiteMLBridge {
    private const val TAG = "TFLiteMLBridge"
    private const val MODEL_FILE = "nsfw_mobilenet_v2_140_224.tflite"
    
    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    
    // Pre-allocated objects to avoid GC pressure in the processing loop
    private var imageProcessor: ImageProcessor? = null
    private var tensorImage: TensorImage? = null
    private var outputBuffer: Array<FloatArray>? = null
    private var cachedBitmap: Bitmap? = null

    @Volatile
    var isInitialized = false
        private set

    private val CLASS_NAMES = arrayOf("drawing", "hentai", "neutral", "porn", "sexy")

    /**
     * Initialize TFLite interpreter and pre-allocate reusable objects
     */
    fun initialize(context: Context) {
        if (isInitialized) {
            Log.w(TAG, "Already initialized")
            return
        }

        try {
            val options = Interpreter.Options()
            options.setUseXNNPACK(true)
            
            val compatList = CompatibilityList()

            // Enable GPU acceleration if supported
            if (compatList.isDelegateSupportedOnThisDevice) {
                val delegateOptions = compatList.bestOptionsForThisDevice
                gpuDelegate = GpuDelegate(delegateOptions)
                options.addDelegate(gpuDelegate)
                Log.d(TAG, "GPU acceleration enabled")
            } else {
                options.setNumThreads(4)
                Log.d(TAG, "Running on CPU (4 threads)")
            }

            // Load model from assets
            val modelBuffer: MappedByteBuffer = FileUtil.loadMappedFile(context, MODEL_FILE)
            val interp = Interpreter(modelBuffer, options)
            interpreter = interp
            
            // 1. Initialize Reusable Image Processor (224x224, [0, 1] normalization)
            imageProcessor = ImageProcessor.Builder()
                .add(ResizeOp(224, 224, ResizeOp.ResizeMethod.BILINEAR))
                .add(NormalizeOp(0f, 255f)) 
                .build()

            // 2. Pre-allocate TensorImage and Output Buffer
            tensorImage = TensorImage(interp.getInputTensor(0).dataType())
            outputBuffer = Array(1) { FloatArray(5) }
            
            isInitialized = true
            Log.d(TAG, "TFLite ML engine initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TFLite ML engine", e)
            isInitialized = false
        }
    }

    /**
     * Classify a frame from raw RGBA byte array. Uses pre-allocated buffers.
     */
    fun classifyFrame(imageData: ByteArray, width: Int, height: Int): ClassificationResult {
        if (!isInitialized || interpreter == null) {
            return ClassificationResult(isSafe = true, confidence = 0f, category = "uninitialized")
        }

        // TFLite Interpreter is NOT thread-safe.
        return synchronized(this) {
            try {
                // 1. Reuse or Create Bitmap to avoid large allocations
                val bitmap = if (cachedBitmap?.width == width && cachedBitmap?.height == height) {
                    cachedBitmap!!
                } else {
                    cachedBitmap?.recycle()
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { cachedBitmap = it }
                }
                
                bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(imageData))

                // 2. Process Image using pre-allocated processor
                tensorImage?.load(bitmap)
                val processedImage = imageProcessor?.process(tensorImage)

                // 3. Run inference into pre-allocated output buffer
                interpreter!!.run(processedImage?.buffer, outputBuffer)

                val scores = outputBuffer!![0]
                
                // 4. Process Results
                val topIndex = scores.indices.maxByOrNull { scores[it] } ?: 2
                val topClass = CLASS_NAMES[topIndex]
                val topScore = scores[topIndex]
                
                // GantMan indices: 0:drawing, 1:hentai, 2:neutral, 3:porn, 4:sexy
                val safeScore = scores[0] + scores[2]
                val unsafeScore = scores[1] + scores[3] + scores[4]
                val isSafe = safeScore > unsafeScore
                
                val category = when (topIndex) {
                    0 -> "drawing"
                    1 -> "hentai"
                    2 -> "neutral"
                    3 -> "porn"
                    4 -> "sexy"
                    else -> "unknown"
                }
                
                val confidence = if (isSafe) safeScore else unsafeScore
                
                Log.d(TAG, "Classification: $category (${String.format("%.3f", topScore)})")
                
                ClassificationResult(
                    isSafe = isSafe,
                    confidence = confidence,
                    category = category,
                    scores = scores.copyOf(), // Copy scores as outputBuffer will be reused
                    topClass = topClass
                )
            } catch (e: Exception) {
                Log.e(TAG, "Inference failed", e)
                ClassificationResult(isSafe = true, confidence = 0f, category = "error")
            }
        }
    }

    /**
     * Implementation of blur in Kotlin to replace Rust dependency
     */
    fun generateBlur(imageData: ByteArray, width: Int, height: Int, radius: Float): ByteArray {
        return try {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(imageData))
            
            val scale = (1.0f / radius.coerceAtLeast(1f)).coerceAtMost(0.5f)
            val smallWidth = (width * scale).toInt().coerceAtLeast(1)
            val smallHeight = (height * scale).toInt().coerceAtLeast(1)
            
            val smallBitmap = Bitmap.createScaledBitmap(bitmap, smallWidth, smallHeight, true)
            val blurredBitmap = Bitmap.createScaledBitmap(smallBitmap, width, height, true)
            
            val outputBuffer = ByteBuffer.allocate(blurredBitmap.byteCount)
            blurredBitmap.copyPixelsToBuffer(outputBuffer)
            outputBuffer.array()
        } catch (e: Exception) {
            Log.e(TAG, "Blur failed", e)
            imageData
        }
    }

    fun destroy() {
        synchronized(this) {
            interpreter?.close()
            interpreter = null
            gpuDelegate?.close()
            gpuDelegate = null
            imageProcessor = null
            tensorImage = null
            outputBuffer = null
            cachedBitmap?.recycle()
            cachedBitmap = null
            isInitialized = false
        }
    }
}
