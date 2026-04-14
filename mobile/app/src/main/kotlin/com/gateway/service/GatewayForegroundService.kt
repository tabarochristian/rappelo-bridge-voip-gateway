package com.gateway.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.gateway.R
import com.gateway.data.api.CommandPoller
import com.gateway.data.api.LocalHttpServer
import com.gateway.service.CommandDispatcher
import com.gateway.app.GatewayApplication
import com.gateway.boot.StartupManager
import com.gateway.bridge.CallBridge
import com.gateway.data.prefs.EncryptedPrefsManager
import com.gateway.queue.CallQueue
import com.gateway.queue.SmsQueue
import com.gateway.telephony.gsm.GsmCallManager
import com.gateway.telephony.sip.SipEngine
import com.gateway.ui.MainActivity
import com.gateway.util.GatewayLogger
import com.gateway.util.NetworkMonitor
import com.gateway.util.WakeLockManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class GatewayForegroundService : LifecycleService() {

    @Inject lateinit var sipEngine: SipEngine
    @Inject lateinit var gsmCallManager: GsmCallManager
    @Inject lateinit var callBridge: CallBridge
    @Inject lateinit var callQueue: CallQueue
    @Inject lateinit var smsQueue: SmsQueue
    @Inject lateinit var commandPoller: CommandPoller
    @Inject lateinit var commandDispatcher: CommandDispatcher
    @Inject lateinit var eventOutboxProcessor: EventOutboxProcessor
    @Inject lateinit var logPersistenceManager: LogPersistenceManager
    @Inject lateinit var localHttpServer: LocalHttpServer
    @Inject lateinit var watchdogManager: WatchdogManager
    @Inject lateinit var wakeLockManager: WakeLockManager
    @Inject lateinit var networkMonitor: NetworkMonitor
    @Inject lateinit var startupManager: StartupManager
    @Inject lateinit var prefsManager: EncryptedPrefsManager

    private var serviceRunning = false

    override fun onCreate() {
        super.onCreate()
        GatewayLogger.info(TAG, "GatewayForegroundService onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        val action = intent?.action ?: ACTION_START
        GatewayLogger.info(TAG, "onStartCommand action: $action")

        when (action) {
            ACTION_START -> startGateway()
            ACTION_STOP -> stopGateway()
            ACTION_HEARTBEAT -> handleHeartbeat()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        GatewayLogger.info(TAG, "GatewayForegroundService onDestroy")
        stopGateway()
        super.onDestroy()
    }

    private fun startGateway() {
        if (serviceRunning) {
            GatewayLogger.info(TAG, "Gateway already running")
            return
        }

        GatewayLogger.info(TAG, "Starting gateway...")

        // Start as foreground service immediately (within 5 seconds requirement)
        startForegroundWithNotification()

        // Initialize components
        lifecycleScope.launch {
            try {
                initializeGateway()
                serviceRunning = true
                _isRunning.value = true
                GatewayLogger.info(TAG, "Gateway started successfully")
            } catch (e: Exception) {
                GatewayLogger.error(TAG, "Failed to start gateway", e)
            }
        }
    }

    private fun startForegroundWithNotification() {
        val notification = createNotification(
            sipStatus = "Initializing",
            callCount = 0,
            smsQueueCount = 0
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private suspend fun initializeGateway() {
        // Start network monitoring
        networkMonitor.startMonitoring()

        // Initialize SIP engine
        sipEngine.initialize()

        // Register SIP account if configured
        if (prefsManager.isSipConfigured()) {
            sipEngine.registerAccount()
        }

        // Initialize GSM call manager
        gsmCallManager.initialize()

        // Initialize call bridge
        callBridge.initialize()

        // Start command poller and dispatcher
        commandPoller.start()
        commandDispatcher.start()

        // Start event outbox processor
        eventOutboxProcessor.start()

        // Start log persistence
        logPersistenceManager.start()

        // Start local HTTP server if enabled
        if (prefsManager.isLocalHttpServerEnabled()) {
            localHttpServer.startServer()
        }

        // Start watchdog
        watchdogManager.startWatching()

        // Setup heartbeat schedulers
        startupManager.setupHeartbeatScheduler()

        // Observe state changes for notification updates
        observeStateChanges()
    }

    private fun observeStateChanges() {
        lifecycleScope.launch {
            combine(
                sipEngine.registrationState,
                callQueue.queueState,
                smsQueue.pendingSmsCount
            ) { sipState, queueState, smsCount ->
                Triple(sipState, queueState, smsCount)
            }.collectLatest { (sipState, queueState, smsCount) ->
                val notification = createNotification(
                    sipStatus = sipState.displayName,
                    callCount = queueState.size,
                    smsQueueCount = smsCount
                )
                updateNotification(notification)
            }
        }
    }

    private fun stopGateway() {
        if (!serviceRunning) {
            return
        }

        GatewayLogger.info(TAG, "Stopping gateway...")

        lifecycleScope.launch {
            try {
                // Stop watchdog
                watchdogManager.stopWatching()

                // Stop command dispatcher and poller
                commandDispatcher.stop()
                commandPoller.stop()

                // Stop event outbox processor
                eventOutboxProcessor.stop()

                // Stop log persistence
                logPersistenceManager.stop()

                // Stop local HTTP server
                localHttpServer.stopServer()

                // Shutdown SIP engine
                sipEngine.shutdown()

                // Shutdown call bridge
                callBridge.shutdown()

                // Stop network monitoring
                networkMonitor.stopMonitoring()

                // Cancel heartbeat schedulers
                startupManager.cancelHeartbeatScheduler()

                // Release wake lock
                wakeLockManager.releaseAll()

                serviceRunning = false
                _isRunning.value = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()

                GatewayLogger.info(TAG, "Gateway stopped successfully")
            } catch (e: Exception) {
                GatewayLogger.error(TAG, "Error stopping gateway", e)
            }
        }
    }

    private fun handleHeartbeat() {
        GatewayLogger.debug(TAG, "Processing heartbeat")
        
        if (!serviceRunning) {
            GatewayLogger.warn(TAG, "Service not fully initialized, starting gateway")
            startGateway()
        }

        // Reschedule alarm backup
        startupManager.rescheduleAlarmBackup()
    }

    private fun createNotification(
        sipStatus: String,
        callCount: Int,
        smsQueueCount: Int
    ): Notification {
        val sipIcon = if (sipStatus == "Registered") "✓" else "✗"
        val contentText = "Gateway Active | SIP: $sipIcon | Calls: $callCount | SMS queued: $smsQueueCount"

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, GatewayForegroundService::class.java).apply {
                action = ACTION_STOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, GatewayApplication.CHANNEL_GATEWAY_CORE)
            .setContentTitle("GSM-SIP Gateway")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_stop, "Stop", stopIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(notification: Notification) {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val TAG = "GatewayService"
        private const val NOTIFICATION_ID = 1

        const val ACTION_START = "com.gateway.action.START"
        const val ACTION_STOP = "com.gateway.action.STOP"
        const val ACTION_HEARTBEAT = "com.gateway.action.HEARTBEAT"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
    }
}
