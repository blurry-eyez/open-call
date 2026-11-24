package xyz.s2105.opencall

import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

val BASE: String = BuildConfig.BASE_URL

/** Cookie jar that survives app restarts (the Google login session is the `oc_sid` cookie). */
class PersistentCookieJar(ctx: Context) : CookieJar {
    private val prefs = ctx.getSharedPreferences("cookies", Context.MODE_PRIVATE)
    private val jar = LinkedHashMap<String, Cookie>()

    init { load() }

    private fun key(c: Cookie) = c.name + "|" + c.domain + "|" + c.path

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        for (c in cookies) if (c.expiresAt < now) jar.remove(key(c)) else jar[key(c)] = c
        persist()
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val dead = jar.values.filter { it.expiresAt < now }
        if (dead.isNotEmpty()) { dead.forEach { jar.remove(key(it)) }; persist() }
        return jar.values.filter { it.matches(url) }
    }

    @Synchronized
    fun clear() { jar.clear(); persist() }

    private fun persist() {
        val arr = JSONArray()
        for (c in jar.values) arr.put(JSONObject()
            .put("n", c.name).put("v", c.value).put("d", c.domain).put("p", c.path)
            .put("e", c.expiresAt).put("s", c.secure).put("h", c.httpOnly).put("ho", c.hostOnly))
        prefs.edit().putString("jar", arr.toString()).apply()
    }

    private fun load() {
        try {
            val arr = JSONArray(prefs.getString("jar", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                var b = Cookie.Builder().name(o.getString("n")).value(o.getString("v"))
                    .path(o.getString("p")).expiresAt(o.getLong("e"))
                b = if (o.getBoolean("ho")) b.hostOnlyDomain(o.getString("d")) else b.domain(o.getString("d"))
                if (o.getBoolean("s")) b = b.secure()
                if (o.getBoolean("h")) b = b.httpOnly()
                val c = b.build()
                jar[key(c)] = c
            }
        } catch (_: Exception) {}
    }
}

object Net {
    lateinit var jar: PersistentCookieJar
    lateinit var http: OkHttpClient      // normal API calls
    lateinit var plain: OkHttpClient     // never follows redirects (we read the Location ourselves)
    lateinit var ws: OkHttpClient        // websocket: no read timeout, 15 s pings

    fun init(ctx: Context) {
        jar = PersistentCookieJar(ctx)
        http = OkHttpClient.Builder().cookieJar(jar)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
        plain = http.newBuilder().followRedirects(false).followSslRedirects(false).build()
        ws = http.newBuilder().readTimeout(0, TimeUnit.SECONDS).pingInterval(15, TimeUnit.SECONDS).build()
    }
}

suspend fun Call.await(): Response = suspendCancellableCoroutine { c ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (!c.isCancelled) c.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) { c.resume(response) { response.close() } }
    })
    c.invokeOnCancellation { try { cancel() } catch (_: Throwable) {} }
}

/** JSON helper: org.json turns JSON null into the string "null"; this returns real nulls. */
fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k).takeIf { it.isNotEmpty() }
