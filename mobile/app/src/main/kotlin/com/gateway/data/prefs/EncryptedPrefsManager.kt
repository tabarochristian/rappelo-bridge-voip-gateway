package com.gateway.data.prefs

import android.content.SharedPreferences
import com.gateway.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EncryptedPrefsManager @Inject constructor(
    private val prefs: SharedPreferences
) {
    // SIP Configuration
    fun getSipServer(): String = prefs.getString(KEY_SIP_SERVER, "") ?: ""
    fun setSipServer(value: String) = prefs.edit().putString(KEY_SIP_SERVER, value).apply()

    fun getSipUsername(): String = prefs.getString(KEY_SIP_USERNAME, "") ?: ""
    fun setSipUsername(value: String) = prefs.edit().putString(KEY_SIP_USERNAME, value).apply()

    fun getSipPassword(): String = prefs.getString(KEY_SIP_PASSWORD, "") ?: ""
    fun setSipPassword(value: String) = prefs.edit().putString(KEY_SIP_PASSWORD, value).apply()

    fun getSipDomain(): String = prefs.getString(KEY_SIP_DOMAIN, "") ?: ""
    fun setSipDomain(value: String) = prefs.edit().putString(KEY_SIP_DOMAIN, value).apply()

    fun getSipDisplayName(): String = prefs.getString(KEY_SIP_DISPLAY_NAME, "Gateway") ?: "Gateway"
    fun setSipDisplayName(value: String) = prefs.edit().putString(KEY_SIP_DISPLAY_NAME, value).apply()

    fun getSipTransport(): String = prefs.getString(KEY_SIP_TRANSPORT, "UDP") ?: "UDP"
    fun setSipTransport(value: String) = prefs.edit().putString(KEY_SIP_TRANSPORT, value).apply()

    fun getSipPort(): Int = prefs.getInt(KEY_SIP_PORT, 5060)
    fun setSipPort(value: Int) = prefs.edit().putInt(KEY_SIP_PORT, value).apply()

    // Failover SIP server
    fun getFailoverSipServer(): String = prefs.getString(KEY_FAILOVER_SIP_SERVER, "") ?: ""
    fun setFailoverSipServer(value: String) = prefs.edit().putString(KEY_FAILOVER_SIP_SERVER, value).apply()

    // STUN/ICE Configuration
    fun getStunServer(): String = prefs.getString(KEY_STUN_SERVER, BuildConfig.DEFAULT_STUN_SERVER) 
        ?: BuildConfig.DEFAULT_STUN_SERVER
    fun setStunServer(value: String) = prefs.edit().putString(KEY_STUN_SERVER, value).apply()

    fun getTurnServer(): String = prefs.getString(KEY_TURN_SERVER, "") ?: ""
    fun setTurnServer(value: String) = prefs.edit().putString(KEY_TURN_SERVER, value).apply()

    fun getTurnUsername(): String = prefs.getString(KEY_TURN_USERNAME, "") ?: ""
    fun setTurnUsername(value: String) = prefs.edit().putString(KEY_TURN_USERNAME, value).apply()

    fun getTurnPassword(): String = prefs.getString(KEY_TURN_PASSWORD, "") ?: ""
    fun setTurnPassword(value: String) = prefs.edit().putString(KEY_TURN_PASSWORD, value).apply()

    fun isIceEnabled(): Boolean = prefs.getBoolean(KEY_ICE_ENABLED, true)
    fun setIceEnabled(value: Boolean) = prefs.edit().putBoolean(KEY_ICE_ENABLED, value).apply()

    // API Configuration
    fun getApiBaseUrl(): String = prefs.getString(KEY_API_BASE_URL, BuildConfig.API_BASE_URL) 
        ?: BuildConfig.API_BASE_URL
    fun setApiBaseUrl(value: String) = prefs.edit().putString(KEY_API_BASE_URL, value).apply()

    fun getApiToken(): String = prefs.getString(KEY_API_TOKEN, "") ?: ""
    fun setApiToken(value: String) = prefs.edit().putString(KEY_API_TOKEN, value).apply()

    fun getCommandPollIntervalMs(): Long = prefs.getLong(KEY_COMMAND_POLL_INTERVAL, 
        BuildConfig.COMMAND_POLL_INTERVAL_MS.toLong())
    fun setCommandPollIntervalMs(value: Long) = prefs.edit().putLong(KEY_COMMAND_POLL_INTERVAL, value).apply()

    // Queue Configuration
    fun getMaxQueueSize(): Int = prefs.getInt(KEY_MAX_QUEUE_SIZE, BuildConfig.MAX_QUEUE_SIZE)
    fun setMaxQueueSize(value: Int) = prefs.edit().putInt(KEY_MAX_QUEUE_SIZE, value).apply()

    fun getQueueOverflowStrategy(): QueueOverflowStrategy {
        val value = prefs.getString(KEY_QUEUE_OVERFLOW_STRATEGY, QueueOverflowStrategy.REJECT.name)
        return try {
            QueueOverflowStrategy.valueOf(value ?: QueueOverflowStrategy.REJECT.name)
        } catch (e: Exception) {
            QueueOverflowStrategy.REJECT
        }
    }
    fun setQueueOverflowStrategy(value: QueueOverflowStrategy) = 
        prefs.edit().putString(KEY_QUEUE_OVERFLOW_STRATEGY, value.name).apply()

    // SIM Configuration
    fun getPreferredSimSlot(): Int = prefs.getInt(KEY_PREFERRED_SIM_SLOT, 0)
    fun setPreferredSimSlot(value: Int) = prefs.edit().putInt(KEY_PREFERRED_SIM_SLOT, value).apply()

    // Default SIP endpoint for bridging GSM calls
    fun getDefaultBridgeEndpoint(): String = prefs.getString(KEY_DEFAULT_BRIDGE_ENDPOINT, "") ?: ""
    fun setDefaultBridgeEndpoint(value: String) = prefs.edit().putString(KEY_DEFAULT_BRIDGE_ENDPOINT, value).apply()

    // Service Configuration
    fun isAutoStartEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_START_ENABLED, true)
    fun setAutoStartEnabled(value: Boolean) = prefs.edit().putBoolean(KEY_AUTO_START_ENABLED, value).apply()

    fun isBatteryOptimizationWarningShown(): Boolean = prefs.getBoolean(KEY_BATTERY_WARNING_SHOWN, false)
    fun setBatteryOptimizationWarningShown(value: Boolean) = 
        prefs.edit().putBoolean(KEY_BATTERY_WARNING_SHOWN, value).apply()

    // Local HTTP Server
    fun isLocalHttpServerEnabled(): Boolean = prefs.getBoolean(KEY_LOCAL_HTTP_ENABLED, 
        BuildConfig.ENABLE_LOCAL_HTTP_SERVER)
    fun setLocalHttpServerEnabled(value: Boolean) = prefs.edit().putBoolean(KEY_LOCAL_HTTP_ENABLED, value).apply()

    fun getLocalHttpPort(): Int = prefs.getInt(KEY_LOCAL_HTTP_PORT, BuildConfig.LOCAL_HTTP_PORT)
    fun setLocalHttpPort(value: Int) = prefs.edit().putInt(KEY_LOCAL_HTTP_PORT, value).apply()

    // Codec preferences
    fun getCodecPriority(): List<String> {
        val default = "PCMU,PCMA,G729,opus"
        val value = prefs.getString(KEY_CODEC_PRIORITY, default) ?: default
        return value.split(",").map { it.trim() }
    }
    fun setCodecPriority(codecs: List<String>) = 
        prefs.edit().putString(KEY_CODEC_PRIORITY, codecs.joinToString(",")).apply()

    // Clear all data
    fun clearAll() {
        prefs.edit().clear().apply()
    }

    // Device identity
    fun getDeviceId(): String = prefs.getString(KEY_DEVICE_ID, "") ?: ""
    fun setDeviceId(value: String) = prefs.edit().putString(KEY_DEVICE_ID, value).apply()

    fun getDeviceName(): String = prefs.getString(KEY_DEVICE_NAME, android.os.Build.MODEL) ?: android.os.Build.MODEL
    fun setDeviceName(value: String) = prefs.edit().putString(KEY_DEVICE_NAME, value).apply()

    // Check if SIP is configured
    fun isSipConfigured(): Boolean {
        return getSipServer().isNotEmpty() && 
               getSipUsername().isNotEmpty() && 
               getSipPassword().isNotEmpty()
    }

    companion object {
        // SIP Keys
        private const val KEY_SIP_SERVER = "sip_server"
        private const val KEY_SIP_USERNAME = "sip_username"
        private const val KEY_SIP_PASSWORD = "sip_password"
        private const val KEY_SIP_DOMAIN = "sip_domain"
        private const val KEY_SIP_DISPLAY_NAME = "sip_display_name"
        private const val KEY_SIP_TRANSPORT = "sip_transport"
        private const val KEY_SIP_PORT = "sip_port"
        private const val KEY_FAILOVER_SIP_SERVER = "failover_sip_server"

        // STUN/ICE Keys
        private const val KEY_STUN_SERVER = "stun_server"
        private const val KEY_TURN_SERVER = "turn_server"
        private const val KEY_TURN_USERNAME = "turn_username"
        private const val KEY_TURN_PASSWORD = "turn_password"
        private const val KEY_ICE_ENABLED = "ice_enabled"

        // API Keys
        private const val KEY_API_BASE_URL = "api_base_url"
        private const val KEY_API_TOKEN = "api_token"
        private const val KEY_COMMAND_POLL_INTERVAL = "command_poll_interval"

        // Queue Keys
        private const val KEY_MAX_QUEUE_SIZE = "max_queue_size"
        private const val KEY_QUEUE_OVERFLOW_STRATEGY = "queue_overflow_strategy"

        // SIM Keys
        private const val KEY_PREFERRED_SIM_SLOT = "preferred_sim_slot"

        // Bridge Keys
        private const val KEY_DEFAULT_BRIDGE_ENDPOINT = "default_bridge_endpoint"

        // Service Keys
        private const val KEY_AUTO_START_ENABLED = "auto_start_enabled"
        private const val KEY_BATTERY_WARNING_SHOWN = "battery_warning_shown"

        // HTTP Server Keys
        private const val KEY_LOCAL_HTTP_ENABLED = "local_http_enabled"
        private const val KEY_LOCAL_HTTP_PORT = "local_http_port"

        // Codec Keys
        private const val KEY_CODEC_PRIORITY = "codec_priority"

        // Device Identity Keys
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_NAME = "device_name"
    }
}

enum class QueueOverflowStrategy {
    REJECT,
    HOLD
}
