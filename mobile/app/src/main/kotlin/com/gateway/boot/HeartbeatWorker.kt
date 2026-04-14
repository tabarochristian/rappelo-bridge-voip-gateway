package com.gateway.boot

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.gateway.data.prefs.EncryptedPrefsManager
import com.gateway.service.GatewayForegroundService
import com.gateway.util.GatewayLogger
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class HeartbeatWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val prefsManager: EncryptedPrefsManager
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        GatewayLogger.info(TAG, "HeartbeatWorker executing")

        return try {
            if (!prefsManager.isAutoStartEnabled()) {
                GatewayLogger.info(TAG, "Auto-start is disabled, skipping heartbeat")
                return Result.success()
            }

            if (!isServiceRunning()) {
                GatewayLogger.warn(TAG, "Service not running, restarting...")
                restartGatewayService()
            } else {
                GatewayLogger.info(TAG, "Service is running normally")
            }

            Result.success()
        } catch (e: Exception) {
            GatewayLogger.error(TAG, "Heartbeat worker failed", e)
            Result.retry()
        }
    }

    private fun isServiceRunning(): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        
        @Suppress("DEPRECATION")
        val services = activityManager.getRunningServices(Int.MAX_VALUE)
        
        return services.any { 
            it.service.className == GatewayForegroundService::class.java.name 
        }
    }

    private fun restartGatewayService() {
        val serviceIntent = Intent(context, GatewayForegroundService::class.java).apply {
            action = GatewayForegroundService.ACTION_START
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }

        GatewayLogger.info(TAG, "Gateway service restarted from HeartbeatWorker")
    }

    companion object {
        private const val TAG = "HeartbeatWorker"
    }
}
