package com.pavlova.ml

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import kotlin.math.sqrt

/**
 * On-device sentence embedding engine using a quantized SBERT (Sentence-BERT) model.
 *
 * Produces fixed-size embedding vectors for text, enabling:
 *   - Cosine similarity between content items
 *   - Semantic clustering (echo chamber detection)
 *   - Diversity measurement via embedding spread
 *   - Feed homogenization tracking
 *
 * Model contract:
 *   Input : int32 token IDs  [1, MAX_SEQ_LEN]
 *   Output: float32 embedding [1, EMBEDDING_DIM]
 *
 * If the TFLite model is not present, falls back to a bag-of-words TF-IDF
 * style embedding using hashed word vectors.
 */
class EmbeddingEngine(private val context: Context) {

    companion object {
        private const val TAG = "EmbeddingEngine"
        private const val MODEL_FILE = "sbert_quantized.tflite"
        private const val MAX_SEQ_LEN = 64
        const val EMBEDDING_DIM = 128  // Matches quantized MiniLM-L6
    }

    private var interpreter: Interpreter? = null
    private var tokenizer: Tokenizer = HashTokenizer(32_000)
    val isModelLoaded: Boolean get() = interpreter != null

    fun load(): Boolean {
        return try {
            val buffer: MappedByteBuffer = FileUtil.loadMappedFile(context, MODEL_FILE)
            interpreter = Interpreter(buffer, Interpreter.Options().apply {
                setNumThreads(2)
                setUseXNNPACK(true)
            })
            tokenizer = Tokenizer.load(context, MODEL_FILE.removeSuffix(".tflite"), fallbackVocabSize = 32_000)
            Log.d(TAG, "SBERT model loaded (tokenizer=${tokenizer::class.simpleName}, vocab=${tokenizer.vocabSize})")
            true
        } catch (e: Exception) {
            Log.w(TAG, "SBERT model not available — using hash embeddings fallback")
            false
        }
    }

    /**
     * Generate an embedding vector for the given text.
     * Uses TFLite model if available, otherwise hash-based fallback.
     */
    fun embed(text: String): FloatArray {
        return if (isModelLoaded) {
            embedWithModel(text) ?: hashEmbed(text)
        } else {
            hashEmbed(text)
        }
    }

    /**
     * Cosine similarity between two embedding vectors.
     */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "Embedding dimensions must match" }
        var dot = 0f; var normA = 0f; var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom > 0f) dot / denom else 0f
    }

    /**
     * Average pairwise cosine distance across a set of embeddings.
     * Lower values = more homogeneous feed (echo chamber signal).
     */
    fun averagePairwiseDistance(embeddings: List<FloatArray>): Float {
        if (embeddings.size < 2) return 1f
        var totalDist = 0f
        var count = 0
        for (i in embeddings.indices) {
            for (j in i + 1 until embeddings.size) {
                totalDist += 1f - cosineSimilarity(embeddings[i], embeddings[j])
                count++
            }
        }
        return if (count > 0) totalDist / count else 1f
    }

    /**
     * Cluster embeddings using simple k-means. Returns cluster assignments.
     */
    fun kMeansCluster(embeddings: List<FloatArray>, k: Int = 5, iterations: Int = 20): List<Int> {
        if (embeddings.isEmpty()) return emptyList()
        val dim = embeddings.first().size
        val effectiveK = k.coerceAtMost(embeddings.size)

        // Init centroids from first k items
        val centroids = Array(effectiveK) { embeddings[it % embeddings.size].copyOf() }
        val assignments = IntArray(embeddings.size)

        repeat(iterations) {
            // Assign
            for (i in embeddings.indices) {
                assignments[i] = centroids.indices.minByOrNull { c ->
                    euclideanDistSq(embeddings[i], centroids[c])
                } ?: 0
            }
            // Update centroids
            for (c in centroids.indices) {
                val members = embeddings.indices.filter { assignments[it] == c }
                if (members.isNotEmpty()) {
                    for (d in 0 until dim) {
                        centroids[c][d] = members.map { embeddings[it][d] }.average().toFloat()
                    }
                }
            }
        }
        return assignments.toList()
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }

    // --- TFLite model path ---

    private fun embedWithModel(text: String): FloatArray? {
        val tokens = tokenizer.encode(text, MAX_SEQ_LEN)
        val input = ByteBuffer.allocateDirect(MAX_SEQ_LEN * 4).apply {
            order(ByteOrder.nativeOrder())
            tokens.forEach { putInt(it) }
            rewind()
        }
        val output = ByteBuffer.allocateDirect(EMBEDDING_DIM * 4).apply {
            order(ByteOrder.nativeOrder())
        }
        try {
            interpreter?.run(input, output)
        } catch (e: Exception) {
            Log.w(TAG, "SBERT model inference failed — disabling, will use hash embedding fallback", e)
            close()
            return null
        }
        output.rewind()
        return FloatArray(EMBEDDING_DIM) { output.float }.also { normalize(it) }
    }

    // --- Hash embedding fallback ---

    /**
     * Deterministic hash-based embedding: each word maps to a fixed random
     * direction in embedding space. Not semantically meaningful, but gives
     * consistent vectors for diversity/distance measurements.
     */
    private fun hashEmbed(text: String): FloatArray {
        val vec = FloatArray(EMBEDDING_DIM)
        val words = text.lowercase().split(Regex("\\W+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return vec

        for (word in words) {
            val seed = word.hashCode().toLong()
            for (d in 0 until EMBEDDING_DIM) {
                // Deterministic pseudo-random based on word hash + dimension
                val h = ((seed * 6364136223846793005L + d * 1442695040888963407L) shr 33).toFloat()
                vec[d] += h
            }
        }
        // Average then normalize
        val n = words.size.toFloat()
        for (d in 0 until EMBEDDING_DIM) vec[d] /= n
        normalize(vec)
        return vec
    }

    private fun normalize(vec: FloatArray) {
        var norm = 0f
        for (v in vec) norm += v * v
        norm = sqrt(norm)
        if (norm > 0f) for (i in vec.indices) vec[i] /= norm
    }

    private fun euclideanDistSq(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) { val d = a[i] - b[i]; sum += d * d }
        return sum
    }
}
