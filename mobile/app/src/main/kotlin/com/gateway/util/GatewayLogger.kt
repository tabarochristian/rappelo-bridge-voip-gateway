package com.gateway.util

import android.content.Context
import android.util.Log
import com.gateway.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object GatewayLogger {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private val _logEvents = MutableSharedFlow<LogEntry>(
        replay = 100,
        extraBufferCapacity = 500
    )
    val logEvents: SharedFlow<LogEntry> = _logEvents.asSharedFlow()
    val logFlow: SharedFlow<LogEntry> = _logEvents.asSharedFlow() // alias

    private var logLevel: LogLevel = LogLevel.VERBOSE
    private var isInitialized = false
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun initialize(context: Context) {
        if (isInitialized) return
        
        logLevel = when (BuildConfig.LOG_LEVEL) {
            "VERBOSE" -> LogLevel.VERBOSE
            "DEBUG" -> LogLevel.DEBUG
            "INFO" -> LogLevel.INFO
            "WARN" -> LogLevel.WARN
            "ERROR" -> LogLevel.ERROR
            else -> LogLevel.INFO
        }
        
        isInitialized = true
    }

    fun verbose(tag: String, message: String, throwable: Throwable? = null) {
        log(LogLevel.VERBOSE, tag, message, throwable)
    }

    fun debug(tag: String, message: String, throwable: Throwable? = null) {
        log(LogLevel.DEBUG, tag, message, throwable)
    }

    fun info(tag: String, message: String, throwable: Throwable? = null) {
        log(LogLevel.INFO, tag, message, throwable)
    }

    fun warn(tag: String, message: String, throwable: Throwable? = null) {
        log(LogLevel.WARN, tag, message, throwable)
    }

    fun error(tag: String, message: String, throwable: Throwable? = null) {
        log(LogLevel.ERROR, tag, message, throwable)
    }

    // Short-form aliases for convenience
    fun v(tag: String, message: String, throwable: Throwable? = null) = verbose(tag, message, throwable)
    fun d(tag: String, message: String, throwable: Throwable? = null) = debug(tag, message, throwable)
    fun i(tag: String, message: String, throwable: Throwable? = null) = info(tag, message, throwable)
    fun w(tag: String, message: String, throwable: Throwable? = null) = warn(tag, message, throwable)
    fun e(tag: String, message: String, throwable: Throwable? = null) = error(tag, message, throwable)

    private fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        if (level.priority < logLevel.priority) return

        val fullMessage = if (throwable != null) {
            "$message\n${Log.getStackTraceString(throwable)}"
        } else {
            message
        }

        // Log to Android logcat
        when (level) {
            LogLevel.VERBOSE -> Log.v(tag, fullMessage)
            LogLevel.DEBUG -> Log.d(tag, fullMessage)
            LogLevel.INFO -> Log.i(tag, fullMessage)
            LogLevel.WARN -> Log.w(tag, fullMessage)
            LogLevel.ERROR -> Log.e(tag, fullMessage)
        }

        // Emit to flow for UI and database persistence
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message,
            throwable = throwable?.message
        )

        scope.launch {
            _logEvents.emit(entry)
        }
    }

    fun formatTimestamp(timestamp: Long): String {
        return dateFormat.format(Date(timestamp))
    }
}

enum class LogLevel(val priority: Int, val label: String) {
    VERBOSE(1, "V"),
    DEBUG(2, "D"),
    INFO(3, "I"),
    WARN(4, "W"),
    ERROR(5, "E")
}

data class LogEntry(
    val timestamp: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val throwable: String? = null
) {
    fun formatForDisplay(): String {
        val time = GatewayLogger.formatTimestamp(timestamp)
        return "[$time] ${level.label}/$tag: $message"
    }
}
