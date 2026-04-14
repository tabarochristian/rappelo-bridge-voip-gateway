package com.gateway.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.gateway.data.db.entity.QueuedCallEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface QueuedCallDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: QueuedCallEntity): Long

    @Query("SELECT * FROM queued_calls WHERE id = :id")
    suspend fun getById(id: Long): QueuedCallEntity?

    @Query("SELECT * FROM queued_calls WHERE callerId = :callerId AND status IN ('WAITING', 'ACTIVE') ORDER BY enqueuedAt DESC LIMIT 1")
    suspend fun getByCallerId(callerId: String): QueuedCallEntity?

    @Query("SELECT * FROM queued_calls ORDER BY enqueuedAt DESC")
    suspend fun getAll(): List<QueuedCallEntity>

    @Query("SELECT * FROM queued_calls WHERE status IN ('WAITING', 'ACTIVE') ORDER BY enqueuedAt ASC")
    fun observeActiveCalls(): Flow<List<QueuedCallEntity>>

    @Query("SELECT COUNT(*) FROM queued_calls WHERE status IN ('WAITING', 'ACTIVE')")
    suspend fun getActiveCount(): Int

    @Query("SELECT COUNT(*) FROM queued_calls")
    suspend fun getTotalCount(): Int

    @Query("SELECT COUNT(*) FROM queued_calls WHERE status = 'COMPLETED'")
    suspend fun getCompletedCount(): Int

    @Query("SELECT COUNT(*) FROM queued_calls WHERE status = 'FAILED'")
    suspend fun getFailedCount(): Int

    @Query("UPDATE queued_calls SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    @Query("UPDATE queued_calls SET answeredAt = :answeredAt WHERE id = :id")
    suspend fun updateAnsweredAt(id: Long, answeredAt: Long)

    @Query("UPDATE queued_calls SET endedAt = :endedAt WHERE id = :id")
    suspend fun updateEndedAt(id: Long, endedAt: Long)

    @Query("UPDATE queued_calls SET failureReason = :reason WHERE id = :id")
    suspend fun updateFailureReason(id: Long, reason: String)

    @Query("DELETE FROM queued_calls WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM queued_calls WHERE endedAt IS NOT NULL AND endedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("SELECT AVG(endedAt - answeredAt) FROM queued_calls WHERE answeredAt IS NOT NULL AND endedAt IS NOT NULL")
    suspend fun getAverageCallDuration(): Long?

    @Query("SELECT AVG(answeredAt - enqueuedAt) FROM queued_calls WHERE answeredAt IS NOT NULL")
    suspend fun getAverageWaitTime(): Long?
}
