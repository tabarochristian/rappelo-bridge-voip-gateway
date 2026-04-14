package com.gateway.telephony.gsm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.gateway.util.GatewayLogger
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SmsReceiverEntryPoint {
        fun smsGsmManager(): SmsGsmManager
        fun dualSimManager(): DualSimManager
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            return
        }

        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            SmsReceiverEntryPoint::class.java
        )
        val smsGsmManager = entryPoint.smsGsmManager()
        val dualSimManager = entryPoint.dualSimManager()

        GatewayLogger.info(TAG, "SMS received")

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        
        if (messages.isNullOrEmpty()) {
            GatewayLogger.warn(TAG, "No messages found in intent")
            return
        }

        // Group message parts by sender (for multipart SMS)
        val groupedMessages = messages.groupBy { it.originatingAddress ?: "Unknown" }

        for ((sender, parts) in groupedMessages) {
            // Concatenate all parts
            val fullBody = parts.joinToString("") { it.messageBody ?: "" }
            val timestamp = parts.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()

            // Try to get SIM slot from intent extras
            val simSlot = extractSimSlot(intent, dualSimManager)

            GatewayLogger.info(TAG, "SMS from ${sender.take(4)}..., length: ${fullBody.length}, simSlot: $simSlot")

            scope.launch {
                try {
                    smsGsmManager.forwardIncomingSms(
                        from = sender,
                        body = fullBody,
                        timestamp = timestamp,
                        simSlot = simSlot
                    )
                } catch (e: Exception) {
                    GatewayLogger.error(TAG, "Failed to process incoming SMS", e)
                }
            }
        }
    }

    private fun extractSimSlot(intent: Intent, dualSimManager: DualSimManager): Int {
        // Try various extras that OEMs use for SIM slot
        val slotExtras = listOf(
            "slot",
            "simId",
            "simSlot",
            "phone",
            "subscription",
            "android.telephony.extra.SLOT_INDEX",
            "android.telephony.extra.SUBSCRIPTION_INDEX"
        )

        for (extra in slotExtras) {
            val value = intent.getIntExtra(extra, -1)
            if (value >= 0) {
                return value
            }
        }

        // Fallback to active SIM slot
        return dualSimManager.getActiveSimSlot()
    }

    companion object {
        private const val TAG = "SmsReceiver"
    }
}
