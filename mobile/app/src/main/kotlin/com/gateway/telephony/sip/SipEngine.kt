package com.gateway.telephony.sip

import kotlinx.coroutines.flow.StateFlow

/**
 * SIP Registration state sealed class
 */
sealed class SipRegistrationState {
    object Unregistered : SipRegistrationState()
    object Registering : SipRegistrationState()
    object Registered : SipRegistrationState()
    data class Failed(val code: Int, val reason: String) : SipRegistrationState()

    val displayName: String
        get() = when (this) {
            is Unregistered -> "Unregistered"
            is Registering -> "Registering"
            is Registered -> "Registered"
            is Failed -> "Failed ($code)"
        }
}

/**
 * SIP Call state sealed class
 */
sealed class SipCallState {
    object Idle : SipCallState()
    data class Incoming(val callId: Int, val remoteUri: String) : SipCallState()
    data class Outgoing(val callId: Int, val remoteUri: String) : SipCallState()
    data class Ringing(val callId: Int, val remoteUri: String) : SipCallState()
    data class Connected(val callId: Int, val remoteUri: String, val startTime: Long = System.currentTimeMillis()) : SipCallState()
    data class OnHold(val callId: Int, val remoteUri: String) : SipCallState()
    data class Disconnected(val callId: Int, val reason: SipDisconnectReason) : SipCallState()

    val displayName: String
        get() = when (this) {
            is Idle -> "Idle"
            is Incoming -> "Incoming"
            is Outgoing -> "Outgoing"
            is Ringing -> "Ringing"
            is Connected -> "Connected"
            is OnHold -> "On Hold"
            is Disconnected -> "Disconnected"
        }
}

enum class SipDisconnectReason {
    NORMAL,
    REJECTED,
    BUSY,
    TIMEOUT,
    ERROR,
    NETWORK_ERROR,
    NOT_FOUND,
    SERVER_ERROR
}

/**
 * Interface for SIP Engine - PJSIP wrapper
 */
interface SipEngine {
    /** Current SIP registration state */
    val registrationState: StateFlow<SipRegistrationState>

    /** Current SIP call state */
    val callState: StateFlow<SipCallState>

    /** Active call count */
    val activeCallCount: StateFlow<Int>

    /** Initialize PJSIP library */
    suspend fun initialize()

    /** Shutdown PJSIP library */
    suspend fun shutdown()

    /** Register SIP account */
    suspend fun registerAccount()

    /** Unregister SIP account */
    suspend fun unregisterAccount()

    /** Make an outgoing SIP call */
    suspend fun makeCall(sipUri: String): Int?

    /** Answer an incoming SIP call */
    suspend fun answerCall(callId: Int): Boolean

    /** Reject an incoming SIP call with specific code */
    suspend fun rejectCall(callId: Int, code: Int = 486): Boolean

    /** Hang up a SIP call */
    suspend fun hangupCall(callId: Int): Boolean

    /** Put a call on hold */
    suspend fun holdCall(callId: Int): Boolean

    /** Resume a held call */
    suspend fun resumeCall(callId: Int): Boolean

    /** Send DTMF digits */
    suspend fun sendDtmf(callId: Int, digits: String)

    /** Transfer call to another URI */
    suspend fun transferCall(callId: Int, targetUri: String): Boolean

    /** Get conference bridge port for audio routing */
    fun getConfPort(callId: Int): Int

    /** Set call callback listener */
    fun setCallListener(listener: SipCallListener?)
}

/**
 * Listener for SIP call events
 */
interface SipCallListener {
    fun onIncomingCall(callId: Int, remoteUri: String)
    fun onCallStateChanged(callId: Int, state: SipCallState)
    fun onRegistrationStateChanged(state: SipRegistrationState)
    fun onMediaStateChanged(callId: Int, hasMedia: Boolean)
}
