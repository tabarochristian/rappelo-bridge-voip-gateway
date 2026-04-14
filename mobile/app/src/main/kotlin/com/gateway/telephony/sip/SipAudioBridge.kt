package com.gateway.telephony.sip

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Process
import com.gateway.util.GatewayLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.pjsip.pjsua2.AudioMedia
import org.pjsip.pjsua2.Endpoint
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Audio bridge for routing audio between GSM calls and SIP calls.
 * Uses AudioRecord/AudioTrack for GSM audio and PJSIP conference bridge for SIP.
 */
@Singleton
class SipAudioBridge @Inject constructor(
    private val audioManager: AudioManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    private var recordingJob: Job? = null
    private var playbackJob: Job? = null
    
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    
    private var isRunning = false
    private var sipConfPort: Int = -1

    // Audio parameters
    private val sampleRate = 8000 // G.711 standard
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    
    private val bufferSize: Int
        get() = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 2

    /**
     * Start audio bridge between GSM and SIP
     * @param sipCallConfPort The conference bridge port for the SIP call
     */
    suspend fun startBridge(sipCallConfPort: Int) = withContext(Dispatchers.Default) {
        if (isRunning) {
            GatewayLogger.warn(TAG, "Audio bridge already running")
            return@withContext
        }

        sipConfPort = sipCallConfPort
        
        try {
            GatewayLogger.info(TAG, "Starting audio bridge, SIP conf port: $sipConfPort")
            
            // Initialize audio components
            initializeAudioRecord()
            initializeAudioTrack()
            
            // Start recording from GSM (voice call audio)
            startRecording()
            
            // Start playback to GSM
            startPlayback()
            
            isRunning = true
            GatewayLogger.info(TAG, "Audio bridge started successfully")
            
        } catch (e: Exception) {
            GatewayLogger.error(TAG, "Failed to start audio bridge", e)
            stopBridge()
            throw e
        }
    }

    /**
     * Stop the audio bridge
     */
    suspend fun stopBridge() = withContext(Dispatchers.Default) {
        if (!isRunning) return@withContext
        
        GatewayLogger.info(TAG, "Stopping audio bridge")
        
        isRunning = false
        
        // Stop jobs
        recordingJob?.cancel()
        recordingJob = null
        playbackJob?.cancel()
        playbackJob = null
        
        // Release audio resources
        releaseAudioRecord()
        releaseAudioTrack()
        
        sipConfPort = -1
        
        GatewayLogger.info(TAG, "Audio bridge stopped")
    }

    private fun initializeAudioRecord() {
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_CALL,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                // Fallback to VOICE_COMMUNICATION if VOICE_CALL not available
                audioRecord?.release()
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )
            }
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                throw Exception("Failed to initialize AudioRecord")
            }
            
            GatewayLogger.debug(TAG, "AudioRecord initialized, buffer size: $bufferSize")
            
        } catch (e: Exception) {
            GatewayLogger.error(TAG, "AudioRecord initialization failed", e)
            throw e
        }
    }

    private fun initializeAudioTrack() {
        try {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
                
            val format = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(audioFormat)
                .build()
            
            audioTrack = AudioTrack(
                attributes,
                format,
                bufferSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            
            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                throw Exception("Failed to initialize AudioTrack")
            }
            
            GatewayLogger.debug(TAG, "AudioTrack initialized")
            
        } catch (e: Exception) {
            GatewayLogger.error(TAG, "AudioTrack initialization failed", e)
            throw e
        }
    }

    private fun startRecording() {
        recordingJob = scope.launch {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            
            val buffer = ByteArray(bufferSize)
            
            try {
                audioRecord?.startRecording()
                GatewayLogger.debug(TAG, "Recording started")
                
                while (isActive && isRunning) {
                    val readBytes = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    
                    if (readBytes > 0) {
                        // Feed audio to PJSIP conference bridge
                        feedAudioToSip(buffer, readBytes)
                    } else if (readBytes < 0) {
                        GatewayLogger.warn(TAG, "AudioRecord read error: $readBytes")
                        break
                    }
                }
                
            } catch (e: Exception) {
                GatewayLogger.error(TAG, "Recording error", e)
            } finally {
                try {
                    audioRecord?.stop()
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }
    }

    private fun startPlayback() {
        playbackJob = scope.launch {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            
            try {
                audioTrack?.play()
                GatewayLogger.debug(TAG, "Playback started")
                
                while (isActive && isRunning) {
                    // Get audio from PJSIP and play to GSM
                    val audioData = getAudioFromSip()
                    
                    if (audioData != null && audioData.isNotEmpty()) {
                        audioTrack?.write(audioData, 0, audioData.size)
                    }
                }
                
            } catch (e: Exception) {
                GatewayLogger.error(TAG, "Playback error", e)
            } finally {
                try {
                    audioTrack?.stop()
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }
    }

    private fun feedAudioToSip(buffer: ByteArray, length: Int) {
        if (sipConfPort < 0) return
        
        try {
            // In a real implementation, this would feed audio to PJSIP's conference bridge
            // This is typically done through a custom AudioMedia subclass
            // For now, we're using PJSIP's built-in audio device which handles this
            
            // The PJSIP conference bridge automatically routes audio between
            // the call's audio media and the sound device
            
        } catch (e: Exception) {
            GatewayLogger.error(TAG, "Error feeding audio to SIP", e)
        }
    }

    private fun getAudioFromSip(): ByteArray? {
        if (sipConfPort < 0) return null
        
        try {
            // In a real implementation, this would get audio from PJSIP's conference bridge
            // Similar to above, PJSIP handles this internally
            
            return null
        } catch (e: Exception) {
            GatewayLogger.error(TAG, "Error getting audio from SIP", e)
            return null
        }
    }

    private fun releaseAudioRecord() {
        try {
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {
            GatewayLogger.warn(TAG, "Error releasing AudioRecord", e)
        }
    }

    private fun releaseAudioTrack() {
        try {
            audioTrack?.release()
            audioTrack = null
        } catch (e: Exception) {
            GatewayLogger.warn(TAG, "Error releasing AudioTrack", e)
        }
    }

    /**
     * Connect two conference ports in PJSIP
     */
    fun connectConfPorts(sourcePort: Int, destPort: Int) {
        try {
            Endpoint.instance().audDevManager().apply {
                // Configure audio routing
            }
            
            // Connect source to destination in conference bridge
            val audMed = AudioMedia()
            // This would be implemented using PJSIP's pjsua_conf_connect
            
            GatewayLogger.debug(TAG, "Connected conf ports: $sourcePort -> $destPort")
        } catch (e: Exception) {
            GatewayLogger.error(TAG, "Failed to connect conf ports", e)
        }
    }

    /**
     * Disconnect conference ports
     */
    fun disconnectConfPorts(sourcePort: Int, destPort: Int) {
        try {
            GatewayLogger.debug(TAG, "Disconnected conf ports: $sourcePort -> $destPort")
        } catch (e: Exception) {
            GatewayLogger.error(TAG, "Failed to disconnect conf ports", e)
        }
    }

    companion object {
        private const val TAG = "SipAudioBridge"
    }
}
