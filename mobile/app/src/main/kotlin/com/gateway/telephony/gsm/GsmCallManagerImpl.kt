package com.gateway.telephony.gsm

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.DisconnectCause
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.gateway.util.GatewayLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GsmCallManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val telephonyManager: TelephonyManager,
    private val telecomManager: TelecomManager,
    private val dualSimManager: DualSimManager
) : GsmCallManager {

    private val _callState = MutableStateFlow<GsmCallState>(GsmCallState.Idle)
    override val callState: StateFlow<GsmCallState> = _callState.asStateFlow()

    private val _signalStrengthDbm = MutableStateFlow<Int?>(null)
    override val signalStrengthDbm: StateFlow<Int?> = _signalStrengthDbm.asStateFlow()

    private val _activeCallCount = MutableStateFlow(0)
    override val activeCallCount: StateFlow<Int> = _activeCallCount.asStateFlow()

    private val _ussdState = MutableStateFlow<UssdState>(UssdState.Idle)
    override val ussdState: StateFlow<UssdState> = _ussdState.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())

    private var telephonyCallback: Any? = null
    @Suppress("DEPRECATION")
    private var phoneStateListener: PhoneStateListener? = null

    private var isInitialized = false
    private var scope: CoroutineScope? = null

    override suspend fun initialize() {
        if (isInitialized) return

        withContext(Dispatchers.Main) {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            setupSignalStrengthCallback()
            observeInCallServiceEvents()
            isInitialized = true
            GatewayLogger.info(TAG, "GsmCallManager initialized (InCallService mode)")
        }
    }

    override suspend fun shutdown() {
        withContext(Dispatchers.Main) {
            scope?.cancel()
            scope = null
            removeSignalStrengthCallback()
            isInitialized = false
            GatewayLogger.info(TAG, "GsmCallManager shutdown")
        }
    }

    /**
     * Observe call events from the InCallService via the singleton connection.
     */
    private fun observeInCallServiceEvents() {
        scope?.launch {
            InCallServiceConnection.callEvents.collect { event ->
                handleInCallEvent(event)
            }
        }
        GatewayLogger.info(TAG, "Observing InCallService events")
    }

    private fun handleInCallEvent(event: InCallServiceConnection.CallEvent) {
        val call = event.call
        val callerId = call.details?.handle?.schemeSpecificPart ?: "Unknown"
        val simSlot = dualSimManager.getActiveSimSlot()

        val newState = when (event.state) {
            Call.STATE_RINGING -> {
                GatewayLogger.info(TAG, "InCallService: Ringing from $callerId")
                GsmCallState.Ringing(callerId, simSlot)
            }
            Call.STATE_DIALING, Call.STATE_CONNECTING -> {
                GatewayLogger.info(TAG, "InCallService: Dialing $callerId")
                // Preserve the number from the Dialing state we set in placeCall()
                val current = _callState.value
                if (current is GsmCallState.Dialing) current
                else GsmCallState.Dialing(callerId, simSlot)
            }
            Call.STATE_ACTIVE -> {
                _activeCallCount.value = 1
                val current = _callState.value
                val id = when (current) {
                    is GsmCallState.Dialing -> current.number
                    is GsmCallState.Ringing -> current.callerId
                    else -> callerId
                }
                GatewayLogger.info(TAG, "InCallService: Active ($id)")
                GsmCallState.Active(id, simSlot)
            }
            Call.STATE_HOLDING -> {
                val current = _callState.value
                val id = when (current) {
                    is GsmCallState.Active -> current.callerId
                    else -> callerId
                }
                GatewayLogger.info(TAG, "InCallService: On Hold ($id)")
                GsmCallState.OnHold(id, simSlot)
            }
            Call.STATE_DISCONNECTING -> {
                // Transitional state, keep current state
                GatewayLogger.info(TAG, "InCallService: Disconnecting")
                _callState.value
            }
            Call.STATE_DISCONNECTED -> {
                _activeCallCount.value = 0
                val reason = mapDisconnectCause(event.disconnectCause)
                val current = _callState.value
                val id = when (current) {
                    is GsmCallState.Active -> current.callerId
                    is GsmCallState.Dialing -> current.number
                    is GsmCallState.Ringing -> current.callerId
                    is GsmCallState.OnHold -> current.callerId
                    else -> callerId
                }
                GatewayLogger.info(TAG, "InCallService: Disconnected ($id) reason=$reason")
                GsmCallState.Ended(reason, id)
            }
            else -> {
                GatewayLogger.debug(TAG, "InCallService: Unhandled state ${InCallServiceConnection.stateToString(event.state)}")
                _callState.value
            }
        }

        _callState.value = newState
        GatewayLogger.debug(TAG, "GSM call state: ${newState.displayName}")

        // Auto-transition Ended → Idle after 1.5s so the UI clears and the system is ready for the next call
        if (newState is GsmCallState.Ended) {
            scope?.launch {
                delay(1500)
                if (_callState.value is GsmCallState.Ended) {
                    _callState.value = GsmCallState.Idle
                    GatewayLogger.debug(TAG, "Auto-transition: Ended → Idle")
                }
            }
        }
    }

    private fun mapDisconnectCause(cause: DisconnectCause?): EndReason {
        return when (cause?.code) {
            DisconnectCause.LOCAL -> EndReason.USER_HANGUP
            DisconnectCause.REMOTE -> EndReason.REMOTE_HANGUP
            DisconnectCause.BUSY -> EndReason.BUSY
            DisconnectCause.REJECTED -> EndReason.REJECTED
            DisconnectCause.MISSED -> EndReason.NO_ANSWER
            DisconnectCause.ERROR -> EndReason.ERROR
            DisconnectCause.CANCELED -> EndReason.USER_HANGUP
            else -> EndReason.NORMAL
        }
    }

    // ========== Signal strength (still uses TelephonyCallback, independent of InCallService) ==========

    private fun setupSignalStrengthCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setupSignalStrengthApi31()
        } else {
            setupSignalStrengthLegacy()
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun setupSignalStrengthApi31() {
        val executor: Executor = context.mainExecutor

        val callback = object : TelephonyCallback(),
            TelephonyCallback.SignalStrengthsListener {
            override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                handleSignalStrengthChanged(signalStrength)
            }
        }

        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
                == PackageManager.PERMISSION_GRANTED) {
                telephonyManager.registerTelephonyCallback(executor, callback)
                telephonyCallback = callback
                GatewayLogger.info(TAG, "Registered signal strength callback (API 31+)")
            }
        } catch (e: Exception) {
            GatewayLogger.error(TAG, "Failed to register signal strength callback", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun setupSignalStrengthLegacy() {
        phoneStateListener = object : PhoneStateListener() {
            override fun onSignalStrengthsChanged(signalStrength: SignalStrength?) {
                signalStrength?.let { handleSignalStrengthChanged(it) }
            }
        }

        try {
            telephonyManager.listen(
                phoneStateListener,
                PhoneStateListener.LISTEN_SIGNAL_STRENGTHS
            )
            GatewayLogger.info(TAG, "Registered signal strength listener (legacy)")
        } catch (e: Exception) {
            GatewayLogger.error(TAG, "Failed to register signal strength listener", e)
        }
    }

    private fun removeSignalStrengthCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback?.let {
                telephonyManager.unregisterTelephonyCallback(it as TelephonyCallback)
            }
            telephonyCallback = null
        } else {
            @Suppress("DEPRECATION")
            phoneStateListener?.let {
                telephonyManager.listen(it, PhoneStateListener.LISTEN_NONE)
            }
            phoneStateListener = null
        }
    }

    private fun handleSignalStrengthChanged(signalStrength: SignalStrength) {
        val dbm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            signalStrength.cellSignalStrengths.firstOrNull()?.dbm ?: -999
        } else {
            @Suppress("DEPRECATION")
            signalStrength.gsmSignalStrength.let { asu ->
                if (asu in 0..31) -113 + 2 * asu else -999
            }
        }
        _signalStrengthDbm.value = if (dbm != -999) dbm else null
    }

    // ========== Call control via InCallService Call objects ==========

    override suspend fun answerCall(): Boolean {
        return withContext(Dispatchers.Main) {
            val call = InCallServiceConnection.activeCall.value
            if (call == null || call.state != Call.STATE_RINGING) {
                GatewayLogger.warn(TAG, "No ringing call to answer")
                return@withContext false
            }
            try {
                call.answer(android.telecom.VideoProfile.STATE_AUDIO_ONLY)
                GatewayLogger.info(TAG, "Call answered via InCallService")
                true
            } catch (e: Exception) {
                GatewayLogger.error(TAG, "Failed to answer call", e)
                false
            }
        }
    }

    override suspend fun rejectCall(): Boolean {
        return withContext(Dispatchers.Main) {
            val call = InCallServiceConnection.activeCall.value
            if (call == null || call.state != Call.STATE_RINGING) {
                GatewayLogger.warn(TAG, "No ringing call to reject")
                return@withContext false
            }
            try {
                call.reject(false, null)
                GatewayLogger.info(TAG, "Call rejected via InCallService")
                true
            } catch (e: Exception) {
                GatewayLogger.error(TAG, "Failed to reject call", e)
                false
            }
        }
    }

    override suspend fun hangupCall(): Boolean {
        return withContext(Dispatchers.Main) {
            val call = InCallServiceConnection.activeCall.value
            if (call == null) {
                GatewayLogger.warn(TAG, "No active call to hang up")
                return@withContext false
            }
            try {
                call.disconnect()
                GatewayLogger.info(TAG, "Call disconnected via InCallService")
                true
            } catch (e: Exception) {
                GatewayLogger.error(TAG, "Failed to disconnect call", e)
                false
            }
        }
    }

    override suspend fun placeCall(number: String, simSlot: Int): Boolean {
        return withContext(Dispatchers.Main) {
            try {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE)
                    != PackageManager.PERMISSION_GRANTED) {
                    GatewayLogger.error(TAG, "Missing CALL_PHONE permission")
                    return@withContext false
                }

                // Set state BEFORE placing call so InCallService sees Dialing
                _callState.value = GsmCallState.Dialing(number, simSlot)

                val phoneUri = Uri.parse("tel:${Uri.encode(number)}")
                val extras = Bundle().apply {
                    dualSimManager.getPhoneAccountHandle(simSlot)?.let { handle ->
                        putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
                    }
                }

                telecomManager.placeCall(phoneUri, extras)
                GatewayLogger.info(TAG, "Placing call to $number on SIM slot $simSlot via TelecomManager")
                true
            } catch (e: Exception) {
                GatewayLogger.error(TAG, "Failed to place call", e)
                _callState.value = GsmCallState.Ended(EndReason.ERROR)
                false
            }
        }
    }

    override suspend fun holdCall(): Boolean {
        return withContext(Dispatchers.Main) {
            val call = InCallServiceConnection.activeCall.value
            if (call == null || call.state != Call.STATE_ACTIVE) {
                GatewayLogger.warn(TAG, "No active call to hold")
                return@withContext false
            }
            try {
                call.hold()
                GatewayLogger.info(TAG, "Call held via InCallService")
                true
            } catch (e: Exception) {
                GatewayLogger.error(TAG, "Failed to hold call", e)
                false
            }
        }
    }

    override suspend fun resumeCall(): Boolean {
        return withContext(Dispatchers.Main) {
            val call = InCallServiceConnection.activeCall.value
            if (call == null || call.state != Call.STATE_HOLDING) {
                GatewayLogger.warn(TAG, "No held call to resume")
                return@withContext false
            }
            try {
                call.unhold()
                GatewayLogger.info(TAG, "Call resumed via InCallService")
                true
            } catch (e: Exception) {
                GatewayLogger.error(TAG, "Failed to resume call", e)
                false
            }
        }
    }

    override suspend fun sendDtmf(digits: String) {
        withContext(Dispatchers.Main) {
            val call = InCallServiceConnection.activeCall.value
            if (call == null || call.state != Call.STATE_ACTIVE) {
                GatewayLogger.warn(TAG, "No active call to send DTMF")
                return@withContext
            }
            try {
                for (digit in digits) {
                    call.playDtmfTone(digit)
                    call.stopDtmfTone()
                }
                GatewayLogger.info(TAG, "DTMF sent via InCallService: $digits")
            } catch (e: Exception) {
                GatewayLogger.error(TAG, "Failed to send DTMF", e)
            }
        }
    }

    override fun updateCallState(state: GsmCallState) {
        _callState.value = state
    }

    override fun updateSignalStrength(dbm: Int) {
        _signalStrengthDbm.value = dbm
    }

    // ========== USSD ==========

    override suspend fun sendUssdRequest(code: String, simSlot: Int): Boolean {
        return withContext(Dispatchers.Main) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                GatewayLogger.error(TAG, "Missing CALL_PHONE permission for USSD")
                _ussdState.value = UssdState.Error(code, "Missing CALL_PHONE permission")
                return@withContext false
            }

            _ussdState.value = UssdState.Sending(code)
            GatewayLogger.info(TAG, "Sending USSD request: $code on SIM $simSlot")

            try {
                val tm = dualSimManager.getSubscriptionId(simSlot)?.let { subId ->
                    telephonyManager.createForSubscriptionId(subId)
                } ?: telephonyManager

                val callback = object : TelephonyManager.UssdResponseCallback() {
                    override fun onReceiveUssdResponse(
                        telephonyManager: TelephonyManager,
                        request: String,
                        response: CharSequence
                    ) {
                        GatewayLogger.info(TAG, "USSD response for $request: $response")
                        _ussdState.value = UssdState.Response(request, response.toString())
                    }

                    override fun onReceiveUssdResponseFailed(
                        telephonyManager: TelephonyManager,
                        request: String,
                        failureCode: Int
                    ) {
                        val reason = when (failureCode) {
                            TelephonyManager.USSD_RETURN_FAILURE -> "Network error"
                            TelephonyManager.USSD_ERROR_SERVICE_UNAVAIL -> "Service unavailable"
                            else -> "Unknown error ($failureCode)"
                        }
                        GatewayLogger.error(TAG, "USSD failed for $request: $reason")
                        _ussdState.value = UssdState.Error(request, reason)
                    }
                }

                tm.sendUssdRequest(code, callback, mainHandler)
                true
            } catch (e: Exception) {
                GatewayLogger.error(TAG, "Failed to send USSD request", e)
                _ussdState.value = UssdState.Error(code, e.message ?: "Unknown error")
                false
            }
        }
    }

    override fun dismissUssd() {
        _ussdState.value = UssdState.Idle
    }

    companion object {
        private const val TAG = "GsmCallManager"
    }
}
