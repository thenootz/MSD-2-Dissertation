package com.pavlova.data.dao

import androidx.room.*
import com.pavlova.data.model.ContentItem
import kotlinx.coroutines.flow.Flow

@Dao
interface ContentItemDao {

    @Query("SELECT * FROM content_items WHERE sessionId = :sessionId ORDER BY position ASC")
    fun getItemsForSession(sessionId: String): Flow<List<ContentItem>>

    @Query("SELECT * FROM content_items WHERE sessionId = :sessionId ORDER BY position ASC")
    suspend fun getItemsForSessionSync(sessionId: String): List<ContentItem>

    @Query("SELECT * FROM content_items ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentItems(limit: Int = 100): Flow<List<ContentItem>>

    @Query("SELECT COUNT(*) FROM content_items WHERE sessionId = :sessionId")
    suspend fun getItemCount(sessionId: String): Int

    @Query("SELECT COUNT(*) FROM content_items")
    suspend fun getTotalCount(): Int

    @Query("SELECT AVG(sentimentScore) FROM content_items WHERE sessionId = :sessionId AND sentimentScore IS NOT NULL")
    suspend fun getAvgSentiment(sessionId: String): Float?

    @Query("SELECT AVG(toxicityScore) FROM content_items WHERE sessionId = :sessionId AND toxicityScore IS NOT NULL")
    suspend fun getAvgToxicity(sessionId: String): Float?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: ContentItem): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<ContentItem>)

    @Update
    suspend fun update(item: ContentItem)

    @Query("UPDATE content_items SET creatorId = :creatorId WHERE id IN (:ids)")
    suspend fun updateCreatorForIds(ids: List<Long>, creatorId: String?)

    @Query("DELETE FROM content_items WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: String)

    @Query("DELETE FROM content_items")
    suspend fun deleteAll()
}
