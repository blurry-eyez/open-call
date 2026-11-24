package xyz.s2105.opencall

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStats
import org.webrtc.RTCStatsCollectorCallback
import org.webrtc.RTCStatsReport
import org.webrtc.RtpParameters
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class Phase { Idle, Connecting, Waiting, InCall }

data class Tile(
    val id: String,
    val name: String,
    val video: VideoTrack?,
    val mirror: Boolean,
    val mic: Boolean,
    val cam: Boolean,
    val screen: Boolean,
    val local: Boolean,
    val connecting: Boolean,
    val stat: String? = null,   // live stats text (Settings -> Advanced -> Diagnostics), only on my own tile
    val note: String? = null,   // small line under "You're sharing your screen"
)

/** Latest numbers for what I am sending (drives the Settings live lines and the network pill). */
data class Live(
    val w: Int, val h: Int, val fps: Int, val kbps: Int, val rttMs: Int, val loss: Double,
    val path: String, val proto: String, val level: String, val auto: Boolean, val conn: String,
)

data class Waiter(val wid: String, val name: String)

data class CallUi(
    val phase: Phase = Phase.Idle,
    val error: String? = null,
    val code: String = "",
    val tiles: List<Tile> = emptyList(),
    val micOn: Boolean = true,
    val camOn: Boolean = true,
    val camAllowed: Boolean = true,
    val canFlip: Boolean = false,
    val speaker: Boolean = true,
    val sharing: Boolean = false,
    val locked: Boolean = false,
    val waiting: List<Waiter> = emptyList(),
    val mode: String = "off",            // room-wide: off | call (voice only) | video (audio first)
    val quotaLeft: Int? = null,          // seconds of free time left (null = no limit)
    val quotaAt: Long = 0L,
    val reconnecting: Boolean = false,
    val pinned: String? = null,
    val bt: Boolean = false,             // audio is going to a Bluetooth headset
    val front: Boolean = true,           // the front (selfie) camera is in use
    val live: Live? = null,
)

/* ---- quality ladder: same levels / numbers as the web client (LEVELS, AQ in static/index.html) ---- */
private class Level(val id: String, val w: Int, val h: Int, val fps: Int, val br: Int)
private val LEVELS = listOf(
    Level("1080", 1920, 1080, 30, 4_500_000),
    Level("720", 1280, 720, 30, 2_500_000),
    Level("480", 854, 480, 30, 1_200_000),
    Level("360", 640, 360, 30, 700_000),
    Level("240", 426, 240, 24, 350_000),
    Level("180", 320, 180, 15, 160_000),
    Level("ultra", 256, 144, 10, 60_000),
)
private const val AUTO_START = 1
private const val AUDIO_BR = 40_000
/** Opus presets: bitrate ceiling (bps) to max playback rate (Hz). */
private val AQ = mapOf("low" to (20_000 to 16_000), "med" to (40_000 to 48_000), "high" to (64_000 to 48_000))
private val AQ_ORDER = listOf("low", "med", "high")

private class Lvl(var idx: Int, var good: Int, var need: Int, var lastChange: Long, var t0: Long)

/** One sample of what the publisher connection is doing. */
private class Out {
    var loss = 0.0
    var avail: Double? = null
    var rtt = 0.0
    var qlr = "none"
    var kbps = 0
    var w = 0
    var h = 0
    var fps = 0
    var path = ""
    var proto = ""
    var bytes = -1L
    var at = 0L
}

private fun RTCStats.num(k: String): Double? = (members[k] as? Number)?.toDouble()
private fun RTCStats.txt(k: String): String? = members[k] as? String
private fun f1(d: Double): String = String.format(java.util.Locale.US, "%.1f", d)

class ReqEx(val code: String, msg: String) : Exception(msg)
private class Gone : Exception("stale")
private class IceTimeout(msg: String) : Exception(msg)

/** One PeerConnection plus the bits we need to wait on. */
class Conn(val label: String) : PeerConnection.Observer {
    lateinit var pc: PeerConnection
    val gathered = CompletableDeferred<Unit>()
    val useful = CompletableDeferred<Unit>()
    val state = MutableStateFlow(PeerConnection.PeerConnectionState.NEW)
    var onTrack: ((RtpTransceiver) -> Unit)? = null
    var onState: ((PeerConnection.PeerConnectionState) -> Unit)? = null

    override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
    override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {}
    override fun onIceConnectionReceivingChange(b: Boolean) {}
    override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {
        if (s == PeerConnection.IceGatheringState.COMPLETE) gathered.complete(Unit)
    }
    override fun onIceCandidate(c: IceCandidate?) {
        val s = c?.sdp ?: return
        if (s.contains(" typ srflx") || s.contains(" typ relay")) useful.complete(Unit)
    }
    override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
    override fun onAddStream(s: MediaStream?) {}
    override fun onRemoveStream(s: MediaStream?) {}
    override fun onDataChannel(d: DataChannel?) {}
    override fun onRenegotiationNeeded() {}
    override fun onConnectionChange(s: PeerConnection.PeerConnectionState) {
        state.value = s
        onState?.invoke(s)
    }
    override fun onTrack(t: RtpTransceiver) { onTrack?.invoke(t) }

    fun close() {
        onTrack = null
        onState = null
        try { pc.close() } catch (_: Throwable) {}
        try { pc.dispose() } catch (_: Throwable) {}
    }
}

private open class Sdp : SdpObserver {
    override fun onCreateSuccess(d: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(e: String?) {}
    override fun onSetFailure(e: String?) {}
}

private suspend fun PeerConnection.makeOffer(): SessionDescription = suspendCancellableCoroutine { c ->
    createOffer(object : Sdp() {
        override fun onCreateSuccess(d: SessionDescription?) { if (d != null) c.resume(d) else c.resumeWithException(RuntimeException("no offer")) }
        override fun onCreateFailure(e: String?) { c.resumeWithException(RuntimeException("createOffer: $e")) }
    }, MediaConstraints())
}

private suspend fun PeerConnection.makeAnswer(): SessionDescription = suspendCancellableCoroutine { c ->
    createAnswer(object : Sdp() {
        override fun onCreateSuccess(d: SessionDescription?) { if (d != null) c.resume(d) else c.resumeWithException(RuntimeException("no answer")) }
        override fun onCreateFailure(e: String?) { c.resumeWithException(RuntimeException("createAnswer: $e")) }
    }, MediaConstraints())
}

private suspend fun PeerConnection.setLocal(d: SessionDescription) = suspendCancellableCoroutine<Unit> { c ->
    setLocalDescription(object : Sdp() {
        override fun onSetSuccess() { c.resume(Unit) }
        override fun onSetFailure(e: String?) { c.resumeWithException(RuntimeException("setLocal: $e")) }
    }, d)
}

private suspend fun PeerConnection.setRemote(d: SessionDescription) = suspendCancellableCoroutine<Unit> { c ->
    setRemoteDescription(object : Sdp() {
        override fun onSetSuccess() { c.resume(Unit) }
        override fun onSetFailure(e: String?) { c.resumeWithException(RuntimeException("setRemote: $e")) }
    }, d)
}

/**
 * Same protocol as the web client (index.html):
 *  - one WebSocket (/ws) for rooms + signaling
 *  - one publisher PeerConnection (audio+video up), one subscriber PeerConnection per remote camera / screen
 *  - one extra publisher PeerConnection while screen sharing
 *  - media flows through the Cloudflare SFU; there is no trickle ICE, so candidates are gathered up front
 * Everything here runs on the main thread; WebRTC callbacks hop back via [scope].
 */
object CallEngine {
    lateinit var app: Application

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _ui = MutableStateFlow(CallUi())
    val ui: StateFlow<CallUi> = _ui
    val events = MutableSharedFlow<String>(extraBufferCapacity = 16)

