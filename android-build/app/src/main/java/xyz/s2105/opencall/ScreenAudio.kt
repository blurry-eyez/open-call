package xyz.s2105.opencall

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * Screen-share sound (what the phone is playing: games, videos, music) via AudioPlaybackCapture.
 *
 * stream-webrtc-android 1.3.10 ignores JavaAudioDeviceModule.Builder.setAudioRecordDataCallback (the callback is
 * stored but never called), so we cannot push PCM into WebRTC that way. Instead the screen-share factory's audio
 * device module is left to run normally, and in its "recording started" callback (which runs on the WebRTC record
 * thread right before it starts reading) we swap its microphone AudioRecord for our playback-capture AudioRecord.
 * WebRTC then reads, paces and encodes the phone's sound exactly like a microphone, and the mic is not used at all.
 * Our own call audio is USAGE_VOICE_COMMUNICATION, which playback capture does not include, so nobody hears themselves.
 */
object ScreenAudio {
    private const val TAG = "ScreenAudio"
    private const val RATE = 48000
    private val lock = Any()
    private var projection: MediaProjection? = null
    private var prepared: AudioRecord? = null

    @Volatile var lastError: String? = null
        private set

    private fun build(mp: MediaProjection, rate: Int, channels: Int): AudioRecord {
        val cfg = AudioPlaybackCaptureConfiguration.Builder(mp)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val ch = if (channels >= 2) 2 else 1
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(rate)
            .setChannelMask(if (ch == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO)
            .build()
        val ar = AudioRecord.Builder()
            .setAudioFormat(fmt)
            .setAudioPlaybackCaptureConfig(cfg)
            .setBufferSizeInBytes(rate * ch * 2 / 2)   // 500 ms
            .build()
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            ar.release()
            throw IllegalStateException("capture not initialised")
        }
        return ar
    }

    /** Checks that playback capture can be created on this phone and keeps it ready for [attach]. */
    fun start(mp: MediaProjection): Boolean {
        stop()
        lastError = null
        if (Build.VERSION.SDK_INT < 29) { lastError = "needs Android 10+"; return false }
        return try {
            val ar = build(mp, RATE, 1)
            synchronized(lock) { projection = mp; prepared = ar }
            true
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
            Log.e(TAG, "playback capture unavailable", t)
            false
        }
    }

    /** Called on the WebRTC record thread of the screen-share audio module, just before it starts reading. */
    fun attach(adm: JavaAudioDeviceModule) {
        val ready = synchronized(lock) { prepared } ?: return
        var mine = ready
        try {
            val input = adm.javaClass.getDeclaredField("audioInput").apply { isAccessible = true }.get(adm)
                ?: throw IllegalStateException("no audio input")
            val field = input.javaClass.getDeclaredField("audioRecord").apply { isAccessible = true }
            val old = field.get(input) as? AudioRecord
            // WebRTC sized its 10 ms buffer for the microphone format: ours must match it exactly.
            if (old != null && (old.sampleRate != ready.sampleRate || old.channelCount != ready.channelCount)) {
                val mp = synchronized(lock) { projection } ?: throw IllegalStateException("projection gone")
                mine = build(mp, old.sampleRate, old.channelCount)
                try { ready.release() } catch (_: Throwable) {}
            }
            mine.startRecording()
            if (mine.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IllegalStateException("capture did not start")
            field.set(input, mine)                       // WebRTC releases it when the share stops
            synchronized(lock) { prepared = null }
            try { adm.setMicrophoneMute(false) } catch (_: Throwable) {}
            if (old != null) {
                try { old.stop() } catch (_: Throwable) {}
                try { old.release() } catch (_: Throwable) {}
            }
            Log.i(TAG, "sharing phone sound (${mine.sampleRate} Hz, ${mine.channelCount} ch)")
        } catch (t: Throwable) {
            // Never let the real microphone leak into the screen-share track.
            try { adm.setMicrophoneMute(true) } catch (_: Throwable) {}
            if (mine !== ready) try { mine.release() } catch (_: Throwable) {}
            lastError = t.message ?: t.javaClass.simpleName
            Log.e(TAG, "attach failed", t)
            CallEngine.events.tryEmit("Screen sound failed: $lastError")
        }
    }

    /** Releases a capture that was never attached. An attached one is released by WebRTC when the share ends. */
    fun stop() {
        val p = synchronized(lock) { val r = prepared; prepared = null; projection = null; r }
        try { p?.release() } catch (_: Throwable) {}
    }
}
