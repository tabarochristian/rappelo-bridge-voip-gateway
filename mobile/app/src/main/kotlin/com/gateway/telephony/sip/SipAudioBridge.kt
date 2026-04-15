package com.gateway.telephony.sip

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Process
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import com.gateway.util.GatewayLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.pjsip.pjsua2.AudioMediaPort
import org.pjsip.pjsua2.ByteVector
import org.pjsip.pjsua2.MediaFormatAudio
import org.pjsip.pjsua2.MediaFrame
import org.pjsip.pjsua2.pjmedia_format_id
import org.pjsip.pjsua2.pjmedia_frame_type
import org.pjsip.pjsua2.pjmedia_type
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Acoustic audio bridge between a GSM call and a SIP call.
 *
 * The phone's SPEAKER is enabled so that:
 *   GSM downlink → speaker → mic → AudioRecord → ringbuffer →
 *     AudioMediaPort.onFrameRequested → PJSIP conf bridge → SIP RTP → VoIP caller
 *
 *   VoIP caller → SIP RTP → PJSIP conf bridge → AudioMediaPort.onFrameReceived →
 *     ringbuffer → AudioTrack(MEDIA) → speaker → mic → GSM uplink → GSM remote
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

    @Volatile
    private var isRunning = false

    // ── Audio parameters (matches G.711 PCMU/PCMA) ──────────────────
    private val sampleRate = 8000
    private val channelInConfig = AudioFormat.CHANNEL_IN_MONO
    private val channelOutConfig = AudioFormat.CHANNEL_OUT_MONO
    private val encoding = AudioFormat.ENCODING_PCM_16BIT

    // 20 ms frame @ 8 kHz, 16-bit mono  =  160 samples  =  320 bytes
    private val frameSizeBytes = 320

    private val minRecBuf: Int
        get() = maxOf(
            AudioRecord.getMinBufferSize(sampleRate, channelInConfig, encoding),
            frameSizeBytes * 4
        )

    private val minPlayBuf: Int
        get() = maxOf(
            AudioTrack.getMinBufferSize(sampleRate, channelOutConfig, encoding),
            frameSizeBytes * 4
        )

    // Ring-buffers between Android audio threads ↔ PJSIP callback thread.
    // Each element is one 20 ms frame (320 bytes).
    val micToSip = ArrayBlockingQueue<ByteArray>(50)
    val sipToSpk = ArrayBlockingQueue<ByteArray>(50)

    // ── PJSIP AudioMediaPort factory ────────────────────────────────

    /**
     * Create the PJSIP AudioMediaPort.
     * **Must be called on the PJSIP thread** (pjDispatcher).
     * The caller is responsible for connecting the returned port to the
     * SIP call's conference bridge and for deleting it when done.
     */
    fun createPort(): AudioMediaPort {
        val port = GsmBridgePort(micToSip, sipToSpk)
        val fmt = MediaFormatAudio()
        fmt.type = pjmedia_type.PJMEDIA_TYPE_AUDIO
        fmt.id = pjmedia_format_id.PJMEDIA_FORMAT_L16.toLong()
        fmt.clockRate = sampleRate.toLong()
        fmt.channelCount = 1
        fmt.bitsPerSample = 16
        fmt.frameTimeUsec = 20_000L          // 20 ms
        fmt.avgBps = (sampleRate * 16).toLong()
        fmt.maxBps = (sampleRate * 16).toLong()
        port.createPort("gsm-bridge", fmt)
        GatewayLogger.info(TAG, "Bridge AudioMediaPort created (${sampleRate}Hz, 20ms frames)")
        return port
    }

    // ── Android audio streaming ─────────────────────────────────────

    /**
     * Start the AudioRecord / AudioTrack threads that feed the ring-buffers
     * consumed by [GsmBridgePort].
     */
    suspend fun startStreaming() = withContext(Dispatchers.Default) {
        if (isRunning) {
            GatewayLogger.warn(TAG, "Audio streaming already running")
            return@withContext
        }
        micToSip.clear()
        sipToSpk.clear()

        val voiceMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
        audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, voiceMax, 0)
        // Max both voice and media volumes for acoustic bridge
        val mediaMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, mediaMax, 0)

        GatewayLogger.info(TAG, "Audio mode=${audioManager.mode}, speaker=${audioManager.isSpeakerphoneOn}, voiceVol=$voiceMax, mediaVol=$mediaMax")

        initializeAudioRecord()
        initializeAudioTrack()
        isRunning = true
        startRecording()
        startPlayback()
        GatewayLogger.info(TAG, "Audio streaming started (telephony tap: src=${audioRecord?.audioSource})")
    }

    /**
     * Stop AudioRecord/AudioTrack and clear buffers.
     * Does NOT delete the PJSIP port – that is owned by [SipEngineImpl].
     */
    suspend fun stopBridge() = withContext(Dispatchers.Default) {
        if (!isRunning) return@withContext
        GatewayLogger.info(TAG, "Stopping audio bridge")
        isRunning = false

        recordingJob?.cancel(); recordingJob = null
        playbackJob?.cancel();  playbackJob = null

        releaseAudioRecord()
        releaseAudioTrack()
        micToSip.clear()
        sipToSpk.clear()

        GatewayLogger.info(TAG, "Audio bridge stopped")
    }

    // ── AudioRecord (mic → ring-buffer) ─────────────────────────────

    private fun initializeAudioRecord() {
        // Priority order:
        // 1. VOICE_DOWNLINK (3) – GSM receive path only. Requires CAPTURE_AUDIO_OUTPUT.
        //    Grant: adb shell pm grant com.gateway android.permission.CAPTURE_AUDIO_OUTPUT
        // 2. VOICE_CALL (4)    – GSM uplink+downlink mix. Same permission required.
        // 3. UNPROCESSED (9)   – Raw mic, no AEC. Best acoustic capture if speaker is on.
        // 4. MIC (1)           – Standard mic, last resort.
        val sources = listOf(
            MediaRecorder.AudioSource.VOICE_DOWNLINK,
            MediaRecorder.AudioSource.VOICE_CALL,
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.MIC
        )
        audioRecord = sources.firstNotNullOfOrNull { src ->
            tryCreateRecord(src).also { rec ->
                if (rec != null) GatewayLogger.info(TAG, "AudioRecord: using source $src")
                else GatewayLogger.debug(TAG, "AudioRecord: source $src unavailable, trying next")
            }
        } ?: throw IllegalStateException("Cannot initialise AudioRecord")

        // Explicitly disable AEC and NS on this capture session so the system
        // does not cancel the GSM speaker output we are trying to record.
        val sessionId = audioRecord!!.audioSessionId
        if (AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler.create(sessionId)?.let { aec ->
                aec.enabled = false
                aec.release()
                GatewayLogger.debug(TAG, "AEC disabled on capture session")
            }
        }
        if (NoiseSuppressor.isAvailable()) {
            NoiseSuppressor.create(sessionId)?.let { ns ->
                ns.enabled = false
                ns.release()
                GatewayLogger.debug(TAG, "NS disabled on capture session")
            }
        }
        GatewayLogger.debug(TAG, "AudioRecord initialised, source=${audioRecord!!.audioSource}, buf=$minRecBuf")
    }

    private fun tryCreateRecord(source: Int): AudioRecord? = try {
        val rec = AudioRecord(source, sampleRate, channelInConfig, encoding, minRecBuf)
        if (rec.state == AudioRecord.STATE_INITIALIZED) rec else { rec.release(); null }
    } catch (e: Exception) {
        GatewayLogger.debug(TAG, "AudioRecord source $source unavailable: ${e.message}")
        null
    }

    private fun startRecording() {
        recordingJob = scope.launch {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buf = ByteArray(frameSizeBytes)
            var frameCount = 0L
            try {
                audioRecord?.startRecording()
                GatewayLogger.debug(TAG, "Recording thread started")
                while (isActive && isRunning) {
                    val n = audioRecord?.read(buf, 0, frameSizeBytes) ?: -1
                    if (n == frameSizeBytes) {
                        micToSip.offer(buf.copyOf())
                        frameCount++
                        if (frameCount % 250 == 0L) {
                            val energy = buf.sumOf { (it.toInt() * it.toInt()).toLong() } / buf.size
                            GatewayLogger.debug(TAG, "Rec: $frameCount frames, energy=$energy, queueSize=${micToSip.size}")
                        }
                    } else if (n < 0) {
                        GatewayLogger.warn(TAG, "AudioRecord.read() error: $n")
                        break
                    }
                }
            } catch (e: Exception) {
                GatewayLogger.error(TAG, "Recording loop error", e)
            } finally {
                try { audioRecord?.stop() } catch (_: Exception) {}
            }
        }
    }

    // ── AudioTrack (ring-buffer → speaker) ──────────────────────────

    private fun initializeAudioTrack() {
        // USAGE_MEDIA routes through STREAM_MUSIC to the speaker.
        // The physical microphone then picks up the SIP audio acoustically for injection
        // into the GSM uplink (modem always reads from mic during MODE_IN_CALL).
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val fmt = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(channelOutConfig)
            .setEncoding(encoding)
            .build()
        audioTrack = AudioTrack(attrs, fmt, minPlayBuf, AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE)
        check(audioTrack!!.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack init failed" }
        GatewayLogger.debug(TAG, "AudioTrack initialised, buf=$minPlayBuf")
    }

    private fun startPlayback() {
        playbackJob = scope.launch {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val silence = ByteArray(frameSizeBytes)
            try {
                audioTrack?.play()
                GatewayLogger.debug(TAG, "Playback thread started")
                while (isActive && isRunning) {
                    val frame = sipToSpk.poll(30, TimeUnit.MILLISECONDS)
                    if (frame != null) {
                        audioTrack?.write(frame, 0, frame.size)
                    } else {
                        audioTrack?.write(silence, 0, frameSizeBytes)
                    }
                }
            } catch (e: Exception) {
                GatewayLogger.error(TAG, "Playback loop error", e)
            } finally {
                try { audioTrack?.stop() } catch (_: Exception) {}
            }
        }
    }

    // ── Cleanup ─────────────────────────────────────────────────────

    private fun releaseAudioRecord() {
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
    }

    private fun releaseAudioTrack() {
        try { audioTrack?.release() } catch (_: Exception) {}
        audioTrack = null
    }

    companion object {
        private const val TAG = "SipAudioBridge"
    }
}

