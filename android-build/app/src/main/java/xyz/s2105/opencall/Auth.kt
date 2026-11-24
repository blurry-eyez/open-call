package xyz.s2105.opencall

import android.app.Activity
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class User(val id: Int, val name: String, val email: String, val picture: String, val admin: Boolean)
data class Pending(val name: String, val email: String, val picture: String)
data class AuthUi(
    val phase: String = "loading",          // loading | anon | pending | user
    val user: User? = null,
    val pending: Pending? = null,
    val notice: String? = null,
    val noticeError: Boolean = true,
    val busy: Boolean = false,
    val configured: Boolean = true,
    val offline: Boolean = false,
)

/**
 * Sign in with Google, inside the app (the way other Android apps do it):
 *   1. Credential Manager shows Google's account sheet and returns a signed Google ID token
 *      (needs an Android OAuth client = package name + SHA-1; the Web client ID is passed as serverClientId)
 *   2. POST the token to /api/google_token; the server verifies it with Google and sets the session cookie
 *   3. the cookie lands in our cookie jar; /api/me then says "user" (logged in) or "pending" (new: ask for phone)
 */
object Auth {
    val ui = MutableStateFlow(AuthUi())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val JSON = "application/json".toMediaType()

    private fun fail(msg: String) = ui.update { it.copy(busy = false, notice = msg, noticeError = true) }

    fun refresh() { scope.launch { me() } }

    suspend fun me() {
        try {
            Net.http.newCall(Request.Builder().url("$BASE/api/me").build()).await().use { r ->
                val j = JSONObject(r.body?.string() ?: "{}")
                val configured = j.optBoolean("configured", true)
                when (j.optString("state")) {
                    "user" -> {
                        val u = j.getJSONObject("user")
                        ui.update { it.copy(phase = "user", offline = false, busy = false, configured = configured, notice = null,
                            user = User(u.optInt("id"), u.optString("name"), u.optString("email"),
                                u.optString("picture"), u.optBoolean("admin")), pending = null) }
                    }
                    "pending" -> {
                        val p = j.getJSONObject("pending")
                        ui.update { it.copy(phase = "pending", offline = false, busy = false, configured = configured, user = null,
                            pending = Pending(p.optString("name"), p.optString("email"), p.optString("picture"))) }
                    }
                    else -> ui.update { it.copy(phase = "anon", offline = false, busy = false, configured = configured, user = null, pending = null) }
                }
            }
        } catch (_: Exception) {
            ui.update { it.copy(offline = true, busy = false, phase = if (it.phase == "loading") "anon" else it.phase) }
        }
    }

    fun signIn(activity: Activity) {
        ui.update { it.copy(busy = true, notice = null) }
        scope.launch {
            try {
                val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID).build()
                val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
                val result = CredentialManager.create(activity).getCredential(activity, request)
                val cred = result.credential
                if (cred is CustomCredential && cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    exchange(GoogleIdTokenCredential.createFrom(cred.data).idToken)
                } else {
                    fail("Unexpected response from Google sign-in.")
                }
            } catch (_: GetCredentialCancellationException) {
                ui.update { it.copy(busy = false) }
            } catch (_: NoCredentialException) {
                fail("No Google account available. Add a Google account in your phone's Settings, and make sure the Android OAuth client (package + SHA-1) exists in Google Cloud.")
            } catch (e: GetCredentialException) {
                fail("Google sign-in failed: ${e.message ?: e.type}")
            } catch (e: Exception) {
                fail("Sign-in failed: ${e.message}")
            }
        }
    }

    private suspend fun exchange(idToken: String) {
        try {
            val body = JSONObject().put("id_token", idToken).toString().toRequestBody(JSON)
            Net.http.newCall(Request.Builder().url("$BASE/api/google_token").post(body).build()).await().use { r ->
                val j = try { JSONObject(r.body?.string() ?: "{}") } catch (_: Exception) { JSONObject() }
                if (r.code == 404) { fail("The server doesn't have the /api/google_token endpoint yet (apply the login.py patch)."); return }
                if (!j.optBoolean("ok")) { fail(j.optString("error", "The server rejected the sign-in.")); return }
            }
            me()
        } catch (_: Exception) {
            ui.update { it.copy(busy = false, offline = true) }
            fail("Could not reach the server.")
        }
    }

    /** -> null on success, otherwise the error message to show. */
    suspend fun signup(name: String, phone: String): String? {
        return try {
            val body = JSONObject().put("name", name).put("phone", phone).toString().toRequestBody(JSON)
            Net.http.newCall(Request.Builder().url("$BASE/api/signup").post(body).build()).await().use { r ->
                val j = JSONObject(r.body?.string() ?: "{}")
                if (j.optBoolean("ok")) { me(); null }
                else {
                    if (j.optString("code") == "expired") me()
                    j.optString("error", "Could not sign up.")
                }
            }
        } catch (_: Exception) { "Could not reach the server." }
    }

    fun logout() {
        scope.launch {
            try {
                Net.http.newCall(Request.Builder().url("$BASE/api/logout")
                    .post("{}".toRequestBody(JSON)).build()).await().close()
            } catch (_: Exception) {}
            Net.jar.clear()
            try { CredentialManager.create(CallEngine.app).clearCredentialState(ClearCredentialStateRequest()) } catch (_: Exception) {}
            ui.update { AuthUi(phase = "anon") }
            me()
        }
    }
}
