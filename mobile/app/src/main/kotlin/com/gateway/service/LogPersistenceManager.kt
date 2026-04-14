package com.gateway.service

import com.gateway.data.db.dao.GatewayLogDao
import com.gateway.data.db.entity.GatewayLogEntity
import com.gateway.util.GatewayLogger
import com.gateway.util.LogEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class LogPersistenceManager @Inject constructor(
    private val gatewayLogDao: GatewayLogDao,
    @Named("ApplicationScope") private val applicationScope: CoroutineScope
) {
    companion object {
        private const val TAG = "LogPersistence"
        private const val MAX_LOG_COUNT = 5000
        private const val CLEANUP_INTERVAL = 500 // trim every N inserts
    }

    private var collectJob: Job? = null
    private var insertCount = 0

    fun start() {
        if (collectJob?.isActive == true) return

        collectJob = applicationScope.launch {
            GatewayLogger.logEvents
                .collect { entry ->
                    persistLog(entry)
                }
        }
    }

    fun stop() {
        collectJob?.cancel()
        collectJob = null
    }

    private suspend fun persistLog(entry: LogEntry) {
        try {
            val entity = GatewayLogEntity(
                timestamp = entry.timestamp,
                level = entry.level.name,
                tag = entry.tag,
                message = entry.message,
                throwable = entry.throwable
            )
            gatewayLogDao.insert(entity)

            insertCount++
            if (insertCount % CLEANUP_INTERVAL == 0) {
                gatewayLogDao.trimToCount(MAX_LOG_COUNT)
            }
        } catch (_: Exception) {
            // Avoid recursive logging if DB write fails
        }
    }
}
