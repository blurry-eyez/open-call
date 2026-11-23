#!/usr/bin/env python3
"""Open Call - pairing-code video calls on Cloudflare Realtime (SFU + TURN).

One process, one port (default 9876). Serves the single-page client, a WebSocket
signaling endpoint, and a small recording store. Media never touches this server:
it flows browser <-> Cloudflare SFU. This server only does rooms, signaling and
the SFU/TURN API calls (which need your secret keys).

Everything this app writes lives INSIDE this project folder:
  database.db   sqlite (recording index)
  recordings/   call recordings (chunk-uploaded by the recording client)
  logs/         server.log (rotating)
"""
from __future__ import annotations

import asyncio
import json
import logging
import logging.handlers
import os
import re
import secrets
import sqlite3
import sys
import time
from collections import defaultdict, deque
from pathlib import Path
from typing import Any

import aiohttp
from aiohttp import web

BASE_DIR = Path(__file__).resolve().parent


# ---------------------------------------------------------------- config ----
def _load_env(path: Path) -> None:
    """Load .env (python-dotenv if present, tiny parser otherwise)."""
    try:
        from dotenv import load_dotenv  # type: ignore

        load_dotenv(path)
        return
    except ImportError:
        pass
    if not path.is_file():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        os.environ.setdefault(key.strip(), value.strip().strip("'\""))


_load_env(BASE_DIR / ".env")

import config  # noqa: E402  (after .env is loaded) Google keys, admin list, call limits
import login  # noqa: E402  Google login, accounts (user.db), sessions


def env_int(name: str, default: int) -> int:
    try:
        return int(os.getenv(name, "").strip() or default)
    except ValueError:
        return default


HOST = os.getenv("HOST", "127.0.0.1").strip() or "127.0.0.1"
PORT = env_int("PORT", 9876)
MAX_PEERS = env_int("MAX_PEERS", 8)
GRACE_SECONDS = env_int("RECONNECT_GRACE_SECONDS", 12)
MAX_REC_MB = env_int("MAX_RECORDING_MB", 4096)
JOINS_PER_MINUTE = env_int("JOINS_PER_MINUTE", 40)
KNOCK_SECONDS = env_int("KNOCK_SECONDS", 120)
PUBLIC_URL = os.getenv("PUBLIC_URL", "").strip().rstrip("/")  # e.g. https://call.example.com (used in link previews / SEO)

CF_APP_ID = os.getenv("CF_REALTIME_APP_ID", "").strip()
CF_APP_SECRET = os.getenv("CF_REALTIME_APP_SECRET", "").strip()
CF_TURN_KEY_ID = os.getenv("CF_TURN_KEY_ID", "").strip()
CF_TURN_API_TOKEN = os.getenv("CF_TURN_API_TOKEN", "").strip()
CF_API = "https://rtc.live.cloudflare.com/v1"

STATIC_DIR = BASE_DIR / "static"
DB_PATH = BASE_DIR / "database.db"
REC_DIR = BASE_DIR / "recordings"
LOG_DIR = BASE_DIR / "logs"
for _d in (STATIC_DIR, REC_DIR, LOG_DIR):
    _d.mkdir(parents=True, exist_ok=True)

log = logging.getLogger("open-call")
log.setLevel(logging.INFO)
_fmt = logging.Formatter("%(asctime)s %(levelname)s %(message)s")
_console = logging.StreamHandler()
_console.setFormatter(_fmt)
log.addHandler(_console)
_file = logging.handlers.RotatingFileHandler(
    LOG_DIR / "server.log", maxBytes=2_000_000, backupCount=3, encoding="utf-8"
)
_file.setFormatter(_fmt)
log.addHandler(_file)
log.propagate = False


class APIError(Exception):
    def __init__(self, message: str, code: str = "error", status: int = 400):
        super().__init__(message)
        self.code = code
        self.status = status


def now_ms() -> int:
    return int(time.time() * 1000)


# -------------------------------------------------------------- database ----
def db() -> sqlite3.Connection:
    conn = sqlite3.connect(DB_PATH, timeout=20)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    return conn


def init_db() -> None:
    with db() as c:
        c.executescript(
            """
            CREATE TABLE IF NOT EXISTS recordings (
                id TEXT PRIMARY KEY,
                code TEXT NOT NULL,
                name TEXT NOT NULL,
                mime TEXT NOT NULL,
                filename TEXT NOT NULL,
                started_ms INTEGER NOT NULL,
                ended_ms INTEGER,
                size INTEGER NOT NULL DEFAULT 0,
                chunks INTEGER NOT NULL DEFAULT 0
            );
            CREATE INDEX IF NOT EXISTS idx_rec_code ON recordings(code, started_ms);
            """
        )


