package com.pavlova.data.dao

import androidx.room.*
import com.pavlova.data.model.FilterEvent
import kotlinx.coroutines.flow.Flow

@Dao
interface FilterEventDao {

    @Query("SELECT * FROM filter_events ORDER BY timestamp DESC")
    fun getAllEvents(): Flow<List<FilterEvent>>

    @Query("SELECT * FROM filter_events WHERE timestamp >= :startTime AND timestamp <= :endTime ORDER BY timestamp DESC")
    fun getEventsBetween(startTime: Long, endTime: Long): Flow<List<FilterEvent>>

    @Query("SELECT COUNT(*) FROM filter_events")
    suspend fun getEventCount(): Int

    @Query("SELECT * FROM filter_events WHERE category = :category ORDER BY timestamp DESC")
    fun getEventsByCategory(category: String): Flow<List<FilterEvent>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: FilterEvent): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<FilterEvent>)

    @Delete
    suspend fun delete(event: FilterEvent)

    @Query("DELETE FROM filter_events")
    suspend fun deleteAll()

    @Query("DELETE FROM filter_events WHERE timestamp < :beforeTime")
    suspend fun deleteOlderThan(beforeTime: Long)
}
