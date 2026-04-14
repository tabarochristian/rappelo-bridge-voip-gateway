package com.gateway.data.api.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ApiResponse<T>(
    @SerialName("success") val success: Boolean,
    @SerialName("data") val data: T? = null,
    @SerialName("error") val error: String? = null,
    @SerialName("timestamp") val timestamp: Long = System.currentTimeMillis()
)

@Serializable
data class CommandRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("action") val action: String,
    @SerialName("params") val params: Map<String, String> = emptyMap(),
    @SerialName("timestamp") val timestamp: Long = System.currentTimeMillis()
)

@Serializable
data class CommandResponse(
    @SerialName("command_id") val commandId: String,
    @SerialName("success") val success: Boolean,
    @SerialName("result") val result: String? = null,
    @SerialName("error") val error: String? = null
)

@Serializable
data class PollResponse(
    @SerialName("commands") val commands: List<CommandRequest> = emptyList(),
    @SerialName("poll_interval_ms") val pollIntervalMs: Long = 30000
)

@Serializable
data class GatewayStatus(
    @SerialName("device_id") val deviceId: String,
    @SerialName("online") val online: Boolean,
    @SerialName("sip_registered") val sipRegistered: Boolean,
    @SerialName("gsm_signal_strength") val gsmSignalStrength: Int,
    @SerialName("active_calls") val activeCalls: Int,
    @SerialName("queued_calls") val queuedCalls: Int,
    @SerialName("pending_sms") val pendingSms: Int,
    @SerialName("uptime_seconds") val uptimeSeconds: Long,
    @SerialName("battery_level") val batteryLevel: Int,
    @SerialName("is_charging") val isCharging: Boolean,
    @SerialName("sim1_active") val sim1Active: Boolean,
    @SerialName("sim2_active") val sim2Active: Boolean,
    @SerialName("timestamp") val timestamp: Long = System.currentTimeMillis()
)

@Serializable
data class CallEvent(
    @SerialName("event_id") val eventId: String,
    @SerialName("event_type") val eventType: String,
    @SerialName("call_id") val callId: String,
    @SerialName("direction") val direction: String,
    @SerialName("remote_number") val remoteNumber: String,
    @SerialName("local_extension") val localExtension: String? = null,
    @SerialName("start_time") val startTime: Long,
    @SerialName("end_time") val endTime: Long? = null,
    @SerialName("duration_seconds") val durationSeconds: Int? = null,
    @SerialName("termination_reason") val terminationReason: String? = null,
    @SerialName("sim_slot") val simSlot: Int = 0,
    @SerialName("timestamp") val timestamp: Long = System.currentTimeMillis()
)

@Serializable
data class SmsEvent(
    @SerialName("event_id") val eventId: String,
    @SerialName("event_type") val eventType: String,
    @SerialName("direction") val direction: String,
    @SerialName("remote_number") val remoteNumber: String,
    @SerialName("message_body") val messageBody: String,
    @SerialName("sim_slot") val simSlot: Int = 0,
    @SerialName("status") val status: String,
    @SerialName("timestamp") val timestamp: Long = System.currentTimeMillis()
)

@Serializable
data class InitiateCallRequest(
    @SerialName("destination") val destination: String,
    @SerialName("caller_id") val callerId: String? = null,
    @SerialName("sim_slot") val simSlot: Int = 0,
    @SerialName("priority") val priority: Int = 0
)

@Serializable
data class SendSmsRequest(
    @SerialName("destination") val destination: String,
    @SerialName("message") val message: String,
    @SerialName("sim_slot") val simSlot: Int = 0
)

@Serializable
data class RegistrationRequest(
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_name") val deviceName: String,
    @SerialName("api_version") val apiVersion: Int = 1,
    @SerialName("capabilities") val capabilities: List<String> = emptyList()
)

@Serializable
data class HeartbeatRequest(
    @SerialName("device_id") val deviceId: String,
    @SerialName("status") val status: GatewayStatus
)

@Serializable
data class EventBatch(
    @SerialName("device_id") val deviceId: String,
    @SerialName("events") val events: List<GatewayEvent>
)

@Serializable
data class GatewayEvent(
    @SerialName("event_id") val eventId: String,
    @SerialName("event_type") val eventType: String,
    @SerialName("payload") val payload: String,
    @SerialName("timestamp") val timestamp: Long = System.currentTimeMillis()
)
