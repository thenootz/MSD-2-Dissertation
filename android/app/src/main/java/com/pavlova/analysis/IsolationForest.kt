package com.pavlova.analysis

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.random.Random

/**
 * Isolation Forest implementation for on-device anomaly detection.
 *
 * Detects abnormal feed sessions by identifying outliers in the
 * multi-dimensional feature space of session metrics:
 *   - Topic entropy, creator concentration, toxicity, sentiment, etc.
 *
 * An anomaly score near 1.0 indicates the session is very different
 * from normal patterns — a potential manipulation signal.
 *
 * Based on: Liu, Ting & Zhou (2008) "Isolation Forest"
 */
class IsolationForest(
    private val numTrees: Int = 100,
    private val subsampleSize: Int = 256,
    private val seed: Long = 42
) {
    private val trees = mutableListOf<IsolationTree>()
    private var trained = false
    private var avgPathLength = 1.0

    /**
     * Train the forest on a dataset of feature vectors.
     * Each vector represents one session's metrics.
     */
    fun fit(data: List<FloatArray>) {
        trees.clear()
        if (data.isEmpty()) return

        val maxDepth = ceil(ln(subsampleSize.toDouble()) / ln(2.0)).toInt()
        val rng = Random(seed)

        repeat(numTrees) {
            val sample = if (data.size <= subsampleSize) data
            else List(subsampleSize) { data[rng.nextInt(data.size)] }
            trees.add(buildTree(sample, 0, maxDepth, rng))
        }

        avgPathLength = averagePathLength(subsampleSize.toDouble())
        trained = true
    }

    /**
     * Compute anomaly score for a single observation.
     * Returns 0.0-1.0 where higher = more anomalous.
     */
    fun score(point: FloatArray): Float {
        if (!trained || trees.isEmpty()) return 0.5f

        val avgPath = trees.map { pathLength(point, it, 0) }.average()
        // Anomaly score formula from the original paper
        val s = Math.pow(2.0, -avgPath / avgPathLength).toFloat()
        return s.coerceIn(0f, 1f)
    }

    /**
     * Score multiple observations. Returns list of anomaly scores.
     */
    fun scoreAll(data: List<FloatArray>): List<Float> = data.map { score(it) }

    /**
     * Predict anomalies above the given threshold.
     * Returns indices of anomalous observations.
     */
    fun predict(data: List<FloatArray>, threshold: Float = 0.6f): List<Int> {
        return data.indices.filter { score(data[it]) > threshold }
    }

    val isTrained: Boolean get() = trained

    // --- Tree construction ---

    private fun buildTree(data: List<FloatArray>, depth: Int, maxDepth: Int, rng: Random): IsolationTree {
        if (data.size <= 1 || depth >= maxDepth) {
            return IsolationTree(size = data.size)
        }

        val dims = data.first().size
        val splitDim = rng.nextInt(dims)

        val min = data.minOf { it[splitDim] }
        val max = data.maxOf { it[splitDim] }

        if (min == max) {
            return IsolationTree(size = data.size)
        }

        val splitValue = min + rng.nextFloat() * (max - min)
        val left = data.filter { it[splitDim] < splitValue }
        val right = data.filter { it[splitDim] >= splitValue }

        return IsolationTree(
            splitDim = splitDim,
            splitValue = splitValue,
            left = buildTree(left, depth + 1, maxDepth, rng),
            right = buildTree(right, depth + 1, maxDepth, rng),
            size = data.size
        )
    }

    private fun pathLength(point: FloatArray, node: IsolationTree, depth: Int): Double {
        if (node.isLeaf) {
            return depth + averagePathLength(node.size.toDouble())
        }
        return if (point[node.splitDim] < node.splitValue) {
            pathLength(point, node.left!!, depth + 1)
        } else {
            pathLength(point, node.right!!, depth + 1)
        }
    }

    /**
     * Average path length of unsuccessful search in BST (Equation 1 from paper).
     * c(n) = 2*H(n-1) - 2*(n-1)/n where H is the harmonic number
     */
    private fun averagePathLength(n: Double): Double {
        if (n <= 1.0) return 0.0
        if (n <= 2.0) return 1.0
        val harmonicNumber = ln(n - 1) + 0.5772156649  // Euler-Mascheroni constant
        return 2.0 * harmonicNumber - 2.0 * (n - 1.0) / n
    }
}

/**
 * Node in an isolation tree.
 */
data class IsolationTree(
    val splitDim: Int = 0,
    val splitValue: Float = 0f,
    val left: IsolationTree? = null,
    val right: IsolationTree? = null,
    val size: Int = 0
) {
    val isLeaf: Boolean get() = left == null && right == null
}
