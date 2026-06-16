package com.pavlova.data.dao

import androidx.room.*
import com.pavlova.data.model.SessionMetrics
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionMetricsDao {

    @Query("SELECT * FROM session_metrics WHERE sessionId = :sessionId ORDER BY computedAt DESC LIMIT 1")
    suspend fun getMetrics(sessionId: String): SessionMetrics?

    @Query("SELECT * FROM session_metrics WHERE sessionId = :sessionId ORDER BY computedAt DESC LIMIT 1")
    fun getLatestMetricsForSession(sessionId: String): Flow<SessionMetrics?>

    @Query("SELECT * FROM session_metrics ORDER BY computedAt DESC")
    fun getAllMetrics(): Flow<List<SessionMetrics>>

    @Query("SELECT * FROM session_metrics ORDER BY computedAt DESC LIMIT :limit")
    fun getRecentMetrics(limit: Int = 30): Flow<List<SessionMetrics>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(metrics: SessionMetrics): Long

    @Query("DELETE FROM session_metrics WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: String)

    @Query("DELETE FROM session_metrics")
    suspend fun deleteAll()
}
