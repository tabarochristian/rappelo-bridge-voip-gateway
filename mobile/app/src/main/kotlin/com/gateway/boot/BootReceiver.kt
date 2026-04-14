package com.gateway.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.gateway.data.prefs.EncryptedPrefsManager
import com.gateway.service.GatewayForegroundService
import com.gateway.util.GatewayLogger
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

class BootReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface BootReceiverEntryPoint {
        fun prefsManager(): EncryptedPrefsManager
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        
        GatewayLogger.info(TAG, "Received boot action: $action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                handleBootCompleted(context)
            }
        }
    }

    private fun handleBootCompleted(context: Context) {
        try {
            val entryPoint = EntryPointAccessors.fromApplication(
                context.applicationContext,
                BootReceiverEntryPoint::class.java
            )
            val prefsManager = entryPoint.prefsManager()

            // Check if auto-start is enabled
            if (!prefsManager.isAutoStartEnabled()) {
                GatewayLogger.info(TAG, "Auto-start is disabled, skipping service start")
                return
            }

            // Start the foreground service
            startGatewayService(context)

        } catch (e: Exception) {
            GatewayLogger.error(TAG, "Failed to start service on boot, falling back to WorkManager", e)
            // Fallback to WorkManager if direct start fails
            scheduleWorkManagerStart(context)
        }
    }

    private fun startGatewayService(context: Context) {
        val serviceIntent = Intent(context, GatewayForegroundService::class.java).apply {
            action = GatewayForegroundService.ACTION_START
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }

        GatewayLogger.info(TAG, "Gateway service started from boot receiver")
    }

    private fun scheduleWorkManagerStart(context: Context) {
        val workRequest = OneTimeWorkRequestBuilder<StartupWorker>()
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            STARTUP_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            workRequest
        )

        GatewayLogger.info(TAG, "Scheduled WorkManager startup task")
    }

    companion object {
        private const val TAG = "BootReceiver"
        private const val STARTUP_WORK_NAME = "gateway_startup_work"
    }
}
