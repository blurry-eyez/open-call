package xyz.s2105.opencall

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

// ------------------------------------------------------------------ web palette (static/index.html :root)

private val Bg0 = Color(0xFF15111F)
private val Bg1 = Color(0xFF201A30)
private val Bg2 = Color(0xFF272040)
private val Bg3 = Color(0xFF2F2750)
private val Bg4 = Color(0xFF3A3162)
private val TextC = Color(0xFFF2F3F5)
private val Muted = Color(0xFFB5BAC1)
private val Faint = Color(0xFF80848E)
private val Blurple = Color(0xFF7A5CFF)
private val Green = Color(0xFF23A55A)
private val Red = Color(0xFFF23F43)
private val Yellow = Color(0xFFF0B232)
private val SoftRed = Color(0xFFFF8486)
private val Glass = Color(0xB31D1730)      // #1d1730b3  top-bar pills
private val CardBg = Color(0xC71D1730)     // #1d1730c7  login / join card
private val TileBg = Color(0xD91A1430)     // #1a1430d9  video tile

@Composable
fun OpenCallTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Blurple, onPrimary = Color.White, background = Bg0, surface = Bg1,
            onBackground = TextC, onSurface = TextC, error = Red,
        ),
    ) {
        // Material3 falls back to BLACK content colour outside a Surface; that was the black text. Pin it to the web text colour.
        CompositionLocalProvider(LocalContentColor provides TextC, content = content)
    }
}

// ------------------------------------------------------------------ root

@Composable
fun AppRoot(
    pip: Boolean,
    activity: Activity,
    onShare: () -> Unit,
    onEnterPip: () -> Unit,
) {
    val auth by Auth.ui.collectAsState()
    val call by CallEngine.ui.collectAsState()
    Box(Modifier.fillMaxSize().background(Bg0)) {
        if (call.phase == Phase.Idle) SceneBg(lobbyDim)
        when {
            call.phase != Phase.Idle -> CallScreen(call, pip, activity, onShare, onEnterPip)
            auth.phase == "user" -> JoinScreen(auth, call.error)
            auth.phase == "pending" -> SignupScreen(auth)
            auth.phase == "loading" -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Blurple) }
            else -> LoginScreen(auth, activity)
        }
        if (!pip) ToastHost()
    }
}

private val lobbyDim = Brush.verticalGradient(0f to Color(0x1A120E20), 0.58f to Color(0x2E120E20), 1f to Color(0x4D120E20))
private val callDim = Brush.verticalGradient(listOf(Color(0x88120E20), Color(0xD0120E20)))

/** The web's winter-night scene (portrait poster, landscape poster on wide screens) with its dimming gradient. */
@Composable
private fun SceneBg(dim: Brush) {
    val cfg = LocalConfiguration.current
    val land = cfg.screenWidthDp > cfg.screenHeightDp
    Image(painterResource(if (land) R.drawable.bg_call_land else R.drawable.bg_login), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    Box(Modifier.fillMaxSize().background(dim))
}

// ------------------------------------------------------------------ toasts (web .toast)

private class ToastMsg(val id: Long, val text: String)

@Composable
private fun ToastHost() {
    var items by remember { mutableStateOf(listOf<ToastMsg>()) }
    LaunchedEffect(Unit) {
        CallEngine.events.collect { msg ->
            val t = ToastMsg(System.nanoTime(), msg)
            items = (items + t).takeLast(3)
            launch { delay(3200); items = items.filter { it.id != t.id } }
        }
    }
    Column(
        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (t in items) {
            val bad = Regex("fail|could not|couldn't|unavailable|denied|needed|blocked|error", RegexOption.IGNORE_CASE).containsMatchIn(t.text)
            Row(
                Modifier.fillMaxWidth(0.92f).widthIn(max = 420.dp).height(IntrinsicSize.Min)
                    .shadow(10.dp, RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp)).background(Bg1),
            ) {
                Box(Modifier.width(4.dp).fillMaxHeight().background(if (bad) Red else Blurple))
                Text(t.text, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
    }
}

// ------------------------------------------------------------------ shared web-style pieces

@Composable
private fun CardColumn(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).padding(20.dp), contentAlignment = Alignment.Center) {
        val shape = RoundedCornerShape(18.dp)
        Column(
            Modifier.fillMaxWidth().widthIn(max = 440.dp).verticalScroll(rememberScrollState())
                .shadow(24.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(shape).background(CardBg).border(1.dp, Color(0x1AFFFFFF), shape).padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
    }
}

/** .logo : 30% rounded square, 145deg #8a6bff -> #ff4fb8, pink glow, white camera glyph. */
@Composable
private fun Logo(size: Int = 52) {
    val shape = RoundedCornerShape(30)
    Box(
        Modifier.size(size.dp)
            .shadow(14.dp, shape, ambientColor = Color(0xFFFF4FB8), spotColor = Color(0xFFFF4FB8))
            .clip(shape).background(Brush.linearGradient(listOf(Color(0xFF8A6BFF), Color(0xFFFF4FB8))))
            .border(1.dp, Brush.verticalGradient(listOf(Color(0x66FFFFFF), Color.Transparent)), shape),
        contentAlignment = Alignment.Center,
    ) { Icon(WebIcons.Cam, null, tint = Color.White, modifier = Modifier.size((size * 0.54f).dp)) }
}

@Composable
private fun Title(text: String) = Text(text, fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.01).em, textAlign = TextAlign.Center)

@Composable
private fun Sub(text: String) = Text(text, color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center)

@Composable
private fun Foot(text: String) = Text(text, color = Faint, fontSize = 12.sp, textAlign = TextAlign.Center)

@Composable
private fun Msg(text: String, color: Color = SoftRed) = Text(text, color = color, fontSize = 14.sp, textAlign = TextAlign.Center)

/** label.f + .in : uppercase label above a dark rounded input with a blurple focus border. */
@Composable
private fun WebField(
    label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier,
    placeholder: String = "", code: Boolean = false, keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Text(label.uppercase(), color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.04.em, modifier = Modifier.padding(bottom = 8.dp))
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true, keyboardOptions = keyboardOptions,
            textStyle = TextStyle(color = TextC, fontSize = 16.sp, fontWeight = if (code) FontWeight.SemiBold else FontWeight.Normal, letterSpacing = if (code) 0.12.em else 0.em),
            cursorBrush = SolidColor(Blurple),
            modifier = Modifier.fillMaxWidth().height(44.dp).onFocusChanged { focused = it.isFocused },
            decorationBox = { inner ->
                Box(
                    Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(Bg0)
                        .border(1.dp, if (focused) Blurple else Color.Transparent, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, color = Faint, fontSize = 16.sp)
                    inner()
                }
            },
        )
    }
}