# ------------------------------------------------------------ cloudflare ----
async def sfu(app: web.Application, method: str, path: str, body: dict | None = None) -> dict[str, Any]:
    if not (CF_APP_ID and CF_APP_SECRET):
        raise APIError("Server is missing CF_REALTIME_APP_ID / CF_REALTIME_APP_SECRET.", "not_configured", 503)
    url = f"{CF_API}/apps/{CF_APP_ID}{path}"
    headers = {"Authorization": f"Bearer {CF_APP_SECRET}", "Content-Type": "application/json"}
    try:
        async with app["http"].request(
            method, url, headers=headers, json=body, timeout=aiohttp.ClientTimeout(total=15, connect=6)
        ) as r:
            raw = await r.text()
            try:
                data = json.loads(raw) if raw else {}
            except json.JSONDecodeError:
                data = {"raw": raw[:500]}
            if r.status >= 400:
                detail = data.get("errorDescription") or data.get("message") or data.get("raw") or raw[:300]
                log.warning("SFU %s %s -> %s %s", method, path, r.status, detail)
                code = "stale" if r.status in (404, 410) else "sfu_error"
                raise APIError(f"Cloudflare SFU error {r.status}: {detail}", code, 502)
            return data if isinstance(data, dict) else {}
    except (aiohttp.ClientError, asyncio.TimeoutError) as exc:
        raise APIError(f"Could not reach Cloudflare SFU: {exc}", "sfu_unreachable", 502)


def check_tracks(result: dict[str, Any]) -> None:
    for t in result.get("tracks", []) or []:
        if isinstance(t, dict) and t.get("errorCode"):
            raise APIError(
                f"Track '{t.get('trackName', '?')}': {t.get('errorDescription') or t['errorCode']}",
                "track_error",
                502,
            )


_ice_cache: dict[str, Any] = {"at": 0.0, "servers": None}


async def ice_servers(app: web.Application) -> dict[str, Any]:
    """STUN + short-lived Cloudflare TURN credentials, cached for 6 h (TTL is 24 h)."""
    stun = [{"urls": ["stun:stun.cloudflare.com:3478"]}]
    if not (CF_TURN_KEY_ID and CF_TURN_API_TOKEN):
        return {"iceServers": stun, "turn": False}
    if _ice_cache["servers"] and time.time() - _ice_cache["at"] < 6 * 3600:
        return {"iceServers": _ice_cache["servers"], "turn": True}
    url = f"{CF_API}/turn/keys/{CF_TURN_KEY_ID}/credentials/generate-ice-servers"
    headers = {"Authorization": f"Bearer {CF_TURN_API_TOKEN}", "Content-Type": "application/json"}
    try:
        async with app["http"].post(
            url, headers=headers, json={"ttl": 86400}, timeout=aiohttp.ClientTimeout(total=10)
        ) as r:
            payload = await r.json(content_type=None)
            if r.status >= 400:
                raise RuntimeError(f"HTTP {r.status}")
        servers = list(stun)
        for s in payload.get("iceServers", []):
            urls = s.get("urls", [])
            if isinstance(urls, str):
                urls = [urls]
            urls = [u for u in urls if ":53" not in u]  # browsers block port 53
            if urls:
                servers.append({**s, "urls": urls})
        _ice_cache.update(at=time.time(), servers=servers)
        return {"iceServers": servers, "turn": len(servers) > 1}
    except Exception as exc:
        log.warning("TURN credential request failed: %s", exc)
        if _ice_cache["servers"]:  # stale creds are better than none
            return {"iceServers": _ice_cache["servers"], "turn": True}
        return {"iceServers": stun, "turn": False}


# ----------------------------------------------------------------- rooms ----
class Peer:
    def __init__(self, pid: str, cid: str, name: str, ws: web.WebSocketResponse, user: dict[str, Any]):
        self.pid = pid
        self.cid = cid
        self.name = name
        self.pic: str = user["picture"]  # Google profile picture URL
        self.admin: bool = user["admin"]
        self.ws: web.WebSocketResponse | None = ws
        self.mic = True
        self.cam = True
        self.rec = False
        self.pub_session: str | None = None
        self.pub_tracks: list[str] = []
        self.pub_mids: list[str] = []
        self.screen_session: str | None = None
        self.screen_tracks: list[str] = []
        self.screen_mids: list[str] = []
        self.sub_sessions: set[str] = set()
        self.pub_lock = asyncio.Lock()
        self.grace: asyncio.Task | None = None
        self.joined_ms = now_ms()

    def public(self) -> dict[str, Any]:
        return {
            "pid": self.pid,
            "name": self.name,
            "pic": self.pic,
            "mic": self.mic,
            "cam": self.cam,
            "rec": self.rec,
            "sessionId": self.pub_session,
            "screenSessionId": self.screen_session,
        }


