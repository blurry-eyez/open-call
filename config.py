"""Open Call settings you edit by hand.

Paste your Google OAuth keys below (or set the same names in .env instead).
Create them at https://console.cloud.google.com/apis/credentials ->
Create credentials -> OAuth client ID -> "Web application", then add this under
"Authorized redirect URIs" (and the same domain under "Authorized JavaScript origins"):

    https://call.2105s.xyz/auth/google/callback      <- redirect URI
    https://call.2105s.xyz                           <- JavaScript origin
"""
import os

# ---- Google OAuth keys ------------------------------------------------------
GOOGLE_CLIENT_ID = ""       # e.g. 1234567890-abcdef.apps.googleusercontent.com
GOOGLE_CLIENT_SECRET = ""   # e.g. GOCSPX-xxxxxxxxxxxxxxxx
GOOGLE_REDIRECT_URI = ""    # optional full override; blank = PUBLIC_URL + /auth/google/callback
PUBLIC_URL = ""   # your public address, no trailing slash. Login always uses this, never the request headers.

# ---- Admins -----------------------------------------------------------------
# These Google accounts can record calls, and a call with any of them inside has no time limit.
ADMIN_EMAILS = [
     #"",
    # "friend@gmail.com",
]

# ---- Call limits for everyone else -----------------------------------------
FREE_CALL_MINUTES = 15       # per room, counted only while no admin is in it
ROOM_COOLDOWN_MINUTES = 60   # after the limit is hit, that pairing code stays closed this long (no leave/rejoin reset)

# ---- Login sessions ---------------------------------------------------------
SESSION_DAYS = 30

# .env / environment values fill in anything left blank above
GOOGLE_CLIENT_ID = GOOGLE_CLIENT_ID or os.getenv("GOOGLE_CLIENT_ID", "").strip()
GOOGLE_CLIENT_SECRET = GOOGLE_CLIENT_SECRET or os.getenv("GOOGLE_CLIENT_SECRET", "").strip()
GOOGLE_REDIRECT_URI = GOOGLE_REDIRECT_URI or os.getenv("GOOGLE_REDIRECT_URI", "").strip()
PUBLIC_URL = (PUBLIC_URL or os.getenv("PUBLIC_URL", "")).strip().rstrip("/")
ADMIN_EMAILS = list(ADMIN_EMAILS) + [e for e in os.getenv("ADMIN_EMAILS", "").split(",") if e.strip()]