/** .btn.primary : purple -> magenta gradient. */
@Composable
private fun PrimaryButton(text: String, enabled: Boolean = true, busy: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(44.dp).alpha(if (enabled) 1f else 0.5f).clip(RoundedCornerShape(8.dp))
            .background(Brush.linearGradient(listOf(Blurple, Color(0xFFD14CFF))))
            .clickable(enabled = enabled && !busy, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
        else Text(text, color = Color.White, fontWeight = FontWeight.SemiBold)
    }
}

/** .btn.sm */
@Composable
private fun SmallButton(text: String, bg: Color = Bg4, fg: Color = TextC, onClick: () -> Unit) {
    Box(
        Modifier.height(34.dp).clip(RoundedCornerShape(8.dp)).background(bg).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun Avatar(name: String, sizeDp: Int, alpha: Float = 1f) {
    var h = 0L
    for (ch in name) h = (h * 31 + ch.code) and 0xFFFFFFFFL     // same hash as the web's hue()
    val hue = (h % 360).toFloat()
    Box(
        Modifier.size(sizeDp.dp).alpha(alpha).clip(CircleShape).background(Color.hsl(hue, 0.45f, 0.42f)),
        contentAlignment = Alignment.Center,
    ) { Text(name.trim().take(1).uppercase().ifEmpty { "?" }, fontSize = (sizeDp * 0.45).sp, fontWeight = FontWeight.Bold, color = Color.White) }
}

// ------------------------------------------------------------------ login / signup / join

@Composable
private fun LoginScreen(a: AuthUi, activity: Activity) {
    CardColumn {
        Logo()
        Spacer(Modifier.height(14.dp))
        Title("Open Call")
        Spacer(Modifier.height(4.dp))
        Sub("Log in with Google to start or join a video call.")
        Spacer(Modifier.height(24.dp))
        a.notice?.let { Msg(it, if (a.noticeError) SoftRed else Color(0xFF4DDC8A)); Spacer(Modifier.height(12.dp)) }
        if (a.offline) { Msg("Can't reach the server. Check your connection."); Spacer(Modifier.height(12.dp)) }
        if (!a.configured) { Msg("Google login isn't set up on the server yet."); Spacer(Modifier.height(12.dp)) }
        // .gbtn.g1
        Row(
            Modifier.fillMaxWidth().height(44.dp).alpha(if (a.busy) 0.6f else 1f).clip(RoundedCornerShape(8.dp)).background(Color(0xFFF3E9D2))
                .clickable(enabled = !a.busy, role = Role.Button) { Auth.signIn(activity) },
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            if (a.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color(0xFF2A2233))
            else Image(rememberVectorPainter(WebIcons.GoogleG), null, Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text("Sign in with Google", color = Color(0xFF2A2233), fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(14.dp))
        Foot("New here? Signing in creates your account. You'll be asked for a phone number once.")
    }
}

@Composable
private fun SignupScreen(a: AuthUi) {
    val p = a.pending ?: return
    var name by remember(p) { mutableStateOf(p.name) }
    var phone by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    CardColumn {
        Avatar(p.name, 72)
        Spacer(Modifier.height(12.dp))
        Title("Finish signing up")
        Spacer(Modifier.height(4.dp))
        Sub(p.email)
        Spacer(Modifier.height(24.dp))
        WebField("Your name", name, { name = it.take(24) })
        Spacer(Modifier.height(16.dp))
        WebField("Phone number", phone, { phone = it }, placeholder = "+91 98765 43210", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
        Spacer(Modifier.height(16.dp))
        PrimaryButton("Create account", enabled = phone.isNotBlank(), busy = busy) {
            busy = true; err = null
            scope.launch { err = Auth.signup(name.trim(), phone.trim()); busy = false }
        }
        err?.let { Spacer(Modifier.height(12.dp)); Msg(it) }
        Spacer(Modifier.height(14.dp))
        Foot("Google doesn't share your number, so we ask once. Each number can only be used for one account.")
        Spacer(Modifier.height(10.dp))
        Text("Use a different Google account", color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center,
            modifier = Modifier.clickable { Auth.logout() }.padding(4.dp))
    }
}

@Composable
private fun JoinScreen(a: AuthUi, callError: String?) {
    val u = a.user ?: return
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE) }
    val link by Store.roomLink.collectAsState()
    var code by remember { mutableStateOf("") }
    var name by remember(u) { mutableStateOf(prefs.getString("name", null) ?: u.name) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(link) { link?.let { code = it; Store.roomLink.value = null } }
    LaunchedEffect(callError) { if (callError != null) { err = callError; CallEngine.clearError() } }

    fun start(camOk: Boolean) {
        prefs.edit().putString("name", name.trim()).apply()
        CallService.start(ctx, code.trim(), name.trim().ifEmpty { u.name }, camOk)
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val mic = res[Manifest.permission.RECORD_AUDIO] == true || granted(ctx, Manifest.permission.RECORD_AUDIO)
        val cam = res[Manifest.permission.CAMERA] == true || granted(ctx, Manifest.permission.CAMERA)
        if (!mic) err = "Microphone permission is needed to join a call." else start(cam)
    }

    CardColumn {
        Logo()
        Spacer(Modifier.height(14.dp))
        Title("Open Call")
        Spacer(Modifier.height(4.dp))
        Sub("Type the same pairing code as your friends to start a video call.")
        Spacer(Modifier.height(24.dp))
        // .meRow
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Bg0).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(u.name, 36)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(u.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(u.email, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            SmallButton("Log out") { Auth.logout() }
        }
        Spacer(Modifier.height(18.dp))
        WebField("Your name", name, { name = it.take(24) }, placeholder = "What should we call you?")
        Spacer(Modifier.height(16.dp))
        WebField("Pairing code", code, { code = it.take(40); err = null }, placeholder = "e.g. sunny-river-42", code = true)
        err?.let { Spacer(Modifier.height(12.dp)); Msg(it) }
        Spacer(Modifier.height(20.dp))
        PrimaryButton("Join call", enabled = code.trim().length >= 3) {
            err = null
            val need = ArrayList<String>()
            if (!granted(ctx, Manifest.permission.RECORD_AUDIO)) need += Manifest.permission.RECORD_AUDIO
            if (!granted(ctx, Manifest.permission.CAMERA)) need += Manifest.permission.CAMERA
            if (Build.VERSION.SDK_INT >= 33 && !granted(ctx, Manifest.permission.POST_NOTIFICATIONS)) need += Manifest.permission.POST_NOTIFICATIONS
            // Android 12+: without this runtime grant the app cannot see or route audio to Bluetooth earphones
            if (Build.VERSION.SDK_INT >= 31 && !granted(ctx, Manifest.permission.BLUETOOTH_CONNECT)) need += Manifest.permission.BLUETOOTH_CONNECT
            if (need.isEmpty()) start(true) else launcher.launch(need.toTypedArray())
        }
        Spacer(Modifier.height(14.dp))
        Foot("Anyone who types the same code joins the same call. Pick something hard to guess.")
    }
}

private fun granted(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

// ------------------------------------------------------------------ call

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CallScreen(c: CallUi, pip: Boolean, activity: Activity, onShare: () -> Unit, onEnterPip: () -> Unit) {
    var fs by rememberSaveable { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var waitPop by remember { mutableStateOf(false) }
    var sharePop by remember { mutableStateOf(false) }
    var idle by remember { mutableStateOf(false) }
    var touch by remember { mutableIntStateOf(0) }
    val inCall = c.phase == Phase.InCall

    BackHandler(enabled = inCall) { if (fs) fs = false else onEnterPip() }
    BackHandler(enabled = inCall && (settings || waitPop || sharePop)) { settings = false; waitPop = false; sharePop = false }

    // Full screen = hide the system bars too; they come back on exit / leaving the call.
    DisposableEffect(fs && inCall) {
        val ctl = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (fs && inCall) ctl.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { ctl.show(WindowInsetsCompat.Type.systemBars()) }
    }
    // controls fade out 3.5 s after the last touch while in full screen (web wake()/idle)
    LaunchedEffect(fs, touch, settings, waitPop, sharePop) {
        idle = false
        if (fs && !settings && !waitPop && !sharePop) { delay(3500); idle = true }
    }
    LaunchedEffect(c.waiting.isEmpty()) { if (c.waiting.isEmpty()) waitPop = false }

    if (!inCall) {
        SceneBg(lobbyDim)
        CardColumn {
            CircularProgressIndicator(color = Blurple)
            Spacer(Modifier.height(16.dp))
            Text(if (c.phase == Phase.Waiting) "Waiting for someone to let you in…" else "Connecting…", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            SmallButton("Cancel") { CallEngine.leave() }
        }
        return
    }
    if (pip) { PipView(c); return }
    // tapping Share first asks about sound (like the browser's "Share audio" box), then opens Android's capture dialog
    val askShare: () -> Unit = { sharePop = true; waitPop = false }

    Box(
        Modifier.fillMaxSize().background(if (fs) Color.Black else Color.Transparent)
            .pointerInput(Unit) {
                awaitPointerEventScope { while (true) { awaitPointerEvent(PointerEventPass.Initial); touch++ } }
            },
    ) {
        if (!fs) {
            SceneBg(callDim)
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
                TopBar(c)
                Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp)) { Stage(c, false) }
                Dock(c, false, askShare, onEnterPip, { settings = true }, { waitPop = !waitPop }, { fs = true })
            }
        } else {
            Box(Modifier.fillMaxSize()) { Stage(c, true) }
            AnimatedVisibility(!idle, Modifier.align(Alignment.TopCenter), enter = fadeIn() + slideInVertically { -it }, exit = fadeOut() + slideOutVertically { -it }) {
                TopBar(c, Modifier.background(Brush.verticalGradient(listOf(Color(0xBB000000), Color.Transparent))).windowInsetsPadding(WindowInsets.safeDrawing))
            }
            AnimatedVisibility(!idle, Modifier.align(Alignment.BottomCenter), enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it }) {
                Dock(c, true, askShare, onEnterPip, { settings = true }, { waitPop = !waitPop }, { fs = false },
                    Modifier.background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))).windowInsetsPadding(WindowInsets.safeDrawing))
            }
        }
        if (waitPop) WaitingPop(c) { waitPop = false }
        if (sharePop) SharePop(onStart = { sharePop = false; onShare() }, onDismiss = { sharePop = false })
        if (settings) SettingsSheet(c) { settings = false }
    }
}

