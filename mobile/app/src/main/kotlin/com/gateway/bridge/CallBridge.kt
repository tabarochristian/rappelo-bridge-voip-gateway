package com.gateway.bridge

import com.gateway.data.api.GatewayApiService
import com.gateway.data.api.model.EventBatch
import com.gateway.data.api.model.GatewayEvent
import com.gateway.data.prefs.EncryptedPrefsManager
import com.gateway.queue.CallQueue
import com.gateway.queue.model.CallDirection
import com.gateway.queue.model.QueueStatus
import com.gateway.telephony.gsm.EndReason
import com.gateway.telephony.gsm.GsmCallManager
import com.gateway.telephony.gsm.GsmCallState
import com.gateway.telephony.sip.SipAudioBridge
import com.gateway.telephony.sip.SipCallListener
import com.gateway.telephony.sip.SipCallState
import com.gateway.telephony.sip.SipDisconnectReason
import com.gateway.telephony.sip.SipEngine
import com.gateway.telephony.sip.SipRegistrationState
import com.gateway.util.GatewayLogger
import com.gateway.util.NetworkMonitor
import com.gateway.util.WakeLockManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Interface for bridging GSM and SIP calls
 */
interface CallBridge {
    /** Current active bridge sessions */
    val activeSessions: StateFlow<List<BridgeSession>>

    /** Initialize call bridge */
    suspend fun initialize()

    /** Shutdown call bridge */
    suspend fun shutdown()

    /** Bridge an incoming GSM call to SIP */
    suspend fun bridgeGsmToSip(callerId: String)

    /** Bridge an incoming SIP call to GSM */
    suspend fun bridgeSipToGsm(sipCallId: Int, targetNumber: String, simSlot: Int = 0)

    /** End a bridge session */
    suspend fun endBridgeSession(sessionId: String)

    /** Get bridge session by ID */
    fun getSession(sessionId: String): BridgeSession?
}

