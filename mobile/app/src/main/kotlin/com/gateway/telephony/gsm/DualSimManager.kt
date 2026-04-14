package com.gateway.telephony.gsm

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import com.gateway.data.prefs.EncryptedPrefsManager
import com.gateway.util.GatewayLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

interface DualSimManager {
    /** List of active SIM subscriptions */
    val activeSubscriptions: StateFlow<List<SimInfo>>

    /** Get the currently active/preferred SIM slot */
    fun getActiveSimSlot(): Int

    /** Get subscription ID for a specific SIM slot */
    fun getSubscriptionId(simSlot: Int): Int?

    /** Get PhoneAccountHandle for a specific SIM slot */
    fun getPhoneAccountHandle(simSlot: Int): PhoneAccountHandle?

    /** Refresh subscription info */
    fun refreshSubscriptions()

    /** Set preferred SIM slot for outgoing calls */
    fun setPreferredSimSlot(slot: Int)
}

@Singleton
class DualSimManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val subscriptionManager: SubscriptionManager,
    private val telecomManager: TelecomManager,
    private val prefsManager: EncryptedPrefsManager
) : DualSimManager {

    private val _activeSubscriptions = MutableStateFlow<List<SimInfo>>(emptyList())
    override val activeSubscriptions: StateFlow<List<SimInfo>> = _activeSubscriptions.asStateFlow()

    private var phoneAccountHandles: Map<Int, PhoneAccountHandle> = emptyMap()

    init {
        refreshSubscriptions()
        setupSubscriptionListener()
    }

    override fun getActiveSimSlot(): Int {
        return prefsManager.getPreferredSimSlot().coerceIn(0, _activeSubscriptions.value.size - 1)
    }

    override fun getSubscriptionId(simSlot: Int): Int? {
        return _activeSubscriptions.value.getOrNull(simSlot)?.subscriptionId
    }

    override fun getPhoneAccountHandle(simSlot: Int): PhoneAccountHandle? {
        return phoneAccountHandles[simSlot]
    }

    override fun refreshSubscriptions() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED) {
            GatewayLogger.warn(TAG, "Missing READ_PHONE_STATE permission")
            return
        }

        try {
            val subscriptions = subscriptionManager.activeSubscriptionInfoList ?: emptyList()
            
            _activeSubscriptions.value = subscriptions.mapIndexed { index, info ->
                SimInfo(
                    slot = info.simSlotIndex,
                    subscriptionId = info.subscriptionId,
                    carrierName = info.carrierName?.toString() ?: "Unknown",
                    displayName = info.displayName?.toString() ?: "SIM ${index + 1}",
                    number = info.number ?: "",
                    iccId = info.iccId ?: "",
                    isActive = true
                )
            }

            refreshPhoneAccountHandles()

            GatewayLogger.info(TAG, "Found ${_activeSubscriptions.value.size} active subscriptions")
            _activeSubscriptions.value.forEach { sim ->
                GatewayLogger.debug(TAG, "SIM ${sim.slot}: ${sim.carrierName} (${sim.displayName})")
            }
        } catch (e: Exception) {
            GatewayLogger.error(TAG, "Failed to refresh subscriptions", e)
        }
    }

    private fun refreshPhoneAccountHandles() {
        try {
            val handles = telecomManager.callCapablePhoneAccounts
            phoneAccountHandles = handles.mapIndexed { index, handle ->
                index to handle
            }.toMap()

            GatewayLogger.debug(TAG, "Found ${phoneAccountHandles.size} phone account handles")
        } catch (e: Exception) {
            GatewayLogger.error(TAG, "Failed to get phone account handles", e)
        }
    }

    override fun setPreferredSimSlot(slot: Int) {
        val maxSlot = (_activeSubscriptions.value.size - 1).coerceAtLeast(0)
        val validSlot = slot.coerceIn(0, maxSlot)
        prefsManager.setPreferredSimSlot(validSlot)
        GatewayLogger.info(TAG, "Set preferred SIM slot to $validSlot")
    }

    private fun setupSubscriptionListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                subscriptionManager.addOnSubscriptionsChangedListener(
                    context.mainExecutor,
                    object : SubscriptionManager.OnSubscriptionsChangedListener() {
                        override fun onSubscriptionsChanged() {
                            GatewayLogger.info(TAG, "Subscriptions changed, refreshing...")
                            refreshSubscriptions()
                        }
                    }
                )
            } catch (e: Exception) {
                GatewayLogger.error(TAG, "Failed to register subscription listener", e)
            }
        }
    }

    companion object {
        private const val TAG = "DualSimManager"
    }
}

data class SimInfo(
    val slot: Int,
    val subscriptionId: Int,
    val carrierName: String,
    val displayName: String,
    val number: String,
    val iccId: String,
    val isActive: Boolean
)
