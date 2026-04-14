package com.gateway.util

import android.annotation.SuppressLint
import android.content.Context
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WakeLockManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "WakeLockManager"
        private const val WAKELOCK_TAG = "GatewayService:WakeLock"
        private const val PARTIAL_WAKELOCK_TAG = "GatewayService:PartialWakeLock"
    }

    private val powerManager: PowerManager by lazy {
        context.getSystemService(Context.POWER_SERVICE) as PowerManager
    }

    private var fullWakeLock: PowerManager.WakeLock? = null
    private var partialWakeLock: PowerManager.WakeLock? = null

    @SuppressLint("WakelockTimeout")
    fun acquireFullWakeLock() {
        if (fullWakeLock?.isHeld == true) {
            GatewayLogger.d(TAG, "Full wake lock already held")
            return
        }

        fullWakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            WAKELOCK_TAG
        ).apply {
            acquire()
        }
        GatewayLogger.d(TAG, "Full wake lock acquired")
    }

    fun acquireFullWakeLock(timeoutMs: Long) {
        if (fullWakeLock?.isHeld == true) {
            GatewayLogger.d(TAG, "Full wake lock already held")
            return
        }

        fullWakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            WAKELOCK_TAG
        ).apply {
            acquire(timeoutMs)
        }
        GatewayLogger.d(TAG, "Full wake lock acquired with timeout: ${timeoutMs}ms")
    }

    fun releaseFullWakeLock() {
        fullWakeLock?.let {
            if (it.isHeld) {
                it.release()
                GatewayLogger.d(TAG, "Full wake lock released")
            }
        }
        fullWakeLock = null
    }

    @SuppressLint("WakelockTimeout")
    fun acquirePartialWakeLock() {
        if (partialWakeLock?.isHeld == true) {
            GatewayLogger.d(TAG, "Partial wake lock already held")
            return
        }

        partialWakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            PARTIAL_WAKELOCK_TAG
        ).apply {
            acquire()
        }
        GatewayLogger.d(TAG, "Partial wake lock acquired")
    }

    fun acquirePartialWakeLock(timeoutMs: Long) {
        if (partialWakeLock?.isHeld == true) {
            GatewayLogger.d(TAG, "Partial wake lock already held")
            return
        }

        partialWakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            PARTIAL_WAKELOCK_TAG
        ).apply {
            acquire(timeoutMs)
        }
        GatewayLogger.d(TAG, "Partial wake lock acquired with timeout: ${timeoutMs}ms")
    }

    fun releasePartialWakeLock() {
        partialWakeLock?.let {
            if (it.isHeld) {
                it.release()
                GatewayLogger.d(TAG, "Partial wake lock released")
            }
        }
        partialWakeLock = null
    }

    fun releaseAll() {
        releaseFullWakeLock()
        releasePartialWakeLock()
        GatewayLogger.d(TAG, "All wake locks released")
    }

    val isFullWakeLockHeld: Boolean
        get() = fullWakeLock?.isHeld == true

    val isPartialWakeLockHeld: Boolean
        get() = partialWakeLock?.isHeld == true

    val isInteractive: Boolean
        get() = powerManager.isInteractive
}
