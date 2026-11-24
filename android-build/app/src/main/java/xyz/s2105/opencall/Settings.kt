package xyz.s2105.opencall

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** User preferences that mirror the web client's Settings sheet (`S.cfg` in static/index.html). */
data class Cfg(
    val quality: String = "auto",      // auto | 1080 | 720 | 480 | 360 | ultra   video quality (Normal mode)
    val audioQ: String = "med",        // low | med | high                          Opus bitrate ceiling + band limit
    val vidPrio: String = "auto",      // auto | low                                video size while in Audio-first mode
    val shareAudio: Boolean = true,    // include the phone's own sound when sharing the screen
    val mirror: Boolean = true,        // mirror the front camera in my own tile
    val connMode: String = "auto",     // auto | all | relay | relay-tcp
    val stats: Boolean = false,        // live stats on my own tile
)

/** Persisted in the same "prefs" file the join screen already uses for the display name. */
object Settings {
    private val _cfg = MutableStateFlow(Cfg())
    val cfg: StateFlow<Cfg> = _cfg
    private var prefs: SharedPreferences? = null

    private val QUALITY = setOf("auto", "1080", "720", "480", "360", "ultra")
    private val AUDIO_Q = setOf("low", "med", "high")
    private val VID_PRIO = setOf("auto", "low")
    private val CONN = setOf("auto", "all", "relay", "relay-tcp")

    fun init(ctx: Context) {
        val p = ctx.applicationContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        prefs = p
        val d = Cfg()
        fun pick(key: String, ok: Set<String>, def: String): String = p.getString(key, def)?.takeIf { it in ok } ?: def
        _cfg.value = Cfg(
            quality = pick("cfg_quality", QUALITY, d.quality),
            audioQ = pick("cfg_audioQ", AUDIO_Q, d.audioQ),
            vidPrio = pick("cfg_vidPrio", VID_PRIO, d.vidPrio),
            shareAudio = p.getBoolean("cfg_shareAudio", d.shareAudio),
            mirror = p.getBoolean("cfg_mirror", d.mirror),
            connMode = pick("cfg_connMode", CONN, d.connMode),
            stats = p.getBoolean("cfg_stats", d.stats),
        )
    }

    fun update(f: (Cfg) -> Cfg) {
        val n = f(_cfg.value)
        _cfg.value = n
        prefs?.edit()?.also { e ->
            e.putString("cfg_quality", n.quality)
            e.putString("cfg_audioQ", n.audioQ)
            e.putString("cfg_vidPrio", n.vidPrio)
            e.putBoolean("cfg_shareAudio", n.shareAudio)
            e.putBoolean("cfg_mirror", n.mirror)
            e.putString("cfg_connMode", n.connMode)
            e.putBoolean("cfg_stats", n.stats)
            e.apply()
        }
    }
}
