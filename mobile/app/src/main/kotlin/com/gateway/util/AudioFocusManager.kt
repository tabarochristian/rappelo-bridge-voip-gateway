package com.gateway.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed class AudioFocusState {
    data object None : AudioFocusState()
    data object Gained : AudioFocusState()
    data object Lost : AudioFocusState()
    data object LostTransient : AudioFocusState()
    data object LostCanDuck : AudioFocusState()
}

@Singleton
class AudioFocusManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "AudioFocusManager"
    }

    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private val _focusState = MutableStateFlow<AudioFocusState>(AudioFocusState.None)
    val focusState: StateFlow<AudioFocusState> = _focusState.asStateFlow()

    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                GatewayLogger.d(TAG, "Audio focus gained")
                _focusState.value = AudioFocusState.Gained
                hasAudioFocus = true
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                GatewayLogger.d(TAG, "Audio focus lost permanently")
                _focusState.value = AudioFocusState.Lost
                hasAudioFocus = false
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                GatewayLogger.d(TAG, "Audio focus lost transiently")
                _focusState.value = AudioFocusState.LostTransient
                hasAudioFocus = false
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                GatewayLogger.d(TAG, "Audio focus lost, can duck")
                _focusState.value = AudioFocusState.LostCanDuck
                // Keep focus, just duck volume
            }
        }
    }

    fun requestCallAudioFocus(): Boolean {
        if (hasAudioFocus) {
            GatewayLogger.d(TAG, "Already have audio focus")
            return true
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(audioAttributes)
            .setAcceptsDelayedFocusGain(true)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(focusChangeListener)
            .build()

        val result = audioManager.requestAudioFocus(audioFocusRequest!!)

        return when (result) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
                GatewayLogger.i(TAG, "Call audio focus granted")
                hasAudioFocus = true
                _focusState.value = AudioFocusState.Gained
                true
            }
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
                GatewayLogger.w(TAG, "Call audio focus delayed")
                false
            }
            AudioManager.AUDIOFOCUS_REQUEST_FAILED -> {
                GatewayLogger.e(TAG, "Call audio focus request failed")
                false
            }
            else -> false
        }
    }

    fun requestMediaAudioFocus(): Boolean {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(audioAttributes)
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener(focusChangeListener)
            .build()

        val result = audioManager.requestAudioFocus(audioFocusRequest!!)

        return when (result) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
                GatewayLogger.i(TAG, "Media audio focus granted")
                hasAudioFocus = true
                _focusState.value = AudioFocusState.Gained
                true
            }
            else -> {
                GatewayLogger.e(TAG, "Media audio focus request failed: $result")
                false
            }
        }
    }

    fun abandonAudioFocus() {
        audioFocusRequest?.let { request ->
            val result = audioManager.abandonAudioFocusRequest(request)
            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                GatewayLogger.d(TAG, "Audio focus abandoned")
            } else {
                GatewayLogger.w(TAG, "Failed to abandon audio focus: $result")
            }
        }
        audioFocusRequest = null
        hasAudioFocus = false
        _focusState.value = AudioFocusState.None
    }

    fun setMode(mode: Int) {
        try {
            audioManager.mode = mode
            GatewayLogger.d(TAG, "Audio mode set to: $mode")
        } catch (e: Exception) {
            GatewayLogger.e(TAG, "Failed to set audio mode: ${e.message}", e)
        }
    }

    fun setCallMode() {
        setMode(AudioManager.MODE_IN_COMMUNICATION)
    }

    fun setNormalMode() {
        setMode(AudioManager.MODE_NORMAL)
    }

    fun setSpeakerphoneOn(enabled: Boolean) {
        try {
            audioManager.isSpeakerphoneOn = enabled
            GatewayLogger.d(TAG, "Speakerphone ${if (enabled) "enabled" else "disabled"}")
        } catch (e: Exception) {
            GatewayLogger.e(TAG, "Failed to set speakerphone: ${e.message}", e)
        }
    }

    fun setMicrophoneMute(muted: Boolean) {
        try {
            audioManager.isMicrophoneMute = muted
            GatewayLogger.d(TAG, "Microphone ${if (muted) "muted" else "unmuted"}")
        } catch (e: Exception) {
            GatewayLogger.e(TAG, "Failed to set microphone mute: ${e.message}", e)
        }
    }

    val isSpeakerphoneOn: Boolean
        get() = audioManager.isSpeakerphoneOn

    val isMicrophoneMute: Boolean
        get() = audioManager.isMicrophoneMute

    val currentMode: Int
        get() = audioManager.mode

    val isBluetoothScoOn: Boolean
        get() = audioManager.isBluetoothScoOn

    fun startBluetoothSco() {
        try {
            audioManager.startBluetoothSco()
            GatewayLogger.d(TAG, "Bluetooth SCO started")
        } catch (e: Exception) {
            GatewayLogger.e(TAG, "Failed to start Bluetooth SCO: ${e.message}", e)
        }
    }

    fun stopBluetoothSco() {
        try {
            audioManager.stopBluetoothSco()
            GatewayLogger.d(TAG, "Bluetooth SCO stopped")
        } catch (e: Exception) {
            GatewayLogger.e(TAG, "Failed to stop Bluetooth SCO: ${e.message}", e)
        }
    }
}
