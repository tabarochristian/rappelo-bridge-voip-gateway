package com.gateway.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "queued_calls",
    indices = [
        Index(value = ["callerId"]),
        Index(value = ["status"]),
        Index(value = ["enqueuedAt"])
    ]
)
data class QueuedCallEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val callerId: String,
    val direction: String, // CallDirection enum name
    val status: String, // QueueStatus enum name
    val simSlot: Int = 0,
    val enqueuedAt: Long,
    val answeredAt: Long? = null,
    val endedAt: Long? = null,
    val failureReason: String? = null
)

enum class SmsStatus {
    PENDING,
    SENDING,
    SENT,
    DELIVERED,
    FAILED,
    RECEIVED
}

@Entity(
    tableName = "sms_queue",
    indices = [
        Index(value = ["status"]),
        Index(value = ["createdAt"])
    ]
)
data class SmsQueueEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val phoneNumber: String,
    val body: String,
    val simSlot: Int = 0,
    val status: SmsStatus,
    val attempts: Int = 0,
    val createdAt: Long
)

@Entity(
    tableName = "sms_log",
    indices = [
        Index(value = ["phoneNumber"]),
        Index(value = ["timestamp"]),
        Index(value = ["isIncoming"])
    ]
)
data class SmsLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val phoneNumber: String,
    val body: String,
    val timestamp: Long,
    val isIncoming: Boolean,
    val simSlot: Int = 0,
    val status: SmsStatus
)

@Entity(
    tableName = "event_outbox",
    indices = [
        Index(value = ["eventType"]),
        Index(value = ["createdAt"]),
        Index(value = ["retryCount"])
    ]
)
data class EventOutboxEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val eventType: String,
    val payload: String, // JSON payload
    val createdAt: Long,
    val retryCount: Int = 0,
    val lastRetryAt: Long? = null,
    val errorMessage: String? = null
)

@Entity(
    tableName = "gateway_logs",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["level"]),
        Index(value = ["tag"])
    ]
)
data class GatewayLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,
    val level: String,
    val tag: String,
    val message: String,
    val throwable: String? = null
)