class Room:
    def __init__(self, code: str):
        self.code = code
        self.peers: dict[str, Peer] = {}
        self.locked = False
        self.audio_mode = "off"  # room-wide: off | call | video (audio first)
        self.waiting: dict[str, dict] = {}  # wid -> {name, ws, fut}
        self.admin_seen: bool | None = None  # last admin state broadcast to the room


ROOMS: dict[str, Room] = {}
_join_log: dict[str, deque] = defaultdict(deque)

# Free time limit: every room gets FREE_CALL_MINUTES, counted only while no admin is inside.
# Used time is kept per pairing code after the room empties, so leave/rejoin can't reset the clock.
FREE_MS = config.FREE_CALL_MINUTES * 60_000
USED: dict[str, list[float]] = {}  # room code -> [free ms used, last activity (epoch s)]


def room_has_admin(room: Room | None) -> bool:
    return bool(room) and any(p.admin for p in room.peers.values())


def room_used_ms(code: str) -> float:
    u = USED.get(code)
    if u and time.time() - u[1] > config.ROOM_COOLDOWN_MINUTES * 60:  # cooldown over: fresh allowance
        USED.pop(code, None)
        u = None
    return u[0] if u else 0.0


def quota_left(room: Room) -> int | None:
    """Seconds of free time left, or None while an admin is in the room (no limit)."""
    return None if room_has_admin(room) else max(0, int((FREE_MS - room_used_ms(room.code)) / 1000))


async def end_free_room(app: web.Application, room: Room) -> None:
    log.info("free limit reached room=%s", room.code)
    msg = {"t": "fatal", "code": "limit", "error": f"The free {config.FREE_CALL_MINUTES}-minute call limit was reached."}
    for p in list(room.peers.values()):
        await safe_send(p.ws, msg)
        await remove_peer(app, room, p)


async def quota_loop(app: web.Application) -> None:
    last, n = time.monotonic(), 0
    while True:
        await asyncio.sleep(1)
        t = time.monotonic()
        dt, last, n = (t - last) * 1000, t, n + 1
        for room in list(ROOMS.values()):
            try:
                if not room.peers:
                    continue
                admin = room_has_admin(room)
                room_used_ms(room.code)  # drops a stale entry
                u = USED.setdefault(room.code, [0.0, time.time()])
                u[1] = time.time()
                if not admin:
                    u[0] += dt
                    if u[0] >= FREE_MS:
                        await end_free_room(app, room)
                        continue
                if n % 5 == 0 or admin != room.admin_seen:
                    room.admin_seen = admin
                    await broadcast(room, {"t": "quota", "left": quota_left(room)})
            except Exception:
                log.exception("quota loop")


def norm_code(raw: Any) -> str:
    return re.sub(r"\s+", "", str(raw or "")).lower()[:40]


def norm_name(raw: Any) -> str:
    name = re.sub(r"[\x00-\x1f\x7f<>]", "", str(raw or "")).strip()[:24]
    return name or "Guest"


def client_ip(request: web.Request) -> str:
    return (
        request.headers.get("CF-Connecting-IP")
        or request.headers.get("X-Forwarded-For", "").split(",")[0].strip()
        or request.remote
        or "?"
    )


def join_allowed(ip: str) -> bool:
    q = _join_log[ip]
    t = time.monotonic()
    while q and t - q[0] > 60:
        q.popleft()
    if len(q) >= JOINS_PER_MINUTE:
        return False
    q.append(t)
    return True


async def safe_send(ws: web.WebSocketResponse | None, msg: dict[str, Any]) -> None:
    if ws is None or ws.closed:
        return
    try:
        await ws.send_json(msg)
    except Exception:
        pass


async def broadcast(room: Room, msg: dict[str, Any], exclude: str | None = None) -> None:
    await asyncio.gather(*(safe_send(p.ws, msg) for p in list(room.peers.values()) if p.pid != exclude))


def waitlist(room: Room) -> list[dict]:
    return [{"wid": w, "name": v["name"]} for w, v in room.waiting.items()]


