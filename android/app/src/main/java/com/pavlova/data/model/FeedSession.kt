package com.pavlova.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * A single feed capture session.
 * Tracks when the user started/stopped auditing a platform's feed.
 */
@Entity(tableName = "feed_sessions")
data class FeedSession(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val platform: String = "tiktok",
    val startTime: Long = System.currentTimeMillis(),
    val endTime: Long? = null,
    val totalItems: Int = 0,
    val avgWatchDurationMs: Long = 0
)
