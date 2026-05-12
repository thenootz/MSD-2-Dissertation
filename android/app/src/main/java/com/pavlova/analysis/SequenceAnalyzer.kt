package com.pavlova.analysis

import android.content.Context
import android.util.Log
import com.pavlova.data.model.ContentItem
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer

/**
 * LSTM-based sequence analyzer for detecting temporal patterns in feed content.
 *
 * Analyses ordered sequences of content feature vectors to detect:
 *   - Progressive emotional intensification
 *   - Topic narrowing / radicalization trajectories
 *   - Behavioral escalation patterns (engagement ramp)
 *
 * Can run with a TFLite LSTM model or a pure-Kotlin windowed statistics fallback.
 *
 * TFLite Model contract:
 *   Input : float32 [1, WINDOW_SIZE, NUM_FEATURES]
 *   Output: float32 [1, NUM_OUTPUT_FEATURES]  (escalation score, direction indicators)
 */
class SequenceAnalyzer(private val context: Context) {

    companion object {
        private const val TAG = "SequenceAnalyzer"
        private const val MODEL_FILE = "sequence_lstm.tflite"
        const val WINDOW_SIZE = 20       // Sliding window of items to analyze
        const val NUM_FEATURES = 5       // sentiment, toxicity, persuasion, topicDiversity, emotionIntensity
        const val NUM_OUTPUTS = 3        // escalation_score, direction (-1/+1), confidence
    }

    private var interpreter: Interpreter? = null
    val isModelLoaded: Boolean get() = interpreter != null

    fun load(): Boolean {
        return try {
            val buffer: MappedByteBuffer = FileUtil.loadMappedFile(context, MODEL_FILE)
            interpreter = Interpreter(buffer, Interpreter.Options().apply {
                setNumThreads(2)
            })
            Log.d(TAG, "LSTM model loaded")
            true
        } catch (e: Exception) {
            Log.w(TAG, "LSTM model not available — using statistical fallback")
            false
        }
    }

    /**
     * Analyze a sequence of content items for escalation patterns.
     * Returns a [SequenceResult] with scores and detected patterns.
     */
    fun analyze(items: List<ContentItem>): SequenceResult {
        if (items.size < 3) return SequenceResult()

        val features = extractFeatures(items)
        return if (isModelLoaded) {
            analyzeWithModel(features)
        } else {
            analyzeWithStatistics(features)
        }
    }

    /**
     * Extract a feature matrix [N, NUM_FEATURES] from content items.
     */
    fun extractFeatures(items: List<ContentItem>): List<FloatArray> {
        return items.map { item ->
            floatArrayOf(
                item.sentimentScore ?: 0f,
                item.toxicityScore ?: 0f,
                item.persuasionScore ?: 0f,
                topicDiversityAtPosition(items, item.position),
                emotionIntensity(item)
            )
        }
    }

    // --- TFLite LSTM path ---

    private fun analyzeWithModel(features: List<FloatArray>): SequenceResult {
        val interp = interpreter ?: return analyzeWithStatistics(features)

        // Take last WINDOW_SIZE items (or pad with zeros)
        val window = if (features.size >= WINDOW_SIZE) {
            features.takeLast(WINDOW_SIZE)
        } else {
            val pad = List(WINDOW_SIZE - features.size) { FloatArray(NUM_FEATURES) }
            pad + features
        }

        val input = ByteBuffer.allocateDirect(WINDOW_SIZE * NUM_FEATURES * 4).apply {
            order(ByteOrder.nativeOrder())
            window.forEach { row -> row.forEach { putFloat(it) } }
            rewind()
        }

        val output = ByteBuffer.allocateDirect(NUM_OUTPUTS * 4).apply {
            order(ByteOrder.nativeOrder())
        }

        interp.run(input, output)
        output.rewind()

        val escalationScore = output.float.coerceIn(0f, 1f)
        val direction = output.float       // -1 = de-escalating, +1 = escalating
        val confidence = output.float.coerceIn(0f, 1f)

        return SequenceResult(
            escalationScore = escalationScore,
            direction = direction,
            confidence = confidence,
            patterns = detectPatterns(features)
        )
    }

    // --- Statistical fallback ---

