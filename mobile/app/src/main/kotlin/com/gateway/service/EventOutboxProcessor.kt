package com.gateway.service

import com.gateway.data.api.GatewayApiService
import com.gateway.data.api.model.EventBatch
import com.gateway.data.api.model.GatewayEvent
import com.gateway.data.db.dao.EventOutboxDao
import com.gateway.data.db.entity.EventOutboxEntity
import com.gateway.data.prefs.EncryptedPrefsManager
import com.gateway.util.GatewayLogger
import com.gateway.util.NetworkMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class EventOutboxProcessor @Inject constructor(
    private val eventOutboxDao: EventOutboxDao,
    private val apiService: GatewayApiService,
    private val prefsManager: EncryptedPrefsManager,
    private val networkMonitor: NetworkMonitor,
    @Named("ApplicationScope") private val applicationScope: CoroutineScope
) {
    companion object {
        private const val TAG = "EventOutboxProcessor"
        private const val PROCESS_INTERVAL_MS = 30_000L
        private const val MAX_RETRIES = 5
        private const val BATCH_SIZE = 10
        private const val CLEANUP_AGE_MS = 7L * 24 * 60 * 60 * 1000 // 7 days
    }

    private var processJob: Job? = null

    fun start() {
        if (processJob?.isActive == true) return

        processJob = applicationScope.launch {
            GatewayLogger.i(TAG, "Event outbox processor started")
            while (isActive) {
                processOutbox()
                delay(PROCESS_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        processJob?.cancel()
        processJob = null
        GatewayLogger.i(TAG, "Event outbox processor stopped")
    }

    suspend fun enqueue(eventType: String, payload: String) {
        val entity = EventOutboxEntity(
            eventType = eventType,
            payload = payload,
            createdAt = System.currentTimeMillis()
        )
        eventOutboxDao.insert(entity)
        GatewayLogger.d(TAG, "Event enqueued: $eventType")
    }

    private suspend fun processOutbox() {
        if (!networkMonitor.isNetworkAvailable.value) return

        val deviceId = prefsManager.getDeviceId()
        if (deviceId.isBlank()) return

        try {
            val pendingEvents = eventOutboxDao.getPendingEvents(MAX_RETRIES, BATCH_SIZE)
            if (pendingEvents.isEmpty()) {
                cleanup()
                return
            }

            GatewayLogger.d(TAG, "Processing ${pendingEvents.size} pending events")

            val gatewayEvents = pendingEvents.map { entity ->
                GatewayEvent(
                    eventId = "evt_${entity.id}_${entity.createdAt}",
                    eventType = entity.eventType,
                    payload = entity.payload,
                    timestamp = entity.createdAt
                )
            }

            val response = apiService.pushEvents(
                EventBatch(deviceId = deviceId, events = gatewayEvents)
            )

            if (response.isSuccessful && response.body()?.success == true) {
                pendingEvents.forEach { entity ->
                    eventOutboxDao.deleteById(entity.id)
                }
                GatewayLogger.i(TAG, "Pushed ${pendingEvents.size} events successfully")
            } else {
                val error = response.body()?.error ?: response.message()
                pendingEvents.forEach { entity ->
                    eventOutboxDao.markRetried(entity.id, System.currentTimeMillis(), error)
                }
                GatewayLogger.w(TAG, "Failed to push events: $error")
            }
        } catch (e: Exception) {
            GatewayLogger.e(TAG, "Error processing outbox", e)
        }
    }

    private suspend fun cleanup() {
        try {
            val cutoff = System.currentTimeMillis() - CLEANUP_AGE_MS
            eventOutboxDao.deleteOlderThan(cutoff)
            eventOutboxDao.deleteFailedOlderThan(MAX_RETRIES, cutoff)
        } catch (e: Exception) {
            GatewayLogger.e(TAG, "Error during outbox cleanup", e)
        }
    }
}
