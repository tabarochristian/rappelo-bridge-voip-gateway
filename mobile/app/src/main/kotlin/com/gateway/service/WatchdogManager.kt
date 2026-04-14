package com.gateway.service

import com.gateway.data.api.GatewayApiService
import com.gateway.data.api.model.EventBatch
import com.gateway.data.api.model.GatewayEvent
import com.gateway.data.api.model.GatewayStatus
import com.gateway.data.api.model.HeartbeatRequest
import com.gateway.data.db.AppDatabase
import com.gateway.data.prefs.EncryptedPrefsManager
import com.gateway.telephony.gsm.GsmCallManager
import com.gateway.telephony.sip.SipEngine
import com.gateway.telephony.sip.SipRegistrationState
import com.gateway.util.GatewayLogger
import com.gateway.util.NetworkMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WatchdogManager @Inject constructor(
    private val sipEngine: SipEngine,
    private val gsmCallManager: GsmCallManager,
    private val networkMonitor: NetworkMonitor,
    private val apiService: GatewayApiService,
    private val prefsManager: EncryptedPrefsManager,
    private val database: AppDatabase
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watchdogJob: Job? = null
    private var heartbeatJob: Job? = null

    private var consecutiveSipFailures = 0

    private val _watchdogState = MutableStateFlow(WatchdogState())
    val watchdogState: StateFlow<WatchdogState> = _watchdogState.asStateFlow()

    fun startWatching() {
        GatewayLogger.info(TAG, "Starting watchdog")

        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive) {
                performHealthCheck()
                delay(HEALTH_CHECK_INTERVAL_MS)
            }
        }

        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                sendHeartbeat()
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    fun stopWatching() {
        GatewayLogger.info(TAG, "Stopping watchdog")
        watchdogJob?.cancel()
        watchdogJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private suspend fun performHealthCheck() {
        try {
            val checks = mutableListOf<HealthCheckResult>()

            // Check SIP registration
            val sipCheck = checkSipRegistration()
            checks.add(sipCheck)

            // Check GSM signal
            val gsmCheck = checkGsmSignal()
            checks.add(gsmCheck)

            // Check database
            val dbCheck = checkDatabase()
            checks.add(dbCheck)

            // Check network
            val networkCheck = checkNetwork()
            checks.add(networkCheck)

            val overallHealthy = checks.all { it.isHealthy }
            
            _watchdogState.value = WatchdogState(
                isHealthy = overallHealthy,
                sipHealthy = sipCheck.isHealthy,
                gsmHealthy = gsmCheck.isHealthy,
                databaseHealthy = dbCheck.isHealthy,
                networkHealthy = networkCheck.isHealthy,
                lastCheckTime = System.currentTimeMillis(),
                issues = checks.filter { !it.isHealthy }.map { it.message }
            )

            if (!overallHealthy) {
                GatewayLogger.warn(TAG, "Health check failed: ${checks.filter { !it.isHealthy }.map { it.message }}")
            }

        } catch (e: Exception) {
            GatewayLogger.error(TAG, "Health check error", e)
        }
    }

    private suspend fun checkSipRegistration(): HealthCheckResult {
        val state = sipEngine.registrationState.value

        return when (state) {
            is SipRegistrationState.Registered -> {
                consecutiveSipFailures = 0
                HealthCheckResult(true, "SIP registered")
            }
            is SipRegistrationState.Registering -> {
                HealthCheckResult(true, "SIP registering")
            }
            is SipRegistrationState.Unregistered,
            is SipRegistrationState.Failed -> {
                consecutiveSipFailures++
                
                if (consecutiveSipFailures >= MAX_SIP_FAILURES) {
                    GatewayLogger.error(TAG, "SIP registration failed $consecutiveSipFailures times, sending alert")
                    sendSipFailureAlert()
                }

                // Attempt re-registration
                if (prefsManager.isSipConfigured() && networkMonitor.isNetworkAvailable.value) {
                    GatewayLogger.info(TAG, "Attempting SIP re-registration")
                    sipEngine.registerAccount()
                }

                HealthCheckResult(false, "SIP not registered: ${state.displayName}")
            }
            else -> HealthCheckResult(false, "SIP state unknown")
        }
    }

    private fun checkGsmSignal(): HealthCheckResult {
        val signalStrength = gsmCallManager.signalStrengthDbm.value
        
        return when {
            signalStrength == null -> HealthCheckResult(false, "GSM signal unknown")
            signalStrength < MIN_SIGNAL_STRENGTH_DBM -> {
                GatewayLogger.warn(TAG, "Weak GSM signal: $signalStrength dBm")
                HealthCheckResult(false, "Weak GSM signal: $signalStrength dBm")
            }
            else -> HealthCheckResult(true, "GSM signal OK: $signalStrength dBm")
        }
    }

    private suspend fun checkDatabase(): HealthCheckResult {
        return try {
            // Simple query to check database accessibility
            database.openHelper.readableDatabase
            HealthCheckResult(true, "Database OK")
        } catch (e: Exception) {
            GatewayLogger.error(TAG, "Database check failed", e)
            HealthCheckResult(false, "Database error: ${e.message}")
        }
    }

    private fun checkNetwork(): HealthCheckResult {
        val isAvailable = networkMonitor.isNetworkAvailable.value
        return if (isAvailable) {
            HealthCheckResult(true, "Network available")
        } else {
            HealthCheckResult(false, "Network unavailable")
        }
    }

    private suspend fun sendHeartbeat() {
        if (!networkMonitor.isNetworkAvailable.value) {
            GatewayLogger.debug(TAG, "Skipping heartbeat - network unavailable")
            return
        }

        try {
            val deviceId = prefsManager.getDeviceId()
            if (deviceId.isBlank()) return

            val state = _watchdogState.value
            val status = GatewayStatus(
                deviceId = deviceId,
                online = true,
                sipRegistered = state.sipHealthy,
                gsmSignalStrength = gsmCallManager.signalStrengthDbm.value ?: -999,
                activeCalls = gsmCallManager.activeCallCount.value,
                queuedCalls = 0,
                pendingSms = 0,
                uptimeSeconds = 0L,
                batteryLevel = 0,
                isCharging = false,
                sim1Active = true,
                sim2Active = false
            )

            apiService.sendHeartbeat(HeartbeatRequest(deviceId = deviceId, status = status))
            GatewayLogger.debug(TAG, "Heartbeat sent successfully")
        } catch (e: Exception) {
            GatewayLogger.warn(TAG, "Failed to send heartbeat", e)
        }
    }

    private suspend fun sendSipFailureAlert() {
        if (!networkMonitor.isNetworkAvailable.value) return

        try {
            val deviceId = prefsManager.getDeviceId()
            if (deviceId.isBlank()) return

            val event = GatewayEvent(
                eventId = "alert_sip_${System.currentTimeMillis()}",
                eventType = "SIP_REGISTRATION_FAILURE",
                payload = "{\"consecutive_failures\":$consecutiveSipFailures}",
                timestamp = System.currentTimeMillis()
            )
            apiService.pushEvents(EventBatch(deviceId = deviceId, events = listOf(event)))
        } catch (e: Exception) {
            GatewayLogger.error(TAG, "Failed to send SIP failure alert", e)
        }
    }

    companion object {
        private const val TAG = "WatchdogManager"
        private const val HEALTH_CHECK_INTERVAL_MS = 30_000L // 30 seconds
        private const val HEARTBEAT_INTERVAL_MS = 300_000L // 5 minutes
        private const val MAX_SIP_FAILURES = 5
        private const val MIN_SIGNAL_STRENGTH_DBM = -110
    }
}

data class WatchdogState(
    val isHealthy: Boolean = true,
    val sipHealthy: Boolean = true,
    val gsmHealthy: Boolean = true,
    val databaseHealthy: Boolean = true,
    val networkHealthy: Boolean = true,
    val lastCheckTime: Long = 0L,
    val issues: List<String> = emptyList()
)

data class HealthCheckResult(
    val isHealthy: Boolean,
    val message: String
)
