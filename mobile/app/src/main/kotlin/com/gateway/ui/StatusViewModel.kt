package com.gateway.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gateway.queue.CallQueue
import com.gateway.queue.SmsQueue
import com.gateway.service.GatewayForegroundService
import com.gateway.telephony.sip.SipEngine
import com.gateway.telephony.sip.SipRegistrationState
import com.gateway.util.GatewayLogger
import com.gateway.util.PermissionHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GatewayUiState(
    val isServiceRunning: Boolean = false,
    val sipRegistered: Boolean = false,
    val sipStatus: String = "Not Connected",
    val gsmStatus: String = "Unknown",
    val queuedCalls: Int = 0,
    val pendingSms: Int = 0,
    val hasAllPermissions: Boolean = false,
    val errorMessage: String? = null
)

data class LogEntry(
    val id: Long,
    val timestamp: Long,
    val level: String,
    val tag: String,
    val message: String
)

@HiltViewModel
class StatusViewModel @Inject constructor(
    private val sipEngine: SipEngine,
    private val callQueue: CallQueue,
    private val smsQueue: SmsQueue,
    private val permissionHelper: PermissionHelper
) : ViewModel() {

    companion object {
        private const val TAG = "StatusViewModel"
        private const val MAX_LOGS = 100
    }

    private val _uiState = MutableStateFlow(GatewayUiState())
    val uiState: StateFlow<GatewayUiState> = _uiState.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private var logIdCounter = 0L

    init {
        observeSipState()
        observeLogs()
        checkPermissions()
        observeServiceState()
    }

    private fun observeSipState() {
        viewModelScope.launch {
            sipEngine.registrationState.collect { state ->
                _uiState.update { current ->
                    current.copy(
                        sipRegistered = state is SipRegistrationState.Registered,
                        sipStatus = when (state) {
                            is SipRegistrationState.Unregistered -> "Unregistered"
                            is SipRegistrationState.Registering -> "Registering..."
                            is SipRegistrationState.Registered -> "Registered"
                            is SipRegistrationState.Failed -> "Failed (${state.code}): ${state.reason}"
                        }
                    )
                }
            }
        }
    }

    private fun observeLogs() {
        viewModelScope.launch {
            GatewayLogger.logFlow.collect { logEntry ->
                val entry = LogEntry(
                    id = logIdCounter++,
                    timestamp = logEntry.timestamp,
                    level = logEntry.level.name,
                    tag = logEntry.tag,
                    message = logEntry.message
                )
                
                _logs.update { current ->
                    (current + entry).takeLast(MAX_LOGS)
                }
            }
        }
    }

    private fun observeServiceState() {
        viewModelScope.launch {
            GatewayForegroundService.isRunning.collect { running ->
                _uiState.update { it.copy(isServiceRunning = running) }
            }
        }
    }

    private fun checkPermissions() {
        _uiState.update { current ->
            current.copy(hasAllPermissions = permissionHelper.hasAllRequiredPermissions())
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { current ->
                current.copy(
                    queuedCalls = callQueue.queueState.value.size,
                    pendingSms = smsQueue.pendingSmsCount.value,
                    hasAllPermissions = permissionHelper.hasAllRequiredPermissions()
                )
            }
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }
}