    private fun analyzeWithStatistics(features: List<FloatArray>): SequenceResult {
        if (features.size < 3) return SequenceResult()

        // Compute slopes for each feature dimension using linear regression
        val slopes = (0 until NUM_FEATURES).map { dim ->
            linearSlope(features.map { it[dim] })
        }

        // Escalation = weighted combination of increasing toxicity, persuasion, emotion
        val sentimentSlope = slopes[0]
        val toxicitySlope = slopes[1]
        val persuasionSlope = slopes[2]
        val emotionSlope = slopes[4]

        val escalation = (toxicitySlope * 0.3f + persuasionSlope * 0.3f +
                emotionSlope * 0.25f + (-sentimentSlope).coerceAtLeast(0f) * 0.15f)
            .coerceIn(0f, 1f)

        val direction = if (escalation > 0.1f) 1f else if (escalation < -0.1f) -1f else 0f

        // Confidence based on how consistent the trend is
        val r2Values = (0 until NUM_FEATURES).map { dim ->
            rSquared(features.map { it[dim] })
        }
        val confidence = r2Values.average().toFloat().coerceIn(0f, 1f)

        return SequenceResult(
            escalationScore = escalation,
            direction = direction,
            confidence = confidence,
            patterns = detectPatterns(features)
        )
    }

    /**
     * Detect named patterns in the feature sequence.
     */
    private fun detectPatterns(features: List<FloatArray>): List<String> {
        val patterns = mutableListOf<String>()
        if (features.size < 5) return patterns

        // Check for toxicity ramp
        val toxSlope = linearSlope(features.map { it[1] })
        if (toxSlope > 0.02f) patterns.add("toxicity_increasing")

        // Check for persuasion ramp
        val persSlope = linearSlope(features.map { it[2] })
        if (persSlope > 0.02f) patterns.add("persuasion_increasing")

        // Check for diversity collapse (topic diversity decreasing)
        val divSlope = linearSlope(features.map { it[3] })
        if (divSlope < -0.02f) patterns.add("diversity_collapsing")

        // Check for emotional intensification
        val emoSlope = linearSlope(features.map { it[4] })
        if (emoSlope > 0.02f) patterns.add("emotional_intensification")

        // Check for sentiment polarization (variance increasing)
        val sentiments = features.map { it[0] }
        val firstHalfVar = variance(sentiments.take(sentiments.size / 2))
        val secondHalfVar = variance(sentiments.drop(sentiments.size / 2))
        if (secondHalfVar > firstHalfVar * 1.5f) patterns.add("sentiment_polarizing")

        return patterns
    }

    // --- Helpers ---

    private fun topicDiversityAtPosition(items: List<ContentItem>, position: Int): Float {
        val window = items.filter { it.position <= position }.takeLast(10)
        val topics = window.mapNotNull { it.topicLabels }
            .flatMap { it.removeSurrounding("[", "]").split(",").map { t -> t.trim().removeSurrounding("\"") } }
            .filter { it.isNotBlank() }
        return if (topics.isEmpty()) 0f else DriftAnalyzer.shannonEntropy(topics)
    }

    private fun emotionIntensity(item: ContentItem): Float {
        val sentiment = kotlin.math.abs(item.sentimentScore ?: 0f)
        val toxicity = item.toxicityScore ?: 0f
        return ((sentiment + toxicity) / 2f).coerceIn(0f, 1f)
    }

    private fun linearSlope(values: List<Float>): Float {
        val n = values.size
        if (n < 2) return 0f
        val xMean = (n - 1) / 2f
        val yMean = values.average().toFloat()
        var num = 0f; var den = 0f
        for (i in values.indices) {
            val dx = i - xMean; val dy = values[i] - yMean
            num += dx * dy; den += dx * dx
        }
        return if (den > 0) num / den else 0f
    }

    private fun rSquared(values: List<Float>): Float {
        if (values.size < 3) return 0f
        val mean = values.average().toFloat()
        val ssTot = values.sumOf { ((it - mean) * (it - mean)).toDouble() }.toFloat()
        if (ssTot == 0f) return 0f
        val slope = linearSlope(values)
        val xMean = (values.size - 1) / 2f
        val ssRes = values.indices.sumOf { i ->
            val predicted = mean + slope * (i - xMean)
            ((values[i] - predicted) * (values[i] - predicted)).toDouble()
        }.toFloat()
        return (1f - ssRes / ssTot).coerceIn(0f, 1f)
    }

    private fun variance(values: List<Float>): Float {
        if (values.size < 2) return 0f
        val mean = values.average()
        return values.map { ((it - mean) * (it - mean)).toFloat() }.average().toFloat()
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}

/**
 * Result of LSTM sequence analysis.
 */
data class SequenceResult(
    /** 0.0 = stable/benign, 1.0 = strong escalation detected */
    val escalationScore: Float = 0f,
    /** -1 = de-escalating, 0 = stable, +1 = escalating */
    val direction: Float = 0f,
    /** Model confidence in the prediction (0-1) */
    val confidence: Float = 0f,
    /** Named patterns detected: toxicity_increasing, diversity_collapsing, etc. */
    val patterns: List<String> = emptyList()
)
