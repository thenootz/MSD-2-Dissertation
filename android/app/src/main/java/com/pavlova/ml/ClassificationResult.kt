package com.pavlova.ml

/**
 * Classification result with 5-class NSFW scores
 * Classes: drawing, hentai, neutral, porn, sexy
 */
data class ClassificationResult(
    /** Whether content is safe (neutral + drawing > hentai + porn + sexy) */
    val isSafe: Boolean,
    /** Aggregate confidence of safety determination */
    val confidence: Float,
    /** Top category name */
    val category: String,
    /** Raw per-class scores [drawing, hentai, neutral, porn, sexy] */
    val scores: FloatArray = floatArrayOf(0f, 0f, 1f, 0f, 0f),
    /** Name of highest-scoring class */
    val topClass: String = "neutral"
) {
    /** Whether content is adult (porn or hentai) */
    val isAdult: Boolean get() = category == "porn" || category == "hentai"
    
    /** Whether content is suggestive (sexy) */
    val isSuggestive: Boolean get() = category == "sexy"
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ClassificationResult) return false
        return isSafe == other.isSafe && 
               confidence == other.confidence &&
               category == other.category && 
               topClass == other.topClass &&
               scores.contentEquals(other.scores)
    }
    
    override fun hashCode(): Int {
        var result = isSafe.hashCode()
        result = 31 * result + confidence.hashCode()
        result = 31 * result + category.hashCode()
        result = 31 * result + topClass.hashCode()
        result = 31 * result + scores.contentHashCode()
        return result
    }
}
