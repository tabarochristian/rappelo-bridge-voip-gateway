package com.gateway.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.gateway.data.db.entity.GatewayLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GatewayLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: GatewayLogEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<GatewayLogEntity>)

    @Query("SELECT * FROM gateway_logs ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 50): List<GatewayLogEntity>

    @Query("SELECT * FROM gateway_logs ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<GatewayLogEntity>>

    @Query("SELECT * FROM gateway_logs WHERE level = :level ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getByLevel(level: String, limit: Int = 50): List<GatewayLogEntity>

    @Query("SELECT * FROM gateway_logs WHERE tag = :tag ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getByTag(tag: String, limit: Int = 50): List<GatewayLogEntity>

    @Query("SELECT * FROM gateway_logs WHERE message LIKE '%' || :search || '%' ORDER BY timestamp DESC LIMIT :limit")
    suspend fun search(search: String, limit: Int = 50): List<GatewayLogEntity>

    @Query("SELECT COUNT(*) FROM gateway_logs")
    suspend fun getTotalCount(): Int

    @Query("SELECT COUNT(*) FROM gateway_logs WHERE level = 'ERROR'")
    suspend fun getErrorCount(): Int

    @Query("SELECT COUNT(*) FROM gateway_logs WHERE level = 'WARN'")
    suspend fun getWarningCount(): Int

    @Query("DELETE FROM gateway_logs WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("DELETE FROM gateway_logs")
    suspend fun deleteAll()

    @Query("DELETE FROM gateway_logs WHERE id NOT IN (SELECT id FROM gateway_logs ORDER BY timestamp DESC LIMIT :keepCount)")
    suspend fun trimToCount(keepCount: Int = 1000)
}
