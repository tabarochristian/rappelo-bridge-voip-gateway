package com.gateway.boot

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.gateway.service.GatewayForegroundService
import com.gateway.util.GatewayLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StartupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val alarmManager: AlarmManager
) {

    fun setupHeartbeatScheduler() {
        setupWorkManagerHeartbeat()
        setupAlarmManagerBackup()
        GatewayLogger.info(TAG, "Heartbeat schedulers configured")
    }

    fun cancelHeartbeatScheduler() {
        cancelWorkManagerHeartbeat()
        cancelAlarmManagerBackup()
        GatewayLogger.info(TAG, "Heartbeat schedulers cancelled")
    }

    private fun setupWorkManagerHeartbeat() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .setRequiresBatteryNotLow(false)
            .setRequiresCharging(false)
            .build()

        val heartbeatRequest = PeriodicWorkRequestBuilder<HeartbeatWorker>(
            HEARTBEAT_INTERVAL_MINUTES, TimeUnit.MINUTES,
            HEARTBEAT_FLEX_MINUTES, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            HEARTBEAT_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            heartbeatRequest
        )

        GatewayLogger.info(TAG, "WorkManager heartbeat scheduled (${HEARTBEAT_INTERVAL_MINUTES}min)")
    }

    private fun cancelWorkManagerHeartbeat() {
        WorkManager.getInstance(context).cancelUniqueWork(HEARTBEAT_WORK_NAME)
    }

    private fun setupAlarmManagerBackup() {
        val intent = Intent(context, GatewayForegroundService::class.java).apply {
            action = GatewayForegroundService.ACTION_HEARTBEAT
        }

        val pendingIntent = PendingIntent.getForegroundService(
            context,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerTime = SystemClock.elapsedRealtime() + ALARM_INTERVAL_MS

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            } else {
                // Fallback to inexact alarm if exact alarms not permitted
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            }
        } else {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerTime,
                pendingIntent
            )
        }

        GatewayLogger.info(TAG, "AlarmManager backup scheduled")
    }

    private fun cancelAlarmManagerBackup() {
        val intent = Intent(context, GatewayForegroundService::class.java).apply {
            action = GatewayForegroundService.ACTION_HEARTBEAT
        }

        val pendingIntent = PendingIntent.getForegroundService(
            context,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        alarmManager.cancel(pendingIntent)
    }

    fun rescheduleAlarmBackup() {
        setupAlarmManagerBackup()
    }

    companion object {
        private const val TAG = "StartupManager"
        private const val HEARTBEAT_WORK_NAME = "gateway_heartbeat"
        private const val HEARTBEAT_INTERVAL_MINUTES = 15L
        private const val HEARTBEAT_FLEX_MINUTES = 5L
        private const val ALARM_INTERVAL_MS = 15 * 60 * 1000L // 15 minutes
        private const val ALARM_REQUEST_CODE = 1001
    }
}
