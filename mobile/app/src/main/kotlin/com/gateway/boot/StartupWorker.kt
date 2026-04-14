package com.gateway.boot

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
class StartupWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val prefsManager: EncryptedPrefsManager
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        GatewayLogger.info(TAG, "StartupWorker executing")

        return try {
            if (!prefsManager.isAutoStartEnabled()) {
                GatewayLogger.info(TAG, "Auto-start is disabled")
                return Result.success()
            }

            startGatewayService()
            Result.success()
        } catch (e: Exception) {
            GatewayLogger.error(TAG, "Failed to start service from worker", e)
            if (runAttemptCount < MAX_RETRY_COUNT) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    private fun startGatewayService() {
        val serviceIntent = Intent(context, GatewayForegroundService::class.java).apply {
            action = GatewayForegroundService.ACTION_START
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }

        GatewayLogger.info(TAG, "Gateway service started from StartupWorker")
    }

    companion object {
        private const val TAG = "StartupWorker"
        private const val MAX_RETRY_COUNT = 3
    }
}
