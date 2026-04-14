package com.gateway.data.api

import com.gateway.data.api.model.CommandRequest
import com.gateway.data.api.model.CommandResponse
import com.gateway.data.prefs.EncryptedPrefsManager
import com.gateway.util.GatewayLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

sealed class PollerState {
    data object Idle : PollerState()
    data object Polling : PollerState()
    data class Error(val message: String, val retryCount: Int) : PollerState()
}

@Singleton
class CommandPoller @Inject constructor(
    private val apiService: GatewayApiService,
    private val prefsManager: EncryptedPrefsManager,
    @Named("ApplicationScope") private val applicationScope: CoroutineScope
) {
    companion object {
        private const val TAG = "CommandPoller"
        private const val DEFAULT_POLL_INTERVAL_MS = 30_000L
        private const val MIN_POLL_INTERVAL_MS = 5_000L
        private const val MAX_POLL_INTERVAL_MS = 300_000L
        private const val MAX_RETRY_COUNT = 5
        private const val BACKOFF_MULTIPLIER = 2.0
    }

    private val _state = MutableStateFlow<PollerState>(PollerState.Idle)
    val state: StateFlow<PollerState> = _state.asStateFlow()

    private val _commands = MutableSharedFlow<CommandRequest>(replay = 0)
    val commands: SharedFlow<CommandRequest> = _commands.asSharedFlow()

    private var pollingJob: Job? = null
    private var pollIntervalMs = DEFAULT_POLL_INTERVAL_MS
    private var lastPollTimestamp: Long = 0
    private var retryCount = 0

    val isPolling: Boolean
        get() = pollingJob?.isActive == true

    fun start() {
        if (pollingJob?.isActive == true) {
            GatewayLogger.d(TAG, "Poller already running")
            return
        }

        pollingJob = applicationScope.launch {
            GatewayLogger.i(TAG, "Starting command polling")
            retryCount = 0
            
            while (isActive) {
                pollOnce()
                delay(pollIntervalMs)
            }
        }
    }

    fun stop() {
        pollingJob?.cancel()
        pollingJob = null
        _state.value = PollerState.Idle
        GatewayLogger.i(TAG, "Command polling stopped")
    }

    suspend fun pollOnce() {
        try {
            _state.value = PollerState.Polling
            
            val deviceId = prefsManager.getDeviceId()
            if (deviceId.isBlank()) {
                GatewayLogger.w(TAG, "No device ID configured, skipping poll")
                _state.value = PollerState.Error("No device ID", retryCount)
                return
            }

            val response = apiService.pollCommands(
                deviceId = deviceId,
                since = if (lastPollTimestamp > 0) lastPollTimestamp else null
            )

            if (response.isSuccessful && response.body()?.success == true) {
                val pollResponse = response.body()?.data
                
                // Update poll interval if server suggests different
                pollResponse?.pollIntervalMs?.let { serverInterval ->
                    pollIntervalMs = serverInterval.coerceIn(
                        MIN_POLL_INTERVAL_MS,
                        MAX_POLL_INTERVAL_MS
                    )
                }

                // Emit received commands
                pollResponse?.commands?.forEach { command ->
                    GatewayLogger.d(TAG, "Received command: ${command.action} (${command.commandId})")
                    _commands.emit(command)
                }

                lastPollTimestamp = System.currentTimeMillis()
                retryCount = 0
                _state.value = PollerState.Idle
                
            } else {
                val errorMsg = response.body()?.error ?: response.message()
                handlePollError("API error: $errorMsg")
            }

        } catch (e: Exception) {
            handlePollError("Poll failed: ${e.message}")
        }
    }

    private fun handlePollError(message: String) {
        retryCount++
        GatewayLogger.e(TAG, "$message (retry $retryCount/$MAX_RETRY_COUNT)")
        
        _state.value = PollerState.Error(message, retryCount)

        if (retryCount >= MAX_RETRY_COUNT) {
            // Apply exponential backoff
            val backoffMultiplier = Math.pow(BACKOFF_MULTIPLIER, (retryCount - MAX_RETRY_COUNT).toDouble())
            pollIntervalMs = (DEFAULT_POLL_INTERVAL_MS * backoffMultiplier)
                .toLong()
                .coerceAtMost(MAX_POLL_INTERVAL_MS)
            
            GatewayLogger.w(TAG, "Max retries reached, backing off to ${pollIntervalMs}ms")
        }
    }

    suspend fun reportResult(commandId: String, success: Boolean, result: String?, error: String?) {
        try {
            val deviceId = prefsManager.getDeviceId()
            if (deviceId.isBlank()) return

            val response = CommandResponse(
                commandId = commandId,
                success = success,
                result = result,
                error = error
            )

            val apiResponse = apiService.reportCommandResult(
                deviceId = deviceId,
                commandId = commandId,
                response = response
            )

            if (apiResponse.isSuccessful) {
                GatewayLogger.d(TAG, "Command result reported: $commandId")
            } else {
                GatewayLogger.e(TAG, "Failed to report command result: ${apiResponse.message()}")
            }

        } catch (e: Exception) {
            GatewayLogger.e(TAG, "Error reporting command result: ${e.message}")
        }
    }

    fun resetBackoff() {
        retryCount = 0
        pollIntervalMs = DEFAULT_POLL_INTERVAL_MS
        GatewayLogger.d(TAG, "Backoff reset")
    }
}
