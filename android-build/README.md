# Open Call for Android

Native Kotlin/Compose app. Google sign-in happens inside the app (Credential Manager) and the app talks to your
Open Call server's API: `/api/google_token`, `/api/me`, `/api/signup`, `/api/ice`, `/ws`.

The APK needs only **two settings** about your deployment. Your Cloudflare keys/IDs never go in the app:
they stay on the server (`config.py`), the app just calls `/api/ice` on your server.

| Setting | What it is |
|---|---|
| `BASE_URL` | Your Open Call server, e.g. `https://call.example.com` |
| `GOOGLE_WEB_CLIENT_ID` | The Google OAuth **Web application** client ID the server already uses (`…apps.googleusercontent.com`) |

Nothing personal is stored in this repo. Settings are read from (first match wins): `-PNAME=…`, environment variable,
or `android-build/local.properties` (git-ignored).

## One-time Google Cloud setup
1. APIs & Services -> Credentials -> Create credentials -> OAuth client ID -> **Android**
   - Package name: `xyz.s2105.opencall`
   - SHA-1: the one printed by `make-keystore.sh` (release) and/or the debug one:
     `keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android | grep SHA1`
2. Keep your existing **Web application** client; its ID is `GOOGLE_WEB_CLIENT_ID`. No redirect URIs are needed for the app.
3. OAuth consent screen: if in "Testing", add your Google accounts as test users.
4. Add a Google account on the phone (Settings -> Google).

## Server (once)
Your server needs `POST /api/google_token` (the `login.py` patch from the web repo) and a restart.

## Build locally
```bash
cd android-build
cp local.properties.example local.properties   # then fill in sdk.dir, BASE_URL, GOOGLE_WEB_CLIENT_ID
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew assembleDebug
```
APK: `app/build/outputs/apk/debug/app-debug.apk` (or `assembleRelease` -> `app/build/outputs/apk/release/`).
Or pass values on the command line: `./gradlew assembleRelease -PBASE_URL=https://call.example.com -PGOOGLE_WEB_CLIENT_ID=…`

## Build a release on GitHub (set secrets BEFORE building)
1. **Signing key (once):** `bash android-build/make-keystore.sh`. It creates a permanent key in `~/.open-call-keystore/`,
   prints its **SHA-1** (add it to the Google Cloud Android client) and the secrets to create. Back that folder up.
2. In the repo: Settings -> Secrets and variables -> Actions -> New repository secret:

   | Secret | Value |
   |---|---|
   | `BASE_URL` | `https://call.example.com` |
   | `GOOGLE_WEB_CLIENT_ID` | `…apps.googleusercontent.com` |
   | `KEYSTORE_BASE64` | from `make-keystore.sh` |
   | `KEYSTORE_PASSWORD` | from `make-keystore.sh` |
   | `KEY_ALIAS` | `opencall` |
   | `KEY_PASSWORD` | from `make-keystore.sh` |
3. Run it: Actions -> **Android release** -> Run workflow (you can type the server URL and client ID right there instead
   of using secrets, and give a `tag` such as `android-v1.2` to publish a release), or push a tag:
   `git tag android-v1.2 && git push --tags`.
4. The run summary and release notes show the **signing SHA-1/SHA-256**. If you ran without `KEYSTORE_BASE64` the build
   uses a temporary key (clearly warned), which is fine for a quick test but its SHA changes every run.

Someone forking this needs their own server, Google project and signing key; the workflow asks for exactly those.

## Settings in the app (matches the web client)
Gear button in a call, saved on the phone:
- **Camera**: selfie / rear, mirror my camera
- **Video quality**: Auto, 1080p, 720p, 480p, 360p, Ultra low (Normal mode)
- **Audio**: Normal / Call only / Audio first (+ Video Auto/Low in Audio first), audio quality Low / Medium / High, audio output
- **Share screen audio**: also asked when you tap Share. Sends the phone's sound (games, videos, music; Android 10+).
  Apps that block capture (e.g. Spotify, Netflix) stay silent.
- **Advanced**: connection mode (Auto / Direct / Relay / Relay TCP), live stats on your own tile

Not in the app: call recording (admin-only, browser), the web "Voice leveler" and microphone picker.