@Composable
private fun TopBar(c: CallUi, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val cfg = LocalConfiguration.current
    Row(
        modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // .room
        Row(
            Modifier.height(32.dp).widthIn(max = (cfg.screenWidthDp * 0.55f).dp).clip(RoundedCornerShape(8.dp)).background(Glass).padding(start = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(c.code, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, letterSpacing = 0.04.em, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Box(
                Modifier.size(26.dp).clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button) {
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText("pairing code", c.code))
                    Toast.makeText(ctx, "Pairing code copied", Toast.LENGTH_SHORT).show()
                },
                contentAlignment = Alignment.Center,
            ) { Icon(WebIcons.Copy, "Copy pairing code", tint = Muted, modifier = Modifier.size(16.dp)) }
        }
        // .count
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(WebIcons.Users, null, tint = Muted, modifier = Modifier.size(16.dp))
            Text("${c.tiles.count { !it.screen }}", color = Muted, fontSize = 13.sp)
        }
        Spacer(Modifier.weight(1f))
        // .qpill
        c.quotaLeft?.let { q ->
            val now by produceState(System.currentTimeMillis()) { while (true) { delay(1000); value = System.currentTimeMillis() } }
            val left = max(0, q - ((now - c.quotaAt) / 1000).toInt())
            val col = if (left <= 60) SoftRed else if (left <= 180) Yellow else Muted
            Row(
                Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(Glass).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(WebIcons.Clock, null, tint = col, modifier = Modifier.size(14.dp))
                Text("%d:%02d free".format(left / 60, left % 60), color = col, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        // .net
        Row(
            Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(Glass).padding(horizontal = if (cfg.screenWidthDp > 560) 12.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val lv = c.live
            val bad = lv != null && (lv.loss > 0.07 || lv.rttMs > 800)
            val warn = lv != null && (lv.loss > 0.02 || lv.rttMs > 400)
            val netCol = if (c.reconnecting) Yellow else if (bad) Red else if (warn) Yellow else Green
            val netTxt = when {
                c.reconnecting -> "Reconnecting…"
                lv == null -> "Connected"
                else -> (if (bad) "Poor" else if (warn) "Fair" else "Connected") + (if (lv.rttMs > 0) " · ${lv.rttMs} ms" else "")
            }
            Box(Modifier.size(9.dp).clip(CircleShape).background(netCol))
            if (cfg.screenWidthDp > 560) Text(netTxt, color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** The video area: grid, spotlight, or (full screen) one person big with the others in a floating bar. */
@Composable
private fun Stage(c: CallUi, fs: Boolean) {
    val spot = c.tiles.firstOrNull { it.id == c.pinned }
        ?: c.tiles.firstOrNull { it.screen && !it.local }
        ?: (if (fs) c.tiles.firstOrNull { !it.local } else null)
    Box(Modifier.fillMaxSize()) {
        if (spot != null) Spotlight(c, spot, fs) else TileGrid(c, fs)
        if (c.tiles.count { !it.screen } == 1) Text(
            "Waiting for others to join this code…", color = Muted, fontSize = 13.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = if (fs) 110.dp else 12.dp).clip(RoundedCornerShape(16.dp)).background(Bg1).padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun TileGrid(c: CallUi, fs: Boolean) {
    val tiles = c.tiles
    val n = tiles.size
    val gap = if (fs) 0.dp else 8.dp
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val portrait = maxHeight > maxWidth
        val cols = when {
            n <= 1 -> 1
            portrait -> if (n == 2) 1 else 2
            else -> ceil(sqrt(n.toFloat())).toInt()
        }
        val rows = ceil(n / cols.toFloat()).toInt()
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
            for (r in 0 until rows) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    for (k in 0 until cols) {
                        val i = r * cols + k
                        if (i < n) TileView(tiles[i], Modifier.weight(1f).fillMaxHeight(), corner = if (fs) 0.dp else 12.dp) { CallEngine.pin(tiles[i].id) }
                        else Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun Spotlight(c: CallUi, main: Tile, fs: Boolean) {
    val rest = c.tiles.filter { it.id != main.id }
    if (!fs) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TileView(main, Modifier.weight(1f).fillMaxWidth()) { CallEngine.pin(main.id) }
            if (rest.isNotEmpty()) LazyRow(Modifier.height(110.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(rest, key = { it.id }) { t -> TileView(t, Modifier.width(150.dp).fillMaxHeight(), small = true) { CallEngine.pin(t.id) } }
            }
        }
    } else {
        Box(Modifier.fillMaxSize()) {
            TileView(main, Modifier.fillMaxSize(), corner = 0.dp) { CallEngine.pin(main.id) }
            // #strip in full screen: translucent rounded bar floating over the video
            if (rest.isNotEmpty()) LazyColumn(
                Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 56.dp, end = 8.dp)
                    .width(108.dp).heightIn(max = 380.dp).clip(RoundedCornerShape(18.dp)).background(Color(0x70000000)).padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(rest, key = { it.id }) { t -> TileView(t, Modifier.fillMaxWidth().height(120.dp), small = true) { CallEngine.pin(t.id) } }
            }
        }
    }
}

@Composable
private fun TileView(t: Tile, modifier: Modifier, corner: Dp = 12.dp, small: Boolean = false, onClick: () -> Unit) {
    val hasVideo = t.video != null && t.cam
    val selfShare = !hasVideo && t.screen && t.local
    val noCam = !hasVideo && !selfShare
    Box(
        modifier.clip(RoundedCornerShape(corner))
            .background(if (noCam) Color(0x401A1430) else TileBg)   // .tile / .tile.nocam
            .clickable(onClick = onClick),
    ) {
        when {
            hasVideo -> VideoView(t.video, mirror = t.mirror, fit = t.screen, modifier = Modifier.fillMaxSize())
            selfShare -> Column(
                Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0x2623A55A), Bg1))).padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(if (small) 4.dp else 12.dp, Alignment.CenterVertically),
            ) {
                Icon(WebIcons.Screen, null, tint = Color(0xFF4DDC8A), modifier = Modifier.size(if (small) 20.dp else 34.dp))
                Text("You’re sharing your screen", fontWeight = FontWeight.SemiBold, fontSize = if (small) 11.sp else 15.sp, textAlign = TextAlign.Center)
                if (!small && t.note != null) Text(t.note, color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center)
                if (!small) Box(
                    Modifier.height(34.dp).clip(RoundedCornerShape(8.dp)).background(Red).clickable(role = Role.Button) { CallEngine.stopScreen() }.padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("Stop sharing", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            }
            else -> Box(Modifier.align(Alignment.Center)) { Avatar(t.name, if (small) 40 else 64, alpha = 0.25f) }   // .tile.nocam .avatar{opacity:.25}
        }
        t.stat?.let { st ->
            Text(
                st, color = Color.White, fontSize = 10.sp, lineHeight = 13.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xAA000000)).padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
        if (t.connecting) Row(
            Modifier.align(Alignment.TopCenter).padding(top = 10.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xBB000000)).padding(start = 9.dp, end = if (small) 9.dp else 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp, color = Color.White, trackColor = Color(0x33FFFFFF))
            if (!small) Text("Connecting…", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        // .label
        Row(
            Modifier.align(Alignment.BottomStart).padding(if (small) 5.dp else 8.dp).clip(RoundedCornerShape(6.dp))
                .background(if (t.screen) Color(0xD91A7F43) else Color(0xAA000000))
                .padding(start = if (small) 5.dp else 7.dp, end = if (small) 6.dp else 9.dp, top = if (small) 2.dp else 3.dp, bottom = if (small) 2.dp else 3.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (!t.mic && !t.screen) Icon(WebIcons.MicOff, null, tint = Red, modifier = Modifier.size(15.dp))
            Text(
                if (t.screen && !t.local) "${t.name}’s screen" else t.name,
                fontSize = if (small) 11.sp else 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PipView(c: CallUi) {
    val t = c.tiles.firstOrNull { it.id == c.pinned } ?: c.tiles.firstOrNull { it.screen && !it.local }
        ?: c.tiles.firstOrNull { !it.local && it.video != null } ?: c.tiles.firstOrNull { !it.local } ?: c.tiles.lastOrNull()
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        if (t?.video != null && t.cam) VideoView(t.video, t.mirror, t.screen, Modifier.fillMaxSize())
        else Avatar(t?.name ?: "?", 56)
        if (t != null) Text(t.name, fontSize = 11.sp, color = Color.White, modifier = Modifier.align(Alignment.BottomStart).padding(4.dp))
    }
}

// ------------------------------------------------------------------ dock (web .dock / .ctl)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Dock(
    c: CallUi, fs: Boolean, onShare: () -> Unit, onPip: () -> Unit,
    onSettings: () -> Unit, onWait: () -> Unit, onFs: () -> Unit, modifier: Modifier = Modifier,
) {
    val cfg = LocalConfiguration.current
    val ctl = minOf(cfg.screenWidthDp * 0.11f, cfg.screenHeightDp * 0.075f).coerceIn(42f, 56f).dp   // --ctl
    val gap = (cfg.screenWidthDp * 0.016f).coerceIn(6f, 14f).dp
    val camOn = c.camOn && c.camAllowed
    FlowRow(
        modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(gap),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Ctl(if (c.micOn) WebIcons.Mic else WebIcons.MicOff, "Mute microphone", ctl, Color(0xFFB06CFF), Color(0xFFFF4FB8), off = !c.micOn) { CallEngine.toggleMic() }
        Ctl(if (c.speaker) WebIcons.Vol else if (c.bt) Icons.Filled.BluetoothAudio else Icons.Filled.PhoneInTalk, "Speaker, earpiece or Bluetooth", ctl, Color(0xFF35D0FF), Color(0xFF4B7BFF), off = !c.speaker && !c.bt) { CallEngine.toggleSpeaker() }
        Ctl(if (camOn) WebIcons.Cam else WebIcons.CamOff, "Camera on or off", ctl, Color(0xFF3DF2A6), Color(0xFF12B5C9), off = !camOn) { CallEngine.toggleCam() }
        if (c.sharing) Ctl(WebIcons.Screen, "Stop sharing your screen", ctl, Color(0xFF43E97B), Color(0xFF10B981)) { CallEngine.stopScreen() }
        else Ctl(WebIcons.Screen, "Share your screen", ctl, Color(0xFFFFB347), Color(0xFFFF4F8B)) { onShare() }
        Ctl(WebIcons.Pip, "Float people in a small window", ctl, Color(0xFFFFD36E), Color(0xFFFF8A4B)) { onPip() }
        if (c.canFlip && camOn) Ctl(WebIcons.Flip, "Flip camera", ctl, Color(0xFF7DF0FF), Color(0xFFA064FF)) { CallEngine.flipCamera() }
        if (c.waiting.isNotEmpty()) Ctl(WebIcons.Wait, "People waiting to join", ctl, Color(0xFFFF8AD8), Color(0xFFFF5A7A), badge = c.waiting.size) { onWait() }
        Ctl(if (c.locked) WebIcons.Lock else WebIcons.Unlock, if (c.locked) "Unlock room" else "Lock room: nobody new can join", ctl, Color(0xFF6EE7B7), Color(0xFF3B9BFF), off = c.locked) { CallEngine.setLock(!c.locked) }
        if (fs) Ctl(WebIcons.FsX, "Exit full screen", ctl, Color(0xFF43E97B), Color(0xFF10B981)) { onFs() }
        else Ctl(WebIcons.Fs, "Full screen", ctl, Color(0xFF9BE15D), Color(0xFF00B09B)) { onFs() }
        Ctl(WebIcons.Gear, "Settings", ctl, Color(0xFF8A9BFF), Color(0xFF6A4DFF)) { onSettings() }
        Ctl(WebIcons.Leave, "Leave call", ctl, Color(0xFFFF6A7D), Color(0xFFE3254F), wide = true) { CallEngine.leave() }
    }
}

/** .ctl : 30% rounded gradient tile, coloured glow, inner top highlight; .ctl.off = dark with red ring. */
@Composable
private fun Ctl(
    icon: ImageVector, desc: String, size: Dp, g1: Color, g2: Color,
    off: Boolean = false, wide: Boolean = false, badge: Int = 0, onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(percent = if (wide) 46 else 30)
    Box(Modifier.height(size).width(if (wide) size * 1.3f else size)) {
        Box(
            Modifier.fillMaxSize()
                .shadow(if (off) 4.dp else 8.dp, shape, clip = false, ambientColor = if (off) Color(0xFFFF4F6E) else g2, spotColor = if (off) Color(0xFFFF4F6E) else g2)
                .clip(shape)
                .background(if (off) SolidColor(Color(0xFF241D38)) else Brush.linearGradient(listOf(g1, g2)))
                .border(
                    if (off) 2.dp else 1.dp,
                    if (off) SolidColor(Color(0xFFFF4F6E)) else Brush.verticalGradient(listOf(Color(0x59FFFFFF), Color.Transparent)),
                    shape,
                )
                .clickable(role = Role.Button, onClickLabel = desc, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, desc, tint = if (off) Color(0xFFFF7B92) else Color.White, modifier = Modifier.size(size * 0.44f)) }
        if (badge > 0) Box(
            Modifier.align(Alignment.TopEnd).offset(5.dp, (-5).dp).height(20.dp).widthIn(min = 20.dp).clip(RoundedCornerShape(10.dp)).background(Color.White).padding(horizontal = 5.dp),
            contentAlignment = Alignment.Center,
        ) { Text("$badge", color = Color(0xFFE3254F), fontSize = 12.sp, fontWeight = FontWeight.Bold) }
    }
}

// ------------------------------------------------------------------ popovers / sheet

@Composable
private fun WaitingPop(c: CallUi, onDismiss: () -> Unit) {
    val none = remember { MutableInteractionSource() }
    Box(Modifier.fillMaxSize().clickable(interactionSource = none, indication = null, onClick = onDismiss)) {
        Column(
            Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(bottom = 88.dp)
                .fillMaxWidth(0.92f).widthIn(max = 330.dp).shadow(18.dp, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)).background(Bg1)
                .clickable(interactionSource = none, indication = null) {}.padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Text("WAITING TO JOIN", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.05.em, modifier = Modifier.padding(bottom = 8.dp))
            for (w in c.waiting) Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(w.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Bg4).clickable { CallEngine.admit(w.wid, false) }, Alignment.Center) { Icon(WebIcons.X, "Deny", tint = Color(0xFFFF7B92), modifier = Modifier.size(20.dp)) }
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1FA463)).clickable { CallEngine.admit(w.wid, true) }, Alignment.Center) { Icon(WebIcons.Check, "Admit", tint = Color.White, modifier = Modifier.size(20.dp)) }
            }
            if (c.waiting.size > 1) Box(
                Modifier.padding(top = 6.dp).fillMaxWidth().height(38.dp).clip(RoundedCornerShape(8.dp)).background(Bg4)
                    .clickable { c.waiting.forEach { CallEngine.admit(it.wid, true) } },
                Alignment.Center,
            ) { Text("Let everyone in", fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun SectionHead(text: String) =
    Text(text.uppercase(), color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.05.em, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))

@Composable
private fun Hint(text: String) = Text(text, color = Muted, fontSize = 12.5.sp, modifier = Modifier.padding(top = 8.dp))

@Composable
private fun LiveLine(text: String) = Text(text, color = Color(0xFF9EE3B8), fontSize = 12.5.sp, modifier = Modifier.padding(top = 8.dp))

/** Segmented control (web .seg). [perRow] wraps long lists onto a second line so labels never get squeezed. */
@Composable
private fun Seg(options: List<Pair<String, String>>, value: String, perRow: Int = options.size, onPick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(Bg0).padding(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        for (row in options.chunked(perRow)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for ((id, label) in row) {
                    val sel = id == value
                    Box(
                        Modifier.weight(1f).height(34.dp).clip(RoundedCornerShape(7.dp)).background(if (sel) Blurple else Color.Transparent).clickable(role = Role.RadioButton) { onPick(id) },
                        Alignment.Center,
                    ) { Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (sel) Color.White else Muted, maxLines = 1, softWrap = false) }
                }
                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Tg(on: Boolean, enabled: Boolean = true, onToggle: () -> Unit) {
    val x by animateDpAsState(if (on) 21.dp else 3.dp, label = "tg")
    Box(
        Modifier.size(width = 42.dp, height = 24.dp).alpha(if (enabled) 1f else 0.4f).clip(RoundedCornerShape(12.dp))
            .background(if (on) Green else Bg4).clickable(enabled = enabled, role = Role.Switch, onClick = onToggle),
    ) {
        Box(Modifier.offset(x, 3.dp).size(18.dp).clip(CircleShape).background(Color.White))
    }
}

/** Web .sw row: title + small grey line on the left, switch on the right. */
@Composable
private fun ToggleRow(title: String, sub: String, on: Boolean, enabled: Boolean = true, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(sub, color = Muted, fontSize = 12.5.sp)
        }
        Tg(on, enabled, onToggle)
    }
}

private val AMODE_HINTS = mapOf(
    "off" to "Standard call. Video quality adapts to your network.",
    "call" to "Voice call only: no camera or screen share, and other people’s video is not downloaded. Least data, and audio stays smooth even on very slow networks.",
    "video" to "Video call that protects audio: video stays small (Auto or Low) and audio gets extra smoothing.",
)

private val CONN_HINTS = mapOf(
    "auto" to "Starts with the fastest direct path and falls back to relay automatically if your network blocks it.",
    "all" to "Direct to Cloudflare when possible (lowest latency). May fail on strict networks.",
    "relay" to "All media through a Cloudflare relay over UDP. Works behind most NATs and firewalls.",
    "relay-tcp" to "Relay over TCP/TLS (port 443). Slowest but passes almost any firewall.",
)

private fun lvlLabel(id: String) = if (id == "ultra") "Ultra low" else "${id}p"

private const val SHARE_AUDIO_SUB = "Sound from games, videos and music is sent along with your screen. Applies the next time you share."
private const val SHARE_AUDIO_OLD = "Sharing your phone’s sound needs Android 10 or newer."

/** Shown when you tap Share: the Android counterpart of the browser's "Share audio" tick box. */
@Composable
private fun SharePop(onStart: () -> Unit, onDismiss: () -> Unit) {
    val s by Settings.cfg.collectAsState()
    val none = remember { MutableInteractionSource() }
    val canAudio = Build.VERSION.SDK_INT >= 29
    Box(Modifier.fillMaxSize().clickable(interactionSource = none, indication = null, onClick = onDismiss)) {
        Column(
            Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(bottom = 88.dp)
                .fillMaxWidth(0.92f).widthIn(max = 360.dp).shadow(18.dp, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)).background(Bg1)
                .clickable(interactionSource = none, indication = null) {}.padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Text("SHARE YOUR SCREEN", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.05.em)
            ToggleRow(
                "Share screen audio",
                if (canAudio) "Include sound from games, videos and music" else SHARE_AUDIO_OLD,
                on = s.shareAudio && canAudio, enabled = canAudio,
            ) { Settings.update { it.copy(shareAudio = !it.shareAudio) } }
            Spacer(Modifier.height(6.dp))
            PrimaryButton("Start sharing") { onStart() }
        }
    }
}

/** Web "Settings" sheet (bottom sheet on phones): same sections and options as static/index.html. */
@Composable
private fun SettingsSheet(c: CallUi, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val cfg = LocalConfiguration.current
    val s by Settings.cfg.collectAsState()
    val none = remember { MutableInteractionSource() }
    var adv by remember { mutableStateOf(false) }
    val live = c.live
    val canAudio = Build.VERSION.SDK_INT >= 29
    Box(
        Modifier.fillMaxSize().background(Color(0xAA000000)).clickable(interactionSource = none, indication = null, onClick = onClose),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.fillMaxWidth().widthIn(max = 520.dp).heightIn(max = (cfg.screenHeightDp * 0.9f).dp)
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)).background(Bg2)
                .clickable(interactionSource = none, indication = null) {}.windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Settings", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClose), Alignment.Center) { Icon(WebIcons.X, "Close", tint = Muted) }
            }
            Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 20.dp)) {
                // ---- Camera
                SectionHead("Camera")
                if (c.canFlip) {
                    Seg(listOf("user" to "Selfie camera", "environment" to "Rear camera"), if (c.front) "user" else "environment") { CallEngine.setFacing(it == "user") }
                    Spacer(Modifier.height(4.dp))
                }
                ToggleRow("Mirror my camera", "Only changes how you see yourself", s.mirror) { CallEngine.setMirror(!s.mirror) }

                // ---- Video quality (Normal mode only: Audio first / Call only manage video themselves)
                if (c.mode == "off") {
                    SectionHead("Video quality")
                    Seg(
                        listOf("auto" to "Auto", "1080" to "1080p", "720" to "720p", "480" to "480p", "360" to "360p", "ultra" to "Ultra low"),
                        s.quality, perRow = 3,
                    ) { CallEngine.setVideoQuality(it) }
                    Hint(
                        if (s.quality == "auto") "Adapts to your connection: steps down fast when the network struggles and back up when it recovers."
                        else "Fixed quality. Your connection may stutter if it can’t keep up — use Auto for the smoothest call.",
                    )
                    if (live != null && c.camOn) LiveLine(
                        "Sending " + (if (live.w > 0) "${live.w}×${live.h}" else lvlLabel(live.level)) + " · ${live.fps} fps · " +
                            String.format(java.util.Locale.US, "%.2f", live.kbps / 1000.0) + " Mbps" +
                            (if (live.auto) " · Auto picked ${lvlLabel(live.level)}" else ""),
                    )
                }

                // ---- Audio
                SectionHead("Audio")
                Seg(listOf("off" to "Normal", "call" to "Call only", "video" to "Audio first"), c.mode) { CallEngine.setRoomMode(it) }
                if (c.mode == "video") {
                    Spacer(Modifier.height(6.dp))
                    Seg(listOf("auto" to "Video: Auto", "low" to "Video: Low"), s.vidPrio) { CallEngine.setVidPrio(it) }
                }
                Hint(AMODE_HINTS[c.mode] ?: "")
                Spacer(Modifier.height(10.dp))
                Seg(listOf("low" to "Audio: Low", "med" to "Medium", "high" to "High"), s.audioQ) { CallEngine.setAudioQuality(it) }
                Spacer(Modifier.height(10.dp))
                Text("Audio output", color = Muted, fontSize = 12.5.sp, modifier = Modifier.padding(bottom = 6.dp))
                Seg(listOf("spk" to "Speaker", "ear" to (if (c.bt) "Bluetooth" else "Earpiece")), if (c.speaker) "spk" else "ear") { CallEngine.setSpeaker(it == "spk") }
                ToggleRow(
                    "Share screen audio", if (canAudio) SHARE_AUDIO_SUB else SHARE_AUDIO_OLD,
                    on = s.shareAudio && canAudio, enabled = canAudio,
                ) { Settings.update { it.copy(shareAudio = !it.shareAudio) } }

                // ---- Room
                SectionHead("Room")
                ToggleRow("Lock room", "Nobody new can join while it is locked", c.locked) { CallEngine.setLock(!c.locked) }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(c.code, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, letterSpacing = 0.08.em, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    SmallButton("Copy code") {
                        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText("pairing code", c.code))
                        Toast.makeText(ctx, "Pairing code copied", Toast.LENGTH_SHORT).show()
                    }
                }

                // ---- Advanced
                Spacer(Modifier.height(20.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Bg4))
                Row(
                    Modifier.fillMaxWidth().clickable(role = Role.Button) { adv = !adv }.padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Advanced", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Icon(WebIcons.Chev, null, tint = Muted, modifier = Modifier.rotate(if (adv) 180f else 0f))
                }
                if (adv) {
                    Text("CONNECTION MODE", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.05.em, modifier = Modifier.padding(bottom = 8.dp))
                    Seg(listOf("auto" to "Auto", "all" to "Direct", "relay" to "Relay", "relay-tcp" to "Relay TCP"), s.connMode, perRow = 2) { CallEngine.setConnMode(it) }
                    Hint(CONN_HINTS[s.connMode] ?: "")
                    if (live != null) LiveLine(
                        "Now: " + (if (live.path.isNotEmpty()) live.path + (if (live.proto.isNotEmpty()) " · ${live.proto}" else "") else live.conn) + " · ping ${live.rttMs} ms",
                    )
                    SectionHead("Diagnostics")
                    ToggleRow("Show live stats on my tile", "Bitrate, resolution, ping, loss", s.stats) { CallEngine.setStats(!s.stats) }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ video

@Composable
fun VideoView(track: VideoTrack?, mirror: Boolean, fit: Boolean, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(Rtc.egl.eglBaseContext, null)
                setEnableHardwareScaler(true)
            }
        },
        update = { v ->
            v.setMirror(mirror)
            v.setScalingType(if (fit) RendererCommon.ScalingType.SCALE_ASPECT_FIT else RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            val old = v.tag as? VideoTrack
            if (old !== track) {
                try { old?.removeSink(v) } catch (_: Throwable) {}
                try { track?.addSink(v) } catch (_: Throwable) {}
                v.tag = track
            }
        },
        onRelease = { v ->
            try { (v.tag as? VideoTrack)?.removeSink(v) } catch (_: Throwable) {}
            v.release()
        },
    )
}
