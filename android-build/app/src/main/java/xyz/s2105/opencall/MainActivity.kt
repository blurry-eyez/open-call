package xyz.s2105.opencall

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val inPip = MutableStateFlow(false)
    private lateinit var shareLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        addOnPictureInPictureModeChangedListener { inPip.value = it.isInPictureInPictureMode }

        shareLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val data = res.data
            if (res.resultCode == RESULT_OK && data != null) {
                CallService.share(this, res.resultCode, data)
                // like the web client: float the call in a small window so you can use other apps while sharing
                window.decorView.postDelayed({ enterPip() }, 700)
            }
        }

        lifecycleScope.launch {
            CallEngine.ui.map { it.phase to it.micOn }.distinctUntilChanged().collect {
                updatePip()
                if (it.first == Phase.Idle) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        // call toasts ("X joined", errors...) are drawn by Ui.kt's ToastHost in the web style

        setContent {
            OpenCallTheme {
                val pip by inPip.collectAsState()
                AppRoot(
                    pip = pip,
                    activity = this,
                    onShare = {
                        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        shareLauncher.launch(mpm.createScreenCaptureIntent())
                    },
                    onEnterPip = { enterPip() },
                )
            }
        }
    }

    override fun onStart() { super.onStart(); CallEngine.setVisible(true) }
    override fun onStop() { super.onStop(); CallEngine.setVisible(false) }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (CallEngine.ui.value.phase == Phase.InCall) enterPip()
    }

    private fun pipSupported() = packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun enterPip() {
        if (!pipSupported() || CallEngine.ui.value.phase != Phase.InCall) return
        try { enterPictureInPictureMode(pipParams()) } catch (_: Exception) {}
    }

    private fun updatePip() {
        if (!pipSupported()) return
        try { setPictureInPictureParams(pipParams()) } catch (_: Exception) {}
    }

    private fun pipParams(): PictureInPictureParams {
        val ui = CallEngine.ui.value
        val inCall = ui.phase == Phase.InCall
        val b = PictureInPictureParams.Builder().setAspectRatio(Rational(9, 16))
        if (inCall) {
            fun act(a: String, req: Int) = PendingIntent.getService(this, req,
                Intent(this, CallService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            b.setActions(listOf(
                RemoteAction(Icon.createWithResource(this, if (ui.micOn) R.drawable.ic_mic_off else R.drawable.ic_mic),
                    if (ui.micOn) "Mute" else "Unmute", "Toggle microphone", act(CallService.A_MUTE, 10)),
                RemoteAction(Icon.createWithResource(this, R.drawable.ic_call_end),
                    "Hang up", "Leave the call", act(CallService.A_STOP, 11)),
            ))
        }
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(inCall).setSeamlessResizeEnabled(true)
        return b.build()
    }
}
