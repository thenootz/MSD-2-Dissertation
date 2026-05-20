package com.pavlova.data.dao

import androidx.room.*
import com.pavlova.data.model.FeedSession
import kotlinx.coroutines.flow.Flow

@Dao
interface FeedSessionDao {

    @Query("SELECT * FROM feed_sessions ORDER BY startTime DESC")
    fun getAllSessions(): Flow<List<FeedSession>>

    @Query("SELECT * FROM feed_sessions WHERE id = :sessionId")
    suspend fun getSession(sessionId: String): FeedSession?

    @Query("SELECT * FROM feed_sessions WHERE endTime IS NULL ORDER BY startTime DESC LIMIT 1")
    suspend fun getActiveSession(): FeedSession?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: FeedSession)

    @Update
    suspend fun update(session: FeedSession)

    @Query("DELETE FROM feed_sessions WHERE id = :sessionId")
    suspend fun delete(sessionId: String)

    @Query("DELETE FROM feed_sessions")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM feed_sessions")
    suspend fun getSessionCount(): Int
}