async def knock(room: Room, name: str, ws: web.WebSocketResponse) -> bool:
    """Hold a newcomer until someone already in the call lets them in (or says no / time runs out)."""
    wid = secrets.token_hex(4)
    fut = asyncio.get_running_loop().create_future()
    room.waiting[wid] = {"name": name, "ws": ws, "fut": fut}
    await safe_send(ws, {"t": "waiting"})
    await broadcast(room, {"t": "waitlist", "list": waitlist(room)})
    deadline = time.monotonic() + KNOCK_SECONDS
    recv = asyncio.ensure_future(ws.receive())
    try:
        while not fut.done():
            left = deadline - time.monotonic()
            if left <= 0:
                break
            await asyncio.wait({fut, recv}, timeout=left, return_when=asyncio.FIRST_COMPLETED)
            if recv.done() and not fut.done():
                msg = recv.result()
                if msg.type != aiohttp.WSMsgType.TEXT or '"leave"' in msg.data:
                    break  # they closed the tab / left while waiting
                recv = asyncio.ensure_future(ws.receive())  # a ping etc.
        ok = fut.done() and fut.result() is True
    finally:
        recv.cancel()
        room.waiting.pop(wid, None)
        if room.peers:
            await broadcast(room, {"t": "waitlist", "list": waitlist(room)})
    if not ok:
        await safe_send(ws, {"t": "fatal", "code": "denied", "error": "Nobody let you in."})
    return ok


async def close_tracks(app: web.Application, session_id: str | None, mids: list[str]) -> None:
    if not session_id or not mids:
        return
    try:
        await sfu(app, "PUT", f"/sessions/{session_id}/tracks/close",
                  {"tracks": [{"mid": m} for m in mids], "force": True})
    except Exception as exc:  # best effort; Cloudflare expires idle sessions anyway
        log.info("close_tracks %s: %s", session_id, exc)


async def remove_peer(app: web.Application, room: Room, peer: Peer) -> None:
    if room.peers.get(peer.pid) is not peer:
        return
    del room.peers[peer.pid]
    if peer.grace and peer.grace is not asyncio.current_task():
        peer.grace.cancel()
    if peer.ws and not peer.ws.closed:
        try:
            await peer.ws.close()
        except Exception:
            pass
    asyncio.create_task(close_tracks(app, peer.pub_session, peer.pub_mids))
    asyncio.create_task(close_tracks(app, peer.screen_session, peer.screen_mids))
    log.info("leave room=%s pid=%s name=%s (%d left)", room.code, peer.pid, peer.name, len(room.peers))
    if room.peers:
        await broadcast(room, {"t": "peer-left", "pid": peer.pid})
    else:
        ROOMS.pop(room.code, None)


async def grace_then_remove(app: web.Application, room: Room, peer: Peer) -> None:
    try:
        await asyncio.sleep(GRACE_SECONDS)
    except asyncio.CancelledError:
        return
    await remove_peer(app, room, peer)


# ------------------------------------------------------ signaling handlers ----
async def h_publish(app: web.Application, room: Room, peer: Peer, m: dict) -> dict:
    kind = "screen" if m.get("kind") == "screen" else "cam"
    sdp = m.get("sdp")
    tracks = m.get("tracks")
    if not isinstance(sdp, str) or not isinstance(tracks, list) or not (1 <= len(tracks) <= 2):
        raise APIError("Invalid publish request.", "bad_request")
    items, names, mids = [], [], []
    for t in tracks:
        name, mid = str(t.get("trackName", "")), str(t.get("mid", ""))
        if name not in ("audio", "video") or not mid or name in names:
            raise APIError("Invalid track list.", "bad_request")
        items.append({"location": "local", "mid": mid, "trackName": name})
        names.append(name)
        mids.append(mid)
    async with peer.pub_lock:
        session = await sfu(app, "POST", "/sessions/new")
        sid = session.get("sessionId")
        if not sid:
            raise APIError("Cloudflare returned no session id.", "sfu_error", 502)
        try:
            res = await sfu(app, "POST", f"/sessions/{sid}/tracks/new",
                            {"sessionDescription": {"type": "offer", "sdp": sdp}, "tracks": items})
            answer = (res.get("sessionDescription") or {}).get("sdp")
            if not answer:
                raise APIError("Cloudflare returned no SDP answer.", "sfu_error", 502)
            check_tracks(res)
        except Exception:
            asyncio.create_task(close_tracks(app, sid, mids))
            raise
        if kind == "screen":
            old_session, old_mids = peer.screen_session, peer.screen_mids
            peer.screen_session, peer.screen_tracks, peer.screen_mids = sid, names, mids
        else:
            old_session, old_mids = peer.pub_session, peer.pub_mids
            peer.pub_session, peer.pub_tracks, peer.pub_mids = sid, names, mids
    if old_session and old_session != sid:
        asyncio.create_task(close_tracks(app, old_session, old_mids))
    if kind == "screen":
        log.info("screen start room=%s pid=%s", room.code, peer.pid)
        await broadcast(room, {"t": "peer-screen", "pid": peer.pid, "sessionId": sid}, exclude=peer.pid)
    else:
        await broadcast(room, {"t": "peer-published", "pid": peer.pid, "sessionId": sid}, exclude=peer.pid)
    return {"sessionId": sid, "sdp": answer}


