package com.pavlova.analysis

import com.pavlova.data.model.ContentItem

/**
 * Builds a first-order Markov transition matrix from content sequences.
 * Tracks topic-to-topic transition probabilities to detect radicalization pathways.
 *
 * Example: if fitness → dieting → extreme dieting → conspiracy wellness appears
 * frequently, the transition probabilities will reveal this funnel.
 */
class MarkovChainAnalyzer {

    /**
     * Transition counts: from-topic → (to-topic → count)
     */
    private val transitions = mutableMapOf<String, MutableMap<String, Int>>()
    private var totalTransitions = 0

    /**
     * Feed a sequence of content items. Extracts topic transitions.
     */
    fun addSequence(items: List<ContentItem>) {
        val topics = items.mapNotNull { item ->
            item.topicLabels?.let { parseTopics(it) }?.firstOrNull()
        }

        for (i in 0 until topics.size - 1) {
            val from = topics[i]
            val to = topics[i + 1]
            transitions.getOrPut(from) { mutableMapOf() }
                .merge(to, 1) { old, new -> old + new }
            totalTransitions++
        }
    }

    /**
     * Get transition probability P(to | from).
     */
    fun transitionProbability(from: String, to: String): Float {
        val fromTransitions = transitions[from] ?: return 0f
        val total = fromTransitions.values.sum()
        val count = fromTransitions[to] ?: 0
        return if (total > 0) count / total.toFloat() else 0f
    }

    /**
     * Get all transitions from a given topic, sorted by probability descending.
     */
    fun transitionsFrom(from: String): List<Pair<String, Float>> {
        val fromTransitions = transitions[from] ?: return emptyList()
        val total = fromTransitions.values.sum().toFloat()
        return fromTransitions.map { (to, count) -> to to count / total }
            .sortedByDescending { it.second }
    }

    /**
     * Get the full transition matrix as a map of maps with probabilities.
     */
    fun getTransitionMatrix(): Map<String, Map<String, Float>> {
        return transitions.mapValues { (_, toMap) ->
            val total = toMap.values.sum().toFloat()
            toMap.mapValues { (_, count) -> count / total }
        }
    }

    /**
     * Find the most likely path of length N starting from a topic.
     * Uses greedy most-probable next step.
     */
    fun mostLikelyPath(startTopic: String, length: Int): List<String> {
        val path = mutableListOf(startTopic)
        var current = startTopic
        repeat(length - 1) {
            val next = transitionsFrom(current).firstOrNull()?.first ?: return path
            path.add(next)
            current = next
        }
        return path
    }

    /**
     * Detect potential radicalization funnels: sequences where topic diversity
     * decreases and the chain converges to a narrow set of topics.
     */
    fun detectFunnels(minPathLength: Int = 3): List<List<String>> {
        val allTopics = transitions.keys.toList()
        val funnels = mutableListOf<List<String>>()

        for (start in allTopics) {
            val path = mostLikelyPath(start, minPathLength + 2)
            if (path.size >= minPathLength) {
                // Check if the path converges (later topics repeat)
                val uniqueInFirstHalf = path.take(path.size / 2).toSet().size
                val uniqueInSecondHalf = path.drop(path.size / 2).toSet().size
                if (uniqueInSecondHalf < uniqueInFirstHalf) {
                    funnels.add(path)
                }
            }
        }
        return funnels
    }

    fun clear() {
        transitions.clear()
        totalTransitions = 0
    }

    private fun parseTopics(json: String): List<String> {
        return json.trim().removeSurrounding("[", "]")
            .split(",")
            .map { it.trim().removeSurrounding("\"") }
            .filter { it.isNotBlank() }
    }
}
