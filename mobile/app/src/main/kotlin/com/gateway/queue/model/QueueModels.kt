package com.gateway.queue.model

import kotlinx.serialization.Serializable

@Serializable
enum class CallDirection {
    GSM_INBOUND,
    GSM_OUTBOUND,
    SIP_INBOUND,
    SIP_OUTBOUND
}

@Serializable
enum class QueueStatus {
    WAITING,
    ACTIVE,
    COMPLETED,
    FAILED
}

@Serializable
data class QueuedCallInfo(
    val id: Long,
    val callerId: String,
    val direction: CallDirection,
    val status: QueueStatus,
    val simSlot: Int,
    val enqueuedAt: Long,
    val answeredAt: Long?,
    val endedAt: Long?,
    val failureReason: String?
) {
    val durationMs: Long?
        get() = if (answeredAt != null && endedAt != null) {
            endedAt - answeredAt
        } else if (answeredAt != null) {
            System.currentTimeMillis() - answeredAt
        } else {
            null
        }

    val waitTimeMs: Long
        get() = (answeredAt ?: System.currentTimeMillis()) - enqueuedAt
}
