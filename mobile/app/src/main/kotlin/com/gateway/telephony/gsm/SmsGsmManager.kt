package com.gateway.telephony.gsm

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import com.gateway.data.api.GatewayApiService
import com.gateway.data.api.model.EventBatch
import com.gateway.data.api.model.GatewayEvent
import com.gateway.data.db.dao.SmsLogDao
import com.gateway.data.db.dao.SmsQueueDao
import com.gateway.data.prefs.EncryptedPrefsManager
import com.gateway.data.db.entity.SmsLogEntity
import com.gateway.data.db.entity.SmsQueueEntity
import com.gateway.data.db.entity.SmsStatus
import com.gateway.queue.SmsQueue
import com.gateway.util.GatewayLogger
import com.gateway.util.NetworkMonitor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

interface SmsGsmManager {
    /** Send an SMS */
    suspend fun sendSms(to: String, body: String, simSlot: Int = 0): Boolean

    /** Queue an SMS for sending with retry logic */
    suspend fun queueSms(to: String, body: String, simSlot: Int = 0): Long

    /** Process SMS send result */
    fun processSendResult(messageId: Long, resultCode: Int)

    /** Process SMS delivery result */
    fun processDeliveryResult(messageId: Long, resultCode: Int)

    /** Forward an incoming SMS to the API */
    suspend fun forwardIncomingSms(from: String, body: String, timestamp: Long, simSlot: Int)

    /** Pending SMS count */
    val pendingSmsCount: StateFlow<Int>
}

