package com.pavlova.ml

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer

/**
 * TFLite-based NLP model runner for on-device RoBERTa / MobileBERT inference.
 *
 * Supports multiple model types via a shared runner pattern:
 *   - Sentiment classification
 *   - Toxicity detection
 *   - Emotion classification
 *   - Persuasion scoring
 *
 * Models are loaded lazily from assets/. If a model file is missing,
 * the runner reports [isAvailable] = false and callers should fall back
 * to the keyword heuristic in [ContentAnalyzer].
 *
 * Expected model contract:
 *   Input : int32 token IDs  [1, MAX_SEQ_LEN]
 *   Output: float32 logits   [1, NUM_CLASSES]
 */
class NlpModelRunner(
    private val context: Context,
    private val modelFileName: String,
    private val maxSeqLen: Int = 128,
    private val numClasses: Int = 2,
    private val labels: List<String> = listOf("negative", "positive")
) {
    companion object {
        private const val TAG = "NlpModelRunner"
    }

    private var interpreter: Interpreter? = null

    val isAvailable: Boolean get() = interpreter != null

    fun load(): Boolean {
        if (interpreter != null) return true
        return try {
            val modelBuffer: MappedByteBuffer = FileUtil.loadMappedFile(context, modelFileName)
            val options = Interpreter.Options().apply {
                setNumThreads(2)
                setUseXNNPACK(true)
            }
            interpreter = Interpreter(modelBuffer, options)
            Log.d(TAG, "Loaded NLP model: $modelFileName ($numClasses classes)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "NLP model not available: $modelFileName — will use heuristic fallback")
            false
        }
    }

    /**
     * Run inference on tokenized input. Returns class probabilities [numClasses].
     */
    fun predict(tokenIds: IntArray): FloatArray {
        val interp = interpreter ?: return FloatArray(numClasses)

        // Pad or truncate to maxSeqLen
        val padded = IntArray(maxSeqLen)
        tokenIds.copyInto(padded, endIndex = minOf(tokenIds.size, maxSeqLen))

        val inputBuffer = ByteBuffer.allocateDirect(maxSeqLen * 4).apply {
            order(ByteOrder.nativeOrder())
            padded.forEach { putInt(it) }
            rewind()
        }

        val outputBuffer = ByteBuffer.allocateDirect(numClasses * 4).apply {
            order(ByteOrder.nativeOrder())
        }

        try {
            interp.run(inputBuffer, outputBuffer)
        } catch (e: Exception) {
            // The placeholder hash-based tokenizer can produce token IDs outside the
            // model's embedding vocab range, which crashes the GATHER op. Disable this
            // runner so callers fall back to the keyword heuristic instead of crashing
            // FeedAnalyzer.processFrame for every subsequent frame.
            Log.w(TAG, "NLP model inference failed for $modelFileName — disabling, will use heuristic fallback", e)
            close()
            return FloatArray(numClasses)
        }
        outputBuffer.rewind()

        val logits = FloatArray(numClasses) { outputBuffer.float }

        // Softmax
        val maxLogit = logits.max()
        val exps = logits.map { kotlin.math.exp((it - maxLogit).toDouble()) }
        val sumExps = exps.sum()
        return FloatArray(numClasses) { (exps[it] / sumExps).toFloat() }
    }

    /**
     * Convenience: predict from raw text using a simple whitespace tokenizer.
     * For production, replace with WordPiece / BPE tokenizer.
     */
    fun predictFromText(text: String): Pair<String, Float> {
        val tokens = simpleTokenize(text)
        val probs = predict(tokens)
        val topIdx = probs.indices.maxByOrNull { probs[it] } ?: 0
        return labels[topIdx] to probs[topIdx]
    }

    /**
     * Get full probability distribution as label→score map.
     */
    fun predictDistribution(text: String): Map<String, Float> {
        val tokens = simpleTokenize(text)
        val probs = predict(tokens)
        return labels.zip(probs.toList()).toMap()
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }

    /**
     * Simple whitespace tokenizer that maps words to hash-based IDs.
     * Replace with a real WordPiece tokenizer for RoBERTa/BERT models.
     */
    private fun simpleTokenize(text: String): IntArray {
        val words = text.lowercase().split(Regex("\\W+")).filter { it.isNotBlank() }
        // [CLS]=101, token IDs via hash, [SEP]=102
        val ids = mutableListOf(101)
        for (w in words.take(maxSeqLen - 2)) {
            ids.add((w.hashCode() and 0x7FFF) + 1000) // Positive ID in vocab range
        }
        ids.add(102)
        return ids.toIntArray()
    }
}
