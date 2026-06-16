package com.pavlova.ml

import android.content.Context
import android.graphics.Bitmap
import android.util.Log

/**
 * Orchestrates the full content analysis pipeline:
 *   1. OCR text extraction (ML Kit)
 *   2. Sentiment analysis (RoBERTa TFLite → keyword fallback)
 *   3. Toxicity scoring (RoBERTa TFLite → keyword fallback)
 *   4. Topic classification (keyword-based — custom TFLite later)
 *   5. Emotion detection (keyword-based — custom TFLite later)
 *   6. Persuasion scoring (keyword-based — custom TFLite later)
 *   7. Semantic embedding (SBERT TFLite → hash fallback)
 *
 * Models are loaded lazily via [initialize]. If a model file is not
 * present in assets/, that model's slot gracefully falls back to
 * keyword heuristics — no crash, no missing functionality.
 */
object ContentAnalyzer {
    private const val TAG = "ContentAnalyzer"

    // TFLite NLP runners (lazy-loaded via initialize)
    private var sentimentRunner: NlpModelRunner? = null
    private var toxicityRunner: NlpModelRunner? = null
    private var embeddingEngine: EmbeddingEngine? = null

    private var initialized = false

    /**
     * Load TFLite models. Call once from Application.onCreate or first use.
     * Safe to call multiple times — idempotent.
     */
    fun initialize(context: Context) {
        if (initialized) return

        sentimentRunner = NlpModelRunner(
            context = context,
            modelFileName = "roberta_sentiment.tflite",
            maxSeqLen = 128,
            numClasses = 3,
            labels = listOf("negative", "neutral", "positive")
        ).also { it.load() }

        toxicityRunner = NlpModelRunner(
            context = context,
            modelFileName = "roberta_toxicity.tflite",
            maxSeqLen = 128,
            numClasses = 2,
            labels = listOf("benign", "toxic")
        ).also { it.load() }

        embeddingEngine = EmbeddingEngine(context).also { it.load() }

        val modelStatus = listOf(
            "sentiment" to (sentimentRunner?.isAvailable == true),
            "toxicity" to (toxicityRunner?.isAvailable == true),
            "sbert" to (embeddingEngine?.isModelLoaded == true)
        )
        Log.d(TAG, "Models: ${modelStatus.joinToString { "${it.first}=${if (it.second) "TFLite" else "heuristic"}" }}")
        initialized = true
    }

    /**
     * Analyze a captured frame: OCR → NLP pipeline → ContentAnalysis
     */
    suspend fun analyze(bitmap: Bitmap): ContentAnalysis {
        val startTime = System.currentTimeMillis()
        val text = TextExtractor.extractText(bitmap)
        if (text.isBlank()) {
            return ContentAnalysis(processingTimeMs = System.currentTimeMillis() - startTime)
        }
        val analysis = analyzeText(text)
        val elapsed = System.currentTimeMillis() - startTime
        Log.d(TAG, "Analysis in ${elapsed}ms: sentiment=${analysis.sentimentScore}, " +
                "topics=${analysis.topics}, toxicity=${analysis.toxicityScore}")
        return analysis.copy(processingTimeMs = elapsed)
    }

    /**
     * Analyze extracted text through the NLP pipeline.
     * Uses TFLite models when available, keyword heuristics otherwise.
     */
    fun analyzeText(text: String): ContentAnalysis {
        val lowerText = text.lowercase()

        // Sentiment: RoBERTa model → keyword fallback
        val sentimentScore = if (sentimentRunner?.isAvailable == true) {
            val dist = sentimentRunner!!.predictDistribution(text)
            val pos = dist["positive"] ?: 0f
            val neg = dist["negative"] ?: 0f
            (pos - neg).coerceIn(-1f, 1f)
        } else {
            analyzeSentimentKeyword(lowerText)
        }

        // Toxicity: RoBERTa model → keyword fallback
        val toxicityScore = if (toxicityRunner?.isAvailable == true) {
            val dist = toxicityRunner!!.predictDistribution(text)
            dist["toxic"] ?: 0f
        } else {
            scoreToxicityKeyword(lowerText)
        }

        // Embedding: SBERT model → hash fallback
        val embedding = embeddingEngine?.embed(text)

        return ContentAnalysis(
            extractedText = text,
            topics = classifyTopics(lowerText),
            sentimentScore = sentimentScore,
            emotionLabel = detectEmotion(lowerText),
            toxicityScore = toxicityScore,
            persuasionScore = scorePersuasion(lowerText),
            creatorId = extractCreatorId(text),
            embedding = embedding
        )
    }

    /** Exposed for reuse by [com.pavlova.analysis.ManipulationDetector] so the SBERT TFLite model
     *  isn't loaded twice into memory. Null before [initialize] is called. */
    fun embeddingEngine(): EmbeddingEngine? = embeddingEngine

    fun destroy() {
        sentimentRunner?.close()
        toxicityRunner?.close()
        embeddingEngine?.close()
        initialized = false
    }

    // --- Keyword heuristics (used when TFLite models are not available) ---

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

    private fun analyzeSentimentKeyword(text: String): Float {
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
        val top = scores.maxByOrNull { it.value }
        return if (top != null && top.value > 0) top.key else "neutral"
    }

    private val TOXIC_PATTERNS = listOf("kill", "die", "idiot", "moron", "shut up", "loser", "trash", "garbage", "worthless", "scum")

    private fun scoreToxicityKeyword(text: String): Float {
        val matchCount = TOXIC_PATTERNS.count { text.contains(it) }
        return (matchCount / 3f).coerceIn(0f, 1f)
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

    private fun extractCreatorId(text: String): String? {
        val mentionPattern = Regex("@([\\w.]+)")
        val match = mentionPattern.find(text)
        return match?.groupValues?.get(1)
    }
}