async def h_subscribe(app: web.Application, room: Room, peer: Peer, m: dict) -> dict:
    target = room.peers.get(str(m.get("pid", "")))
    if not target or target is peer:
        raise APIError("That participant is gone.", "gone")
    kind = "screen" if m.get("kind") == "screen" else "cam"
    t_session = target.screen_session if kind == "screen" else target.pub_session
    t_names = target.screen_tracks if kind == "screen" else target.pub_tracks
    if kind == "cam" and m.get("audioOnly"):  # receiver wants audio only (call-only mode / weak network): never forward video
        t_names = [n for n in t_names if n == "audio"]
    if not t_session or not t_names:
        raise APIError("Participant is still connecting.", "not_ready")
    session = await sfu(app, "POST", "/sessions/new")
    sid = session.get("sessionId")
    if not sid:
        raise APIError("Cloudflare returned no session id.", "sfu_error", 502)
    tracks = [{"location": "remote", "sessionId": t_session, "trackName": n} for n in t_names]
    try:
        res = await sfu(app, "POST", f"/sessions/{sid}/tracks/new", {"tracks": tracks})
        desc = res.get("sessionDescription") or {}
        if not desc.get("sdp"):
            raise APIError("Cloudflare returned no SDP offer.", "sfu_error", 502)
        check_tracks(res)
    except APIError as exc:
        if exc.code in ("stale", "track_error"):  # publisher not ready yet -> client retries
            exc = APIError(str(exc), "not_ready", 409)
        raise exc
    peer.sub_sessions.add(sid)
    return {"sessionId": sid, "sdp": desc["sdp"]}


async def h_renegotiate(app: web.Application, room: Room, peer: Peer, m: dict) -> dict:
    sid, sdp = str(m.get("sessionId", "")), m.get("sdp")
    if not isinstance(sdp, str) or (sid not in peer.sub_sessions and sid not in (peer.pub_session, peer.screen_session)):
        raise APIError("Unknown session.", "forbidden", 403)
    await sfu(app, "PUT", f"/sessions/{sid}/renegotiate", {"sessionDescription": {"type": "answer", "sdp": sdp}})
    return {}


HANDLERS = {"publish": h_publish, "subscribe": h_subscribe, "renegotiate": h_renegotiate}


async def run_request(app: web.Application, room: Room, peer: Peer, ws: web.WebSocketResponse, m: dict) -> None:
    rid = m.get("id")
    try:
        data = await HANDLERS[m["t"]](app, room, peer, m)
        await safe_send(ws, {"t": "res", "id": rid, "ok": True, **data})
    except APIError as exc:
        await safe_send(ws, {"t": "res", "id": rid, "ok": False, "error": str(exc), "code": exc.code})
    except Exception as exc:
        log.exception("handler %s failed", m.get("t"))
        await safe_send(ws, {"t": "res", "id": rid, "ok": False, "error": "Server error.", "code": "server"})


