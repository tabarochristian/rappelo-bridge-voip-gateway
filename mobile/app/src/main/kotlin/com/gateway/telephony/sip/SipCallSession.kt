package com.gateway.telephony.sip

import com.gateway.util.GatewayLogger
import org.pjsip.pjsua2.*

/**
 * Custom SIP Call implementation wrapping PJSIP Call
 */
class GatewaySipCall : Call {

    private val engine: SipEngineImpl

    constructor(account: GatewaySipAccount, engine: SipEngineImpl) : super(account) {
        this.engine = engine
    }

    constructor(account: GatewaySipAccount, engine: SipEngineImpl, callId: Int) : super(account, callId) {
        this.engine = engine
    }

    override fun onCallState(prm: OnCallStateParam) {
        try {
            engine.onCallState(this, prm)
        } catch (e: Exception) {
            GatewayLogger.error(TAG, "Error in onCallState callback", e)
        }
    }

    override fun onCallMediaState(prm: OnCallMediaStateParam) {
        try {
            engine.onCallMediaState(this, prm)
        } catch (e: Exception) {
            GatewayLogger.error(TAG, "Error in onCallMediaState callback", e)
        }
    }

    override fun onCallTsxState(prm: OnCallTsxStateParam) {
        GatewayLogger.debug(TAG, "Call transaction state changed")
    }

    override fun onCallSdpCreated(prm: OnCallSdpCreatedParam) {
        GatewayLogger.debug(TAG, "SDP created")
    }

    override fun onStreamCreated(prm: OnStreamCreatedParam) {
        GatewayLogger.debug(TAG, "Media stream created")
    }

    override fun onStreamDestroyed(prm: OnStreamDestroyedParam) {
        GatewayLogger.debug(TAG, "Media stream destroyed")
    }

    override fun onDtmfDigit(prm: OnDtmfDigitParam) {
        GatewayLogger.info(TAG, "DTMF digit received: ${prm.digit}")
    }

    override fun onCallTransferRequest(prm: OnCallTransferRequestParam) {
        GatewayLogger.info(TAG, "Call transfer request to ${prm.dstUri}")
        // Accept transfers by default
    }

    override fun onCallTransferStatus(prm: OnCallTransferStatusParam) {
        GatewayLogger.debug(TAG, "Call transfer status: ${prm.statusCode}")
    }

    override fun onCallReplaceRequest(prm: OnCallReplaceRequestParam) {
        GatewayLogger.info(TAG, "Call replace request")
    }

    override fun onCallReplaced(prm: OnCallReplacedParam) {
        GatewayLogger.info(TAG, "Call replaced with call ${prm.newCallId}")
    }

    override fun onCallRxOffer(prm: OnCallRxOfferParam) {
        GatewayLogger.debug(TAG, "Received SDP offer")
    }

    override fun onCallTxOffer(prm: OnCallTxOfferParam) {
        GatewayLogger.debug(TAG, "Sending SDP offer")
    }

    override fun onInstantMessage(prm: OnInstantMessageParam) {
        GatewayLogger.info(TAG, "Instant message in call from ${prm.fromUri}")
    }

    override fun onInstantMessageStatus(prm: OnInstantMessageStatusParam) {
        GatewayLogger.debug(TAG, "Instant message status in call: ${prm.code}")
    }

    override fun onTypingIndication(prm: OnTypingIndicationParam) {
        // Ignore
    }

    override fun onCallRedirected(prm: OnCallRedirectedParam): Int {
        GatewayLogger.info(TAG, "Call redirected to ${prm.targetUri}")
        return 0
    }

    override fun onCallMediaTransportState(prm: OnCallMediaTransportStateParam) {
        GatewayLogger.debug(TAG, "Media transport state: ${prm.state}")
    }

    override fun onCallMediaEvent(prm: OnCallMediaEventParam) {
        GatewayLogger.debug(TAG, "Media event received")
    }

    override fun onCreateMediaTransport(prm: OnCreateMediaTransportParam) {
        GatewayLogger.debug(TAG, "Creating media transport")
    }

    override fun onCreateMediaTransportSrtp(prm: OnCreateMediaTransportSrtpParam) {
        GatewayLogger.debug(TAG, "Creating SRTP media transport")
    }

    companion object {
        private const val TAG = "SipCall"
    }
}
