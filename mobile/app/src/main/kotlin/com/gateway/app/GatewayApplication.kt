package com.gateway.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.gateway.util.GatewayLogger
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class GatewayApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        instance = this
        
        initializeLogging()
        createNotificationChannels()
        
        GatewayLogger.info("GatewayApplication", "Application initialized")
    }

    private fun initializeLogging() {
        GatewayLogger.initialize(this)
    }

    private fun createNotificationChannels() {
        val notificationManager = getSystemService(NotificationManager::class.java)

        // Gateway Core Channel - for foreground service
        val coreChannel = NotificationChannel(
            CHANNEL_GATEWAY_CORE,
            "Gateway Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent notification for gateway service"
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
        }

        // Alerts Channel - for important events
        val alertsChannel = NotificationChannel(
            CHANNEL_ALERTS,
            "Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Important gateway alerts and warnings"
            setShowBadge(true)
            enableLights(true)
            enableVibration(true)
        }

        // Call Events Channel
        val callsChannel = NotificationChannel(
            CHANNEL_CALLS,
            "Call Events",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Notifications about call events"
            setShowBadge(true)
        }

        // SMS Events Channel
        val smsChannel = NotificationChannel(
            CHANNEL_SMS,
            "SMS Events",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Notifications about SMS events"
            setShowBadge(true)
        }

        notificationManager.createNotificationChannels(
            listOf(coreChannel, alertsChannel, callsChannel, smsChannel)
        )
    }

    companion object {
        const val CHANNEL_GATEWAY_CORE = "gateway_core"
        const val CHANNEL_ALERTS = "gateway_alerts"
        const val CHANNEL_CALLS = "gateway_calls"
        const val CHANNEL_SMS = "gateway_sms"

        @Volatile
        private var instance: GatewayApplication? = null

        fun getInstance(): GatewayApplication {
            return instance ?: throw IllegalStateException("Application not initialized")
        }
    }
}
