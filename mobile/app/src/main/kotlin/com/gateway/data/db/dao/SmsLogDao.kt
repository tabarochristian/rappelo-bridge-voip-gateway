package com.gateway.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.gateway.data.db.entity.SmsLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SmsLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SmsLogEntity): Long

    @Query("SELECT * FROM sms_log WHERE id = :id")
    suspend fun getById(id: Long): SmsLogEntity?

    @Query("SELECT * FROM sms_log ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 50): List<SmsLogEntity>

    @Query("SELECT * FROM sms_log ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<SmsLogEntity>>

    @Query("SELECT * FROM sms_log WHERE phoneNumber = :phoneNumber ORDER BY timestamp DESC")
    suspend fun getByPhoneNumber(phoneNumber: String): List<SmsLogEntity>

    @Query("SELECT * FROM sms_log WHERE isIncoming = :isIncoming ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getByDirection(isIncoming: Boolean, limit: Int = 50): List<SmsLogEntity>

    @Query("SELECT COUNT(*) FROM sms_log")
    suspend fun getTotalCount(): Int

    @Query("SELECT COUNT(*) FROM sms_log WHERE isIncoming = 1")
    suspend fun getIncomingCount(): Int

    @Query("SELECT COUNT(*) FROM sms_log WHERE isIncoming = 0")
    suspend fun getOutgoingCount(): Int

    @Query("DELETE FROM sms_log WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("DELETE FROM sms_log")
    suspend fun deleteAll()
}
