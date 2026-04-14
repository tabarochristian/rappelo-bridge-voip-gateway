package com.gateway.queue

import com.gateway.data.db.dao.QueuedCallDao
import com.gateway.data.db.entity.QueuedCallEntity
import com.gateway.data.prefs.EncryptedPrefsManager
import com.gateway.data.prefs.QueueOverflowStrategy
import com.gateway.queue.model.CallDirection
import com.gateway.queue.model.QueueStatus
import com.gateway.queue.model.QueuedCallInfo
import com.gateway.telephony.gsm.EndReason
import com.gateway.util.GatewayLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Call queue interface for managing incoming and outgoing calls
 */
interface CallQueue {
    /** Current queue state */
    val queueState: StateFlow<List<QueuedCallInfo>>

    /** Active calls count */
    val activeCallCount: StateFlow<Int>

    /** Waiting calls count */
    val waitingCallCount: StateFlow<Int>

    /**
     * Enqueue a new call
     * @return true if enqueued, false if queue is full
     */
    suspend fun enqueueCall(
        callerId: String,
        direction: CallDirection,
        simSlot: Int = 0
    ): Boolean

    /** Mark a call as active (answered) */
    suspend fun markCallActive(callerId: String)

    /** Mark a call as completed */
    suspend fun markCallCompleted(callerId: String, reason: EndReason)

    /** Mark a call as failed */
    suspend fun markCallFailed(callerId: String, reason: String)

    /** Remove a call from queue */
    suspend fun removeCall(callId: Long)

    /** Get call by ID */
    suspend fun getCall(callId: Long): QueuedCallInfo?

    /** Get call by caller ID */
    suspend fun getCallByCallerId(callerId: String): QueuedCallInfo?

    /** Clear completed calls older than specified time */
    suspend fun clearOldCalls(maxAgeMs: Long = 24 * 60 * 60 * 1000)

    /** Check if queue has capacity */
    fun hasCapacity(): Boolean
}

@Singleton
class CallQueueImpl @Inject constructor(
    private val queuedCallDao: QueuedCallDao,
    private val prefsManager: EncryptedPrefsManager
) : CallQueue {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _queueState = MutableStateFlow<List<QueuedCallInfo>>(emptyList())
    override val queueState: StateFlow<List<QueuedCallInfo>> = _queueState.asStateFlow()

    override val activeCallCount: StateFlow<Int> = queueState
        .map { calls -> calls.count { it.status == QueueStatus.ACTIVE } }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    override val waitingCallCount: StateFlow<Int> = queueState
        .map { calls -> calls.count { it.status == QueueStatus.WAITING } }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    init {
        // Observe database changes
        scope.launch {
            queuedCallDao.observeActiveCalls().collect { entities ->
                _queueState.value = entities.map { it.toInfo() }
            }
        }
    }

    override suspend fun enqueueCall(
        callerId: String,
        direction: CallDirection,
        simSlot: Int
    ): Boolean {
        val maxQueueSize = prefsManager.getMaxQueueSize()
        val overflowStrategy = prefsManager.getQueueOverflowStrategy()
        
        val currentSize = queuedCallDao.getActiveCount()
        
        if (currentSize >= maxQueueSize) {
            GatewayLogger.warn(TAG, "Queue full ($currentSize/$maxQueueSize)")
            
            return when (overflowStrategy) {
                QueueOverflowStrategy.REJECT -> {
                    GatewayLogger.info(TAG, "Rejecting call due to queue overflow")
                    false
                }
                QueueOverflowStrategy.HOLD -> {
                    // Still enqueue but mark as waiting
                    GatewayLogger.info(TAG, "Holding call in queue overflow")
                    insertCall(callerId, direction, simSlot, QueueStatus.WAITING)
                    true
                }
            }
        }

        insertCall(callerId, direction, simSlot, QueueStatus.WAITING)
        return true
    }

    private suspend fun insertCall(
        callerId: String,
        direction: CallDirection,
        simSlot: Int,
        status: QueueStatus
    ) {
        val entity = QueuedCallEntity(
            callerId = callerId,
            direction = direction.name,
            status = status.name,
            simSlot = simSlot,
            enqueuedAt = System.currentTimeMillis()
        )
        
        queuedCallDao.insert(entity)
        GatewayLogger.info(TAG, "Enqueued call from $callerId, direction: $direction")
    }

    override suspend fun markCallActive(callerId: String) {
        val entity = queuedCallDao.getByCallerId(callerId)
        if (entity == null) {
            GatewayLogger.warn(TAG, "Call not found for $callerId")
            return
        }

        queuedCallDao.updateStatus(entity.id, QueueStatus.ACTIVE.name)
        queuedCallDao.updateAnsweredAt(entity.id, System.currentTimeMillis())
        
        GatewayLogger.info(TAG, "Marked call ${entity.id} as active")
    }

    override suspend fun markCallCompleted(callerId: String, reason: EndReason) {
        val entity = queuedCallDao.getByCallerId(callerId)
        if (entity == null) {
            GatewayLogger.warn(TAG, "Call not found for $callerId")
            return
        }

        queuedCallDao.updateStatus(entity.id, QueueStatus.COMPLETED.name)
        queuedCallDao.updateEndedAt(entity.id, System.currentTimeMillis())
        
        GatewayLogger.info(TAG, "Marked call ${entity.id} as completed, reason: $reason")
    }

    override suspend fun markCallFailed(callerId: String, reason: String) {
        val entity = queuedCallDao.getByCallerId(callerId)
        if (entity == null) {
            GatewayLogger.warn(TAG, "Call not found for $callerId")
            return
        }

        queuedCallDao.updateStatus(entity.id, QueueStatus.FAILED.name)
        queuedCallDao.updateEndedAt(entity.id, System.currentTimeMillis())
        queuedCallDao.updateFailureReason(entity.id, reason)
        
        GatewayLogger.info(TAG, "Marked call ${entity.id} as failed: $reason")
    }

    override suspend fun removeCall(callId: Long) {
        queuedCallDao.deleteById(callId)
        GatewayLogger.debug(TAG, "Removed call $callId from queue")
    }

    override suspend fun getCall(callId: Long): QueuedCallInfo? {
        return queuedCallDao.getById(callId)?.toInfo()
    }

    override suspend fun getCallByCallerId(callerId: String): QueuedCallInfo? {
        return queuedCallDao.getByCallerId(callerId)?.toInfo()
    }

    override suspend fun clearOldCalls(maxAgeMs: Long) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        queuedCallDao.deleteOlderThan(cutoff)
        GatewayLogger.debug(TAG, "Cleared old calls before $cutoff")
    }

    override fun hasCapacity(): Boolean {
        val maxQueueSize = prefsManager.getMaxQueueSize()
        return _queueState.value.count { 
            it.status == QueueStatus.WAITING || it.status == QueueStatus.ACTIVE 
        } < maxQueueSize
    }

    private fun QueuedCallEntity.toInfo(): QueuedCallInfo {
        return QueuedCallInfo(
            id = id,
            callerId = callerId,
            direction = CallDirection.valueOf(direction),
            status = QueueStatus.valueOf(status),
            simSlot = simSlot,
            enqueuedAt = enqueuedAt,
            answeredAt = answeredAt,
            endedAt = endedAt,
            failureReason = failureReason
        )
    }

    companion object {
        private const val TAG = "CallQueue"
    }
}
