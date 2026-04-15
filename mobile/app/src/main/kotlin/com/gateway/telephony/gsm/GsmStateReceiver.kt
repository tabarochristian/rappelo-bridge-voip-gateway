package com.gateway.telephony.gsm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import com.gateway.queue.CallQueue
import com.gateway.queue.model.CallDirection
import com.gateway.util.GatewayLogger
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Legacy broadcast receiver for phone state changes.
 * When InCallService is bound (app is default dialer), ALL call state management
 * is handled by GatewayInCallService → InCallServiceConnection → GsmCallManagerImpl.
 * This receiver only logs events as a diagnostic aid when InCallService is active.
 */
class GsmStateReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface GsmStateReceiverEntryPoint {
        fun gsmCallManager(): GsmCallManager
        fun callQueue(): CallQueue
        fun dualSimManager(): DualSimManager
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var gsmCallManager: GsmCallManager
    private lateinit var callQueue: CallQueue
    private lateinit var dualSimManager: DualSimManager

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            GsmStateReceiverEntryPoint::class.java
        )
        gsmCallManager = entryPoint.gsmCallManager()
        callQueue = entryPoint.callQueue()
        dualSimManager = entryPoint.dualSimManager()

        // When InCallService is bound, it is the sole authority for call state.
        // Do NOT call updateCallState() — it would race with InCallService events.
        if (InCallServiceConnection.isServiceBound.value) {
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: action
            GatewayLogger.debug(TAG, "Received $state (InCallService active, ignoring)")
            return
        }

        GatewayLogger.debug(TAG, "Received action: $action")

        when (action) {
            TelephonyManager.ACTION_PHONE_STATE_CHANGED -> {
                handlePhoneStateChanged(intent)
            }
            Intent.ACTION_NEW_OUTGOING_CALL -> {
                handleOutgoingCall(intent)
            }
        }
    }

    private fun handlePhoneStateChanged(intent: Intent) {
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val phoneNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

        GatewayLogger.info(TAG, "Phone state changed: $state, number: ${phoneNumber?.take(4)}...")

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                handleIncomingCall(phoneNumber ?: "Unknown")
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                handleCallAnswered()
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                handleCallEnded()
            }
        }
    }

    private fun handleIncomingCall(callerId: String) {
        GatewayLogger.info(TAG, "Incoming call from: $callerId")

        val simSlot = dualSimManager.getActiveSimSlot()
        gsmCallManager.updateCallState(GsmCallState.Ringing(callerId, simSlot))

        // Enqueue the call
        scope.launch {
            try {
                val enqueued = callQueue.enqueueCall(
                    callerId = callerId,
                    direction = CallDirection.GSM_INBOUND,
                    simSlot = simSlot
                )

                if (!enqueued) {
                    GatewayLogger.warn(TAG, "Call queue full, rejecting call")
                    gsmCallManager.rejectCall()
                }
            } catch (e: Exception) {
                GatewayLogger.error(TAG, "Error enqueueing incoming call", e)
            }
        }
    }

    private fun handleCallAnswered() {
        val currentState = gsmCallManager.callState.value
        
        when (currentState) {
            is GsmCallState.Ringing -> {
                GatewayLogger.info(TAG, "Call answered: ${currentState.callerId}")
                gsmCallManager.updateCallState(
                    GsmCallState.Active(currentState.callerId, currentState.simSlot)
                )
                
                scope.launch {
                    callQueue.markCallActive(currentState.callerId)
                }
            }
            is GsmCallState.Dialing -> {
                GatewayLogger.info(TAG, "Outgoing call connected: ${currentState.number}")
                gsmCallManager.updateCallState(
                    GsmCallState.Active(currentState.number, currentState.simSlot)
                )
            }
            else -> {
                GatewayLogger.debug(TAG, "Call went off-hook from state: ${currentState.displayName}")
            }
        }
    }

    private fun handleCallEnded() {
        val currentState = gsmCallManager.callState.value
        
        val callerId = when (currentState) {
            is GsmCallState.Active -> currentState.callerId
            is GsmCallState.Ringing -> currentState.callerId
            is GsmCallState.Dialing -> currentState.number
            is GsmCallState.OnHold -> currentState.callerId
            else -> null
        }

        GatewayLogger.info(TAG, "Call ended: $callerId")
        
        gsmCallManager.updateCallState(GsmCallState.Ended(EndReason.NORMAL, callerId))

        scope.launch {
            callerId?.let {
                callQueue.markCallCompleted(it, EndReason.NORMAL)
            }
            
            // Reset to idle after a brief delay
            kotlinx.coroutines.delay(500)
            gsmCallManager.updateCallState(GsmCallState.Idle)
        }
    }

    private fun handleOutgoingCall(intent: Intent) {
        val phoneNumber = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER) ?: return

        GatewayLogger.info(TAG, "Outgoing call to: ${phoneNumber.take(4)}...")

        val simSlot = dualSimManager.getActiveSimSlot()
        gsmCallManager.updateCallState(GsmCallState.Dialing(phoneNumber, simSlot))

        scope.launch {
            callQueue.enqueueCall(
                callerId = phoneNumber,
                direction = CallDirection.GSM_OUTBOUND,
                simSlot = simSlot
            )
        }
    }

    companion object {
        private const val TAG = "GsmStateReceiver"
    }
}
