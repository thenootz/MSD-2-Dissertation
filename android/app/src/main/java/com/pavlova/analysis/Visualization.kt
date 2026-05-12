package com.pavlova.analysis

import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Lightweight UMAP-inspired dimensionality reduction for on-device visualization.
 *
 * Projects high-dimensional embeddings (128-D SBERT vectors or feature vectors)
 * down to 2D coordinates for scatter-plot visualization in Compose.
 *
 * This is a simplified force-directed layout based on UMAP principles:
 *   1. Build a k-nearest-neighbor graph
 *   2. Optimize 2D positions via attractive/repulsive forces
 *
 * Not a full UMAP implementation, but produces interpretable 2D layouts
 * suitable for visualizing feed content clusters in a dissertation dashboard.
 */
object UmapProjector {

    /**
     * Project high-dimensional points to 2D.
     *
     * @param points List of high-dimensional vectors
     * @param k Number of nearest neighbors
     * @param iterations Optimization steps
     * @param labels Optional labels for each point (returned in result)
     * @return List of (x, y, label?) triples
     */
    fun project(
        points: List<FloatArray>,
        k: Int = 5,
        iterations: Int = 200,
        labels: List<String?>? = null
    ): List<ProjectedPoint> {
        if (points.isEmpty()) return emptyList()
        if (points.size == 1) return listOf(ProjectedPoint(0f, 0f, labels?.firstOrNull()))

        val n = points.size
        val effectiveK = k.coerceAtMost(n - 1)

        // 1. Build KNN graph
        val neighbors = Array(n) { i ->
            points.indices
                .filter { it != i }
                .sortedBy { j -> euclideanDist(points[i], points[j]) }
                .take(effectiveK)
        }

        // 2. Initialize 2D positions randomly (spectral initialization is better but heavier)
        val rng = Random(42)
        val positions = Array(n) { floatArrayOf(rng.nextFloat() * 2 - 1, rng.nextFloat() * 2 - 1) }

        // 3. Force-directed optimization
        val learningRate = 1f
        for (iter in 0 until iterations) {
            val lr = learningRate * (1f - iter.toFloat() / iterations)  // Anneal

            for (i in 0 until n) {
                var fx = 0f; var fy = 0f

                // Attractive forces (pull neighbors closer)
                for (j in neighbors[i]) {
                    val dx = positions[j][0] - positions[i][0]
                    val dy = positions[j][1] - positions[i][1]
                    val dist = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
                    val force = 0.1f * dist  // Linear attractive
                    fx += force * dx / dist
                    fy += force * dy / dist
                }

                // Repulsive forces (push non-neighbors away)
                for (j in 0 until n) {
                    if (j == i || j in neighbors[i]) continue
                    val dx = positions[j][0] - positions[i][0]
                    val dy = positions[j][1] - positions[i][1]
                    val distSq = (dx * dx + dy * dy).coerceAtLeast(0.01f)
                    val force = -0.01f / distSq  // Inverse-square repulsive
                    fx += force * dx
                    fy += force * dy
                }

                positions[i][0] += lr * fx.coerceIn(-0.5f, 0.5f)
                positions[i][1] += lr * fy.coerceIn(-0.5f, 0.5f)
            }
        }

        // 4. Normalize to [0, 1] range
        val minX = positions.minOf { it[0] }
        val maxX = positions.maxOf { it[0] }
        val minY = positions.minOf { it[1] }
        val maxY = positions.maxOf { it[1] }
        val rangeX = (maxX - minX).coerceAtLeast(0.001f)
        val rangeY = (maxY - minY).coerceAtLeast(0.001f)

        return positions.indices.map { i ->
            ProjectedPoint(
                x = (positions[i][0] - minX) / rangeX,
                y = (positions[i][1] - minY) / rangeY,
                label = labels?.getOrNull(i)
            )
        }
    }

    private fun euclideanDist(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        val len = minOf(a.size, b.size)
        for (i in 0 until len) { val d = a[i] - b[i]; sum += d * d }
        return sqrt(sum)
    }
}

/**
 * A point projected into 2D visualization space.
 */
data class ProjectedPoint(
    val x: Float,
    val y: Float,
    val label: String? = null
)

/**
 * Graph structure for content relationship visualization.
 *
 * Models connections between:
 *   - Topics (co-occurrence in feed)
 *   - Creators (shared topic space)
 *   - Content items (semantic similarity)
 *
 * Provides data for a NetworkX-style graph rendered in Compose Canvas.
 */
class ContentGraph {

    private val nodes = mutableMapOf<String, GraphNode>()
    private val edges = mutableListOf<GraphEdge>()

    fun addNode(id: String, label: String, type: NodeType, weight: Float = 1f) {
        nodes[id] = GraphNode(id, label, type, weight)
    }

    fun addEdge(sourceId: String, targetId: String, weight: Float = 1f, label: String? = null) {
        if (sourceId in nodes && targetId in nodes) {
            edges.add(GraphEdge(sourceId, targetId, weight, label))
        }
    }

    fun getNodes(): List<GraphNode> = nodes.values.toList()
    fun getEdges(): List<GraphEdge> = edges.toList()

    /**
     * Build a topic co-occurrence graph from content items' topic labels.
     */
    fun buildTopicGraph(topicLists: List<List<String>>) {
        val cooccurrence = mutableMapOf<Pair<String, String>, Int>()
        val topicCounts = mutableMapOf<String, Int>()

        for (topics in topicLists) {
            for (t in topics) {
                topicCounts.merge(t, 1) { a, b -> a + b }
            }
            for (i in topics.indices) {
                for (j in i + 1 until topics.size) {
                    val pair = if (topics[i] < topics[j]) topics[i] to topics[j] else topics[j] to topics[i]
                    cooccurrence.merge(pair, 1) { a, b -> a + b }
                }
            }
        }

        topicCounts.forEach { (topic, count) ->
            addNode(topic, topic, NodeType.TOPIC, count.toFloat())
        }

        cooccurrence.forEach { (pair, count) ->
            addEdge(pair.first, pair.second, count.toFloat())
        }
    }

    fun clear() {
        nodes.clear()
        edges.clear()
    }
}

enum class NodeType { TOPIC, CREATOR, CONTENT }

data class GraphNode(
    val id: String,
    val label: String,
    val type: NodeType,
    val weight: Float = 1f
)

data class GraphEdge(
    val sourceId: String,
    val targetId: String,
    val weight: Float = 1f,
    val label: String? = null
)
