package com.gateway.telephony.gsm

import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.DisconnectCause
import android.telecom.InCallService
import com.gateway.util.GatewayLogger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton bridge between the system-managed GatewayInCallService and Hilt-managed GsmCallManagerImpl.
 * InCallService cannot use Hilt injection, so this object acts as the communication layer.
 */
object InCallServiceConnection {

    private const val TAG = "InCallServiceConn"

    data class CallEvent(
        val call: Call,
        val state: Int,
        val disconnectCause: DisconnectCause? = null
    )

    private var inCallService: InCallService? = null

    private val _activeCall = MutableStateFlow<Call?>(null)
    val activeCall: StateFlow<Call?> = _activeCall.asStateFlow()

    private val _callEvents = MutableSharedFlow<CallEvent>(extraBufferCapacity = 16)
    val callEvents: SharedFlow<CallEvent> = _callEvents.asSharedFlow()

    private val _isServiceBound = MutableStateFlow(false)
    val isServiceBound: StateFlow<Boolean> = _isServiceBound.asStateFlow()

    private val callCallbacks = mutableMapOf<Call, Call.Callback>()

    fun onServiceBound(service: InCallService) {
        inCallService = service
        _isServiceBound.value = true
        GatewayLogger.info(TAG, "InCallService bound")
    }

    fun onServiceUnbound() {
        inCallService = null
        _isServiceBound.value = false
        callCallbacks.clear()
        _activeCall.value = null
        GatewayLogger.info(TAG, "InCallService unbound")
    }

    /**
     * Set the call audio route via the live InCallService.
     * Use [CallAudioState.ROUTE_SPEAKER], [CallAudioState.ROUTE_EARPIECE], etc.
     */
    fun setAudioRoute(route: Int) {
        val svc = inCallService
        if (svc != null) {
            svc.setAudioRoute(route)
            GatewayLogger.info(TAG, "Audio route set to $route via InCallService")
        } else {
            GatewayLogger.warn(TAG, "Cannot set audio route – InCallService not bound")
        }
    }

    fun onCallAdded(call: Call) {
        GatewayLogger.info(TAG, "Call added: ${call.details?.handle} state=${stateToString(call.state)}")
        _activeCall.value = call

        val callback = object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) {
                GatewayLogger.info(TAG, "Call state changed: ${stateToString(state)}")
                if (state == Call.STATE_DISCONNECTED) {
                    _callEvents.tryEmit(
                        CallEvent(call, state, call.details?.disconnectCause)
                    )
                } else {
                    _callEvents.tryEmit(CallEvent(call, state))
                }
            }
        }

        call.registerCallback(callback)
        callCallbacks[call] = callback

        // Handle SELECT_PHONE_ACCOUNT (dual-SIM prompt) — auto-select first available account
        if (call.state == Call.STATE_SELECT_PHONE_ACCOUNT) {
            val accounts = call.details?.accountHandle
            GatewayLogger.info(TAG, "SELECT_PHONE_ACCOUNT — auto-selecting first account")
            try {
                // phoneAccountSelected with null = let system choose default
                call.phoneAccountSelected(call.details?.accountHandle, false)
            } catch (e: Exception) {
                GatewayLogger.warn(TAG, "Failed to auto-select phone account", e)
            }
        }

        // Emit the initial state
        _callEvents.tryEmit(CallEvent(call, call.state))
    }

    fun onCallRemoved(call: Call) {
        GatewayLogger.info(TAG, "Call removed: ${call.details?.handle}")

        callCallbacks.remove(call)?.let { callback ->
            call.unregisterCallback(callback)
        }

        if (_activeCall.value == call) {
            _activeCall.value = null
        }
    }

    fun stateToString(state: Int): String = when (state) {
        Call.STATE_NEW -> "NEW"
        Call.STATE_DIALING -> "DIALING"
        Call.STATE_RINGING -> "RINGING"
        Call.STATE_HOLDING -> "HOLDING"
        Call.STATE_ACTIVE -> "ACTIVE"
        Call.STATE_DISCONNECTED -> "DISCONNECTED"
        Call.STATE_CONNECTING -> "CONNECTING"
        Call.STATE_DISCONNECTING -> "DISCONNECTING"
        Call.STATE_SELECT_PHONE_ACCOUNT -> "SELECT_PHONE_ACCOUNT"
        Call.STATE_PULLING_CALL -> "PULLING_CALL"
        else -> "UNKNOWN($state)"
    }
}