    // ---- session ----
    private var code = ""
    private var myName = ""
    private val cid = UUID.randomUUID().toString()
    private var ws: WebSocket? = null
    private var wsUp = false
    private var leaving = false
    private var wsBackoff = 0
    private var welcomeWait: CompletableDeferred<JSONObject>? = null
    private var joined = false
    private var reqId = 0
    private val pending = HashMap<Int, CompletableDeferred<JSONObject>>()
    private var iceJson: JSONObject? = null
    private var autoMode = "all"
    private var joinJob: Job? = null
    private var pingJob: Job? = null
    private var tickJob: Job? = null
    private var statsJob: Job? = null

    // ---- quality control (see Settings.kt) ----
    private var lvl = Lvl(AUTO_START, 0, 10, 0L, 0L)
    private var aStep = 0
    private var aGood = 0
    private var lastOut: Out? = null
    private var prevBytes = -1L
    private var prevAt = 0L
    private var capW = 0
    private var capH = 0

    // ---- room state ----
    private class PeerInfo(val pid: String, var name: String, var mic: Boolean, var cam: Boolean) {
        var screenSession: String? = null
    }
    private class Remote(val key: String, val owner: String, val screen: Boolean) {
        var sessionId: String? = null
        var conn: Conn? = null
        var gen = 0
        var video: VideoTrack? = null
        var audio: AudioTrack? = null
        var audioOnly = false
    }
    private val peers = LinkedHashMap<String, PeerInfo>()
    private val remotes = LinkedHashMap<String, Remote>()      // pid -> camera, "pid:screen" -> screen
    private var mode = "off"
    private var locked = false
    private var waiting: List<Waiter> = emptyList()
    private var quotaLeft: Int? = null
    private var quotaAt = 0L
    private var pinned: String? = null

    // ---- local media ----
    private var micOn = true
    private var camOn = true
    private var camAllowed = true
    private var visible = true
    private var frontFacing = true
    private var speaker = true
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var capturer: CameraVideoCapturer? = null
    private var camHelper: SurfaceTextureHelper? = null
    private var camCapturing = false

    // ---- publishing ----
    private var pubConn: Conn? = null
    private var pubGen = 0
    private var aSender: RtpSender? = null
    private var vSender: RtpSender? = null

    // ---- screen share ----
    private class Screen(val mp: MediaProjection) {
        var factory: PeerConnectionFactory? = null
        var vSource: VideoSource? = null
        var vTrack: VideoTrack? = null
        var aSource: AudioSource? = null
        var aTrack: AudioTrack? = null
        var capturer: ProjectionCapturer? = null
        var helper: SurfaceTextureHelper? = null
        var conn: Conn? = null
        var gen = 0
        var cb: MediaProjection.Callback? = null
        var withAudio = false
    }
    private var screen: Screen? = null

    private var focus: AudioFocusRequest? = null
    private var prevAudioMode = AudioManager.MODE_NORMAL

    // =============================================================== public API

    val busy get() = _ui.value.phase != Phase.Idle

    fun join(codeIn: String, name: String, wantCam: Boolean) {
        if (busy) return
        code = codeIn.trim().lowercase().replace(Regex("\\s+"), "")
        myName = name.trim().ifEmpty { "Guest" }
        leaving = false
        joined = false
        wsBackoff = 0
        micOn = true
        camAllowed = wantCam
        camOn = wantCam
        mode = "off"
        locked = false
        waiting = emptyList()
        quotaLeft = null
        pinned = null
        aStep = 0; aGood = 0; lastOut = null; prevBytes = -1L
        val t0 = System.currentTimeMillis()
        lvl = Lvl(max(AUTO_START, topIdx()), 0, 10, t0, t0)
        _ui.value = CallUi(phase = Phase.Connecting, code = code, camOn = camOn, camAllowed = camAllowed)
        joinJob = scope.launch {
            try {
                Rtc.init(app)
                loadIce()
                startLocalMedia()
                audioStart()
                val w = connectWs()
                onWelcome(w)
                _ui.update { it.copy(phase = Phase.InCall) }
                startTicker()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = e.message ?: "Could not join"
                val ec = (e as? ReqEx)?.code
                cleanup()
                _ui.value = CallUi(phase = Phase.Idle, error = msg)
                if (ec == "auth") Auth.refresh()
            }
        }
    }

    fun leave() {
        if (!busy) return
        leaving = true
        try { ws?.send(JSONObject().put("t", "leave").toString()) } catch (_: Exception) {}
        cleanup()
        _ui.value = CallUi(phase = Phase.Idle)
    }

    fun clearError() { _ui.update { it.copy(error = null) } }

    fun toggleMic() {
        micOn = !micOn
        audioTrack?.setEnabled(micOn)
        announce(); publishUi()
    }

    fun toggleCam() {
        if (!camAllowed || mode == "call") { toast("Camera is off in this mode"); return }
        camOn = !camOn
        applyCamera()
    }

