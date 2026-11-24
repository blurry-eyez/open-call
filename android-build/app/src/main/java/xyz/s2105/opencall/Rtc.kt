package xyz.s2105.opencall

import android.content.Context
import android.os.Build
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule

/** Process-wide WebRTC factories. */
object Rtc {
    private var inited = false
    lateinit var egl: EglBase
    lateinit var factory: PeerConnectionFactory          // camera + microphone
    var screenFactory: PeerConnectionFactory? = null      // screen share: its "microphone" is replaced by captured system audio
        private set
    var screenAdm: JavaAudioDeviceModule? = null
        private set

    fun init(ctx: Context) {
        if (inited) return
        inited = true
        val app = ctx.applicationContext
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions())
        egl = EglBase.create()
        val adm = JavaAudioDeviceModule.builder(app)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        factory = build(adm)
    }

    private fun build(adm: AudioDeviceModule): PeerConnectionFactory =
        PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()

    /**
     * A second factory whose audio module reads the phone's playback (AudioPlaybackCapture, Android 10+) instead of
     * the microphone (see ScreenAudio.attach). That lets the screen share publish its own audio track next to the
     * normal mic track. Returns null where unsupported.
     */
    fun ensureScreenFactory(ctx: Context): PeerConnectionFactory? {
        if (Build.VERSION.SDK_INT < 29) return null
        screenFactory?.let { return it }
        return try {
            val adm = JavaAudioDeviceModule.builder(ctx.applicationContext)
                .setUseHardwareAcousticEchoCanceler(false)
                .setUseHardwareNoiseSuppressor(false)
                .setUseStereoInput(false)
                .setSampleRate(48000)
                // setAudioRecordDataCallback is a no-op in stream-webrtc 1.3.10; swap the capture source instead
                .setAudioRecordStateCallback(object : JavaAudioDeviceModule.AudioRecordStateCallback {
                    override fun onWebRtcAudioRecordStart() { screenAdm?.let { ScreenAudio.attach(it) } }
                    override fun onWebRtcAudioRecordStop() {}
                })
                .createAudioDeviceModule()
            screenAdm = adm
            build(adm).also { screenFactory = it }
        } catch (_: Throwable) {
            null
        }
    }
}