@Singleton
class CallBridgeImpl @Inject constructor(
    private val sipEngine: SipEngine,
    private val gsmCallManager: GsmCallManager,
    private val sipAudioBridge: SipAudioBridge,
    private val audioRouter: AudioRouter,
    private val callQueue: CallQueue,
    private val wakeLockManager: WakeLockManager,
    private val prefsManager: EncryptedPrefsManager,
    private val apiService: GatewayApiService,
    private val networkMonitor: NetworkMonitor
) : CallBridge, SipCallListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val sessions = mutableMapOf<String, BridgeSession>()
    
    private val _activeSessions = MutableStateFlow<List<BridgeSession>>(emptyList())
    override val activeSessions: StateFlow<List<BridgeSession>> = _activeSessions.asStateFlow()

    override suspend fun initialize() {
        GatewayLogger.info(TAG, "Initializing CallBridge")
        
        // Set SIP call listener
        sipEngine.setCallListener(this)
        
        // Observe GSM call state changes
        observeGsmCalls()
        
        GatewayLogger.info(TAG, "CallBridge initialized")
    }

    override suspend fun shutdown() {
        GatewayLogger.info(TAG, "Shutting down CallBridge")
        
        // End all active sessions
        sessions.values.toList().forEach { session ->
            try {
                endBridgeSession(session.sessionId)
            } catch (e: Exception) {
                GatewayLogger.warn(TAG, "Error ending session ${session.sessionId}", e)
            }
        }
        
        sipEngine.setCallListener(null)
        
        GatewayLogger.info(TAG, "CallBridge shutdown complete")
    }

    private fun observeGsmCalls() {
        scope.launch {
            gsmCallManager.callState.collectLatest { state ->
                handleGsmCallStateChange(state)
            }
        }
    }

    private suspend fun handleGsmCallStateChange(state: GsmCallState) {
        GatewayLogger.debug(TAG, "GSM call state changed: ${state.displayName}")
        
        when (state) {
            is GsmCallState.Ringing -> {
                // Check if we should auto-bridge incoming GSM calls
                val bridgeEndpoint = prefsManager.getDefaultBridgeEndpoint()
                if (bridgeEndpoint.isNotEmpty()) {
                    bridgeGsmToSip(state.callerId)
                }
            }
            is GsmCallState.Active -> {
                // Check if there's a pending bridge session
                val session = findSessionByGsmCaller(state.callerId)
                if (session != null && session.state == BridgeSessionState.PENDING_GSM_ANSWER) {
                    handleGsmCallAnswered(session, state)
                }
            }
            is GsmCallState.Ended -> {
                // Find and end related bridge session
                val session = findSessionByGsmCaller(state.callerId ?: "")
                session?.let {
                    endBridgeSession(it.sessionId)
                }
            }
            else -> {}
        }
    }

    override suspend fun bridgeGsmToSip(callerId: String) {
        GatewayLogger.info(TAG, "Bridging GSM call from $callerId to SIP")
        
        // Acquire wake lock
        wakeLockManager.acquirePartialWakeLock()
        
        val sessionId = generateSessionId()
        val bridgeEndpoint = prefsManager.getDefaultBridgeEndpoint()
        
        if (bridgeEndpoint.isEmpty()) {
            GatewayLogger.error(TAG, "No bridge endpoint configured")
            wakeLockManager.releasePartialWakeLock()
            return
        }

        // Answer GSM call first
        val answered = gsmCallManager.answerCall()
        if (!answered) {
            GatewayLogger.error(TAG, "Failed to answer GSM call")
            wakeLockManager.releasePartialWakeLock()
            return
        }

        // Create bridge session
        val session = BridgeSession(
            sessionId = sessionId,
            direction = BridgeDirection.GSM_TO_SIP,
            gsmCallerId = callerId,
            sipEndpoint = bridgeEndpoint,
            state = BridgeSessionState.PENDING_SIP_CALL,
            startTime = System.currentTimeMillis()
        )
        
        sessions[sessionId] = session
        updateActiveSessions()

        // Make SIP call
        val sipCallId = sipEngine.makeCall(bridgeEndpoint)
        if (sipCallId == null) {
            GatewayLogger.error(TAG, "Failed to initiate SIP call")
            session.state = BridgeSessionState.FAILED
            session.endReason = "SIP call failed"
            endBridgeSession(sessionId)
            return
        }

        session.sipCallId = sipCallId
        session.state = BridgeSessionState.SIP_RINGING
        updateActiveSessions()
        
        // Send event to API
        sendCallEvent(CallEventType.BRIDGE_STARTED, session)
    }

    override suspend fun bridgeSipToGsm(sipCallId: Int, targetNumber: String, simSlot: Int) {
        GatewayLogger.info(TAG, "Bridging SIP call $sipCallId to GSM $targetNumber")
        
        wakeLockManager.acquirePartialWakeLock()
        
        val sessionId = generateSessionId()

        // Create bridge session
        val session = BridgeSession(
            sessionId = sessionId,
            direction = BridgeDirection.SIP_TO_GSM,
            sipCallId = sipCallId,
            gsmTargetNumber = targetNumber,
            simSlot = simSlot,
            state = BridgeSessionState.PENDING_GSM_CALL,
            startTime = System.currentTimeMillis()
        )
        
        sessions[sessionId] = session
        updateActiveSessions()

        // Send 180 Ringing to SIP caller (already done in SipEngine)

        // Place GSM call
        val gsmCallPlaced = gsmCallManager.placeCall(targetNumber, simSlot)
        if (!gsmCallPlaced) {
            GatewayLogger.error(TAG, "Failed to place GSM call")
            
            // Send 480 Temporarily Unavailable to SIP
            sipEngine.rejectCall(sipCallId, 480)
            
            session.state = BridgeSessionState.FAILED
            session.endReason = "GSM call failed"
            endBridgeSession(sessionId)
            return
        }

        session.gsmCallerId = targetNumber
        session.state = BridgeSessionState.PENDING_GSM_ANSWER
        updateActiveSessions()
        
        sendCallEvent(CallEventType.BRIDGE_STARTED, session)
    }

    private suspend fun handleGsmCallAnswered(session: BridgeSession, gsmState: GsmCallState.Active) {
        GatewayLogger.info(TAG, "GSM call answered for session ${session.sessionId}")
        
        // Answer SIP call (200 OK)
        val sipCallId = session.sipCallId
        if (sipCallId != null) {
            val answered = sipEngine.answerCall(sipCallId)
            if (!answered) {
                GatewayLogger.error(TAG, "Failed to answer SIP call")
                session.state = BridgeSessionState.FAILED
                endBridgeSession(session.sessionId)
                return
            }
        }
        
        // Connect audio bridge
        connectAudio(session)
        
        session.state = BridgeSessionState.CONNECTED
        session.connectedTime = System.currentTimeMillis()
        updateActiveSessions()
        
        sendCallEvent(CallEventType.BRIDGE_CONNECTED, session)
    }

    private suspend fun connectAudio(session: BridgeSession) {
        val sipCallId = session.sipCallId ?: return
        
        GatewayLogger.info(TAG, "Connecting audio for session ${session.sessionId}")
        
        try {
            // Request audio focus
            audioRouter.requestAudioFocus()
            
            // Get SIP conference port
            val confPort = sipEngine.getConfPort(sipCallId)
            
            // Start audio bridge
            sipAudioBridge.startBridge(confPort)
            
            GatewayLogger.info(TAG, "Audio connected for session ${session.sessionId}")
            
        } catch (e: Exception) {
            GatewayLogger.error(TAG, "Failed to connect audio", e)
        }
    }

    override suspend fun endBridgeSession(sessionId: String) {
        val session = sessions[sessionId] ?: return
        
        GatewayLogger.info(TAG, "Ending bridge session $sessionId")
        
        try {
            // Stop audio bridge
            sipAudioBridge.stopBridge()
            
            // Abandon audio focus
            audioRouter.abandonAudioFocus()
            
            // Hang up SIP call
            session.sipCallId?.let { sipCallId ->
                try {
                    sipEngine.hangupCall(sipCallId)
                } catch (e: Exception) {
                    GatewayLogger.warn(TAG, "Error hanging up SIP call", e)
                }
            }
            
            // Hang up GSM call if it's still active
            val gsmState = gsmCallManager.callState.value
            if (gsmState is GsmCallState.Active || gsmState is GsmCallState.Ringing) {
                try {
                    gsmCallManager.hangupCall()
                } catch (e: Exception) {
                    GatewayLogger.warn(TAG, "Error hanging up GSM call", e)
                }
            }
            
            // Update session state
            session.state = BridgeSessionState.ENDED
            session.endTime = System.currentTimeMillis()
            
            // Send event
            sendCallEvent(CallEventType.BRIDGE_ENDED, session)
            
            // Update call queue
            session.gsmCallerId?.let { callerId ->
                callQueue.markCallCompleted(callerId, EndReason.NORMAL)
            }
            
        } finally {
            sessions.remove(sessionId)
            updateActiveSessions()
            
            // Release wake lock if no active sessions
            if (sessions.isEmpty()) {
                wakeLockManager.releasePartialWakeLock()
            }
        }
    }

    override fun getSession(sessionId: String): BridgeSession? {
        return sessions[sessionId]
    }

    // SipCallListener implementation
    override fun onIncomingCall(callId: Int, remoteUri: String) {
        GatewayLogger.info(TAG, "Incoming SIP call: $callId from $remoteUri")
        
        scope.launch {
            // Check if we should bridge to GSM
            val targetNumber = extractTargetNumber(remoteUri)
            if (targetNumber != null) {
                bridgeSipToGsm(callId, targetNumber, prefsManager.getPreferredSimSlot())
            } else {
                // Queue the call if no target number
                callQueue.enqueueCall(
                    callerId = remoteUri,
                    direction = CallDirection.SIP_INBOUND,
                    simSlot = 0
                )
            }
        }
    }

    override fun onCallStateChanged(callId: Int, state: SipCallState) {
        GatewayLogger.debug(TAG, "SIP call $callId state: ${state.displayName}")
        
        scope.launch {
            val session = findSessionBySipCallId(callId)
            
            when (state) {
                is SipCallState.Connected -> {
                    if (session?.direction == BridgeDirection.GSM_TO_SIP) {
                        session.state = BridgeSessionState.CONNECTED
                        session.connectedTime = System.currentTimeMillis()
                        connectAudio(session)
                        sendCallEvent(CallEventType.BRIDGE_CONNECTED, session)
                        updateActiveSessions()
                    }
                }
                is SipCallState.Disconnected -> {
                    session?.let { endBridgeSession(it.sessionId) }
                }
                else -> {}
            }
        }
    }

    override fun onRegistrationStateChanged(state: SipRegistrationState) {
        // Handled by SipEngine directly
    }

    override fun onMediaStateChanged(callId: Int, hasMedia: Boolean) {
        GatewayLogger.debug(TAG, "SIP call $callId media state: hasMedia=$hasMedia")
    }

    private fun findSessionByGsmCaller(callerId: String): BridgeSession? {
        return sessions.values.find { 
            it.gsmCallerId == callerId || it.gsmTargetNumber == callerId 
        }
    }

    private fun findSessionBySipCallId(callId: Int): BridgeSession? {
        return sessions.values.find { it.sipCallId == callId }
    }

    private fun extractTargetNumber(sipUri: String): String? {
        // Extract phone number from SIP URI
        // Example: sip:+1234567890@domain.com -> +1234567890
        val regex = Regex("""sip:(\+?\d+)@""")
        return regex.find(sipUri)?.groupValues?.getOrNull(1)
    }

    private fun generateSessionId(): String {
        return "bridge_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"
    }

    private fun updateActiveSessions() {
        _activeSessions.value = sessions.values.toList()
    }

    private suspend fun sendCallEvent(type: CallEventType, session: BridgeSession) {
        if (!networkMonitor.isNetworkAvailable.value) return

        try {
            val deviceId = prefsManager.getDeviceId()
            if (deviceId.isBlank()) return

            val payload = org.json.JSONObject().apply {
                put("session_id", session.sessionId)
                put("direction", session.direction.name)
                put("gsm_caller_id", session.gsmCallerId ?: "")
                put("sip_endpoint", session.sipEndpoint ?: "")
                put("state", session.state.name)
                put("duration_ms", session.connectedTime?.let { System.currentTimeMillis() - it } ?: 0L)
            }.toString()

            val event = GatewayEvent(
                eventId = "bridge_${type.name.lowercase()}_${System.currentTimeMillis()}",
                eventType = type.name,
                payload = payload,
                timestamp = System.currentTimeMillis()
            )

            apiService.pushEvents(EventBatch(deviceId = deviceId, events = listOf(event)))
        } catch (e: Exception) {
            GatewayLogger.warn(TAG, "Failed to send call event", e)
        }
    }

    companion object {
        private const val TAG = "CallBridge"
    }
}

enum class CallEventType {
    BRIDGE_STARTED,
    BRIDGE_CONNECTED,
    BRIDGE_ENDED,
    BRIDGE_FAILED
}

data class BridgeSession(
    val sessionId: String,
    val direction: BridgeDirection,
    var gsmCallerId: String? = null,
    var gsmTargetNumber: String? = null,
    var sipCallId: Int? = null,
    var sipEndpoint: String? = null,
    var simSlot: Int = 0,
    var state: BridgeSessionState = BridgeSessionState.PENDING,
    val startTime: Long = System.currentTimeMillis(),
    var connectedTime: Long? = null,
    var endTime: Long? = null,
    var endReason: String? = null
)

enum class BridgeDirection {
    GSM_TO_SIP,
    SIP_TO_GSM
}

enum class BridgeSessionState {
    PENDING,
    PENDING_GSM_CALL,
    PENDING_GSM_ANSWER,
    PENDING_SIP_CALL,
    SIP_RINGING,
    CONNECTED,
    ENDED,
    FAILED
}
