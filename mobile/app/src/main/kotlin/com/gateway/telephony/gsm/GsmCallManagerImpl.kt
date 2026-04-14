package com.gateway.telephony.gsm

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.view.KeyEvent
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.gateway.util.GatewayLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private var telephonyCallback: Any? = null
    @Suppress("DEPRECATION")
    private var phoneStateListener: PhoneStateListener? = null

    private var isInitialized = false

    override suspend fun initialize() {
        if (isInitialized) return

        withContext(Dispatchers.Main) {
            setupTelephonyCallbacks()
            isInitialized = true
            GatewayLogger.info(TAG, "GsmCallManager initialized")
        }
    }

    override suspend fun shutdown() {
        withContext(Dispatchers.Main) {
            removeTelephonyCallbacks()
            isInitialized = false
            GatewayLogger.info(TAG, "GsmCallManager shutdown")
        }
    }

    private fun setupTelephonyCallbacks() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setupTelephonyCallbackApi31()
        } else {
            setupPhoneStateListenerLegacy()
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun setupTelephonyCallbackApi31() {
        val executor: Executor = context.mainExecutor

        val callback = object : TelephonyCallback(), 
            TelephonyCallback.CallStateListener,
            TelephonyCallback.SignalStrengthsListener {

            override fun onCallStateChanged(state: Int) {
                handleCallStateChanged(state, null)
            }

            override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                handleSignalStrengthChanged(signalStrength)
            }
        }

        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) 
                == PackageManager.PERMISSION_GRANTED) {
                telephonyManager.registerTelephonyCallback(executor, callback)
                telephonyCallback = callback
                GatewayLogger.info(TAG, "Registered TelephonyCallback (API 31+)")
            } else {
                GatewayLogger.warn(TAG, "Missing READ_PHONE_STATE permission")
            }
        } catch (e: Exception) {
            GatewayLogger.error(TAG, "Failed to register TelephonyCallback", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun setupPhoneStateListenerLegacy() {
        phoneStateListener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                handleCallStateChanged(state, phoneNumber)
            }

            override fun onSignalStrengthsChanged(signalStrength: SignalStrength?) {
                signalStrength?.let { handleSignalStrengthChanged(it) }
            }
        }

        try {
            telephonyManager.listen(
                phoneStateListener,
                PhoneStateListener.LISTEN_CALL_STATE or PhoneStateListener.LISTEN_SIGNAL_STRENGTHS
            )
            GatewayLogger.info(TAG, "Registered PhoneStateListener (legacy)")
        } catch (e: Exception) {
            GatewayLogger.error(TAG, "Failed to register PhoneStateListener", e)
        }
    }

    private fun removeTelephonyCallbacks() {
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

    private fun handleCallStateChanged(state: Int, phoneNumber: String?) {
        val newState = when (state) {
            TelephonyManager.CALL_STATE_IDLE -> {
                _activeCallCount.value = 0
                val previousState = _callState.value
                if (previousState is GsmCallState.Active || previousState is GsmCallState.Ringing) {
                    GsmCallState.Ended(EndReason.NORMAL, phoneNumber)
                } else {
                    GsmCallState.Idle
                }
            }
            TelephonyManager.CALL_STATE_RINGING -> {
                val callerId = phoneNumber ?: "Unknown"
                GatewayLogger.info(TAG, "Incoming call from: $callerId")
                GsmCallState.Ringing(callerId, dualSimManager.getActiveSimSlot())
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                _activeCallCount.value = 1
                val currentState = _callState.value
                when (currentState) {
                    is GsmCallState.Ringing -> {
                        GatewayLogger.info(TAG, "Call answered: ${currentState.callerId}")
                        GsmCallState.Active(currentState.callerId, currentState.simSlot)
                    }
                    is GsmCallState.Dialing -> {
                        GatewayLogger.info(TAG, "Outgoing call connected: ${currentState.number}")
                        GsmCallState.Active(currentState.number, currentState.simSlot)
                    }
                    else -> {
                        GsmCallState.Active(phoneNumber ?: "Unknown", dualSimManager.getActiveSimSlot())
                    }
                }
            }
            else -> _callState.value
        }

        _callState.value = newState
        GatewayLogger.debug(TAG, "Call state changed: ${newState.displayName}")
    }

    private fun handleSignalStrengthChanged(signalStrength: SignalStrength) {
        val dbm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            signalStrength.cellSignalStrengths.firstOrNull()?.dbm ?: -999
        } else {
            @Suppress("DEPRECATION")
            signalStrength.gsmSignalStrength.let { asu ->
                if (asu in 0..31) {
                    -113 + 2 * asu
                } else {
                    -999
                }
            }
        }

        _signalStrengthDbm.value = if (dbm != -999) dbm else null
    }

    override suspend fun answerCall(): Boolean {
        return withContext(Dispatchers.Main) {
            try {
                if (_callState.value !is GsmCallState.Ringing) {
                    GatewayLogger.warn(TAG, "No ringing call to answer")
                    return@withContext false
                }

                // Try TelecomManager first (requires ANSWER_PHONE_CALLS permission)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS)
                        == PackageManager.PERMISSION_GRANTED) {
                        telecomManager.acceptRingingCall()
                        GatewayLogger.info(TAG, "Call answered via TelecomManager")
                        return@withContext true
                    }
                }

                // Fallback: Send key event (less reliable but doesn't need special permission)
                answerViaKeyEvent()
                true
            } catch (e: Exception) {
                GatewayLogger.error(TAG, "Failed to answer call", e)
                false
            }
        }
    }

    private fun answerViaKeyEvent() {
        try {
            // Simulate headset button press
            val downIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK))
            }
            context.sendOrderedBroadcast(downIntent, null)

            val upIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK))
            }
            context.sendOrderedBroadcast(upIntent, null)

            GatewayLogger.info(TAG, "Call answered via key event")
        } catch (e: Exception) {
            GatewayLogger.error(TAG, "Failed to answer via key event", e)
        }
    }

    override suspend fun rejectCall(): Boolean {
        return withContext(Dispatchers.Main) {
            try {
                if (_callState.value !is GsmCallState.Ringing) {
                    GatewayLogger.warn(TAG, "No ringing call to reject")
                    return@withContext false
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS)
                        == PackageManager.PERMISSION_GRANTED) {
                        telecomManager.endCall()
                        GatewayLogger.info(TAG, "Call rejected via TelecomManager")
                        return@withContext true
                    }
                }

                // Fallback
                rejectViaKeyEvent()
                true
            } catch (e: Exception) {
                GatewayLogger.error(TAG, "Failed to reject call", e)
                false
            }
        }
    }

    private fun rejectViaKeyEvent() {
        try {
            val down = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENDCALL))
            }
            context.sendOrderedBroadcast(down, null)

            val up = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENDCALL))
            }
            context.sendOrderedBroadcast(up, null)

            GatewayLogger.info(TAG, "Call rejected via key event")
        } catch (e: Exception) {
            GatewayLogger.error(TAG, "Failed to reject via key event", e)
        }
    }

    override suspend fun hangupCall(): Boolean {
        return withContext(Dispatchers.Main) {
            try {
                val currentState = _callState.value
                if (currentState !is GsmCallState.Active && currentState !is GsmCallState.Dialing) {
                    GatewayLogger.warn(TAG, "No active call to hang up")
                    return@withContext false
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    telecomManager.endCall()
                    GatewayLogger.info(TAG, "Call ended via TelecomManager")
                    return@withContext true
                }

                // Legacy fallback
                rejectViaKeyEvent()
                true
            } catch (e: Exception) {
                GatewayLogger.error(TAG, "Failed to hang up call", e)
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

                val phoneUri = Uri.parse("tel:${Uri.encode(number)}")
                val callIntent = Intent(Intent.ACTION_CALL, phoneUri).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    
                    // Set SIM slot for dual SIM devices
                    dualSimManager.getSubscriptionId(simSlot)?.let { subId ->
                        putExtra("android.telecom.extra.PHONE_ACCOUNT_HANDLE",
                            dualSimManager.getPhoneAccountHandle(simSlot))
                    }
                }

                _callState.value = GsmCallState.Dialing(number, simSlot)
                context.startActivity(callIntent)

                GatewayLogger.info(TAG, "Placing call to $number on SIM slot $simSlot")
                true
            } catch (e: Exception) {
                GatewayLogger.error(TAG, "Failed to place call", e)
                _callState.value = GsmCallState.Ended(EndReason.ERROR)
                false
            }
        }
    }

    override suspend fun holdCall(): Boolean {
        // Holding calls requires InCallService which needs to be the default dialer
        GatewayLogger.warn(TAG, "Hold call not implemented - requires InCallService")
        return false
    }

    override suspend fun resumeCall(): Boolean {
        GatewayLogger.warn(TAG, "Resume call not implemented - requires InCallService")
        return false
    }

    override suspend fun sendDtmf(digits: String) {
        // DTMF sending requires InCallService
        GatewayLogger.warn(TAG, "Send DTMF not implemented - requires InCallService")
    }

    override fun updateCallState(state: GsmCallState) {
        _callState.value = state
    }

    override fun updateSignalStrength(dbm: Int) {
        _signalStrengthDbm.value = dbm
    }

    companion object {
        private const val TAG = "GsmCallManager"
    }
}
