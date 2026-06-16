package com.pavlova.ml

/**
 * Analysis result for a single content item.
 * Multi-dimensional NLP scores from the RoBERTa + SBERT + keyword pipeline.
 */
data class ContentAnalysis(
    /** OCR-extracted text from the feed item */
    val extractedText: String = "",

    /** Detected topic labels */
    val topics: List<String> = emptyList(),

    /** Sentiment score: -1.0 (very negative) to +1.0 (very positive) */
    val sentimentScore: Float = 0f,

    /** Primary emotion: joy, anger, fear, sadness, surprise, disgust, neutral */
    val emotionLabel: String = "neutral",

    /** Toxicity score: 0.0 (benign) to 1.0 (highly toxic) */
    val toxicityScore: Float = 0f,

    /** Persuasion/manipulation language score: 0.0 to 1.0 */
    val persuasionScore: Float = 0f,

    /** Creator handle extracted from an on-screen @mention (without the '@') */
    val creatorId: String? = null,

    /** SBERT embedding vector for semantic clustering */
    val embedding: FloatArray? = null,

    /** Processing time in milliseconds */
    val processingTimeMs: Long = 0
) {
    /** Encode topics as JSON array string for Room storage */
    fun topicsAsJson(): String =
        topics.joinToString(",", "[", "]") { "\"$it\"" }
}