async def ws_handler(request: web.Request) -> web.StreamResponse:
    app = request.app
    ws = web.WebSocketResponse(heartbeat=15, max_msg_size=1_000_000)
    await ws.prepare(request)
    ip = client_ip(request)
    room: Room | None = None
    peer: Peer | None = None
    left_explicitly = False
    try:
        try:
            hello = await asyncio.wait_for(ws.receive_json(), timeout=10)
        except Exception:
            await ws.close()
            return ws
        if not isinstance(hello, dict) or hello.get("t") != "hello":
            await ws.close()
            return ws
        user = login.user_from_request(request)  # Google login cookie
        if not user:
            await safe_send(ws, {"t": "fatal", "code": "auth", "error": "Please log in with Google first."})
            await ws.close()
            return ws
        if not join_allowed(ip):
            await safe_send(ws, {"t": "fatal", "code": "rate", "error": "Too many join attempts. Wait a minute."})
            await ws.close()
            return ws
        code = norm_code(hello.get("code"))
        cid = re.sub(r"[^\w-]", "", str(hello.get("cid", "")))[:40]
        if len(code) < 3 or not cid:
            await safe_send(ws, {"t": "fatal", "code": "bad_code", "error": "Pairing code must be at least 3 characters."})
            await ws.close()
            return ws
        name = norm_name(hello.get("name") or user["name"])
        if not user["admin"] and not room_has_admin(ROOMS.get(code)) and room_used_ms(code) >= FREE_MS:
            await safe_send(ws, {"t": "fatal", "code": "limit", "error": (
                f"This room already used its free {config.FREE_CALL_MINUTES} minutes. "
                "Try again later, or ask an admin to join.")})
            await ws.close()
            return ws
        room = ROOMS.setdefault(code, Room(code))
        existing = next((p for p in room.peers.values() if p.cid == cid), None)
        resumed = existing is not None
        if existing:
            peer = existing
            if peer.grace:
                peer.grace.cancel()
                peer.grace = None
            old = peer.ws
            peer.ws = ws
            peer.name = name
            if old and old is not ws and not old.closed:
                await old.close()
        else:
            if room.peers:  # someone is already here: ask them first
                if room.locked:
                    await safe_send(ws, {"t": "fatal", "code": "locked", "error": "This room is locked."})
                    await ws.close()
                    return ws
                if not await knock(room, name, ws):
                    await ws.close()
                    return ws
                room = ROOMS.setdefault(code, room)
            if len(room.peers) >= MAX_PEERS:
                await safe_send(ws, {"t": "fatal", "code": "full", "error": f"This call is full ({MAX_PEERS} people)."})
                await ws.close()
                return ws
            peer = Peer(secrets.token_hex(4), cid, name, ws, user)
            room.peers[peer.pid] = peer
        log.info("join room=%s pid=%s name=%s resumed=%s (%d in room)", code, peer.pid, name, resumed, len(room.peers))
        await safe_send(ws, {
            "t": "welcome", "pid": peer.pid, "resumed": resumed,
            "peers": [p.public() for p in room.peers.values() if p is not peer],
            "turn": bool(CF_TURN_KEY_ID and CF_TURN_API_TOKEN),
            "locked": room.locked, "waiting": waitlist(room), "mode": room.audio_mode,
            "quota": quota_left(room),
        })
        if not resumed:
            await broadcast(room, {"t": "peer-joined", "peer": peer.public()}, exclude=peer.pid)

        async for msg in ws:
            if msg.type != aiohttp.WSMsgType.TEXT:
                continue
            try:
                m = json.loads(msg.data)
            except ValueError:
                continue
            if not isinstance(m, dict):
                continue
            t = m.get("t")
            if t in HANDLERS:
                asyncio.create_task(run_request(app, room, peer, ws, m))
            elif t == "state":
                for key in ("mic", "cam", "rec"):
                    if key in m:
                        setattr(peer, key, bool(m[key]))
                await broadcast(room, {"t": "peer-state", "pid": peer.pid, "mic": peer.mic,
                                       "cam": peer.cam, "rec": peer.rec}, exclude=peer.pid)
            elif t == "screen-stop":
                sid, mids = peer.screen_session, peer.screen_mids
                peer.screen_session, peer.screen_tracks, peer.screen_mids = None, [], []
                asyncio.create_task(close_tracks(app, sid, mids))
                await broadcast(room, {"t": "peer-screen", "pid": peer.pid, "sessionId": None}, exclude=peer.pid)
            elif t == "admit":
                w = room.waiting.get(str(m.get("wid", "")))
                if w and not w["fut"].done():
                    w["fut"].set_result(bool(m.get("ok")))
            elif t == "lock":
                room.locked = bool(m.get("on"))
                if room.locked:  # locking also turns away anyone still knocking
                    for w in room.waiting.values():
                        if not w["fut"].done():
                            w["fut"].set_result(False)
                await broadcast(room, {"t": "lock", "on": room.locked})
            elif t == "mode":
                if m.get("mode") in ("off", "call", "video") and m["mode"] != room.audio_mode:
                    room.audio_mode = m["mode"]
                    await broadcast(room, {"t": "mode", "mode": room.audio_mode, "by": peer.name})
            elif t == "ping":
                await safe_send(ws, {"t": "pong", "ts": m.get("ts")})
            elif t == "leave":
                left_explicitly = True
                break
    except Exception:
        log.exception("ws error")
    finally:
        if room and peer and peer.ws is ws:
            if left_explicitly:
                await remove_peer(app, room, peer)
            else:
                peer.ws = None
                peer.grace = asyncio.create_task(grace_then_remove(app, room, peer))
        if not ws.closed:
            await ws.close()
    return ws


# ------------------------------------------------------------- recordings ----
REC_ID = re.compile(r"^[0-9a-f]{24}$")


def find_peer(code: str, cid: str) -> Peer | None:
    room = ROOMS.get(norm_code(code))
    if not room:
        return None
    return next((p for p in room.peers.values() if p.cid == cid), None)


async def read_json(request: web.Request) -> dict:
    try:
        data = await request.json()
    except Exception:
        raise APIError("Expected JSON body.")
    if not isinstance(data, dict):
        raise APIError("Expected JSON object.")
    return data


def need_admin(request: web.Request) -> None:
    """Recording is only for the admin emails in config.py."""
    user = login.user_from_request(request)
    if not user or not user["admin"]:
        raise APIError("Recording is restricted.", "forbidden", 403)


