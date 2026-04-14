package com.gateway.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.gateway.data.db.entity.SmsQueueEntity
import com.gateway.data.db.entity.SmsStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface SmsQueueDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SmsQueueEntity): Long

    @Query("SELECT * FROM sms_queue WHERE id = :id")
    suspend fun getById(id: Long): SmsQueueEntity?

    @Query("SELECT * FROM sms_queue ORDER BY createdAt DESC")
    suspend fun getAll(): List<SmsQueueEntity>

    @Query("SELECT * FROM sms_queue WHERE status = 'PENDING' ORDER BY createdAt ASC")
    suspend fun getPendingMessages(): List<SmsQueueEntity>

    @Query("SELECT * FROM sms_queue WHERE status IN ('PENDING', 'SENDING') ORDER BY createdAt ASC")
    fun observePendingMessages(): Flow<List<SmsQueueEntity>>

    @Query("SELECT COUNT(*) FROM sms_queue WHERE status IN ('PENDING', 'SENDING')")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM sms_queue WHERE status IN ('PENDING', 'SENDING')")
    suspend fun getPendingCount(): Int

    @Query("SELECT COUNT(*) FROM sms_queue")
    suspend fun getTotalCount(): Int

    @Query("SELECT COUNT(*) FROM sms_queue WHERE status IN ('SENT', 'DELIVERED')")
    suspend fun getSentCount(): Int

    @Query("SELECT COUNT(*) FROM sms_queue WHERE status = 'FAILED'")
    suspend fun getFailedCount(): Int

    @Query("UPDATE sms_queue SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: SmsStatus)

    @Query("UPDATE sms_queue SET attempts = attempts + 1 WHERE id = :id")
    suspend fun incrementAttempts(id: Long)

    @Query("DELETE FROM sms_queue WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM sms_queue WHERE createdAt < :cutoff AND status IN ('SENT', 'DELIVERED', 'FAILED')")
    suspend fun deleteOlderThan(cutoff: Long)
}