    fun flipCamera() {
        capturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFront: Boolean) { scope.launch { frontFacing = isFront; publishUi() } }
            override fun onCameraSwitchError(err: String?) {}
        })
    }

    fun toggleSpeaker() { speaker = !speaker; applySpeaker(); publishUi() }

    fun setVisible(v: Boolean) {
        if (visible == v) return
        visible = v
        if (busy) applyCamera()
    }

    fun pin(id: String?) { pinned = if (pinned == id) null else id; publishUi() }

    fun setLock(on: Boolean) = send(JSONObject().put("t", "lock").put("on", on))
    fun admit(wid: String, ok: Boolean) = send(JSONObject().put("t", "admit").put("wid", wid).put("ok", ok))
    fun setRoomMode(m: String) = send(JSONObject().put("t", "mode").put("mode", m))

    // ----------------------------------------------------------- settings (mirror of the web Settings sheet)
    fun setVideoQuality(q: String) {
        Settings.update { it.copy(quality = q) }
        if (q == "auto") { val now = System.currentTimeMillis(); lvl = Lvl(max(AUTO_START, topIdx()), 0, 10, now, now) }
        applyQuality(); publishUi()
    }

    fun setVidPrio(v: String) { Settings.update { it.copy(vidPrio = v) }; applyQuality(); publishUi() }

    fun setAudioQuality(q: String) { Settings.update { it.copy(audioQ = q) }; aStep = 0; applyAudioParams() }

    fun setMirror(on: Boolean) { Settings.update { it.copy(mirror = on) }; publishUi() }

    fun setStats(on: Boolean) { Settings.update { it.copy(stats = on) }; publishUi() }

    fun setSpeaker(on: Boolean) { if (speaker == on) return; speaker = on; applySpeaker(); publishUi() }

    fun setFacing(front: Boolean) {
        if (front == frontFacing) return
        if (camCapturing) flipCamera() else { frontFacing = front; publishUi() }
    }

    /** Direct / Relay / Relay TCP / Auto: rebuild every media connection with the new path. */
    fun setConnMode(m: String) {
        if (m == Settings.cfg.value.connMode) return
        Settings.update { it.copy(connMode = m) }
        autoMode = "all"
        if (_ui.value.phase != Phase.InCall) return
        toast("Reconnecting with " + when (m) { "all" -> "Direct"; "relay" -> "Relay"; "relay-tcp" -> "Relay TCP"; else -> "Auto" } + " mode…")
        scope.launch { publishCam() }
        screen?.let { publishScreen(it) }
        for (r in remotes.values) subscribe(r)
    }

    // ----------------------------------------------------------- screen share
    fun startScreen(mp: MediaProjection) {
        if (!busy || _ui.value.phase != Phase.InCall) { mp.stop(); return }
        if (mode == "call") { toast("Voice-only mode: no screen sharing"); mp.stop(); return }
        stopScreen()
        val sc = Screen(mp)
        screen = sc
        val wantAudio = Settings.cfg.value.shareAudio
        try {
            val cb = object : MediaProjection.Callback() {
                override fun onStop() { scope.launch { if (screen === sc) stopScreen() } }
            }
            sc.cb = cb
            mp.registerCallback(cb, Handler(Looper.getMainLooper()))

            // screen size: keep the display's aspect ratio, longest side <= 1920, even numbers
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            val disp = (app.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
            @Suppress("DEPRECATION")
            disp.getRealMetrics(dm)
            val scale = minOf(1f, 1920f / maxOf(dm.widthPixels, dm.heightPixels))
            val w = (dm.widthPixels * scale).toInt() / 2 * 2
            val h = (dm.heightPixels * scale).toInt() / 2 * 2

            // audio (phone playback) -> its own factory; falls back to video only
            val audioOk = wantAudio && ScreenAudio.start(mp)
            val f = (if (audioOk) Rtc.ensureScreenFactory(app) else null) ?: Rtc.factory
            sc.factory = f
            sc.withAudio = audioOk && f !== Rtc.factory
            if (!sc.withAudio) ScreenAudio.stop()

            sc.vSource = f.createVideoSource(true)
            sc.vSource!!.adaptOutputFormat(w, h, 30)
            sc.vTrack = f.createVideoTrack("screen-v", sc.vSource)
            if (sc.withAudio) {
                val cons = MediaConstraints().apply {
                    for (k in listOf("googEchoCancellation", "googNoiseSuppression", "googAutoGainControl", "googHighpassFilter"))
                        mandatory.add(MediaConstraints.KeyValuePair(k, "false"))
                }
                sc.aSource = f.createAudioSource(cons)
                sc.aTrack = f.createAudioTrack("screen-a", sc.aSource)
            }
            sc.helper = SurfaceTextureHelper.create("screen", Rtc.egl.eglBaseContext)
            sc.capturer = ProjectionCapturer(mp, w, h, dm.densityDpi).also {
                it.initialize(sc.helper, app, sc.vSource!!.capturerObserver)
                it.startCapture(w, h, 30)
            }
        } catch (e: Throwable) {
            toast("Could not start screen sharing")
            stopScreen()
            return
        }
        publishUi()
        publishScreen(sc)
        toast(when {
            sc.withAudio -> "Sharing your screen with audio"
            wantAudio -> "Sharing your screen (no sound: ${ScreenAudio.lastError ?: "not supported"})"
            else -> "Sharing your screen (without sound)"
        })
    }

    fun stopScreen() {
        val sc = screen ?: return
        screen = null
        sc.gen++
        send(JSONObject().put("t", "screen-stop"))
        ScreenAudio.stop()
        try { sc.capturer?.stopCapture() } catch (_: Throwable) {}
        try { sc.conn?.close() } catch (_: Throwable) {}
        try { sc.cb?.let { sc.mp.unregisterCallback(it) } } catch (_: Throwable) {}
        try { sc.mp.stop() } catch (_: Throwable) {}
        try { sc.vTrack?.dispose() } catch (_: Throwable) {}
        try { sc.aTrack?.dispose() } catch (_: Throwable) {}
        try { sc.vSource?.dispose() } catch (_: Throwable) {}
        try { sc.aSource?.dispose() } catch (_: Throwable) {}
        try { sc.helper?.dispose() } catch (_: Throwable) {}
        publishUi()
    }

    // =============================================================== ICE / modes

    private suspend fun loadIce() {
        Net.http.newCall(Request.Builder().url("$BASE/api/ice").build()).await().use { r ->
            val j = JSONObject(r.body?.string() ?: "{}")
            if (r.code == 401) throw ReqEx("auth", j.optString("error", "Please log in with Google first."))
            if (!r.isSuccessful) throw ReqEx("ice", "Could not load connection servers")
            iceJson = j
        }
    }

    private fun turnAvailable() = iceJson?.optBoolean("turn") == true

    /** Auto walks direct -> relay -> TCP relay; a fixed choice is used as is (relay modes need TURN on the server). */
    private fun effMode(): String {
        val m = Settings.cfg.value.connMode
        return if (m == "auto") autoMode else if (m != "all" && !turnAvailable()) "all" else m
    }

    private fun escalate(): Boolean {
        if (Settings.cfg.value.connMode != "auto" || !turnAvailable()) return false
        val order = listOf("all", "relay", "relay-tcp")
        val i = order.indexOf(autoMode)
        if (i >= order.size - 1) return false
        autoMode = order[i + 1]
        toast(if (autoMode == "relay") "Direct path blocked — switching to relay" else "Relay blocked — switching to TCP relay")
        return true
    }

    private fun iceServersFor(mode: String): List<PeerConnection.IceServer> {
        val arr = iceJson?.optJSONArray("iceServers") ?: JSONArray()
        val all = ArrayList<PeerConnection.IceServer>()
        val filtered = ArrayList<PeerConnection.IceServer>()
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            val raw = s.opt("urls")
            val urls: List<String> = when (raw) {
                is JSONArray -> (0 until raw.length()).map { raw.getString(it) }
                is String -> listOf(raw)
                else -> emptyList()
            }
            if (urls.isEmpty()) continue
            fun build(list: List<String>): PeerConnection.IceServer {
                val b = PeerConnection.IceServer.builder(list)
                s.str("username")?.let { b.setUsername(it) }
                s.str("credential")?.let { b.setPassword(it) }
                return b.createIceServer()
            }
            all += build(urls)
            val use = when (mode) {
                "all" -> urls
                "relay" -> urls.filter { it.startsWith("turn:") || it.startsWith("turns:") }
                else -> urls.filter { it.startsWith("turns:") || (it.startsWith("turn:") && it.contains("transport=tcp")) }
            }
            if (use.isNotEmpty()) filtered += build(use)
        }
        val out = if (mode == "all" || filtered.isEmpty()) all else filtered
        return out.ifEmpty { listOf(PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer()) }
    }

    private fun makeConn(mode: String, factory: PeerConnectionFactory, label: String): Conn {
        val c = Conn(label)
        val cfg = PeerConnection.RTCConfiguration(iceServersFor(mode)).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            iceTransportsType = if (mode == "all") PeerConnection.IceTransportsType.ALL else PeerConnection.IceTransportsType.RELAY
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
            iceCandidatePoolSize = 1
        }
        c.pc = factory.createPeerConnection(cfg, c) ?: throw RuntimeException("Could not create connection")
        return c
    }

    /** Wait for candidates, but only ~300 ms past the first server-reflexive / relay one (no trickle ICE in the SFU). */
    private suspend fun Conn.gather(mode: String) {
        withTimeoutOrNull(if (mode == "all") 2500L else 4000L) {
            select<Unit> {
                gathered.onAwait { }
                useful.onAwait { delay(300) }
            }
        }
    }

    private suspend fun Conn.waitConnected(ms: Long) {
        val s = try {
            withTimeout(ms) {
                state.first { it == PeerConnection.PeerConnectionState.CONNECTED ||
                    it == PeerConnection.PeerConnectionState.FAILED || it == PeerConnection.PeerConnectionState.CLOSED }
            }
        } catch (_: TimeoutCancellationException) {
            throw IceTimeout("Connection timed out")
        }
        if (s != PeerConnection.PeerConnectionState.CONNECTED) throw IceTimeout("ICE ${s.name.lowercase()}")
    }

    /** Build -> connect -> return. Retries with backoff and walks direct -> relay -> TCP relay. */
    private suspend fun establish(
        label: String, factory: PeerConnectionFactory, stale: () -> Boolean,
        build: suspend (Conn, String) -> Unit,
    ): Conn? {
        var attempt = 0
        while (true) {
            if (stale()) return null
            val mode = effMode()
            var c: Conn? = null
            try {
                c = makeConn(mode, factory, label)
                build(c, mode)
                if (stale()) { c.close(); return null }
                c.waitConnected(if (mode == "all") 5000L else 9000L)
                return c
            } catch (e: CancellationException) {
                c?.close(); throw e
            } catch (e: Exception) {
                c?.close()
                if (stale() || e is Gone) return null
                attempt++
                if (e is IceTimeout) escalate()
                if (e is ReqEx && e.code == "auth") throw e
                if (attempt >= 8) { toast("$label failed: ${e.message}"); return null }
                delay(if ((e as? ReqEx)?.code == "not_ready") 700L else minOf(3000L, 350L * attempt))
            }
        }
    }

    // =============================================================== signaling

    private fun send(o: JSONObject) {
        if (wsUp) try { ws?.send(o.toString()) } catch (_: Exception) {}
    }

    private suspend fun request(t: String, payload: JSONObject): JSONObject {
        if (!wsUp) throw ReqEx("offline", "Offline")
        val id = ++reqId
        val d = CompletableDeferred<JSONObject>()
        pending[id] = d
        payload.put("t", t).put("id", id)
        ws?.send(payload.toString())
        try {
            return withTimeout(15_000) { d.await() }
        } catch (_: TimeoutCancellationException) {
            pending.remove(id)
            throw ReqEx("timeout", "Server timed out")
        }
    }

    private fun failPending() {
        for (d in pending.values) d.completeExceptionally(ReqEx("offline", "Connection lost"))
        pending.clear()
    }

    private suspend fun connectWs(): JSONObject {
        val d = CompletableDeferred<JSONObject>()
        welcomeWait = d
        openWs()
        return d.await()
    }

    private fun openWs() {
        val req = Request.Builder().url("$BASE/ws").build()
        var sock: WebSocket? = null
        sock = Net.ws.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                scope.launch {
                    if (ws !== webSocket) return@launch
                    wsUp = true
                    wsBackoff = 0
                    webSocket.send(JSONObject().put("t", "hello").put("code", code).put("name", myName).put("cid", cid).toString())
                    _ui.update { it.copy(reconnecting = false) }
                }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val m = try { JSONObject(text) } catch (_: Exception) { return }
                scope.launch { if (ws === webSocket) onMsg(m) }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { scope.launch { wsClosed(webSocket) } }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { scope.launch { wsClosed(webSocket) } }
        })
        ws = sock
    }

    private fun wsClosed(socket: WebSocket) {
        if (ws !== socket) return
        wsUp = false
        failPending()
        if (leaving) return
        val w = welcomeWait
        if (!joined && w != null && wsBackoff >= 3) {
            welcomeWait = null
            w.completeExceptionally(ReqEx("net", "Could not reach the server"))
            return
        }
        wsBackoff++
        if (joined) _ui.update { it.copy(reconnecting = true) }
        scope.launch {
            delay(minOf(4000L, 400L * wsBackoff))
            if (!leaving && ws === socket) openWs()
        }
    }

    private fun onMsg(m: JSONObject) {
        when (m.optString("t")) {
            "res" -> {
                val d = pending.remove(m.optInt("id")) ?: return
                if (m.optBoolean("ok")) d.complete(m)
                else d.completeExceptionally(ReqEx(m.optString("code", "error"), m.optString("error", "Failed")))
            }
            "fatal" -> {
                val msg = m.optString("error", "Disconnected")
                val c = m.optString("code")
                leaving = true
                val w = welcomeWait
                if (w != null) { welcomeWait = null; w.completeExceptionally(ReqEx(c, msg)) }
                else { cleanup(); _ui.value = CallUi(phase = Phase.Idle, error = msg); if (c == "auth") Auth.refresh() }
            }
            "quota" -> setQuota(if (m.isNull("left")) null else m.optInt("left"))
            "welcome" -> {
                val w = welcomeWait
                if (w != null) { welcomeWait = null; joined = true; w.complete(m) } else onWelcome(m)
            }
            "waiting" -> _ui.update { it.copy(phase = Phase.Waiting) }
            "waitlist" -> { waiting = parseWait(m.optJSONArray("list")); publishUi() }
            "lock" -> { locked = m.optBoolean("on"); publishUi() }
            "mode" -> roomMode(m.optString("mode", "off"), m.str("by"))
            "peer-joined" -> { val p = addPeer(m.getJSONObject("peer")); toast("${p.name} joined") }
            "peer-published" -> {
                val r = remotes[m.optString("pid")]
                if (r != null) { r.sessionId = m.str("sessionId"); subscribe(r) }
            }
            "peer-state" -> {
                val p = peers[m.optString("pid")]
                if (p != null) { p.mic = m.optBoolean("mic", true); p.cam = m.optBoolean("cam", true); publishUi() }
            }
            "peer-left" -> { val p = peers[m.optString("pid")]; if (p != null) { toast("${p.name} left"); removePeer(p.pid) } }
            "peer-screen" -> {
                val p = peers[m.optString("pid")] ?: return
                p.screenSession = m.str("sessionId")
                syncScreens()
            }
        }
    }

    private fun parseWait(a: JSONArray?): List<Waiter> {
        a ?: return emptyList()
        return (0 until a.length()).map { val o = a.getJSONObject(it); Waiter(o.optString("wid"), o.optString("name")) }
    }

    private fun onWelcome(m: JSONObject) {
        locked = m.optBoolean("locked")
        waiting = parseWait(m.optJSONArray("waiting"))
        setQuota(if (m.isNull("quota")) null else m.optInt("quota"), publish = false)
        val resumed = m.optBoolean("resumed")
        val list = m.optJSONArray("peers") ?: JSONArray()
        val known = HashSet<String>()
        for (i in 0 until list.length()) known += list.getJSONObject(i).optString("pid")
        for (pid in peers.keys.toList()) if (pid !in known) removePeer(pid)
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            val pid = o.optString("pid")
            val ex = peers[pid]
            if (ex == null) addPeer(o, quiet = true)
            else {
                ex.name = o.optString("name", ex.name); ex.mic = o.optBoolean("mic", true); ex.cam = o.optBoolean("cam", true)
                ex.screenSession = o.str("screenSessionId")
                val sid = o.str("sessionId")
                val r = remotes[pid]
                if (r != null && sid != null && sid != r.sessionId) { r.sessionId = sid; subscribe(r) }
            }
        }
        syncScreens()
        val rm = m.optString("mode", "off")
        if (!resumed && list.length() == 0 && mode != "off") send(JSONObject().put("t", "mode").put("mode", mode))
        else if (rm != mode) roomMode(rm, null)
        if (_ui.value.phase == Phase.Waiting || _ui.value.phase == Phase.Connecting) {
            // first join: publish our media
            scope.launch { publishCam() }
            applyCamera(announceToo = false)
            announce()
        } else {
            // reconnected after a drop
            announce()
            if (!resumed) { scope.launch { publishCam() }; screen?.let { publishScreen(it) } }
            else for (r in remotes.values) if (r.conn == null || r.conn?.state?.value != PeerConnection.PeerConnectionState.CONNECTED) subscribe(r)
            toast("Reconnected")
        }
        publishUi()
    }

    private fun addPeer(o: JSONObject, quiet: Boolean = false): PeerInfo {
        val pid = o.optString("pid")
        val p = PeerInfo(pid, o.optString("name", "Guest"), o.optBoolean("mic", true), o.optBoolean("cam", true))
        p.screenSession = o.str("screenSessionId")
        peers[pid] = p
        val r = Remote(pid, pid, false)
        r.audioOnly = mode == "call"
        r.sessionId = o.str("sessionId")
        remotes[pid] = r
        if (r.sessionId != null) subscribe(r)
        if (!quiet) syncScreens()
        publishUi()
        return p
    }

    private fun removePeer(pid: String) {
        peers.remove(pid)
        for (k in listOf(pid, "$pid:screen")) remotes.remove(k)?.let { closeRemote(it) }
        if (pinned == pid || pinned == "$pid:screen") pinned = null
        publishUi()
    }

    private fun closeRemote(r: Remote) {
        r.gen++
        r.conn?.close(); r.conn = null
        r.video = null; r.audio = null
    }

    /** Make remote screen-share tiles match what the room is sharing (none in voice-only mode). */
    private fun syncScreens() {
        for (p in peers.values) {
            val key = "${p.pid}:screen"
            val sid = if (mode == "call") null else p.screenSession
            val ex = remotes[key]
            if (sid == null) {
                if (ex != null) { remotes.remove(key); closeRemote(ex); if (pinned == key) pinned = null }
            } else if (ex == null) {
                val r = Remote(key, p.pid, true)
                r.sessionId = sid
                remotes[key] = r
                toast("${p.name} is sharing their screen")
                subscribe(r)
            } else if (ex.sessionId != sid) {
                ex.sessionId = sid
                subscribe(ex)
            }
        }
        publishUi()
    }

    private fun roomMode(m: String, by: String?) {
        if (m == mode) return
        mode = m
        if (by != null) toast(when (m) { "call" -> "$by switched to voice-only"; "video" -> "$by switched to audio-first video"; else -> "$by switched to normal call" })
        val ao = m == "call"
        for (r in remotes.values) if (!r.screen && r.audioOnly != ao) { r.audioOnly = ao; subscribe(r) }
        if (ao) stopScreen()
        syncScreens()
        aStep = 0
        lvl.idx = max(lvl.idx, topIdx())
        applyQuality(); applyAudioParams()
        applyCamera()
    }

    private fun setQuota(left: Int?, publish: Boolean = true) {
        quotaLeft = left
        quotaAt = System.currentTimeMillis()
        if (publish) publishUi()
    }

    // =============================================================== subscribe

    private fun subscribe(r: Remote) {
        val sid = r.sessionId
        r.gen++
        val gen = r.gen
        r.conn?.close(); r.conn = null
        r.video = null; r.audio = null
        publishUi()
        if (sid == null) return
        scope.launch {
            val c = establish(label = "Connecting", factory = Rtc.factory,
                stale = { gen != r.gen || !remotes.containsKey(r.key) }) { c, mode ->
                c.onTrack = { t ->
                    val tr = t.receiver.track()
                    scope.launch {
                        if (gen == r.gen) {
                            when (tr) {
                                is VideoTrack -> r.video = tr
                                is AudioTrack -> { r.audio = tr; tr.setEnabled(true) }
                            }
                            publishUi()
                        }
                    }
                }
                val res = request("subscribe", JSONObject().put("pid", r.owner)
                    .put("kind", if (r.screen) "screen" else "cam")
                    .put("audioOnly", !r.screen && r.audioOnly))
                if (gen != r.gen) throw Gone()
                c.pc.setRemote(SessionDescription(SessionDescription.Type.OFFER, res.getString("sdp")))
                val ans = c.pc.makeAnswer()
                c.pc.setLocal(ans)
                c.gather(mode)
                request("renegotiate", JSONObject().put("sessionId", res.getString("sessionId"))
                    .put("sdp", c.pc.localDescription.description))
            } ?: return@launch
            if (gen != r.gen || !remotes.containsKey(r.key)) { c.close(); return@launch }
            r.conn = c
            c.onState = { s ->
                scope.launch {
                    if (gen == r.gen) {
                        if (s == PeerConnection.PeerConnectionState.FAILED) subscribe(r)
                        else if (s == PeerConnection.PeerConnectionState.DISCONNECTED) {
                            delay(4000)
                            if (gen == r.gen && c.state.value != PeerConnection.PeerConnectionState.CONNECTED) subscribe(r)
                        }
                    }
                }
            }
            publishUi()
        }
    }

    // =============================================================== local media

    private fun startLocalMedia() {
        val f = Rtc.factory
        audioSource = f.createAudioSource(MediaConstraints())
        audioTrack = f.createAudioTrack("mic", audioSource).also { it.setEnabled(micOn) }
        videoSource = f.createVideoSource(false)
        applyAdapt()
        videoTrack = f.createVideoTrack("cam", videoSource)
        videoTrack!!.setEnabled(camOn)
    }

    /** Camera runs only when: camera on, permission granted, app visible (or in PiP), and not voice-only mode. */
    private fun applyCamera(announceToo: Boolean = true) {
        val want = camOn && camAllowed && visible && mode != "call"
        if (want) startCamera() else stopCamera()
        videoTrack?.setEnabled(want)
        if (announceToo) announce()
        publishUi()
    }

    private fun startCamera() {
        if (camCapturing) return
        val src = videoSource ?: return
        try {
            val en = Camera2Enumerator(app)
            val names = en.deviceNames
            val name = names.firstOrNull { en.isFrontFacing(it) == frontFacing } ?: names.firstOrNull() ?: run {
                camAllowed = false; return
            }
            val cap = en.createCapturer(name, null)
            camHelper = SurfaceTextureHelper.create("cam", Rtc.egl.eglBaseContext)
            cap.initialize(camHelper, app, src.capturerObserver)
            val (cw, ch) = captureSizeFor(currentLevel())
            cap.startCapture(cw, ch, 30)
            capW = cw; capH = ch
            capturer = cap
            camCapturing = true
        } catch (e: Throwable) {
            toast("Could not open the camera")
            camAllowed = false
            camOn = false
        }
    }

    private fun stopCamera() {
        if (!camCapturing) return
        camCapturing = false
        try { capturer?.stopCapture() } catch (_: Throwable) {}
        try { capturer?.dispose() } catch (_: Throwable) {}
        capturer = null
        try { camHelper?.dispose() } catch (_: Throwable) {}
        camHelper = null
    }

    private fun announce() {
        send(JSONObject().put("t", "state").put("mic", micOn).put("cam", camOn && camAllowed && visible && mode != "call").put("rec", false))
    }

    // =============================================================== publish

    private suspend fun publishCam() {
        val gen = ++pubGen
        pubConn?.close(); pubConn = null
        prevBytes = -1L
        val a = audioTrack ?: return
        val v = videoTrack ?: return
        var senders: Pair<RtpSender, RtpSender>? = null
        val c = establish("Publishing", Rtc.factory, { gen != pubGen }) { c, mode ->
            val init = RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, listOf("oc-cam"))
            val aT = c.pc.addTransceiver(a, init)
            val vT = c.pc.addTransceiver(v, init)
            c.pc.setLocal(c.pc.makeOffer())
            c.gather(mode)
            val tracks = JSONArray()
                .put(JSONObject().put("mid", aT.mid).put("trackName", "audio"))
                .put(JSONObject().put("mid", vT.mid).put("trackName", "video"))
            val r = request("publish", JSONObject().put("sdp", c.pc.localDescription.description).put("tracks", tracks))
            if (gen != pubGen) throw Gone()
            c.pc.setRemote(SessionDescription(SessionDescription.Type.ANSWER, opusAnswer(r.getString("sdp"))))
            senders = aT.sender to vT.sender
        } ?: return
        if (gen != pubGen) { c.close(); return }
        pubConn = c
        aSender = senders?.first
        vSender = senders?.second
        tuneSenders()
        c.onState = { s ->
            scope.launch {
                if (gen == pubGen) {
                    if (s == PeerConnection.PeerConnectionState.FAILED) publishCam()
                    else if (s == PeerConnection.PeerConnectionState.DISCONNECTED) {
                        delay(4000)
                        if (gen == pubGen && c.state.value != PeerConnection.PeerConnectionState.CONNECTED) publishCam()
                    }
                }
            }
        }
    }

    private fun tuneSenders() { applyQuality(); applyAudioParams() }

    // =============================================================== quality control (port of the web's applyQuality / autoStep / audioStep)

    /** Video quality actually in force: Audio-first pins it to Auto (small) or Low. */
    private fun effQ(): String {
        val c = Settings.cfg.value
        return if (mode == "video") (if (c.vidPrio == "low") "ultra" else "auto") else c.quality
    }

    /** Best level the auto controller may climb to (Audio-first never goes above 360p). */
    private fun topIdx(): Int = if (mode == "video") 3 else 0

    private fun currentLevel(): Level {
        val q = effQ()
        if (q == "auto") return LEVELS[max(lvl.idx, topIdx()).coerceIn(0, LEVELS.size - 1)]
        return LEVELS.firstOrNull { it.id == q } ?: LEVELS[1]
    }

    private fun captureSizeFor(l: Level): Pair<Int, Int> = if (l.h >= 1080) 1920 to 1080 else 1280 to 720

    /** Raise the camera's capture size when a higher level needs it (lower only when quality is fixed, to avoid camera restarts in Auto). */
    private fun ensureCapture(l: Level) {
        if (!camCapturing) return
        val (cw, ch) = captureSizeFor(l)
        if (cw == capW && ch == capH) return
        if (cw < capW && effQ() == "auto") return
        try { capturer?.changeCaptureFormat(cw, ch, 30); capW = cw; capH = ch } catch (_: Throwable) {}
    }

    private fun applyAdapt(l: Level = currentLevel()) {
        try { videoSource?.adaptOutputFormat(l.w, l.h, l.fps) } catch (_: Throwable) {}
    }

    private fun applyQuality() {
        val l = currentLevel()
        ensureCapture(l)
        applyAdapt(l)
        try {
            vSender?.let { s ->
                val p = s.parameters
                p.encodings.firstOrNull()?.let { it.maxBitrateBps = l.br; it.maxFramerate = l.fps; it.active = true }
                p.degradationPreference = if (effQ() == "auto") RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
                else RtpParameters.DegradationPreference.BALANCED
                s.setParameters(p)
            }
        } catch (_: Throwable) {}
    }

    private fun audioBr(): Int {
        val top = AQ_ORDER.indexOf(Settings.cfg.value.audioQ).coerceAtLeast(0)
        return (AQ[AQ_ORDER[max(0, top - aStep)]] ?: AQ.getValue("med")).first
    }

    private fun applyAudioParams() {
        try {
            aSender?.let { s ->
                val p = s.parameters
                p.encodings.firstOrNull()?.let { it.maxBitrateBps = audioBr() }
                s.setParameters(p)
            }
        } catch (_: Throwable) {}
    }

    /** Pure auto-quality controller (drops fast on congestion, climbs slowly, waits longer after every drop). */
    private fun autoStep(st: Lvl, s: Out, now: Long, top: Int, prio: Boolean): Boolean {
        val cur = LEVELS[st.idx]
        val warm = now - st.t0 < 8000
        val avail = s.avail
        val congested = s.loss > (if (prio) 0.03 else 0.07) || s.qlr == "bandwidth" || s.rtt > (if (prio) 0.6 else 0.9) ||
            (!warm && avail != null && avail < (if (prio) cur.br * 1.6 + AUDIO_BR else cur.br * 0.7))
        if (congested) {
            st.good = 0
            if (now - st.lastChange > (if (prio) 1500 else 2500) && st.idx < LEVELS.size - 1) {
                st.idx++; st.lastChange = now; st.need = min(30, ceil(st.need * 1.5).toInt()); return true
            }
            return false
        }
        val next = LEVELS.getOrNull(st.idx - 1)
        val healthy = s.loss < 0.02 && s.rtt < 0.4 &&
            (avail == null || (next != null && avail > (if (prio) next.br * 2.2 + AUDIO_BR else next.br * 1.4)))
        if (healthy && next != null && st.idx > top) {
            if (++st.good >= st.need && now - st.lastChange > 4000) {
                st.idx--; st.good = 0; st.lastChange = now; st.need = max(5, st.need - 1); return true
            }
        } else if (!healthy) st.good = max(0, st.good - 1)
        return false
    }

    /** Audio-first modes: step the audio bitrate down fast on trouble, back up slowly (never below "low"). */
    private fun audioStep(s: Out) {
        val bad = s.loss > 0.05 || s.rtt > 0.8
        val ok = s.loss < 0.02 && s.rtt < 0.4
        val top = AQ_ORDER.indexOf(Settings.cfg.value.audioQ)
        if (bad && aStep < top) { aStep++; aGood = 0; applyAudioParams() }
        else if (ok && aStep > 0 && ++aGood >= 8) { aStep--; aGood = 0; applyAudioParams() }
        else if (!ok) aGood = 0
    }

    // =============================================================== stats (every 2 s, like the web's statsTick)

    private fun startStats() {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (true) {
                delay(2000)
                try { statsTick() } catch (e: CancellationException) { throw e } catch (_: Throwable) {}
            }
        }
    }

    private suspend fun statsOf(pc: PeerConnection): RTCStatsReport? = withTimeoutOrNull(1500L) {
        suspendCancellableCoroutine<RTCStatsReport?> { c ->
            try {
                pc.getStats(RTCStatsCollectorCallback { rep -> if (c.isActive) c.resume(rep) })
            } catch (_: Throwable) {
                if (c.isActive) c.resume(null)
            }
        }
    }

    private suspend fun sampleOut(pc: PeerConnection): Out? {
        val rep = statsOf(pc) ?: return null
        val now = System.currentTimeMillis()
        val all = rep.statsMap
        val o = Out()
        o.at = now
        var pairId: String? = null
        for (r in all.values) if (r.type == "transport") r.txt("selectedCandidatePairId")?.let { pairId = it }
        var pair: RTCStats? = null
        var vseen = false
        for (r in all.values) {
            val kind = r.txt("kind") ?: r.txt("mediaType")
            when (r.type) {
                "outbound-rtp" -> if (kind == "video") {
                    o.qlr = r.txt("qualityLimitationReason") ?: "none"
                    o.w = (r.num("frameWidth") ?: 0.0).toInt()
                    o.h = (r.num("frameHeight") ?: 0.0).toInt()
                    o.fps = (r.num("framesPerSecond") ?: 0.0).roundToInt()
                    val b = r.num("bytesSent")?.toLong()
                    if (b != null) {
                        o.bytes = b
                        if (prevBytes >= 0 && now > prevAt) o.kbps = ((b - prevBytes) * 8 / (now - prevAt)).toInt()
                    }
                }
                "remote-inbound-rtp" -> {
                    val v = kind == "video"
                    // no video sent (call-only) -> the audio report drives the controllers
                    if (v || !vseen) {
                        o.loss = r.num("fractionLost") ?: 0.0
                        r.num("roundTripTime")?.takeIf { it > 0 }?.let { o.rtt = it }
                    }
                    if (v) vseen = true
                }
                "candidate-pair" ->
                    if (r.id == pairId || (pairId == null && r.members["nominated"] == true && r.txt("state") == "succeeded")) pair = r
            }
        }
        val pr = pair
        if (pr != null) {
            pr.num("availableOutgoingBitrate")?.takeIf { it > 0 }?.let { o.avail = it }
            if (o.rtt == 0.0) pr.num("currentRoundTripTime")?.let { o.rtt = it }
            val lc = pr.txt("localCandidateId")?.let { all[it] }
            if (lc != null) {
                val ct = lc.txt("candidateType")
                o.path = if (ct == "relay") "relay" else if (ct == "srflx") "direct (STUN)" else "direct"
                o.proto = (lc.txt("relayProtocol") ?: lc.txt("protocol") ?: "").uppercase()
            }
        }
        return o
    }

    private suspend fun statsTick() {
        val c = pubConn ?: return
        if (c.state.value != PeerConnection.PeerConnectionState.CONNECTED) return
        val s = sampleOut(c.pc) ?: return
        if (pubConn !== c) return
        prevBytes = s.bytes; prevAt = s.at
        lastOut = s
        if (effQ() == "auto" && camOn && camCapturing) {
            lvl.idx = max(lvl.idx, topIdx())
            if (autoStep(lvl, s, System.currentTimeMillis(), topIdx(), mode != "off")) applyQuality()
        }
        if (mode != "off") audioStep(s)
        val l = currentLevel()
        _ui.update {
            it.copy(live = Live(s.w, s.h, s.fps, s.kbps, (s.rtt * 1000).roundToInt(), s.loss, s.path, s.proto, l.id, effQ() == "auto", effMode()))
        }
        if (Settings.cfg.value.stats) publishUi()
    }

    private fun statText(): String? {
        val s = lastOut ?: return null
        val l = currentLevel()
        return buildString {
            append(if (s.w > 0) "${s.w}×${s.h}" else "—").append(" @${s.fps}fps\n")
            append("${s.kbps} kbps · ${if (l.id == "ultra") "ultra" else l.id + "p"}${if (effQ() == "auto") " (auto)" else ""}\n")
            append("ping ${(s.rtt * 1000).roundToInt()} ms · loss ${f1(s.loss * 100)}%\n")
            append("${s.path} ${s.proto} · ${effMode()}".trim())
        }
    }

    private fun publishScreen(sc: Screen) {
        val gen = ++sc.gen
        sc.conn?.close(); sc.conn = null
        val f = sc.factory ?: return
        val v = sc.vTrack ?: return
        scope.launch {
            var vs: RtpSender? = null
            val c = establish("Screen share", f, { screen !== sc || gen != sc.gen }) { c, mode ->
                val init = RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, listOf("oc-screen"))
                val vT = c.pc.addTransceiver(v, init)
                val aT = sc.aTrack?.let { c.pc.addTransceiver(it, init) }
                c.pc.setLocal(c.pc.makeOffer())
                c.gather(mode)
                val tracks = JSONArray().put(JSONObject().put("mid", vT.mid).put("trackName", "video"))
                if (aT != null) tracks.put(JSONObject().put("mid", aT.mid).put("trackName", "audio"))
                val r = request("publish", JSONObject().put("kind", "screen")
                    .put("sdp", c.pc.localDescription.description).put("tracks", tracks))
                if (screen !== sc || gen != sc.gen) throw Gone()
                c.pc.setRemote(SessionDescription(SessionDescription.Type.ANSWER, r.getString("sdp")))
                vs = vT.sender
            } ?: return@launch
            if (screen !== sc || gen != sc.gen) { c.close(); return@launch }
            sc.conn = c
            try {
                vs?.let { s ->
                    val p = s.parameters
                    p.encodings.firstOrNull()?.let { it.maxBitrateBps = 3_000_000; it.maxFramerate = 30 }
                    p.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
                    s.setParameters(p)
                }
            } catch (_: Throwable) {}
            c.onState = { s ->
                scope.launch {
                    if (screen === sc && gen == sc.gen) {
                        if (s == PeerConnection.PeerConnectionState.FAILED) publishScreen(sc)
                        else if (s == PeerConnection.PeerConnectionState.DISCONNECTED) {
                            delay(4000)
                            if (screen === sc && gen == sc.gen && c.state.value != PeerConnection.PeerConnectionState.CONNECTED) publishScreen(sc)
                        }
                    }
                }
            }
        }
    }

    /** The SFU's answer decides how our Opus sender behaves: in-band FEC, mono, bitrate / band cap from Settings, DTX in audio-first modes. */
    private fun opusAnswer(sdp: String): String {
        val pt = Regex("a=rtpmap:(\\d+) opus/48000").find(sdp)?.groupValues?.get(1) ?: return sdp
        val q = AQ[Settings.cfg.value.audioQ] ?: AQ.getValue("med")
        val want = linkedMapOf("useinbandfec" to "1", "usedtx" to if (mode == "off") "0" else "1",
            "stereo" to "0", "maxplaybackrate" to q.second.toString(), "maxaveragebitrate" to q.first.toString())
        val re = Regex("a=fmtp:$pt ([^\\r\\n]*)")
        val m = re.find(sdp)
        val cur = linkedMapOf<String, String?>()
        m?.groupValues?.get(1)?.split(";")?.forEach { kv ->
            val p = kv.split("=", limit = 2)
            if (p[0].isNotBlank()) cur[p[0].trim()] = p.getOrNull(1)
        }
        cur.putAll(want)
        val line = "a=fmtp:$pt " + cur.entries.joinToString(";") { if (it.value == null) it.key else "${it.key}=${it.value}" }
        return if (m != null) sdp.replace(re, Regex.escapeReplacement(line))
        else sdp.replace(Regex("(a=rtpmap:$pt opus/48000[^\\r\\n]*)")) { it.value + "\r\n" + line }
    }

    // =============================================================== audio routing

    private fun audioStart() {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        prevAudioMode = am.mode
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        lastBt = btPresent(am) != null
        speaker = !lastBt            // start on the headset if one is connected, else loudspeaker
        applySpeaker()
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(a: Array<out AudioDeviceInfo>?) { onRouteChange() }
            override fun onAudioDevicesRemoved(a: Array<out AudioDeviceInfo>?) { onRouteChange() }
        }
        am.registerAudioDeviceCallback(cb, Handler(Looper.getMainLooper()))
        devCb = cb
        if (Build.VERSION.SDK_INT >= 26) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener { }
                .build()
            am.requestAudioFocus(req)
            focus = req
        }
    }

    private fun audioStop() {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        devCb?.let { am.unregisterAudioDeviceCallback(it) }
        devCb = null
        if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
        else { if (am.isBluetoothScoOn) { am.stopBluetoothSco(); am.isBluetoothScoOn = false } }
        btActive = false
        am.isSpeakerphoneOn = false
        am.mode = prevAudioMode
        if (Build.VERSION.SDK_INT >= 26) focus?.let { am.abandonAudioFocusRequest(it) }
        focus = null
    }

    private var devCb: AudioDeviceCallback? = null
    private var lastBt = false
    private var btActive = false

    private fun btPresent(am: AudioManager): AudioDeviceInfo? {
        val list: List<AudioDeviceInfo> =
            if (Build.VERSION.SDK_INT >= 31) am.availableCommunicationDevices else am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        return list.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET) ||
                (Build.VERSION.SDK_INT < 31 && it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        }
    }

    /** Headset plugged in / connected / removed mid-call: follow it (headset on connect, loudspeaker on disconnect). */
    private fun onRouteChange() {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val bt = btPresent(am) != null
        if (bt != lastBt) { lastBt = bt; speaker = !bt }
        applySpeaker()
        publishUi()
    }

    private fun applySpeaker() {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val bt = btPresent(am)
        btActive = bt != null && !speaker
        if (Build.VERSION.SDK_INT >= 31) {
            val devs = am.availableCommunicationDevices
            val want = when {
                speaker -> devs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                bt != null -> bt
                else -> devs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
            }
            if (want != null) am.setCommunicationDevice(want) else am.clearCommunicationDevice()
        } else if (btActive) {
            am.isSpeakerphoneOn = false
            if (!am.isBluetoothScoOn) { am.startBluetoothSco(); am.isBluetoothScoOn = true }
        } else {
            if (am.isBluetoothScoOn) { am.stopBluetoothSco(); am.isBluetoothScoOn = false }
            am.isSpeakerphoneOn = speaker
        }
    }

    // =============================================================== teardown

    private fun cleanup() {
        joinJob?.cancel(); joinJob = null
        pingJob?.cancel(); tickJob?.cancel(); statsJob?.cancel(); statsJob = null; lastOut = null
        stopScreen()
        for (r in remotes.values) closeRemote(r)
        remotes.clear(); peers.clear()
        pubGen++
        pubConn?.close(); pubConn = null
        stopCamera()
        try { audioTrack?.dispose() } catch (_: Throwable) {}
        try { videoTrack?.dispose() } catch (_: Throwable) {}
        try { audioSource?.dispose() } catch (_: Throwable) {}
        try { videoSource?.dispose() } catch (_: Throwable) {}
        audioTrack = null; videoTrack = null; audioSource = null; videoSource = null
        aSender = null; vSender = null
        try { ws?.close(1000, "bye") } catch (_: Exception) {}
        ws = null; wsUp = false
        failPending()
        welcomeWait?.let { if (it.isActive) it.cancel() }
        welcomeWait = null
        joined = false
        audioStop()
    }

    private fun startTicker() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (true) { delay(10_000); send(JSONObject().put("t", "ping").put("ts", System.currentTimeMillis())) }
        }
        startStats()
    }

    private fun toast(msg: String) { events.tryEmit(msg) }

    // =============================================================== UI state

    private fun publishUi() {
        if (_ui.value.phase == Phase.Idle) return
        val tiles = ArrayList<Tile>()
        for (r in remotes.values.filter { it.screen }) {
            val p = peers[r.owner] ?: continue
            tiles += Tile(r.key, p.name, r.video, false, true, true, true, false, r.video == null)
        }
        for (p in peers.values) {
            val r = remotes[p.pid]
            val showVideo = p.cam && mode != "call"
            tiles += Tile(p.pid, p.name, if (showVideo) r?.video else null, false, p.mic, showVideo, false, false,
                r != null && r.sessionId != null && r.video == null && showVideo)
        }
        val sc = screen
        val cfg = Settings.cfg.value
        if (sc != null) tiles.add(0, Tile("me:screen", "Your screen", null, false, true, false, true, true, false,
            note = if (sc.withAudio) "Your phone’s sound is shared too" else "Your phone’s sound is not shared"))
        val camActive = camOn && camAllowed && visible && mode != "call"
        tiles += Tile("me", "You", if (camActive) videoTrack else null, frontFacing && cfg.mirror, micOn, camActive, false, true, false,
            stat = if (cfg.stats) statText() else null)
        val canFlip = try { Camera2Enumerator(app).deviceNames.size > 1 } catch (_: Throwable) { false }
        _ui.update {
            it.copy(tiles = tiles, micOn = micOn, camOn = camOn, camAllowed = camAllowed, canFlip = canFlip,
                speaker = speaker, sharing = sc != null, locked = locked, waiting = waiting, mode = mode,
                quotaLeft = quotaLeft, quotaAt = quotaAt, pinned = pinned, bt = btActive, front = frontFacing)
        }
    }
}