async def rec_start(request: web.Request) -> web.Response:
    need_admin(request)
    data = await read_json(request)
    peer = find_peer(data.get("code"), str(data.get("cid", "")))
    if not peer:
        raise APIError("Join the call first.", "forbidden", 403)
    mime = str(data.get("mime", ""))
    ext = "mp4" if "mp4" in mime else "webm"
    rid = secrets.token_hex(12)
    filename = f"{rid}.{ext}"
    (REC_DIR / filename).touch()
    with db() as c:
        c.execute(
            "INSERT INTO recordings(id,code,name,mime,filename,started_ms) VALUES(?,?,?,?,?,?)",
            (rid, norm_code(data.get("code")), peer.name, mime[:100], filename, now_ms()),
        )
    log.info("recording start id=%s room=%s by=%s", rid, norm_code(data.get("code")), peer.name)
    return web.json_response({"id": rid})


def rec_row(rid: str) -> sqlite3.Row:
    if not REC_ID.match(rid):
        raise APIError("Not found.", "not_found", 404)
    with db() as c:
        row = c.execute("SELECT * FROM recordings WHERE id=?", (rid,)).fetchone()
    if not row:
        raise APIError("Not found.", "not_found", 404)
    return row


async def rec_chunk(request: web.Request) -> web.Response:
    need_admin(request)
    row = rec_row(request.match_info["id"])
    if row["ended_ms"]:
        raise APIError("Recording already finished.", "closed", 409)
    try:
        seq = int(request.query.get("seq", ""))
    except ValueError:
        raise APIError("seq required.")
    if seq < row["chunks"]:  # retry of a chunk we already stored
        return web.json_response({"ok": True, "dup": True})
    if seq > row["chunks"]:
        raise APIError("Out of order chunk.", "order", 409)
    body = await request.read()
    if row["size"] + len(body) > MAX_REC_MB * 1024 * 1024:
        raise APIError("Recording size limit reached.", "too_big", 413)
    with open(REC_DIR / row["filename"], "ab") as f:
        f.write(body)
    with db() as c:
        c.execute("UPDATE recordings SET size=size+?, chunks=chunks+1 WHERE id=?", (len(body), row["id"]))
    return web.json_response({"ok": True})


async def rec_stop(request: web.Request) -> web.Response:
    need_admin(request)
    row = rec_row(request.match_info["id"])
    with db() as c:
        c.execute("UPDATE recordings SET ended_ms=? WHERE id=? AND ended_ms IS NULL", (now_ms(), row["id"]))
    log.info("recording stop id=%s size=%s", row["id"], row["size"])
    return web.json_response({"ok": True})


async def rec_list(request: web.Request) -> web.Response:
    need_admin(request)
    code =norm_code(request.query.get("code"))
    if not find_peer(code, request.query.get("cid", "")):
        raise APIError("Join the call first.", "forbidden", 403)
    with db() as c:
        rows = c.execute(
            "SELECT id,name,mime,started_ms,ended_ms,size FROM recordings WHERE code=? AND size>0 "
            "ORDER BY started_ms DESC LIMIT 30", (code,)).fetchall()
    return web.json_response({"recordings": [dict(r) for r in rows]})


async def rec_download(request: web.Request) -> web.StreamResponse:
    need_admin(request)
    row = rec_row(request.match_info["id"])
    path = REC_DIR / row["filename"]
    if not path.is_file():
        raise APIError("File missing.", "not_found", 404)
    stamp = time.strftime("%Y%m%d-%H%M%S", time.localtime(row["started_ms"] / 1000))
    ext = path.suffix
    return web.FileResponse(path, headers={
        "Content-Disposition": f'attachment; filename="call-{stamp}{ext}"',
        "Content-Type": row["mime"].split(";")[0] or "video/webm",
        "Cache-Control": "no-store",
    })


# ----------------------------------------------------------------- app ----
@web.middleware
async def errors_and_headers(request: web.Request, handler):
    try:
        response = await handler(request)
    except APIError as exc:
        response = web.json_response({"ok": False, "error": str(exc), "code": exc.code}, status=exc.status)
    except web.HTTPException:
        raise
    except Exception:
        log.exception("unhandled error on %s", request.path)
        response = web.json_response({"ok": False, "error": "Server error."}, status=500)
    response.headers.setdefault("X-Content-Type-Options", "nosniff")
    response.headers.setdefault("Referrer-Policy", "no-referrer")
    response.headers.setdefault("Permissions-Policy", "camera=(self), microphone=(self), display-capture=(self)")
    return response