@Singleton
class SmsGsmManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val subscriptionManager: SubscriptionManager,
    private val smsQueueDao: SmsQueueDao,
    private val smsLogDao: SmsLogDao,
    private val apiService: GatewayApiService,
    private val networkMonitor: NetworkMonitor,
    private val dualSimManager: DualSimManager,
    private val prefsManager: EncryptedPrefsManager,
    private val eventOutboxDao: com.gateway.data.db.dao.EventOutboxDao
) : SmsGsmManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _pendingSmsCount = MutableStateFlow(0)
    override val pendingSmsCount: StateFlow<Int> = _pendingSmsCount.asStateFlow()

    private val pendingIntents = mutableMapOf<Long, Pair<PendingIntent, PendingIntent>>()

    init {
        registerResultReceivers()
        refreshPendingCount()
    }

    override suspend fun sendSms(to: String, body: String, simSlot: Int): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            GatewayLogger.error(TAG, "Missing SEND_SMS permission")
            return false
        }

        return try {
            val smsManager = getSmsManager(simSlot)
            
            // Split message if too long
            val parts = smsManager.divideMessage(body)
            
            if (parts.size == 1) {
                smsManager.sendTextMessage(to, null, body, null, null)
            } else {
                smsManager.sendMultipartTextMessage(to, null, parts, null, null)
            }

            // Log the SMS
            smsLogDao.insert(SmsLogEntity(
                phoneNumber = to,
                body = body,
                timestamp = System.currentTimeMillis(),
                isIncoming = false,
                simSlot = simSlot,
                status = SmsStatus.SENT
            ))

            GatewayLogger.info(TAG, "SMS sent to ${to.take(4)}...")
            true
        } catch (e: Exception) {
            GatewayLogger.error(TAG, "Failed to send SMS", e)
            false
        }
    }

    override suspend fun queueSms(to: String, body: String, simSlot: Int): Long {
        val entity = SmsQueueEntity(
            phoneNumber = to,
            body = body,
            simSlot = simSlot,
            status = SmsStatus.PENDING,
            createdAt = System.currentTimeMillis(),
            attempts = 0
        )

        val id = smsQueueDao.insert(entity)
        refreshPendingCount()
        
        // Start processing queue
        processSmsQueue()
        
        return id
    }

    private fun processSmsQueue() {
        scope.launch {
            val pendingMessages = smsQueueDao.getPendingMessages()
            
            for (message in pendingMessages) {
                if (message.attempts >= MAX_RETRY_ATTEMPTS) {
                    smsQueueDao.updateStatus(message.id, SmsStatus.FAILED)
                    continue
                }

                try {
                    val success = sendSmsWithCallbacks(message)
                    if (!success) {
                        val backoffDelay = calculateBackoff(message.attempts)
                        delay(backoffDelay)
                    }
                } catch (e: Exception) {
                    GatewayLogger.error(TAG, "Error processing queued SMS", e)
                    smsQueueDao.incrementAttempts(message.id)
                }
            }
            
            refreshPendingCount()
        }
    }

    private suspend fun sendSmsWithCallbacks(message: SmsQueueEntity): Boolean {
        val smsManager = getSmsManager(message.simSlot)
        
        val sentIntent = PendingIntent.getBroadcast(
            context,
            message.id.toInt(),
            Intent(ACTION_SMS_SENT).putExtra(EXTRA_MESSAGE_ID, message.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val deliveredIntent = PendingIntent.getBroadcast(
            context,
            message.id.toInt() + 10000,
            Intent(ACTION_SMS_DELIVERED).putExtra(EXTRA_MESSAGE_ID, message.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        pendingIntents[message.id] = Pair(sentIntent, deliveredIntent)
        
        smsQueueDao.updateStatus(message.id, SmsStatus.SENDING)
        smsQueueDao.incrementAttempts(message.id)

        return try {
            val parts = smsManager.divideMessage(message.body)
            
            if (parts.size == 1) {
                smsManager.sendTextMessage(
                    message.phoneNumber,
                    null,
                    message.body,
                    sentIntent,
                    deliveredIntent
                )
            } else {
                val sentIntents = ArrayList<PendingIntent>().apply { add(sentIntent) }
                val deliveredIntents = ArrayList<PendingIntent>().apply { add(deliveredIntent) }
                smsManager.sendMultipartTextMessage(
                    message.phoneNumber,
                    null,
                    parts,
                    sentIntents,
                    deliveredIntents
                )
            }
            true
        } catch (e: Exception) {
            GatewayLogger.error(TAG, "Failed to send queued SMS", e)
            smsQueueDao.updateStatus(message.id, SmsStatus.PENDING)
            false
        }
    }

    override fun processSendResult(messageId: Long, resultCode: Int) {
        scope.launch {
            when (resultCode) {
                android.app.Activity.RESULT_OK -> {
                    smsQueueDao.updateStatus(messageId, SmsStatus.SENT)
                    GatewayLogger.info(TAG, "SMS $messageId sent successfully")
                }
                else -> {
                    val message = smsQueueDao.getById(messageId)
                    if (message != null && message.attempts < MAX_RETRY_ATTEMPTS) {
                        smsQueueDao.updateStatus(messageId, SmsStatus.PENDING)
                    } else {
                        smsQueueDao.updateStatus(messageId, SmsStatus.FAILED)
                    }
                    GatewayLogger.warn(TAG, "SMS $messageId send failed with code $resultCode")
                }
            }
            refreshPendingCount()
        }
    }

    override fun processDeliveryResult(messageId: Long, resultCode: Int) {
        scope.launch {
            when (resultCode) {
                android.app.Activity.RESULT_OK -> {
                    smsQueueDao.updateStatus(messageId, SmsStatus.DELIVERED)
                    GatewayLogger.info(TAG, "SMS $messageId delivered")
                }
                else -> {
                    GatewayLogger.warn(TAG, "SMS $messageId delivery failed with code $resultCode")
                }
            }
            pendingIntents.remove(messageId)
            refreshPendingCount()
        }
    }

    override suspend fun forwardIncomingSms(from: String, body: String, timestamp: Long, simSlot: Int) {
        // Log to database
        smsLogDao.insert(SmsLogEntity(
            phoneNumber = from,
            body = body,
            timestamp = timestamp,
            isIncoming = true,
            simSlot = simSlot,
            status = SmsStatus.RECEIVED
        ))

        // Forward to API
        val smsPayload = org.json.JSONObject().apply {
            put("from", from)
            put("body", body)
            put("sim_slot", simSlot)
        }.toString()

        if (networkMonitor.isNetworkAvailable.value) {
            try {
                val deviceId = prefsManager.getDeviceId()
                if (deviceId.isBlank()) return

                val event = GatewayEvent(
                    eventId = "sms_in_${System.currentTimeMillis()}",
                    eventType = "SMS_RECEIVED",
                    payload = smsPayload,
                    timestamp = timestamp
                )
                apiService.pushEvents(EventBatch(deviceId = deviceId, events = listOf(event)))
                GatewayLogger.info(TAG, "Incoming SMS forwarded to API")
            } catch (e: Exception) {
                GatewayLogger.error(TAG, "Failed to forward SMS to API", e)
                // Queue for retry via event outbox
                try {
                    val outboxEntity = com.gateway.data.db.entity.EventOutboxEntity(
                        eventType = "SMS_RECEIVED",
                        payload = smsPayload,
                        createdAt = System.currentTimeMillis()
                    )
                    eventOutboxDao.insert(outboxEntity)
                    GatewayLogger.info(TAG, "SMS event queued in outbox for retry")
                } catch (outboxError: Exception) {
                    GatewayLogger.error(TAG, "Failed to queue SMS event in outbox", outboxError)
                }
            }
        } else {
            // No network — queue directly to outbox
            try {
                val outboxEntity = com.gateway.data.db.entity.EventOutboxEntity(
                    eventType = "SMS_RECEIVED",
                    payload = smsPayload,
                    createdAt = System.currentTimeMillis()
                )
                eventOutboxDao.insert(outboxEntity)
                GatewayLogger.info(TAG, "No network — SMS event queued in outbox")
            } catch (outboxError: Exception) {
                GatewayLogger.error(TAG, "Failed to queue SMS event in outbox", outboxError)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun getSmsManager(simSlot: Int): SmsManager {
        val subscriptionId = dualSimManager.getSubscriptionId(simSlot)
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
                .createForSubscriptionId(subscriptionId ?: SmsManager.getDefaultSmsSubscriptionId())
        } else {
            subscriptionId?.let {
                SmsManager.getSmsManagerForSubscriptionId(it)
            } ?: SmsManager.getDefault()
        }
    }

    private fun registerResultReceivers() {
        val sentFilter = IntentFilter(ACTION_SMS_SENT)
        val deliveredFilter = IntentFilter(ACTION_SMS_DELIVERED)

        val sentReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, -1)
                if (messageId != -1L) {
                    processSendResult(messageId, resultCode)
                }
            }
        }

        val deliveredReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, -1)
                if (messageId != -1L) {
                    processDeliveryResult(messageId, resultCode)
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(sentReceiver, sentFilter, Context.RECEIVER_NOT_EXPORTED)
            context.registerReceiver(deliveredReceiver, deliveredFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(sentReceiver, sentFilter)
            context.registerReceiver(deliveredReceiver, deliveredFilter)
        }
    }

    private fun refreshPendingCount() {
        scope.launch {
            _pendingSmsCount.value = smsQueueDao.getPendingCount()
        }
    }

    private fun calculateBackoff(attempts: Int): Long {
        return (Math.pow(2.0, attempts.toDouble()) * 1000).toLong().coerceAtMost(60000)
    }

    companion object {
        private const val TAG = "SmsGsmManager"
        private const val ACTION_SMS_SENT = "com.gateway.SMS_SENT"
        private const val ACTION_SMS_DELIVERED = "com.gateway.SMS_DELIVERED"
        private const val EXTRA_MESSAGE_ID = "message_id"
        private const val MAX_RETRY_ATTEMPTS = 3
    }
}
