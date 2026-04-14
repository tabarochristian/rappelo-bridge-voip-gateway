package com.gateway.data.api

import com.gateway.bridge.CallBridge
import com.gateway.data.prefs.EncryptedPrefsManager
import com.gateway.queue.CallQueue
import com.gateway.queue.SmsQueue
import com.gateway.queue.model.QueuedCallInfo
import com.gateway.telephony.gsm.SmsGsmManager
import com.gateway.telephony.sip.SipEngine
import com.gateway.util.GatewayLogger
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class LocalHttpServer @Inject constructor(
    private val sipEngine: SipEngine,
    private val callBridge: CallBridge,
    private val callQueue: CallQueue,
    private val smsQueue: SmsQueue,
    private val smsManager: SmsGsmManager,
    private val prefsManager: EncryptedPrefsManager,
    private val gatewayLogDao: com.gateway.data.db.dao.GatewayLogDao,
    @Named("ApplicationScope") private val applicationScope: CoroutineScope
) : NanoHTTPD(DEFAULT_PORT) {

    companion object {
        private const val TAG = "LocalHttpServer"
        private const val DEFAULT_PORT = 8080
        private const val MIME_JSON = "application/json"
    }

    @Volatile
    private var isRunning = false

    val port: Int get() = DEFAULT_PORT

    fun startServer(): Boolean {
        return try {
            if (!isRunning) {
                start(SOCKET_READ_TIMEOUT, false)
                isRunning = true
                GatewayLogger.i(TAG, "Local HTTP server started on port $DEFAULT_PORT")
            }
            true
        } catch (e: Exception) {
            GatewayLogger.e(TAG, "Failed to start HTTP server: ${e.message}", e)
            false
        }
    }

    fun stopServer() {
        try {
            if (isRunning) {
                stop()
                isRunning = false
                GatewayLogger.i(TAG, "Local HTTP server stopped")
            }
        } catch (e: Exception) {
            GatewayLogger.e(TAG, "Error stopping HTTP server: ${e.message}", e)
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        GatewayLogger.d(TAG, "Request: $method $uri")

        return try {
            when {
                uri == "/" || uri == "/health" -> handleHealth()
                uri == "/status" && method == Method.GET -> handleGetStatus()
                uri == "/call" && method == Method.POST -> handleInitiateCall(session)
                uri == "/call/hangup" && method == Method.POST -> handleHangup(session)
                uri == "/sms" && method == Method.POST -> handleSendSms(session)
                uri == "/queue/calls" && method == Method.GET -> handleGetCallQueue()
                uri == "/queue/sms" && method == Method.GET -> handleGetSmsQueue()
                uri == "/logs" && method == Method.GET -> handleGetLogs(session)
                else -> errorResponse(Response.Status.NOT_FOUND, "Endpoint not found")
            }
        } catch (e: Exception) {
            GatewayLogger.e(TAG, "Error handling request: ${e.message}", e)
            errorResponse(Response.Status.INTERNAL_ERROR, e.message ?: "Unknown error")
        }
    }

    private fun handleHealth(): Response {
        val response = JSONObject().apply {
            put("status", "ok")
            put("timestamp", System.currentTimeMillis())
        }
        return jsonResponse(response)
    }

    private fun handleGetStatus(): Response {
        val sipState = sipEngine.registrationState.value
        val activeSessions = callBridge.activeSessions.value

        val obj = JSONObject().apply {
            put("device_id", prefsManager.getDeviceId())
            put("sip_registered", sipState.toString().contains("Registered"))
            put("sip_state", sipState.displayName)
            put("active_sessions", activeSessions.size)
            put("queued_calls", callQueue.queueState.value.size)
            put("pending_sms", smsQueue.pendingSmsCount.value)
            put("timestamp", System.currentTimeMillis())
        }
        return jsonResponse(obj)
    }

    private fun handleInitiateCall(session: IHTTPSession): Response {
        val params = parseBody(session)
        val destination = params["destination"]
            ?: return errorResponse(Response.Status.BAD_REQUEST, "destination required")

        val simSlot = params["sim_slot"]?.toIntOrNull() ?: 0

        applicationScope.launch {
            callQueue.enqueueCall(
                callerId = destination,
                direction = com.gateway.queue.model.CallDirection.GSM_OUTBOUND,
                simSlot = simSlot
            )
        }

        return jsonResponse(JSONObject().apply {
            put("success", true)
            put("message", "Call queued")
        })
    }

    private fun handleHangup(session: IHTTPSession): Response {
        val params = parseBody(session)
        val callId = params["call_id"]
            ?: return errorResponse(Response.Status.BAD_REQUEST, "call_id required")

        applicationScope.launch {
            callBridge.endBridgeSession(callId)
        }

        return jsonResponse(JSONObject().apply {
            put("success", true)
            put("message", "Hangup initiated")
        })
    }

    private fun handleSendSms(session: IHTTPSession): Response {
        val params = parseBody(session)
        val destination = params["destination"]
            ?: return errorResponse(Response.Status.BAD_REQUEST, "destination required")
        val message = params["message"]
            ?: return errorResponse(Response.Status.BAD_REQUEST, "message required")

        val simSlot = params["sim_slot"]?.toIntOrNull() ?: 0

        applicationScope.launch {
            smsManager.queueSms(destination, message, simSlot)
        }

        return jsonResponse(JSONObject().apply {
            put("success", true)
            put("message", "SMS queued for delivery")
        })
    }

    private fun handleGetCallQueue(): Response {
        val calls = callQueue.queueState.value
        val callsArray = org.json.JSONArray().apply {
            calls.forEach { call ->
                put(JSONObject().apply {
                    put("id", call.id)
                    put("caller_id", call.callerId)
                    put("direction", call.direction.name)
                    put("status", call.status.name)
                    put("enqueued_at", call.enqueuedAt)
                })
            }
        }
        return jsonResponse(JSONObject().apply {
            put("count", calls.size)
            put("calls", callsArray)
        })
    }

    private fun handleGetSmsQueue(): Response {
        val messages = smsQueue.pendingMessages.value
        val messagesArray = org.json.JSONArray().apply {
            messages.forEach { sms ->
                put(JSONObject().apply {
                    put("id", sms.id)
                    put("destination", sms.phoneNumber)
                    put("status", sms.status.name)
                    put("attempts", sms.attempts)
                })
            }
        }
        return jsonResponse(JSONObject().apply {
            put("count", messages.size)
            put("messages", messagesArray)
        })
    }

    private fun handleGetLogs(session: IHTTPSession): Response {
        val limit = session.parameters["limit"]?.firstOrNull()?.toIntOrNull() ?: 50
        val level = session.parameters["level"]?.firstOrNull()
        val tag = session.parameters["tag"]?.firstOrNull()
        val search = session.parameters["search"]?.firstOrNull()

        val logsArray = org.json.JSONArray()
        try {
            val logs = kotlinx.coroutines.runBlocking {
                when {
                    level != null -> gatewayLogDao.getByLevel(level.uppercase(), limit)
                    tag != null -> gatewayLogDao.getByTag(tag, limit)
                    search != null -> gatewayLogDao.search(search, limit)
                    else -> gatewayLogDao.getRecent(limit)
                }
            }
            logs.forEach { logEntity ->
                logsArray.put(JSONObject().apply {
                    put("id", logEntity.id)
                    put("timestamp", logEntity.timestamp)
                    put("level", logEntity.level)
                    put("tag", logEntity.tag)
                    put("message", logEntity.message)
                    if (logEntity.throwable != null) put("throwable", logEntity.throwable)
                })
            }
        } catch (e: Exception) {
            GatewayLogger.e(TAG, "Error querying logs: ${e.message}", e)
        }

        return jsonResponse(JSONObject().apply {
            put("logs", logsArray)
            put("count", logsArray.length())
            put("limit", limit)
            if (level != null) put("level_filter", level)
            if (tag != null) put("tag_filter", tag)
            if (search != null) put("search_filter", search)
        })
    }

    private fun parseBody(session: IHTTPSession): Map<String, String> {
        val files = mutableMapOf<String, String>()
        session.parseBody(files)
        val body = files["postData"] ?: ""
        return try {
            if (body.isNotBlank()) {
                val obj = JSONObject(body)
                obj.keys().asSequence().associateWith { obj.getString(it) }
            } else {
                session.parms ?: emptyMap()
            }
        } catch (e: Exception) {
            session.parms ?: emptyMap()
        }
    }

    private fun jsonResponse(data: JSONObject): Response {
        return newFixedLengthResponse(
            Response.Status.OK,
            MIME_JSON,
            data.toString(2)
        )
    }

    private fun errorResponse(status: Response.Status, message: String): Response {
        val error = JSONObject().apply {
            put("success", false)
            put("error", message)
            put("timestamp", System.currentTimeMillis())
        }
        return newFixedLengthResponse(status, MIME_JSON, error.toString())
    }
}
