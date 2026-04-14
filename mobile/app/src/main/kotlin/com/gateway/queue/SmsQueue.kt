package com.gateway.queue

import com.gateway.data.db.dao.SmsQueueDao
import com.gateway.data.db.entity.SmsQueueEntity
import com.gateway.data.db.entity.SmsStatus
import com.gateway.telephony.gsm.SmsGsmManager
import com.gateway.util.GatewayLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SMS Queue interface for managing outgoing SMS messages
 */
interface SmsQueue {
    /** Pending SMS count */
    val pendingSmsCount: StateFlow<Int>

    /** All pending SMS messages */
    val pendingMessages: StateFlow<List<SmsQueueInfo>>

    /** Queue an SMS for sending */
    suspend fun queueSms(to: String, body: String, simSlot: Int = 0): Long

    /** Retry a failed SMS */
    suspend fun retrySms(messageId: Long): Boolean

    /** Cancel a pending SMS */
    suspend fun cancelSms(messageId: Long)

    /** Clear all completed/failed SMS older than specified time */
    suspend fun clearOldMessages(maxAgeMs: Long = 24 * 60 * 60 * 1000)

    /** Start processing queue */
    fun startProcessing()

    /** Stop processing queue */
    fun stopProcessing()
}

@Singleton
class SmsQueueImpl @Inject constructor(
    private val smsQueueDao: SmsQueueDao,
    private val smsGsmManager: SmsGsmManager
) : SmsQueue {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _pendingSmsCount = MutableStateFlow(0)
    override val pendingSmsCount: StateFlow<Int> = _pendingSmsCount.asStateFlow()

    private val _pendingMessages = MutableStateFlow<List<SmsQueueInfo>>(emptyList())
    override val pendingMessages: StateFlow<List<SmsQueueInfo>> = _pendingMessages.asStateFlow()

    private var isProcessing = false

    init {
        scope.launch {
            smsQueueDao.observePendingCount().collect { count ->
                _pendingSmsCount.value = count
            }
        }

        scope.launch {
            smsQueueDao.observePendingMessages().collect { entities ->
                _pendingMessages.value = entities.map { it.toInfo() }
            }
        }
    }

    override suspend fun queueSms(to: String, body: String, simSlot: Int): Long {
        val entity = SmsQueueEntity(
            phoneNumber = to,
            body = body,
            simSlot = simSlot,
            status = SmsStatus.PENDING,
            createdAt = System.currentTimeMillis(),
            attempts = 0
        )

        val id = smsQueueDao.insert(entity)
        GatewayLogger.info(TAG, "Queued SMS to ${to.take(4)}..., id: $id")
        
        return id
    }

    override suspend fun retrySms(messageId: Long): Boolean {
        val entity = smsQueueDao.getById(messageId)
        if (entity == null) {
            GatewayLogger.warn(TAG, "SMS $messageId not found")
            return false
        }

        if (entity.attempts >= MAX_RETRY_ATTEMPTS) {
            GatewayLogger.warn(TAG, "SMS $messageId exceeded max retries")
            return false
        }

        smsQueueDao.updateStatus(messageId, SmsStatus.PENDING)
        GatewayLogger.info(TAG, "Reset SMS $messageId for retry")
        
        return true
    }

    override suspend fun cancelSms(messageId: Long) {
        smsQueueDao.deleteById(messageId)
        GatewayLogger.info(TAG, "Cancelled SMS $messageId")
    }

    override suspend fun clearOldMessages(maxAgeMs: Long) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        smsQueueDao.deleteOlderThan(cutoff)
        GatewayLogger.debug(TAG, "Cleared old SMS messages before $cutoff")
    }

    override fun startProcessing() {
        if (isProcessing) return
        isProcessing = true

        scope.launch {
            GatewayLogger.info(TAG, "Starting SMS queue processing")
            
            while (isActive && isProcessing) {
                processNextMessage()
                delay(PROCESS_INTERVAL_MS)
            }
        }
    }

    override fun stopProcessing() {
        isProcessing = false
        GatewayLogger.info(TAG, "Stopped SMS queue processing")
    }

    private suspend fun processNextMessage() {
        val pendingMessages = smsQueueDao.getPendingMessages()
        
        for (message in pendingMessages) {
            if (!isProcessing) break
            
            if (message.attempts >= MAX_RETRY_ATTEMPTS) {
                smsQueueDao.updateStatus(message.id, SmsStatus.FAILED)
                continue
            }

            try {
                GatewayLogger.debug(TAG, "Processing SMS ${message.id}")
                
                smsQueueDao.updateStatus(message.id, SmsStatus.SENDING)
                smsQueueDao.incrementAttempts(message.id)
                
                val success = smsGsmManager.sendSms(
                    to = message.phoneNumber,
                    body = message.body,
                    simSlot = message.simSlot
                )

                if (success) {
                    smsQueueDao.updateStatus(message.id, SmsStatus.SENT)
                    GatewayLogger.info(TAG, "SMS ${message.id} sent successfully")
                } else {
                    handleSendFailure(message)
                }
                
            } catch (e: Exception) {
                GatewayLogger.error(TAG, "Error processing SMS ${message.id}", e)
                handleSendFailure(message)
            }
            
            // Small delay between messages
            delay(500)
        }
    }

    private suspend fun handleSendFailure(message: SmsQueueEntity) {
        val updatedAttempts = message.attempts + 1
        
        if (updatedAttempts >= MAX_RETRY_ATTEMPTS) {
            smsQueueDao.updateStatus(message.id, SmsStatus.FAILED)
            GatewayLogger.warn(TAG, "SMS ${message.id} failed after $updatedAttempts attempts")
        } else {
            // Reset to pending for retry
            smsQueueDao.updateStatus(message.id, SmsStatus.PENDING)
            
            // Calculate backoff delay
            val backoffMs = calculateBackoff(updatedAttempts)
            GatewayLogger.debug(TAG, "SMS ${message.id} will retry in ${backoffMs}ms")
            delay(backoffMs)
        }
    }

    private fun calculateBackoff(attempts: Int): Long {
        return (Math.pow(2.0, attempts.toDouble()) * 1000).toLong().coerceAtMost(60000)
    }

    private fun SmsQueueEntity.toInfo(): SmsQueueInfo {
        return SmsQueueInfo(
            id = id,
            phoneNumber = phoneNumber,
            body = body,
            simSlot = simSlot,
            status = status,
            attempts = attempts,
            createdAt = createdAt
        )
    }

    companion object {
        private const val TAG = "SmsQueue"
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val PROCESS_INTERVAL_MS = 5000L
    }
}

data class SmsQueueInfo(
    val id: Long,
    val phoneNumber: String,
    val body: String,
    val simSlot: Int,
    val status: SmsStatus,
    val attempts: Int,
    val createdAt: Long
)
