package com.gateway.telephony.gsm

import android.telecom.Call
import android.telecom.InCallService
import com.gateway.util.GatewayLogger

/**
 * System-managed InCallService that receives Call objects when the app is the default dialer.
 * Delegates all call events to InCallServiceConnection singleton for consumption by GsmCallManagerImpl.
 */
class GatewayInCallService : InCallService() {

    companion object {
        private const val TAG = "GatewayInCallService"
    }

    override fun onCreate() {
        super.onCreate()
        GatewayLogger.info(TAG, "InCallService created")
    }

    override fun onBind(intent: android.content.Intent?): android.os.IBinder? {
        GatewayLogger.info(TAG, "InCallService binding")
        InCallServiceConnection.onServiceBound(this)
        return super.onBind(intent)
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        GatewayLogger.info(TAG, "InCallService unbinding")
        InCallServiceConnection.onServiceUnbound()
        return super.onUnbind(intent)
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        GatewayLogger.info(TAG, "onCallAdded: ${call.details?.handle}")
        InCallServiceConnection.onCallAdded(call)

        // Silence the ringer for incoming calls — we handle them programmatically
        if (call.state == Call.STATE_RINGING) {
            try {
                setMuted(false)  // Ensure mic is not muted for the bridge
            } catch (e: Exception) {
                GatewayLogger.warn(TAG, "Could not set mute state", e)
            }
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        GatewayLogger.info(TAG, "onCallRemoved: ${call.details?.handle}")
        InCallServiceConnection.onCallRemoved(call)
    }

    override fun onDestroy() {
        GatewayLogger.info(TAG, "InCallService destroyed")
        super.onDestroy()
    }
}
