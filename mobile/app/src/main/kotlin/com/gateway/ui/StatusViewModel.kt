package com.gateway.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gateway.queue.CallQueue
import com.gateway.queue.SmsQueue
import com.gateway.service.GatewayForegroundService
import com.gateway.telephony.gsm.GsmCallManager
import com.gateway.telephony.gsm.GsmCallState
import com.gateway.telephony.gsm.UssdState
import com.gateway.telephony.sip.SipEngine
import com.gateway.telephony.sip.SipRegistrationState
import com.gateway.util.GatewayLogger
import com.gateway.util.PermissionHelper
import kotlinx.coroutines.delay
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
    val errorMessage: String? = null,
    val activeCallNumber: String? = null,
    val activeCallState: String? = null,
    val activeCallDurationSec: Long = 0,
    val ussdSending: Boolean = false,
    val ussdResponse: String? = null,
    val ussdError: String? = null
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
    private val gsmCallManager: GsmCallManager,
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
        observeGsmCallState()
        observeUssdState()
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

    private fun observeGsmCallState() {
        viewModelScope.launch {
            gsmCallManager.callState.collect { state ->
                val (number, label) = when (state) {
                    is GsmCallState.Ringing -> state.callerId to "Ringing"
                    is GsmCallState.Dialing -> state.number to "Dialing"
                    is GsmCallState.Active -> state.callerId to "Active"
                    is GsmCallState.OnHold -> state.callerId to "On Hold"
                    is GsmCallState.Ended -> null to null  // Hide card on Ended
                    is GsmCallState.Idle -> null to null
                }
                _uiState.update { it.copy(activeCallNumber = number, activeCallState = label) }
            }
        }
        // Timer ticks for call duration
        viewModelScope.launch {
            while (true) {
                delay(1000)
                val state = gsmCallManager.callState.value
                if (state is GsmCallState.Active) {
                    val dur = (System.currentTimeMillis() - state.startTime) / 1000
                    _uiState.update { it.copy(activeCallDurationSec = dur) }
                }
            }
        }
    }

    fun hangupActiveCall() {
        viewModelScope.launch {
            gsmCallManager.hangupCall()
        }
    }

    // ========== USSD ==========

    private fun observeUssdState() {
        viewModelScope.launch {
            gsmCallManager.ussdState.collect { state ->
                _uiState.update { current ->
                    when (state) {
                        is UssdState.Idle -> current.copy(ussdSending = false, ussdResponse = null, ussdError = null)
                        is UssdState.Sending -> current.copy(ussdSending = true, ussdResponse = null, ussdError = null)
                        is UssdState.Response -> current.copy(ussdSending = false, ussdResponse = state.message, ussdError = null)
                        is UssdState.Error -> current.copy(ussdSending = false, ussdResponse = null, ussdError = state.errorMessage)
                    }
                }
            }
        }
    }

    fun sendUssd(code: String) {
        viewModelScope.launch {
            gsmCallManager.sendUssdRequest(code)
        }
    }

    fun dismissUssd() {
        gsmCallManager.dismissUssd()
    }

    fun placeCall(number: String) {
        viewModelScope.launch {
            gsmCallManager.placeCall(number)
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
