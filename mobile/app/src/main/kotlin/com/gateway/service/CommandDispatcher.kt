package com.gateway.service

import com.gateway.bridge.CallBridge
import com.gateway.data.api.CommandPoller
import com.gateway.data.api.GatewayApiService
import com.gateway.data.api.model.CommandRequest
import com.gateway.data.prefs.EncryptedPrefsManager
import com.gateway.queue.CallQueue
import com.gateway.queue.model.CallDirection
import com.gateway.telephony.gsm.GsmCallManager
import com.gateway.telephony.gsm.SmsGsmManager
import com.gateway.telephony.gsm.UssdState
import com.gateway.telephony.sip.SipEngine
import com.gateway.util.GatewayLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class CommandDispatcher @Inject constructor(
    private val commandPoller: CommandPoller,
    private val sipEngine: SipEngine,
    private val callBridge: CallBridge,
    private val callQueue: CallQueue,
    private val smsManager: SmsGsmManager,
    private val gsmCallManager: GsmCallManager,
    private val prefsManager: EncryptedPrefsManager,
    @Named("ApplicationScope") private val applicationScope: CoroutineScope
) {
    companion object {
        private const val TAG = "CommandDispatcher"
    }

    private var collectJob: Job? = null

    fun start() {
        if (collectJob?.isActive == true) return

        collectJob = applicationScope.launch {
            commandPoller.commands.collect { command ->
                handleCommand(command)
            }
        }
        GatewayLogger.i(TAG, "Command dispatcher started")
    }

    fun stop() {
        collectJob?.cancel()
        collectJob = null
        GatewayLogger.i(TAG, "Command dispatcher stopped")
    }

    private suspend fun handleCommand(command: CommandRequest) {
        GatewayLogger.i(TAG, "Dispatching command: ${command.action} (${command.commandId})")

        try {
            when (command.action) {
                "make_call" -> handleMakeCall(command)
                "hangup_call" -> handleHangupCall(command)
                "send_sms" -> handleSendSms(command)
                "send_ussd" -> handleSendUssd(command)
                "sip_register" -> handleSipRegister(command)
                "sip_unregister" -> handleSipUnregister(command)
                "get_status" -> handleGetStatus(command)
                "restart_sip" -> handleRestartSip(command)
                else -> {
                    GatewayLogger.w(TAG, "Unknown command action: ${command.action}")
                    commandPoller.reportResult(
                        commandId = command.commandId,
                        success = false,
                        result = null,
                        error = "Unknown action: ${command.action}"
                    )
                }
            }
        } catch (e: Exception) {
            GatewayLogger.e(TAG, "Error handling command ${command.commandId}", e)
            commandPoller.reportResult(
                commandId = command.commandId,
                success = false,
                result = null,
                error = e.message ?: "Unknown error"
            )
        }
    }

    private suspend fun handleMakeCall(command: CommandRequest) {
        val destination = command.params["destination"]
        if (destination.isNullOrBlank()) {
            commandPoller.reportResult(command.commandId, false, null, "Missing 'destination' parameter")
            return
        }

        val simSlot = command.params["sim_slot"]?.toIntOrNull() ?: 0
        val enqueued = callQueue.enqueueCall(
            callerId = destination,
            direction = CallDirection.GSM_OUTBOUND,
            simSlot = simSlot
        )

        commandPoller.reportResult(
            commandId = command.commandId,
            success = enqueued,
            result = if (enqueued) "Call queued to $destination" else null,
            error = if (!enqueued) "Queue full or call rejected" else null
        )
    }

    private suspend fun handleHangupCall(command: CommandRequest) {
        val callId = command.params["call_id"]
        if (callId.isNullOrBlank()) {
            commandPoller.reportResult(command.commandId, false, null, "Missing 'call_id' parameter")
            return
        }

        callBridge.endBridgeSession(callId)
        commandPoller.reportResult(command.commandId, true, "Hangup initiated for $callId", null)
    }

    private suspend fun handleSendSms(command: CommandRequest) {
        val destination = command.params["destination"]
        val message = command.params["message"]

        if (destination.isNullOrBlank() || message.isNullOrBlank()) {
            commandPoller.reportResult(command.commandId, false, null, "Missing 'destination' or 'message' parameter")
            return
        }

        val simSlot = command.params["sim_slot"]?.toIntOrNull() ?: 0
        val messageId = smsManager.queueSms(destination, message, simSlot)

        commandPoller.reportResult(
            commandId = command.commandId,
            success = messageId > 0,
            result = if (messageId > 0) "SMS queued (id=$messageId)" else null,
            error = if (messageId <= 0) "Failed to queue SMS" else null
        )
    }

    private suspend fun handleSipRegister(command: CommandRequest) {
        sipEngine.registerAccount()
        commandPoller.reportResult(command.commandId, true, "SIP registration initiated", null)
    }

    private suspend fun handleSipUnregister(command: CommandRequest) {
        sipEngine.unregisterAccount()
        commandPoller.reportResult(command.commandId, true, "SIP unregistered", null)
    }

    private suspend fun handleGetStatus(command: CommandRequest) {
        val sipState = sipEngine.registrationState.value
        val activeSessions = callBridge.activeSessions.value
        val queuedCalls = callQueue.queueState.value

        val status = buildString {
            append("sip=${sipState.displayName}")
            append(", active_sessions=${activeSessions.size}")
            append(", queued_calls=${queuedCalls.size}")
            append(", pending_sms=${smsManager.pendingSmsCount.value}")
        }

        commandPoller.reportResult(command.commandId, true, status, null)
    }

    private suspend fun handleRestartSip(command: CommandRequest) {
        sipEngine.shutdown()
        sipEngine.initialize()
        if (prefsManager.isSipConfigured()) {
            sipEngine.registerAccount()
        }
        commandPoller.reportResult(command.commandId, true, "SIP engine restarted", null)
    }

    private suspend fun handleSendUssd(command: CommandRequest) {
        val code = command.params["code"]
        if (code.isNullOrBlank()) {
            commandPoller.reportResult(command.commandId, false, null, "Missing 'code' parameter")
            return
        }

        val simSlot = command.params["sim_slot"]?.toIntOrNull() ?: 0
        val sent = gsmCallManager.sendUssdRequest(code, simSlot)
        if (!sent) {
            commandPoller.reportResult(command.commandId, false, null, "Failed to send USSD request")
            return
        }

        // Wait for response (up to 30 seconds)
        var attempts = 0
        while (attempts < 60) {
            kotlinx.coroutines.delay(500)
            val state = gsmCallManager.ussdState.value
            when (state) {
                is UssdState.Response -> {
                    commandPoller.reportResult(command.commandId, true, state.message, null)
                    gsmCallManager.dismissUssd()
                    return
                }
                is UssdState.Error -> {
                    commandPoller.reportResult(command.commandId, false, null, state.errorMessage)
                    gsmCallManager.dismissUssd()
                    return
                }
                else -> attempts++
            }
        }
        commandPoller.reportResult(command.commandId, false, null, "USSD request timed out")
        gsmCallManager.dismissUssd()
    }
}
