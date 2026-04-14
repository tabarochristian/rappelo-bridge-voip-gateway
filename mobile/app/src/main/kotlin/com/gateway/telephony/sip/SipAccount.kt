package com.gateway.telephony.sip

import com.gateway.util.GatewayLogger
import org.pjsip.pjsua2.*

/**
 * Custom SIP Account implementation wrapping PJSIP Account
 */
class GatewaySipAccount(
    private val engine: SipEngineImpl
) : Account() {

    override fun onRegState(prm: OnRegStateParam) {
        try {
            engine.onRegState(prm)
        } catch (e: Exception) {
            GatewayLogger.error(TAG, "Error in onRegState callback", e)
        }
    }

    override fun onIncomingCall(prm: OnIncomingCallParam) {
        try {
            val call = GatewaySipCall(this, engine, prm.callId)
            engine.onIncomingCall(call, prm)
        } catch (e: Exception) {
            GatewayLogger.error(TAG, "Error in onIncomingCall callback", e)
        }
    }

    override fun onRegStarted(prm: OnRegStartedParam) {
        GatewayLogger.debug(TAG, "Registration started, renew=${prm.renew}")
    }

    override fun onIncomingSubscribe(prm: OnIncomingSubscribeParam) {
        GatewayLogger.debug(TAG, "Incoming subscribe request")
        // Accept presence subscriptions by default
        prm.code = 200
    }

    override fun onInstantMessage(prm: OnInstantMessageParam) {
        GatewayLogger.info(TAG, "Instant message received from ${prm.fromUri}")
        // Handle SIP MESSAGE if needed
    }

    override fun onInstantMessageStatus(prm: OnInstantMessageStatusParam) {
        GatewayLogger.debug(TAG, "Instant message status: ${prm.code}")
    }

    override fun onTypingIndication(prm: OnTypingIndicationParam) {
        // Ignore typing indications
    }

    override fun onMwiInfo(prm: OnMwiInfoParam) {
        GatewayLogger.debug(TAG, "MWI info received")
        // Handle voicemail notifications if needed
    }

    companion object {
        private const val TAG = "SipAccount"
    }
}
