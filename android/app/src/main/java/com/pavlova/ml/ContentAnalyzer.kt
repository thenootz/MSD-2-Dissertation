package com.pavlova.ml

import android.content.Context
import android.util.Log

/**
 * Orchestrates the full content analysis pipeline:
 *   1. OCR text extraction (ML Kit)
 *   2. Sentiment analysis (TFLite — placeholder for MobileBERT)
 *   3. Toxicity scoring (TFLite — placeholder)
 *   4. Topic classification (keyword-based MVP, TFLite later)
 *   5. Emotion detection (keyword-based MVP, TFLite later)
 *   6. Persuasion scoring (heuristic MVP)
 *
 * Phase 1 uses keyword heuristics. Phase 2 swaps in real TFLite NLP models.
 */
object ContentAnalyzer {
    private const val TAG = "ContentAnalyzer"

    /**
     * Analyze a captured frame: OCR → NLP pipeline → ContentAnalysis
     */
    suspend fun analyze(imageData: ByteArray, width: Int, height: Int): ContentAnalysis {
        val startTime = System.currentTimeMillis()

        // Step 1: OCR
        val text = TextExtractor.extractText(imageData, width, height)
        if (text.isBlank()) {
            return ContentAnalysis(processingTimeMs = System.currentTimeMillis() - startTime)
        }

        // Step 2-6: NLP analysis on extracted text
        val analysis = analyzeText(text)

        val elapsed = System.currentTimeMillis() - startTime
        Log.d(TAG, "Full analysis in ${elapsed}ms: sentiment=${analysis.sentimentScore}, " +
                "topics=${analysis.topics}, toxicity=${analysis.toxicityScore}")

        return analysis.copy(processingTimeMs = elapsed)
    }

    /**
     * Analyze extracted text through the NLP pipeline.
     * Phase 1: keyword heuristics. Phase 2: swap in TFLite models.
     */
    fun analyzeText(text: String): ContentAnalysis {
        val lowerText = text.lowercase()

        return ContentAnalysis(
            extractedText = text,
            topics = classifyTopics(lowerText),
            sentimentScore = analyzeSentiment(lowerText),
            emotionLabel = detectEmotion(lowerText),
            toxicityScore = scoreToxicity(lowerText),
            persuasionScore = scorePersuasion(lowerText),
            creatorId = extractCreatorId(text)
        )
    }

    // --- Phase 1: Keyword-based heuristics (to be replaced by TFLite models) ---

    private val TOPIC_KEYWORDS = mapOf(
        "politics" to listOf("election", "vote", "democrat", "republican", "trump", "biden", "congress", "political", "government", "policy", "liberal", "conservative"),
        "news" to listOf("breaking", "report", "journalist", "news", "headline", "update", "confirmed"),
        "health" to listOf("health", "fitness", "workout", "diet", "exercise", "mental health", "wellness", "medical"),
        "finance" to listOf("stock", "crypto", "invest", "money", "finance", "trading", "market", "bitcoin", "profit"),
        "entertainment" to listOf("movie", "music", "celebrity", "concert", "album", "show", "streaming"),
        "technology" to listOf("tech", "ai", "software", "app", "startup", "programming", "developer"),
        "conspiracy" to listOf("conspiracy", "cover-up", "they don't want you to know", "wake up", "truth", "exposed", "secret"),
        "lifestyle" to listOf("recipe", "fashion", "travel", "home", "decor", "beauty", "skincare"),
        "humor" to listOf("funny", "lol", "comedy", "joke", "meme", "hilarious"),
        "education" to listOf("learn", "tutorial", "how to", "explain", "course", "study", "university")
    )

    private fun classifyTopics(text: String): List<String> {
        return TOPIC_KEYWORDS.entries
            .filter { (_, keywords) -> keywords.any { text.contains(it) } }
            .map { it.key }
            .ifEmpty { listOf("general") }
    }

    private val POSITIVE_WORDS = setOf("love", "great", "amazing", "wonderful", "happy", "beautiful", "excellent", "fantastic", "awesome", "good", "best", "perfect")
    private val NEGATIVE_WORDS = setOf("hate", "terrible", "horrible", "awful", "angry", "disgusting", "worst", "bad", "stupid", "pathetic", "fear", "scary", "disaster", "crisis")

    private fun analyzeSentiment(text: String): Float {
        val words = text.split(Regex("\\W+"))
        val posCount = words.count { it in POSITIVE_WORDS }
        val negCount = words.count { it in NEGATIVE_WORDS }
        val total = (posCount + negCount).coerceAtLeast(1)
        return ((posCount - negCount) / total.toFloat()).coerceIn(-1f, 1f)
    }

    private fun detectEmotion(text: String): String {
        val emotions = mapOf(
            "anger" to listOf("angry", "furious", "rage", "outrage", "mad", "infuriating"),
            "fear" to listOf("scared", "afraid", "terrified", "fear", "panic", "alarming"),
            "joy" to listOf("happy", "joyful", "excited", "thrilled", "celebrate", "wonderful"),
            "sadness" to listOf("sad", "depressed", "heartbroken", "grief", "crying", "miserable"),
            "surprise" to listOf("shocked", "surprised", "unexpected", "unbelievable", "wow"),
            "disgust" to listOf("disgusting", "gross", "revolting", "vile", "sickening")
        )
        val scores = emotions.mapValues { (_, words) -> words.count { text.contains(it) } }
        val topEmotion = scores.maxByOrNull { it.value }
        return if (topEmotion != null && topEmotion.value > 0) topEmotion.key else "neutral"
    }

    private val TOXIC_PATTERNS = listOf("kill", "die", "idiot", "moron", "shut up", "loser", "trash", "garbage", "worthless", "scum")

    private fun scoreToxicity(text: String): Float {
        val matchCount = TOXIC_PATTERNS.count { text.contains(it) }
        return (matchCount / 3f).coerceIn(0f, 1f) // 3+ matches = max toxicity
    }

    private val PERSUASION_PATTERNS = listOf(
        "you need to", "you must", "act now", "limited time", "don't miss", "hurry",
        "secret", "they don't want", "wake up", "open your eyes", "think about it",
        "share this", "spread the word", "everyone needs to see", "exposed", "the truth"
    )

    private fun scorePersuasion(text: String): Float {
        val matchCount = PERSUASION_PATTERNS.count { text.contains(it) }
        return (matchCount / 3f).coerceIn(0f, 1f)
    }

    /**
     * Extract @ mentions or creator-like patterns from text. Returns a hash.
     */
    private fun extractCreatorId(text: String): String? {
        val mentionPattern = Regex("@([\\w.]+)")
        val match = mentionPattern.find(text)
        return match?.groupValues?.get(1)?.hashCode()?.toString()
    }
}
