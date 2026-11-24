package xyz.s2105.opencall

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.view.Surface
import org.webrtc.CapturerObserver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer

/**
 * Screen capturer on top of an already-created MediaProjection (WebRTC's own ScreenCapturerAndroid would
 * call getMediaProjection() a second time, which Android 14+ forbids once we also need the projection for audio).
 */
class ProjectionCapturer(
    private val projection: MediaProjection,
    private val outW: Int,
    private val outH: Int,
    private val dpi: Int,
) : VideoCapturer {
    private var helper: SurfaceTextureHelper? = null
    private var observer: CapturerObserver? = null
    private var display: VirtualDisplay? = null

    override fun initialize(h: SurfaceTextureHelper?, ctx: Context?, o: CapturerObserver?) {
        helper = h
        observer = o
    }

    override fun startCapture(width: Int, height: Int, framerate: Int) {
        val hp = helper ?: return
        hp.setTextureSize(outW, outH)
        display = projection.createVirtualDisplay(
            "opencall-share", outW, outH, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            Surface(hp.surfaceTexture), null, null)
        hp.startListening { frame -> observer?.onFrameCaptured(frame) }
        observer?.onCapturerStarted(true)
    }

    override fun stopCapture() {
        try { helper?.stopListening() } catch (_: Throwable) {}
        try { display?.release() } catch (_: Throwable) {}
        display = null
        observer?.onCapturerStopped()
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {}
    override fun dispose() { stopCapture() }
    override fun isScreencast() = true
}
