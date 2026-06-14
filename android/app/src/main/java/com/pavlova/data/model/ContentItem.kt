package com.pavlova.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single content item captured from a social media feed.
 * Contains extracted text, NLP scores, and metadata.
 * Privacy-preserving: no raw screenshots stored — only extracted text and scores.
 */
@Entity(
    tableName = "content_items",
    foreignKeys = [
        ForeignKey(
            entity = FeedSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class ContentItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val sessionId: String,
    val timestamp: Long = System.currentTimeMillis(),

    /** Position in the feed (order seen) */
    val position: Int = 0,

    /** OCR-extracted text (captions, hashtags, overlays) */
    val textContent: String? = null,

    /** Creator handle extracted from an on-screen @mention (without the '@') */
    val creatorId: String? = null,

    /** Absolute path to a downscaled JPEG thumbnail of this frame, if saved */
    val screenshotPath: String? = null,

    // --- NLP Scores (filled by analysis pipeline) ---

    /** Topic labels as JSON array, e.g. ["politics","news"] */
    val topicLabels: String? = null,

    /** Sentiment score: -1.0 (very negative) to +1.0 (very positive) */
    val sentimentScore: Float? = null,

    /** Primary emotion label: joy, anger, fear, sadness, surprise, disgust, neutral */
    val emotionLabel: String? = null,

    /** Toxicity score: 0.0 (benign) to 1.0 (highly toxic) */
    val toxicityScore: Float? = null,

    /** Persuasion/manipulation score: 0.0 (informational) to 1.0 (highly persuasive) */
    val persuasionScore: Float? = null,

    /** Estimated watch duration in milliseconds */
    val watchDurationMs: Long = 0,

    /** User action: scroll, like, skip, share, comment, none */
    val userAction: String = "scroll"
)
