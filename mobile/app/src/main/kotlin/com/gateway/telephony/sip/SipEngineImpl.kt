package com.gateway.telephony.sip

import android.content.Context
import com.gateway.data.prefs.EncryptedPrefsManager
import com.gateway.util.GatewayLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.pjsip.pjsua2.*
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SipEngineImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefsManager: EncryptedPrefsManager
) : SipEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Single-threaded dispatcher for all PJSIP operations.
    // PJSIP requires threads to be registered via pj_thread_register();
    // by confining every call to one thread we register it once and avoid crashes.
    private val pjDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "pjsip-thread").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private var pjThreadRegistered = false

    private val _registrationState = MutableStateFlow<SipRegistrationState>(SipRegistrationState.Unregistered)
    override val registrationState: StateFlow<SipRegistrationState> = _registrationState.asStateFlow()

    private val _callState = MutableStateFlow<SipCallState>(SipCallState.Idle)
    override val callState: StateFlow<SipCallState> = _callState.asStateFlow()

    private val _activeCallCount = MutableStateFlow(0)
    override val activeCallCount: StateFlow<Int> = _activeCallCount.asStateFlow()

    private var endpoint: Endpoint? = null
    private var sipAccount: GatewaySipAccount? = null
    private var callListener: SipCallListener? = null
    private var isInitialized = false

    private val activeCalls = mutableMapOf<Int, GatewaySipCall>()
    private var registrationRetryCount = 0
    private val maxRegistrationRetries = 10

    override suspend fun initialize() = withContext(pjDispatcher) {
        if (isInitialized) {
            GatewayLogger.warn(TAG, "SIP engine already initialized")
            return@withContext
        }

        try {
            GatewayLogger.info(TAG, "Initializing PJSIP...")

            // Create endpoint
            endpoint = Endpoint()
            endpoint?.libCreate()

            // Configure endpoint
            val epConfig = EpConfig()
            
            // Log config
            epConfig.logConfig.level = 4
            epConfig.logConfig.consoleLevel = 4
            
            // Media config
            epConfig.medConfig.clockRate = 16000
            epConfig.medConfig.sndClockRate = 16000
            epConfig.medConfig.channelCount = 1
            epConfig.medConfig.ecOptions = 1
            epConfig.medConfig.ecTailLen = 200
            epConfig.medConfig.noVad = true

            // UA config
            epConfig.uaConfig.userAgent = "GsmSipGateway/1.0"
            epConfig.uaConfig.maxCalls = 4

            endpoint?.libInit(epConfig)

            // Create transports
            createTransports()

            // Set codec priorities
            configureCodecs()

            // Start endpoint
            endpoint?.libStart()

            // Register this dedicated thread with PJSIP
            if (!pjThreadRegistered) {
                endpoint?.libRegisterThread(Thread.currentThread().name)
                pjThreadRegistered = true
            }

            isInitialized = true
            GatewayLogger.info(TAG, "PJSIP initialized successfully")

        } catch (e: Exception) {
            GatewayLogger.error(TAG, "Failed to initialize PJSIP", e)
            throw e
        }
    }

    private fun createTransports() {
        val ep = endpoint ?: return

        try {
            // UDP transport
            val udpConfig = TransportConfig()
            udpConfig.port = 5060
            ep.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_UDP, udpConfig)
            GatewayLogger.debug(TAG, "UDP transport created on port 5060")

            // TCP transport
            val tcpConfig = TransportConfig()
            tcpConfig.port = 5060
            ep.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_TCP, tcpConfig)
            GatewayLogger.debug(TAG, "TCP transport created on port 5060")

        } catch (e: Exception) {
            GatewayLogger.error(TAG, "Failed to create transports", e)
        }
    }

    private fun configureCodecs() {
        val ep = endpoint ?: return

        try {
            val codecs = prefsManager.getCodecPriority()
            val codecPriorities = mapOf(
                "PCMU" to "PCMU/8000/1",
                "PCMA" to "PCMA/8000/1",
                "G729" to "G729/8000/1",
                "opus" to "opus/48000/2"
            )

            // Set all codecs to low priority first
            for ((_, codecId) in codecPriorities) {
                try {
                    ep.codecSetPriority(codecId, 0.toShort())
                } catch (e: Exception) {
                    // Codec might not exist
                }
            }

            // Set priorities based on config
            var priority: Short = 255
            for (codec in codecs) {
                codecPriorities[codec]?.let { codecId ->
                    try {
                        ep.codecSetPriority(codecId, priority)
                        priority = (priority - 10).toShort()
                    } catch (e: Exception) {
                        GatewayLogger.debug(TAG, "Codec $codec not available")
                    }
                }
            }

            // Disable video codecs
            try {
                ep.codecSetPriority("H264/97", 0.toShort())
                ep.codecSetPriority("VP8/90000", 0.toShort())
                ep.codecSetPriority("VP9/90000", 0.toShort())
            } catch (e: Exception) {
                // Video codecs might not exist
            }

            GatewayLogger.info(TAG, "Codec priorities configured: $codecs")

        } catch (e: Exception) {
            GatewayLogger.error(TAG, "Failed to configure codecs", e)
        }
    }

    override suspend fun shutdown() = withContext(pjDispatcher) {
        if (!isInitialized) return@withContext

        try {
            GatewayLogger.info(TAG, "Shutting down PJSIP...")

            // Hang up all calls
            activeCalls.values.toList().forEach { call ->
                try {
                    call.hangup(CallOpParam())
                } catch (e: Exception) {
                    GatewayLogger.warn(TAG, "Error hanging up call", e)
                }
            }
            activeCalls.clear()

            // Unregister account
            sipAccount?.let {
                try {
                    it.setRegistration(false)
                    it.delete()
                } catch (e: Exception) {
                    GatewayLogger.warn(TAG, "Error unregistering account", e)
                }
            }
            sipAccount = null

            // Destroy endpoint
            endpoint?.let {
                try {
                    it.libDestroy()
                } catch (e: Exception) {
                    GatewayLogger.warn(TAG, "Error destroying endpoint", e)
                }
            }
            endpoint = null

            isInitialized = false
            _registrationState.value = SipRegistrationState.Unregistered
            _callState.value = SipCallState.Idle
            _activeCallCount.value = 0

            GatewayLogger.info(TAG, "PJSIP shutdown complete")

        } catch (e: Exception) {
            GatewayLogger.error(TAG, "Error during PJSIP shutdown", e)
        }
    }

    override suspend fun registerAccount() = withContext(pjDispatcher) {
        if (!isInitialized) {
            GatewayLogger.error(TAG, "SIP engine not initialized")
            return@withContext
        }

        try {
            _registrationState.value = SipRegistrationState.Registering
            
            val sipServer = prefsManager.getSipServer()
            val sipUsername = prefsManager.getSipUsername()
            val sipPassword = prefsManager.getSipPassword()
            val sipDomain = prefsManager.getSipDomain().ifEmpty { sipServer }
            val sipDisplayName = prefsManager.getSipDisplayName()
            val sipTransport = prefsManager.getSipTransport()
            
            if (sipServer.isEmpty() || sipUsername.isEmpty() || sipPassword.isEmpty()) {
                GatewayLogger.error(TAG, "Incomplete SIP configuration")
                _registrationState.value = SipRegistrationState.Failed(400, "Incomplete configuration")
                return@withContext
            }

            // Delete existing account if any
            sipAccount?.let {
                try {
                    it.delete()
                } catch (e: Exception) {
                    // Ignore
                }
            }

            // Create account config
            val accConfig = AccountConfig()
            
            // ID/URI
            accConfig.idUri = "sip:$sipUsername@$sipDomain"
            
            // Registration URI
            val transport = when (sipTransport.uppercase()) {
                "TCP" -> ";transport=tcp"
                "TLS" -> ";transport=tls"
                else -> ""
            }
            accConfig.regConfig.registrarUri = "sip:$sipServer$transport"
            accConfig.regConfig.timeoutSec = 300
            accConfig.regConfig.retryIntervalSec = 60

            // Auth credentials
            val authCred = AuthCredInfo()
            authCred.scheme = "digest"
            authCred.realm = "*"
            authCred.username = sipUsername
            authCred.data = sipPassword
            authCred.dataType = 0 // Plain text password
            accConfig.sipConfig.authCreds.add(authCred)

            // NAT config
            val stunServer = prefsManager.getStunServer()
            if (stunServer.isNotEmpty()) {
                accConfig.natConfig.iceEnabled = prefsManager.isIceEnabled()
                accConfig.natConfig.sipStunUse = pjsua_stun_use.PJSUA_STUN_USE_DEFAULT
                accConfig.natConfig.mediaStunUse = pjsua_stun_use.PJSUA_STUN_USE_DEFAULT
            }

            // TURN config
            val turnServer = prefsManager.getTurnServer()
            if (turnServer.isNotEmpty()) {
                accConfig.natConfig.turnEnabled = true
                accConfig.natConfig.turnServer = turnServer
                accConfig.natConfig.turnUserName = prefsManager.getTurnUsername()
                accConfig.natConfig.turnPassword = prefsManager.getTurnPassword()
            }

            // Media config
            accConfig.mediaConfig.srtpUse = pjmedia_srtp_use.PJMEDIA_SRTP_OPTIONAL

            // Create account
            sipAccount = GatewaySipAccount(this@SipEngineImpl)
            sipAccount?.create(accConfig)

            GatewayLogger.info(TAG, "SIP account created, registering...")
            registrationRetryCount = 0

        } catch (e: Exception) {
            GatewayLogger.error(TAG, "Failed to register SIP account", e)
            _registrationState.value = SipRegistrationState.Failed(500, e.message ?: "Unknown error")
            scheduleRegistrationRetry()
        }
    }

    override suspend fun unregisterAccount(): Unit = withContext(pjDispatcher) {
        sipAccount?.let {
            try {
                it.setRegistration(false)
                _registrationState.value = SipRegistrationState.Unregistered
                GatewayLogger.info(TAG, "SIP account unregistered")
            } catch (e: Exception) {
                GatewayLogger.error(TAG, "Failed to unregister", e)
            }
        }
    }

    override suspend fun makeCall(sipUri: String): Int? = withContext(pjDispatcher) {
        if (!isInitialized || sipAccount == null) {
            GatewayLogger.error(TAG, "Cannot make call - not initialized")
            return@withContext null
        }

        try {
            val call = GatewaySipCall(sipAccount!!, this@SipEngineImpl)
            val callParam = CallOpParam()
            callParam.opt.audioCount = 1
            callParam.opt.videoCount = 0

            call.makeCall(sipUri, callParam)
            
            val callId = call.id
            activeCalls[callId] = call
            _activeCallCount.value = activeCalls.size
            _callState.value = SipCallState.Outgoing(callId, sipUri)

            GatewayLogger.info(TAG, "Making SIP call to $sipUri, callId: $callId")
            callId

        } catch (e: Exception) {
            GatewayLogger.error(TAG, "Failed to make SIP call", e)
            null
        }
    }

    override suspend fun answerCall(callId: Int): Boolean = withContext(pjDispatcher) {
        val call = activeCalls[callId]
        if (call == null) {
            GatewayLogger.warn(TAG, "Call $callId not found")
            return@withContext false
        }

        try {
            val param = CallOpParam()
            param.statusCode = pjsip_status_code.PJSIP_SC_OK
            call.answer(param)
            
            GatewayLogger.info(TAG, "Answered call $callId")
            true
        } catch (e: Exception) {
            GatewayLogger.error(TAG, "Failed to answer call $callId", e)
            false
        }
    }

    override suspend fun rejectCall(callId: Int, code: Int): Boolean = withContext(pjDispatcher) {
        val call = activeCalls[callId]
        if (call == null) {
            GatewayLogger.warn(TAG, "Call $callId not found")
            return@withContext false
        }

        try {
            val param = CallOpParam()
            param.statusCode = code
            call.hangup(param)
            
            activeCalls.remove(callId)
            _activeCallCount.value = activeCalls.size
            
            GatewayLogger.info(TAG, "Rejected call $callId with code $code")
            true
        } catch (e: Exception) {
            GatewayLogger.error(TAG, "Failed to reject call $callId", e)
            false
        }
    }

    override suspend fun hangupCall(callId: Int): Boolean = withContext(pjDispatcher) {
        val call = activeCalls[callId]
        if (call == null) {
            GatewayLogger.warn(TAG, "Call $callId not found")
            return@withContext false
        }

        try {
            val param = CallOpParam()
            call.hangup(param)
            
            activeCalls.remove(callId)
            _activeCallCount.value = activeCalls.size
            
            GatewayLogger.info(TAG, "Hung up call $callId")
            true
        } catch (e: Exception) {
            GatewayLogger.error(TAG, "Failed to hang up call $callId", e)
            false
        }
    }

    override suspend fun holdCall(callId: Int): Boolean = withContext(pjDispatcher) {
        val call = activeCalls[callId]
        if (call == null) {
            GatewayLogger.warn(TAG, "Call $callId not found")
            return@withContext false
        }

        try {
            val param = CallOpParam()
            call.setHold(param)
            GatewayLogger.info(TAG, "Call $callId put on hold")
            true
        } catch (e: Exception) {
            GatewayLogger.error(TAG, "Failed to hold call $callId", e)
            false
        }
    }

    override suspend fun resumeCall(callId: Int): Boolean = withContext(pjDispatcher) {
        val call = activeCalls[callId]
        if (call == null) {
            GatewayLogger.warn(TAG, "Call $callId not found")
            return@withContext false
        }

        try {
            val param = CallOpParam()
            param.opt.audioCount = 1
            param.opt.videoCount = 0
            call.reinvite(param)
            GatewayLogger.info(TAG, "Call $callId resumed")
            true
        } catch (e: Exception) {
            GatewayLogger.error(TAG, "Failed to resume call $callId", e)
            false
        }
    }

    override suspend fun sendDtmf(callId: Int, digits: String) = withContext(pjDispatcher) {
        val call = activeCalls[callId]
        if (call == null) {
            GatewayLogger.warn(TAG, "Call $callId not found")
            return@withContext
        }

        try {
            call.dialDtmf(digits)
            GatewayLogger.info(TAG, "Sent DTMF $digits on call $callId")
        } catch (e: Exception) {
            GatewayLogger.error(TAG, "Failed to send DTMF", e)
        }
    }

    override suspend fun transferCall(callId: Int, targetUri: String): Boolean = withContext(pjDispatcher) {
        val call = activeCalls[callId]
        if (call == null) {
            GatewayLogger.warn(TAG, "Call $callId not found")
            return@withContext false
        }

        try {
            val param = CallOpParam()
            call.xfer(targetUri, param)
            GatewayLogger.info(TAG, "Transferred call $callId to $targetUri")
            true
        } catch (e: Exception) {
            GatewayLogger.error(TAG, "Failed to transfer call $callId", e)
            false
        }
    }

    override fun getConfPort(callId: Int): Int {
        val call = activeCalls[callId] ?: return -1
        return try {
            val ci = call.info
            val media = ci.media
            val mediaCount: Int = media?.size ?: 0
            if (mediaCount > 0) media.get(0).audioConfSlot else -1
        } catch (e: Exception) {
            -1
        }
    }

    override fun setCallListener(listener: SipCallListener?) {
        callListener = listener
    }

    // Internal callback handlers
    internal fun onRegState(info: OnRegStateParam) {
        val code = info.code
        val reason = info.reason

        GatewayLogger.info(TAG, "Registration state: code=$code, reason=$reason")

        _registrationState.value = when {
            code == 200 -> {
                registrationRetryCount = 0
                SipRegistrationState.Registered
            }
            code in 100..199 -> SipRegistrationState.Registering
            else -> {
                scheduleRegistrationRetry()
                SipRegistrationState.Failed(code, reason)
            }
        }

        callListener?.onRegistrationStateChanged(_registrationState.value)
    }

    internal fun onIncomingCall(call: GatewaySipCall, info: OnIncomingCallParam) {
        val callId = call.id
        val remoteUri = info.rdata.srcAddress

        GatewayLogger.info(TAG, "Incoming SIP call from $remoteUri, callId: $callId")

        activeCalls[callId] = call
        _activeCallCount.value = activeCalls.size
        _callState.value = SipCallState.Incoming(callId, remoteUri)

        // Send 180 Ringing
        try {
            val param = CallOpParam()
            param.statusCode = pjsip_status_code.PJSIP_SC_RINGING
            call.answer(param)
        } catch (e: Exception) {
            GatewayLogger.error(TAG, "Failed to send 180 Ringing", e)
        }

        callListener?.onIncomingCall(callId, remoteUri)
    }

    internal fun onCallState(call: GatewaySipCall, info: OnCallStateParam) {
        val callId = call.id
        val callInfo = call.info
        val state = callInfo.state
        val stateText = callInfo.stateText
        val lastCode = callInfo.lastStatusCode
        val remoteUri = callInfo.remoteUri

        GatewayLogger.info(TAG, "Call state changed: callId=$callId, state=$stateText, code=$lastCode")

        val newState = when (state) {
            pjsip_inv_state.PJSIP_INV_STATE_NULL,
            pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED -> {
                activeCalls.remove(callId)
                _activeCallCount.value = activeCalls.size
                
                val reason = mapDisconnectReason(lastCode)
                SipCallState.Disconnected(callId, reason)
            }
            pjsip_inv_state.PJSIP_INV_STATE_CALLING -> SipCallState.Outgoing(callId, remoteUri)
            pjsip_inv_state.PJSIP_INV_STATE_INCOMING -> SipCallState.Incoming(callId, remoteUri)
            pjsip_inv_state.PJSIP_INV_STATE_EARLY -> SipCallState.Ringing(callId, remoteUri)
            pjsip_inv_state.PJSIP_INV_STATE_CONNECTING -> SipCallState.Outgoing(callId, remoteUri)
            pjsip_inv_state.PJSIP_INV_STATE_CONFIRMED -> SipCallState.Connected(callId, remoteUri)
            else -> _callState.value
        }

        _callState.value = newState
        callListener?.onCallStateChanged(callId, newState)
    }

    internal fun onCallMediaState(call: GatewaySipCall, info: OnCallMediaStateParam) {
        val callId = call.id
        val callInfo = call.info

        GatewayLogger.info(TAG, "Media state changed for call $callId")

        for (i in 0 until callInfo.media.size.toInt()) {
            val mediaInfo = callInfo.media[i]
            if (mediaInfo.type == pjmedia_type.PJMEDIA_TYPE_AUDIO &&
                (mediaInfo.status == pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE ||
                 mediaInfo.status == pjsua_call_media_status.PJSUA_CALL_MEDIA_REMOTE_HOLD)) {

                val hasMedia = mediaInfo.status == pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE
                callListener?.onMediaStateChanged(callId, hasMedia)
                break
            }
        }
    }

    private fun mapDisconnectReason(code: Int): SipDisconnectReason {
        return when (code) {
            200 -> SipDisconnectReason.NORMAL
            486 -> SipDisconnectReason.BUSY
            487 -> SipDisconnectReason.NORMAL // Request Terminated
            480 -> SipDisconnectReason.TIMEOUT
            404 -> SipDisconnectReason.NOT_FOUND
            401, 407 -> SipDisconnectReason.ERROR // Auth required
            408, 504 -> SipDisconnectReason.TIMEOUT
            in 500..599 -> SipDisconnectReason.SERVER_ERROR
            else -> SipDisconnectReason.ERROR
        }
    }

    private fun scheduleRegistrationRetry() {
        if (registrationRetryCount >= maxRegistrationRetries) {
            GatewayLogger.error(TAG, "Max registration retries reached")
            return
        }

        registrationRetryCount++
        val delayMs = calculateBackoff(registrationRetryCount)
        
        GatewayLogger.info(TAG, "Scheduling registration retry in ${delayMs}ms (attempt $registrationRetryCount)")

        scope.launch {
            delay(delayMs)
            if (_registrationState.value !is SipRegistrationState.Registered) {
                registerAccount()
            }
        }
    }

    private fun calculateBackoff(attempt: Int): Long {
        // Exponential backoff: 1s → 2s → 4s → 8s → ... → max 60s
        return (Math.pow(2.0, (attempt - 1).toDouble()) * 1000).toLong().coerceAtMost(60000)
    }

    companion object {
        private const val TAG = "SipEngine"
    }
}
