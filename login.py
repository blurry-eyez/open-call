#!/usr/bin/env python3
"""Open Call - Google login, accounts and sessions.

Flow: "Log in / Sign up with Google" -> Google -> /auth/google/callback.
  * Google account already registered -> logged straight in (never registered twice).
  * New Google account -> sign-up step asks for a phone number (Google doesn't share it),
    then the account is saved in user.db with the Google name, email and profile picture.
  * A phone number that is already registered can't be used for a second account.
Sessions are random tokens in an HttpOnly cookie; only their hash is stored in user.db.
"""
from __future__ import annotations

import asyncio
import hashlib
import re
import secrets
import sqlite3
import time
from contextlib import contextmanager
from pathlib import Path
from typing import Any
from urllib.parse import urlencode

import aiohttp
from aiohttp import web

import config

USER_DB = Path(__file__).resolve().parent / "user.db"
C_SID, C_STATE, C_PEND = "oc_sid", "oc_state", "oc_pend"
G_AUTH = "https://accounts.google.com/o/oauth2/v2/auth"
G_TOKEN = "https://oauth2.googleapis.com/token"
G_INFO = "https://openidconnect.googleapis.com/v1/userinfo"
PENDING: dict[str, dict] = {}  # sign-up token -> Google profile waiting for a phone number (15 min)
_log: Any = None
_origin: Any = None


def now_ms() -> int:
    return int(time.time() * 1000)


# -------------------------------------------------------------- database ----
@contextmanager
def conn():
    c = sqlite3.connect(USER_DB, timeout=20)
    c.row_factory = sqlite3.Row
    try:
        yield c
        c.commit()
    finally:
        c.close()


def init() -> None:
    with conn() as c:
        c.execute("PRAGMA journal_mode=WAL")
        c.executescript(
            """
            CREATE TABLE IF NOT EXISTS users (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                google_sub TEXT NOT NULL UNIQUE,
                email TEXT NOT NULL UNIQUE,
                name TEXT NOT NULL,
                picture TEXT NOT NULL DEFAULT '',
                phone TEXT NOT NULL,
                phone_key TEXT NOT NULL UNIQUE,
                created_ms INTEGER NOT NULL,
                last_login_ms INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS sessions (
                token_hash TEXT PRIMARY KEY,
                user_id INTEGER NOT NULL REFERENCES users(id),
                expires_ms INTEGER NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_sess_exp ON sessions(expires_ms);
            """
        )
        c.execute("DELETE FROM sessions WHERE expires_ms<?", (now_ms(),))


def clean_name(raw: Any) -> str:
    return re.sub(r"[\x00-\x1f\x7f<>]", "", str(raw or "")).strip()[:24] or "Guest"


def clean_phone(raw: Any) -> tuple[str | None, str | None]:
    """-> (phone as stored, key used for duplicate checks). The key is the last 10 digits, so
    +91 98765 43210, 09876543210 and 9876543210 all count as the same number."""
    s = re.sub(r"[\s().-]", "", str(raw or ""))
    if not re.fullmatch(r"\+?\d{8,15}", s):
        return None, None
    return s, s.lstrip("+")[-10:]


def is_admin(email: str) -> bool:
    return email.strip().lower() in {e.strip().lower() for e in config.ADMIN_EMAILS}


def public_user(row: sqlite3.Row) -> dict[str, Any]:
    return {"id": row["id"], "name": row["name"], "email": row["email"], "picture": row["picture"],
            "admin": is_admin(row["email"])}


def find_user(sub: str, email: str) -> sqlite3.Row | None:
    with conn() as c:
        return c.execute("SELECT * FROM users WHERE google_sub=? OR email=?", (sub, email)).fetchone()


def touch_login(uid: int, picture: str) -> None:
    with conn() as c:  # Google's picture URL can change, so refresh it on every login
        c.execute("UPDATE users SET last_login_ms=?, picture=CASE WHEN ?<>'' THEN ? ELSE picture END WHERE id=?",
                  (now_ms(), picture, picture, uid))


