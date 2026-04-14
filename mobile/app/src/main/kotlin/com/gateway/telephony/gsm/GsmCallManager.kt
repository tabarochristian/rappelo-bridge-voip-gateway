package com.gateway.telephony.gsm

import kotlinx.coroutines.flow.StateFlow

/**
 * GSM Call State sealed class representing all possible call states
 */
sealed class GsmCallState {
    object Idle : GsmCallState()
    data class Ringing(val callerId: String, val simSlot: Int = 0) : GsmCallState()
    data class Dialing(val number: String, val simSlot: Int = 0) : GsmCallState()
    data class Active(val callerId: String, val simSlot: Int = 0, val startTime: Long = System.currentTimeMillis()) : GsmCallState()
    data class OnHold(val callerId: String, val simSlot: Int = 0) : GsmCallState()
    data class Ended(val reason: EndReason, val callerId: String? = null) : GsmCallState()

    val displayName: String
        get() = when (this) {
            is Idle -> "Idle"
            is Ringing -> "Ringing"
            is Dialing -> "Dialing"
            is Active -> "Active"
            is OnHold -> "On Hold"
            is Ended -> "Ended"
        }
}

enum class EndReason {
    NORMAL,
    REJECTED,
    BUSY,
    NO_ANSWER,
    ERROR,
    NETWORK_ERROR,
    USER_HANGUP,
    REMOTE_HANGUP
}

/**
 * Interface for managing GSM calls
 */
interface GsmCallManager {
    /** Current call state */
    val callState: StateFlow<GsmCallState>

    /** Signal strength in dBm */
    val signalStrengthDbm: StateFlow<Int?>

    /** Number of active calls */
    val activeCallCount: StateFlow<Int>

    /** Initialize the GSM call manager */
    suspend fun initialize()

    /** Shutdown the GSM call manager */
    suspend fun shutdown()

    /**
     * Answer an incoming call
     * @return true if call was answered successfully
     */
    suspend fun answerCall(): Boolean

    /**
     * Reject an incoming call
     * @return true if call was rejected successfully
     */
    suspend fun rejectCall(): Boolean

    /**
     * Hang up the current active call
     * @return true if call was hung up successfully
     */
    suspend fun hangupCall(): Boolean

    /**
     * Place an outgoing GSM call
     * @param number The phone number to dial
     * @param simSlot The SIM slot to use (0 or 1)
     * @return true if call was initiated successfully
     */
    suspend fun placeCall(number: String, simSlot: Int = 0): Boolean

    /**
     * Hold the current active call
     * @return true if call was placed on hold successfully
     */
    suspend fun holdCall(): Boolean

    /**
     * Resume a held call
     * @return true if call was resumed successfully
     */
    suspend fun resumeCall(): Boolean

    /**
     * Send DTMF tones
     * @param digits The DTMF digits to send
     */
    suspend fun sendDtmf(digits: String)

    /**
     * Update the call state (called by GsmStateReceiver)
     */
    fun updateCallState(state: GsmCallState)

    /**
     * Update signal strength (called by telephony callback)
     */
    fun updateSignalStrength(dbm: Int)
}
