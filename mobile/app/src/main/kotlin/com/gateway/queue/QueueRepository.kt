package com.gateway.queue

import com.gateway.data.db.dao.QueuedCallDao
import com.gateway.data.db.dao.SmsQueueDao
import com.gateway.queue.model.CallDirection
import com.gateway.queue.model.QueueStatus
import com.gateway.queue.model.QueuedCallInfo
import com.gateway.telephony.gsm.EndReason
import com.gateway.util.GatewayLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for unified queue operations
 */
interface QueueRepository {
    /** Observe all active items in queues */
    fun observeActiveQueues(): Flow<QueueSummary>

    /** Get queue statistics */
    suspend fun getQueueStats(): QueueStats

    /** Cleanup old entries from all queues */
    suspend fun cleanupOldEntries(maxAgeMs: Long = 24 * 60 * 60 * 1000)

    /** Export queue data as JSON */
    suspend fun exportQueueData(): String
}

@Singleton
class QueueRepositoryImpl @Inject constructor(
    private val callQueue: CallQueue,
    private val smsQueue: SmsQueue,
    private val queuedCallDao: QueuedCallDao,
    private val smsQueueDao: SmsQueueDao
) : QueueRepository {

    override fun observeActiveQueues(): Flow<QueueSummary> {
        return combine(
            callQueue.queueState,
            smsQueue.pendingMessages
        ) { calls, sms ->
            QueueSummary(
                activeCalls = calls.filter { it.status == QueueStatus.ACTIVE },
                waitingCalls = calls.filter { it.status == QueueStatus.WAITING },
                pendingSms = sms.filter { it.status == com.gateway.data.db.entity.SmsStatus.PENDING },
                failedSms = sms.filter { it.status == com.gateway.data.db.entity.SmsStatus.FAILED }
            )
        }
    }

    override suspend fun getQueueStats(): QueueStats {
        val totalCalls = queuedCallDao.getTotalCount()
        val activeCalls = queuedCallDao.getActiveCount()
        val completedCalls = queuedCallDao.getCompletedCount()
        val failedCalls = queuedCallDao.getFailedCount()
        
        val totalSms = smsQueueDao.getTotalCount()
        val pendingSms = smsQueueDao.getPendingCount()
        val sentSms = smsQueueDao.getSentCount()
        val failedSms = smsQueueDao.getFailedCount()
        
        val avgCallDuration = queuedCallDao.getAverageCallDuration()
        val avgWaitTime = queuedCallDao.getAverageWaitTime()

        return QueueStats(
            totalCalls = totalCalls,
            activeCalls = activeCalls,
            completedCalls = completedCalls,
            failedCalls = failedCalls,
            totalSms = totalSms,
            pendingSms = pendingSms,
            sentSms = sentSms,
            failedSms = failedSms,
            averageCallDurationMs = avgCallDuration,
            averageWaitTimeMs = avgWaitTime
        )
    }

    override suspend fun cleanupOldEntries(maxAgeMs: Long) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        
        queuedCallDao.deleteOlderThan(cutoff)
        smsQueueDao.deleteOlderThan(cutoff)
        
        GatewayLogger.info(TAG, "Cleaned up queue entries older than $cutoff")
    }

    override suspend fun exportQueueData(): String {
        val calls = queuedCallDao.getAll()
        val sms = smsQueueDao.getAll()
        
        val callsJson = calls.map { call ->
            """
            {
                "id": ${call.id},
                "callerId": "${call.callerId}",
                "direction": "${call.direction}",
                "status": "${call.status}",
                "simSlot": ${call.simSlot},
                "enqueuedAt": ${call.enqueuedAt},
                "answeredAt": ${call.answeredAt},
                "endedAt": ${call.endedAt},
                "failureReason": ${call.failureReason?.let { "\"$it\"" } ?: "null"}
            }
            """.trimIndent()
        }
        
        val smsJson = sms.map { msg ->
            """
            {
                "id": ${msg.id},
                "phoneNumber": "${msg.phoneNumber}",
                "simSlot": ${msg.simSlot},
                "status": "${msg.status.name}",
                "attempts": ${msg.attempts},
                "createdAt": ${msg.createdAt}
            }
            """.trimIndent()
        }
        
        return """
        {
            "exportedAt": ${System.currentTimeMillis()},
            "calls": [${callsJson.joinToString(",")}],
            "sms": [${smsJson.joinToString(",")}]
        }
        """.trimIndent()
    }

    companion object {
        private const val TAG = "QueueRepository"
    }
}

data class QueueSummary(
    val activeCalls: List<QueuedCallInfo>,
    val waitingCalls: List<QueuedCallInfo>,
    val pendingSms: List<SmsQueueInfo>,
    val failedSms: List<SmsQueueInfo>
) {
    val totalActiveItems: Int
        get() = activeCalls.size + waitingCalls.size + pendingSms.size
}

data class QueueStats(
    val totalCalls: Int,
    val activeCalls: Int,
    val completedCalls: Int,
    val failedCalls: Int,
    val totalSms: Int,
    val pendingSms: Int,
    val sentSms: Int,
    val failedSms: Int,
    val averageCallDurationMs: Long?,
    val averageWaitTimeMs: Long?
)
