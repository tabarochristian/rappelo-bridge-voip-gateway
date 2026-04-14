package com.gateway.bridge

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import com.gateway.util.GatewayLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Audio routing interface for managing audio devices and focus
 */
interface AudioRouter {
    /** Current audio route */
    val currentRoute: StateFlow<AudioRoute>

    /** Is Bluetooth SCO available */
    val isBluetoothAvailable: StateFlow<Boolean>

    /** Request audio focus for call */
    fun requestAudioFocus(): Boolean

    /** Abandon audio focus */
    fun abandonAudioFocus()

    /** Route audio to speaker */
    fun routeToSpeaker()

    /** Route audio to earpiece */
    fun routeToEarpiece()

    /** Route audio to Bluetooth */
    fun routeToBluetooth(): Boolean

    /** Start Bluetooth SCO */
    fun startBluetoothSco(): Boolean

    /** Stop Bluetooth SCO */
    fun stopBluetoothSco()

    /** Get available audio devices */
    fun getAvailableDevices(): List<AudioDeviceInfo>
}

@Singleton
class AudioRouterImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioManager: AudioManager
) : AudioRouter {

    private val _currentRoute = MutableStateFlow(AudioRoute.EARPIECE)
    override val currentRoute: StateFlow<AudioRoute> = _currentRoute.asStateFlow()

    private val _isBluetoothAvailable = MutableStateFlow(false)
    override val isBluetoothAvailable: StateFlow<Boolean> = _isBluetoothAvailable.asStateFlow()

    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    private var bluetoothHeadset: BluetoothHeadset? = null

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE -> {
                GatewayLogger.info(TAG, "Audio focus gained")
                hasAudioFocus = true
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                GatewayLogger.info(TAG, "Audio focus lost permanently")
                hasAudioFocus = false
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                GatewayLogger.info(TAG, "Audio focus lost transiently")
                // Continue playing, but could reduce volume
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                GatewayLogger.debug(TAG, "Audio focus - should duck")
                // Continue playing at lower volume
            }
        }
    }

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(
                        BluetoothProfile.EXTRA_STATE,
                        BluetoothProfile.STATE_DISCONNECTED
                    )
                    _isBluetoothAvailable.value = state == BluetoothProfile.STATE_CONNECTED
                    GatewayLogger.info(TAG, "Bluetooth headset state: $state")
                }
                AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED -> {
                    val state = intent.getIntExtra(
                        AudioManager.EXTRA_SCO_AUDIO_STATE,
                        AudioManager.SCO_AUDIO_STATE_DISCONNECTED
                    )
                    when (state) {
                        AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                            _currentRoute.value = AudioRoute.BLUETOOTH
                            GatewayLogger.info(TAG, "Bluetooth SCO connected")
                        }
                        AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                            if (_currentRoute.value == AudioRoute.BLUETOOTH) {
                                _currentRoute.value = AudioRoute.EARPIECE
                            }
                            GatewayLogger.info(TAG, "Bluetooth SCO disconnected")
                        }
                    }
                }
            }
        }
    }

    init {
        registerBluetoothReceiver()
        checkBluetoothAvailability()
    }

    private fun registerBluetoothReceiver() {
        val filter = IntentFilter().apply {
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(bluetoothStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(bluetoothStateReceiver, filter)
        }
    }

    private fun checkBluetoothAvailability() {
        try {
            val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
            if (bluetoothAdapter != null && bluetoothAdapter.isEnabled) {
                bluetoothAdapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        if (profile == BluetoothProfile.HEADSET) {
                            bluetoothHeadset = proxy as BluetoothHeadset
                            _isBluetoothAvailable.value = bluetoothHeadset?.connectedDevices?.isNotEmpty() == true
                        }
                    }

                    override fun onServiceDisconnected(profile: Int) {
                        if (profile == BluetoothProfile.HEADSET) {
                            bluetoothHeadset = null
                            _isBluetoothAvailable.value = false
                        }
                    }
                }, BluetoothProfile.HEADSET)
            }
        } catch (e: SecurityException) {
            GatewayLogger.warn(TAG, "Bluetooth permission not granted", e)
        } catch (e: Exception) {
            GatewayLogger.error(TAG, "Error checking Bluetooth availability", e)
        }
    }

    override fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) return true

        GatewayLogger.info(TAG, "Requesting audio focus")

        return try {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAcceptsDelayedFocusGain(true)
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()

            audioFocusRequest = request
            val result = audioManager.requestAudioFocus(request)
            hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

            if (hasAudioFocus) {
                // Configure audio for voice call
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                GatewayLogger.info(TAG, "Audio focus granted")
            } else {
                GatewayLogger.warn(TAG, "Audio focus request failed: $result")
            }

            hasAudioFocus
        } catch (e: Exception) {
            GatewayLogger.error(TAG, "Error requesting audio focus", e)
            false
        }
    }

    override fun abandonAudioFocus() {
        if (!hasAudioFocus) return

        GatewayLogger.info(TAG, "Abandoning audio focus")

        try {
            audioFocusRequest?.let {
                audioManager.abandonAudioFocusRequest(it)
            }
            
            audioManager.mode = AudioManager.MODE_NORMAL
            hasAudioFocus = false
            audioFocusRequest = null
            
        } catch (e: Exception) {
            GatewayLogger.error(TAG, "Error abandoning audio focus", e)
        }
    }

    override fun routeToSpeaker() {
        GatewayLogger.info(TAG, "Routing to speaker")
        
        try {
            stopBluetoothSco()
            audioManager.isSpeakerphoneOn = true
            _currentRoute.value = AudioRoute.SPEAKER
        } catch (e: Exception) {
            GatewayLogger.error(TAG, "Error routing to speaker", e)
        }
    }

    override fun routeToEarpiece() {
        GatewayLogger.info(TAG, "Routing to earpiece")
        
        try {
            stopBluetoothSco()
            audioManager.isSpeakerphoneOn = false
            _currentRoute.value = AudioRoute.EARPIECE
        } catch (e: Exception) {
            GatewayLogger.error(TAG, "Error routing to earpiece", e)
        }
    }

    override fun routeToBluetooth(): Boolean {
        GatewayLogger.info(TAG, "Routing to Bluetooth")
        return startBluetoothSco()
    }

    override fun startBluetoothSco(): Boolean {
        if (!_isBluetoothAvailable.value) {
            GatewayLogger.warn(TAG, "Bluetooth not available")
            return false
        }

        return try {
            audioManager.isSpeakerphoneOn = false
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true
            true
        } catch (e: Exception) {
            GatewayLogger.error(TAG, "Error starting Bluetooth SCO", e)
            false
        }
    }

    override fun stopBluetoothSco() {
        try {
            if (audioManager.isBluetoothScoOn) {
                audioManager.isBluetoothScoOn = false
                audioManager.stopBluetoothSco()
            }
        } catch (e: Exception) {
            GatewayLogger.error(TAG, "Error stopping Bluetooth SCO", e)
        }
    }

    override fun getAvailableDevices(): List<AudioDeviceInfo> {
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
    }

    companion object {
        private const val TAG = "AudioRouter"
    }
}

enum class AudioRoute {
    EARPIECE,
    SPEAKER,
    BLUETOOTH,
    WIRED_HEADSET
}