/**
 * PJSIP AudioMediaPort connected to the conference bridge.
 *
 * [onFrameRequested] is called by PJSIP when it needs audio TO SEND to the
 * remote SIP endpoint (mic data captured from the GSM call).
 *
 * [onFrameReceived] is called by PJSIP when it has audio FROM the remote SIP
 * endpoint that should be played into the GSM call via the speaker.
 */
class GsmBridgePort(
    private val micToSip: ArrayBlockingQueue<ByteArray>,
    private val sipToSpk: ArrayBlockingQueue<ByteArray>
) : AudioMediaPort() {

    private var reqCount = 0L
    private var nonEmptyReq = 0L
    private var rcvCount = 0L

    override fun onFrameRequested(frame: MediaFrame) {
        val data = micToSip.poll()
        reqCount++
        // 320 bytes of PCM-16 = 160 shorts (PJSIP ByteVector holds one short per element)
        if (data != null && data.size == FRAME_BYTES) {
            nonEmptyReq++
            val shorts = ShortArray(FRAME_SAMPLES) { i ->
                ((data[i * 2 + 1].toInt() shl 8) or (data[i * 2].toInt() and 0xFF)).toShort()
            }
            frame.buf = ByteVector(shorts)
            frame.type = pjmedia_frame_type.PJMEDIA_FRAME_TYPE_AUDIO
            frame.size = FRAME_SAMPLES.toLong()
        } else {
            // No mic data ready – provide silence so PJSIP keeps running
            frame.buf = ByteVector(ShortArray(FRAME_SAMPLES))
            frame.type = pjmedia_frame_type.PJMEDIA_FRAME_TYPE_AUDIO
            frame.size = FRAME_SAMPLES.toLong()
        }
        if (reqCount % 250 == 0L) {
            GatewayLogger.debug("BridgePort", "TX: $nonEmptyReq/$reqCount non-empty, RX: $rcvCount")
        }
    }

    override fun onFrameReceived(frame: MediaFrame) {
        if (frame.type != pjmedia_frame_type.PJMEDIA_FRAME_TYPE_AUDIO) return
        val bv = frame.buf ?: return
        val samples = frame.size.toInt()  // number of shorts in ByteVector
        if (samples <= 0) return
        rcvCount++
        // Convert shorts → little-endian PCM-16 bytes for AudioTrack
        val data = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val s = bv[i].toInt()
            data[i * 2]     = (s and 0xFF).toByte()
            data[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        sipToSpk.offer(data)
    }

    companion object {
        private const val FRAME_BYTES   = 320   // 20 ms @ 8 kHz, 16-bit mono
        private const val FRAME_SAMPLES = 160   // FRAME_BYTES / 2 (shorts)
    }
}