def register(p: dict, name: str, phone: str, key: str) -> tuple[int | None, str | None]:
    """Create the account. -> (user id, None) or (None, error). If this Google account got registered
    in the meantime it just returns the existing user: nobody is registered twice."""
    with conn() as c:
        row = c.execute("SELECT id FROM users WHERE google_sub=? OR email=?", (p["sub"], p["email"])).fetchone()
        if row:
            return row["id"], None
        if c.execute("SELECT 1 FROM users WHERE phone_key=?", (key,)).fetchone():
            return None, "phone_taken"
        try:
            cur = c.execute(
                "INSERT INTO users(google_sub,email,name,picture,phone,phone_key,created_ms,last_login_ms) "
                "VALUES(?,?,?,?,?,?,?,?)",
                (p["sub"], p["email"], name, p["picture"], phone, key, now_ms(), now_ms()))
        except sqlite3.IntegrityError:
            return None, "phone_taken"
        return cur.lastrowid, None


# -------------------------------------------------------------- sessions ----
def _hash(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


def new_session(uid: int) -> str:
    token = secrets.token_urlsafe(32)
    with conn() as c:
        c.execute("INSERT INTO sessions VALUES(?,?,?)", (_hash(token), uid, now_ms() + config.SESSION_DAYS * 86_400_000))
    return token


def user_from_request(request: web.Request) -> dict[str, Any] | None:
    """The logged-in user behind this request/websocket (cookie), or None."""
    token = request.cookies.get(C_SID)
    if not token:
        return None
    with conn() as c:
        row = c.execute("SELECT u.* FROM sessions s JOIN users u ON u.id=s.user_id "
                        "WHERE s.token_hash=? AND s.expires_ms>?", (_hash(token), now_ms())).fetchone()
    return public_user(row) if row else None


def _pending(request: web.Request) -> dict | None:
    for k in [k for k, v in PENDING.items() if v["exp"] < time.time()]:
        del PENDING[k]
    return PENDING.get(request.cookies.get(C_PEND, ""))


# ---------------------------------------------------------------- routes ----
def _redirect(to: str) -> web.Response:
    return web.Response(status=302, headers={"Location": to, "Cache-Control": "no-store"})


def _set(resp: web.Response, request: web.Request, name: str, value: str, max_age: int) -> None:
    resp.set_cookie(name, value, max_age=max_age, httponly=True, samesite="Lax", path="/",
                    secure=base_url(request).startswith("https://"))


def _err(message: str, code: str, status: int = 400) -> web.Response:
    return web.json_response({"ok": False, "error": message, "code": code}, status=status)


def _configured() -> bool:
    return bool(config.GOOGLE_CLIENT_ID and config.GOOGLE_CLIENT_SECRET)


def base_url(request: web.Request) -> str:
    """Our public address. config.PUBLIC_URL wins so the redirect URI is always exactly what Google has registered
    (behind the tunnel the request's own scheme/host can differ); the request is only a fallback."""
    return config.PUBLIC_URL or _origin(request)


def redirect_uri(request: web.Request | None = None) -> str:
    return config.GOOGLE_REDIRECT_URI or (config.PUBLIC_URL or (_origin(request) if request else "https://YOUR-DOMAIN")) + "/auth/google/callback"


async def google_start(request: web.Request) -> web.Response:
    if not _configured():
        return _redirect("/?notice=unconfigured")
    mode = "signup" if request.query.get("mode") == "signup" else "login"
    _log.info("google login: redirect_uri=%s (must be listed in Google's Authorized redirect URIs)", redirect_uri(request))
    state = secrets.token_urlsafe(24) + "~" + mode
    resp = _redirect(G_AUTH + "?" + urlencode({
        "client_id": config.GOOGLE_CLIENT_ID, "redirect_uri": redirect_uri(request), "response_type": "code",
        "scope": "openid email profile", "state": state, "prompt": "select_account"}))
    _set(resp, request, C_STATE, state, 600)
    return resp


async def _google_profile(request: web.Request, code: str) -> dict[str, str]:
    http, t = request.app["http"], aiohttp.ClientTimeout(total=10)
    try:
        async with http.post(G_TOKEN, timeout=t, data={
                "code": code, "client_id": config.GOOGLE_CLIENT_ID, "client_secret": config.GOOGLE_CLIENT_SECRET,
                "redirect_uri": redirect_uri(request), "grant_type": "authorization_code"}) as r:
            tok = await r.json(content_type=None)
            if r.status >= 400 or "access_token" not in tok:
                raise ValueError(f"token exchange failed: {tok.get('error_description') or tok.get('error') or r.status}")
        async with http.get(G_INFO, timeout=t, headers={"Authorization": "Bearer " + tok["access_token"]}) as r:
            info = await r.json(content_type=None)
            if r.status >= 400:
                raise ValueError(f"userinfo failed: {r.status}")
    except (aiohttp.ClientError, asyncio.TimeoutError) as exc:
        raise ValueError(f"could not reach Google: {exc}")
    sub, email = str(info.get("sub", "")), str(info.get("email", "")).strip().lower()
    if not sub or not email:
        raise ValueError("Google returned no email")
    if not info.get("email_verified"):
        raise ValueError("unverified")
    pic = str(info.get("picture") or "")
    return {"sub": sub, "email": email, "name": clean_name(info.get("name") or email.split("@")[0]),
            "picture": pic if pic.startswith("https://") and len(pic) < 600 else ""}


async def google_callback(request: web.Request) -> web.Response:
    q, want = request.query, request.cookies.get(C_STATE, "")
    mode = want.rpartition("~")[2]

    def done(to: str, token: str | None = None, pend: str | None = None) -> web.Response:
        resp = _redirect(to)
        resp.del_cookie(C_STATE, path="/")
        if token:
            _set(resp, request, C_SID, token, config.SESSION_DAYS * 86400)
            resp.del_cookie(C_PEND, path="/")
        if pend:
            _set(resp, request, C_PEND, pend, 900)
        return resp

    if q.get("error"):
        return done("/?notice=denied")
    if not want or not q.get("code") or not secrets.compare_digest(q.get("state", ""), want):
        return done("/?notice=error")
    try:
        p = await _google_profile(request, q["code"])
    except ValueError as exc:
        _log.warning("google login failed: %s", exc)
        return done("/?notice=" + ("unverified" if str(exc) == "unverified" else "error"))
    row = find_user(p["sub"], p["email"])
    if row:  # already registered: just log in (never a second account)
        touch_login(row["id"], p["picture"])
        _log.info("login uid=%s", row["id"])
        return done("/?notice=exists" if mode == "signup" else "/", token=new_session(row["id"]))
    pend = secrets.token_urlsafe(24)
    PENDING[pend] = {**p, "exp": time.time() + 900}
    return done("/?notice=new" if mode == "login" else "/", pend=pend)


async def api_me(request: web.Request) -> web.Response:
    out: dict[str, Any] = {"ok": True, "configured": _configured(), "state": "anon"}
    user = user_from_request(request)
    if user:
        out.update(state="user", user=user)
    elif (p := _pending(request)):
        out.update(state="pending", pending={k: p[k] for k in ("name", "email", "picture")})
    return web.json_response(out, headers={"Cache-Control": "no-store"})


async def api_signup(request: web.Request) -> web.Response:
    p = _pending(request)
    if not p:
        return _err("Your Google sign-in expired. Log in with Google again.", "expired", 401)
    try:
        data = await request.json()
    except Exception:
        data = None
    if not isinstance(data, dict):
        return _err("Expected JSON object.", "bad_request")
    phone, key = clean_phone(data.get("phone"))
    if not phone:
        return _err("Enter a valid phone number (8-15 digits, country code optional).", "bad_phone")
    uid, problem = register(p, clean_name(data.get("name") or p["name"]), phone, key)
    if problem:
        return _err("That phone number is already registered with another account. "
                    "Log in with the Google account you used for it.", "phone_taken", 409)
    PENDING.pop(request.cookies.get(C_PEND, ""), None)
    _log.info("signup uid=%s", uid)
    resp = web.json_response({"ok": True})
    _set(resp, request, C_SID, new_session(uid), config.SESSION_DAYS * 86400)
    resp.del_cookie(C_PEND, path="/")
    return resp


async def api_logout(request: web.Request) -> web.Response:
    token = request.cookies.get(C_SID)
    if token:
        with conn() as c:
            c.execute("DELETE FROM sessions WHERE token_hash=?", (_hash(token),))
    PENDING.pop(request.cookies.get(C_PEND, ""), None)
    resp = web.json_response({"ok": True})
    resp.del_cookie(C_SID, path="/")
    resp.del_cookie(C_PEND, path="/")
    return resp


def setup(app: web.Application, origin_fn: Any, log: Any) -> None:
    """Create user.db and register the login routes. origin_fn(request) -> public https://host."""
    global _log, _origin
    _log, _origin = log, origin_fn
    init()
    app.router.add_get("/auth/google", google_start)
    app.router.add_get("/auth/google/callback", google_callback)
    app.router.add_get("/api/me", api_me)
    app.router.add_post("/api/signup", api_signup)
    app.router.add_post("/api/logout", api_logout)
