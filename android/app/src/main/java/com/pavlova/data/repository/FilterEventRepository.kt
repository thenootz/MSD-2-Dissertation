package com.pavlova.data.repository

import com.pavlova.data.dao.FilterEventDao
import com.pavlova.data.model.FilterEvent
import kotlinx.coroutines.flow.Flow

class FilterEventRepository(private val dao: FilterEventDao) {

    val allEvents: Flow<List<FilterEvent>> = dao.getAllEvents()

    suspend fun insert(event: FilterEvent): Long {
        return dao.insert(event)
    }

    suspend fun deleteAll() {
        dao.deleteAll()
    }

    suspend fun getEventCount(): Int {
        return dao.getEventCount()
    }

    fun getEventsBetween(startTime: Long, endTime: Long): Flow<List<FilterEvent>> {
        return dao.getEventsBetween(startTime, endTime)
    }

    fun getEventsByCategory(category: String): Flow<List<FilterEvent>> {
        return dao.getEventsByCategory(category)
    }

    suspend fun deleteOlderThan(beforeTime: Long) {
        dao.deleteOlderThan(beforeTime)
    }
}