def origin(request: web.Request) -> str:
    """Public https://host used for og:image / canonical links (PUBLIC_URL wins, else read from the request)."""
    if PUBLIC_URL:
        return PUBLIC_URL
    proto = request.headers.get("X-Forwarded-Proto", "").split(",")[0].strip() or request.scheme
    host = request.headers.get("X-Forwarded-Host", "").split(",")[0].strip() or request.host
    return f"{proto}://{host}"


async def index(request: web.Request) -> web.StreamResponse:
    html = (STATIC_DIR / "index.html").read_text(encoding="utf-8").replace("__ORIGIN__", origin(request))
    return web.Response(text=html, content_type="text/html", headers={"Cache-Control": "no-cache"})


async def favicon(request: web.Request) -> web.StreamResponse:
    return web.FileResponse(STATIC_DIR / "favicon.ico", headers={"Cache-Control": "public, max-age=86400"})


async def robots(request: web.Request) -> web.Response:
    # Only the landing page is public; the API, recordings and websocket are not for crawlers.
    body = f"User-agent: *\nAllow: /\nDisallow: /api/\nDisallow: /auth/\nDisallow: /rec/\nDisallow: /ws\n\nSitemap: {origin(request)}/sitemap.xml\n"
    return web.Response(text=body, content_type="text/plain")


async def sitemap(request: web.Request) -> web.Response:
    body = ('<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">'
            f"<url><loc>{origin(request)}/</loc><changefreq>monthly</changefreq></url></urlset>")
    return web.Response(text=body, content_type="application/xml")


async def api_ice(request: web.Request) -> web.Response:
    if not login.user_from_request(request):
        raise APIError("Please log in with Google first.", "auth", 401)
    return web.json_response(await ice_servers(request.app), headers={"Cache-Control": "no-store"})


async def health(request: web.Request) -> web.Response:
    return web.json_response({
        "ok": True, "rooms": len(ROOMS), "peers": sum(len(r.peers) for r in ROOMS.values()),
        "sfu": bool(CF_APP_ID and CF_APP_SECRET), "turn": bool(CF_TURN_KEY_ID and CF_TURN_API_TOKEN),
    })


async def on_startup(app: web.Application) -> None:
    app["http"] = aiohttp.ClientSession()
    app["quota"] = asyncio.create_task(quota_loop(app))


async def on_cleanup(app: web.Application) -> None:
    app["quota"].cancel()
    await app["http"].close()


def build_app() -> web.Application:
    app = web.Application(middlewares=[errors_and_headers], client_max_size=32 * 1024 * 1024)
    app.router.add_get("/", index)
    app.router.add_get("/ws", ws_handler)
    app.router.add_get("/favicon.ico", favicon)
    app.router.add_get("/robots.txt", robots)
    app.router.add_get("/sitemap.xml", sitemap)
    app.router.add_get("/health", health)
    app.router.add_get("/api/ice", api_ice)
    app.router.add_post("/api/rec/start", rec_start)
    app.router.add_post("/api/rec/{id}/chunk", rec_chunk)
    app.router.add_post("/api/rec/{id}/stop", rec_stop)
    app.router.add_get("/api/recordings", rec_list)
    app.router.add_get("/rec/{id}", rec_download)
    app.router.add_static("/static/", STATIC_DIR, show_index=False)
    login.setup(app, origin, log)  # /auth/google, /api/me, /api/signup, /api/logout + user.db
    app.on_startup.append(on_startup)
    app.on_cleanup.append(on_cleanup)
    return app


def main() -> None:
    init_db()
    print(f"Open Call on http://{HOST}:{PORT}/   (project folder: {BASE_DIR})")
    print(f"  database   {DB_PATH}")
    print(f"  recordings {REC_DIR}")
    print(f"  log        {LOG_DIR / 'server.log'}")
    print(f"  users      {login.USER_DB}")
    if not (config.GOOGLE_CLIENT_ID and config.GOOGLE_CLIENT_SECRET):
        print("WARNING: Google keys not set in config.py - nobody can log in.")
    else:
        print(f"  Google redirect URI to allow: {login.redirect_uri()}")
        print(f"  Google JavaScript origin:     {config.PUBLIC_URL or 'https://YOUR-DOMAIN'}")
    if not config.ADMIN_EMAILS:
        print("NOTE: ADMIN_EMAILS is empty in config.py - nobody can record and every call is limited.")
    if not (CF_APP_ID and CF_APP_SECRET):
        print("WARNING: CF_REALTIME_APP_ID / CF_REALTIME_APP_SECRET not set - calls cannot start.")
    if not (CF_TURN_KEY_ID and CF_TURN_API_TOKEN):
        print("WARNING: CF_TURN_KEY_ID / CF_TURN_API_TOKEN not set - STUN only (no relay modes).")
    web.run_app(build_app(), host=HOST, port=PORT, access_log=None, print=None)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(0)
