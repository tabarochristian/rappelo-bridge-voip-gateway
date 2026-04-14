package com.gateway.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.gateway.data.db.entity.EventOutboxEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EventOutboxDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: EventOutboxEntity): Long

    @Query("SELECT * FROM event_outbox WHERE id = :id")
    suspend fun getById(id: Long): EventOutboxEntity?

    @Query("SELECT * FROM event_outbox ORDER BY createdAt ASC")
    suspend fun getAll(): List<EventOutboxEntity>

    @Query("SELECT * FROM event_outbox WHERE retryCount < :maxRetries ORDER BY createdAt ASC LIMIT :limit")
    suspend fun getPendingEvents(maxRetries: Int = 5, limit: Int = 10): List<EventOutboxEntity>

    @Query("SELECT COUNT(*) FROM event_outbox WHERE retryCount < :maxRetries")
    fun observePendingCount(maxRetries: Int = 5): Flow<Int>

    @Query("SELECT COUNT(*) FROM event_outbox")
    suspend fun getTotalCount(): Int

    @Query("SELECT COUNT(*) FROM event_outbox WHERE retryCount >= :maxRetries")
    suspend fun getFailedCount(maxRetries: Int = 5): Int

    @Query("UPDATE event_outbox SET retryCount = retryCount + 1, lastRetryAt = :timestamp, errorMessage = :error WHERE id = :id")
    suspend fun markRetried(id: Long, timestamp: Long, error: String?)

    @Query("DELETE FROM event_outbox WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM event_outbox WHERE createdAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("DELETE FROM event_outbox WHERE retryCount >= :maxRetries AND createdAt < :cutoff")
    suspend fun deleteFailedOlderThan(maxRetries: Int, cutoff: Long)
}
