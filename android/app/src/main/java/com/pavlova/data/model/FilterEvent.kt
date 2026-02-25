package com.pavlova.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Privacy-preserving filter event log
 * NO screenshots are stored - only metadata
 */
@Entity(tableName = "filter_events")
data class FilterEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    val timestamp: Long,
    val category: String,  // e.g., "safe", "unsafe", "nsfw"
    val confidence: Float,
    val action: String     // e.g., "blur", "pixelate", "alert"
)
