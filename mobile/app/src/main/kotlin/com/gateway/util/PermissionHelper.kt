package com.gateway.util

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PermissionHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "PermissionHelper"

        val REQUIRED_PERMISSIONS = buildList {
            // Phone permissions
            add(Manifest.permission.CALL_PHONE)
            add(Manifest.permission.READ_PHONE_STATE)
            add(Manifest.permission.READ_CALL_LOG)
            add(Manifest.permission.ANSWER_PHONE_CALLS)
            
            // Audio permissions
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.MODIFY_AUDIO_SETTINGS)
            
            // SMS permissions
            add(Manifest.permission.SEND_SMS)
            add(Manifest.permission.RECEIVE_SMS)
            add(Manifest.permission.READ_SMS)
            
            // Contacts (for caller ID)
            add(Manifest.permission.READ_CONTACTS)

            // Notifications (Android 13+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }

            // Bluetooth (Android 12+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }

        val PHONE_PERMISSIONS = listOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.ANSWER_PHONE_CALLS
        )

        val SMS_PERMISSIONS = listOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS
        )

        val AUDIO_PERMISSIONS = listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.MODIFY_AUDIO_SETTINGS
        )
    }

    fun hasAllRequiredPermissions(): Boolean {
        return REQUIRED_PERMISSIONS.all { hasPermission(it) }
    }

    fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == 
            PackageManager.PERMISSION_GRANTED
    }

    fun hasPhonePermissions(): Boolean {
        return PHONE_PERMISSIONS.all { hasPermission(it) }
    }

    fun hasSmsPermissions(): Boolean {
        return SMS_PERMISSIONS.all { hasPermission(it) }
    }

    fun hasAudioPermissions(): Boolean {
        return AUDIO_PERMISSIONS.all { hasPermission(it) }
    }

    fun getMissingPermissions(): List<String> {
        return REQUIRED_PERMISSIONS.filter { !hasPermission(it) }
    }

    fun getMissingPhonePermissions(): List<String> {
        return PHONE_PERMISSIONS.filter { !hasPermission(it) }
    }

    fun getMissingSmsPermissions(): List<String> {
        return SMS_PERMISSIONS.filter { !hasPermission(it) }
    }

    fun getMissingAudioPermissions(): List<String> {
        return AUDIO_PERMISSIONS.filter { !hasPermission(it) }
    }

    fun shouldShowRationale(activity: Activity, permission: String): Boolean {
        return ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
    }

    fun requestPermissions(activity: Activity, permissions: List<String>, requestCode: Int) {
        ActivityCompat.requestPermissions(
            activity,
            permissions.toTypedArray(),
            requestCode
        )
    }

    fun requestAllPermissions(activity: Activity, requestCode: Int) {
        val missing = getMissingPermissions()
        if (missing.isNotEmpty()) {
            requestPermissions(activity, missing, requestCode)
        }
    }

    fun isIgnoringBatteryOptimizations(): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun createBatteryOptimizationIntent(): Intent {
        return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }

    fun createAppSettingsIntent(): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun canDrawOverlays(): Boolean {
        return Settings.canDrawOverlays(context)
    }

    fun createOverlaySettingsIntent(): Intent {
        return Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }

    fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true // Before Android 13, no runtime permission needed
        }
    }

    fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            true // Before Android 12, no runtime permission needed
        }
    }

    data class PermissionStatus(
        val allGranted: Boolean,
        val phoneGranted: Boolean,
        val smsGranted: Boolean,
        val audioGranted: Boolean,
        val notificationGranted: Boolean,
        val batteryOptimizationExempt: Boolean,
        val missingPermissions: List<String>
    )

    fun getPermissionStatus(): PermissionStatus {
        return PermissionStatus(
            allGranted = hasAllRequiredPermissions(),
            phoneGranted = hasPhonePermissions(),
            smsGranted = hasSmsPermissions(),
            audioGranted = hasAudioPermissions(),
            notificationGranted = hasNotificationPermission(),
            batteryOptimizationExempt = isIgnoringBatteryOptimizations(),
            missingPermissions = getMissingPermissions()
        )
    }

    fun logPermissionStatus() {
        val status = getPermissionStatus()
        GatewayLogger.i(TAG, "Permission status:")
        GatewayLogger.i(TAG, "  All granted: ${status.allGranted}")
        GatewayLogger.i(TAG, "  Phone: ${status.phoneGranted}")
        GatewayLogger.i(TAG, "  SMS: ${status.smsGranted}")
        GatewayLogger.i(TAG, "  Audio: ${status.audioGranted}")
        GatewayLogger.i(TAG, "  Notification: ${status.notificationGranted}")
        GatewayLogger.i(TAG, "  Battery exempt: ${status.batteryOptimizationExempt}")
        if (status.missingPermissions.isNotEmpty()) {
            GatewayLogger.w(TAG, "  Missing: ${status.missingPermissions.joinToString()}")
        }
    }
}
