# Setup and configuration

This guide takes you from a clean clone to a working local Open Call instance.

## Prerequisites

- Python 3.10 or newer.
- Git.
- A Cloudflare account and a Realtime SFU application.
- A modern browser with camera and microphone support.

No Node.js build step is required. The frontend is served from `static/index.html`.

## 1. Clone the project

```bash
git clone https://github.com/blurry-eyez/open-call.git
cd open-call
```

## 2. Create Cloudflare credentials

Follow [Cloudflare Realtime setup](CLOUDFLARE_SETUP.md). You need an SFU App ID and App Secret to establish calls. TURN credentials are optional for a basic test, but recommended for real-world connectivity.

## 3. Configure environment variables

```bash
cp .env.example .env
chmod 600 .env
```

Edit `.env` and set:

```dotenv
CF_REALTIME_APP_ID=...
CF_REALTIME_APP_SECRET=...
CF_TURN_KEY_ID=...
CF_TURN_API_TOKEN=...
```

The SFU values are required. The TURN values are optional; without them, the app returns Cloudflare STUN configuration only and the UI cannot use TURN relay modes.

Do not commit `.env`. It is covered by `.gitignore`.

## 4. Install and run

The helper script creates a virtual environment and installs the Python dependencies:

```bash
./run.sh
```

Or install manually:

```bash
python3 -m venv .venv
source .venv/bin/activate
python -m pip install -r requirements.txt
python server.py
```

The default bind address is `127.0.0.1:9876`.

## 5. Test with two browsers

1. Open `http://127.0.0.1:9876` in a browser.
2. Enter a display name and a pairing code of at least three characters.
3. Allow camera and microphone access.
4. Open a second browser or a private window on the same machine. Do not duplicate the first tab if you want to test a separate participant identity.
5. Join using the same code and a different display name.
6. Accept the join request in the first participant's waiting list.
7. Confirm that audio and video appear in both directions.

For a one-machine test, headphones prevent feedback. For phones and remote participants, deploy behind HTTPS first.

## Health check

```bash
curl -sS http://127.0.0.1:9876/health
```

Example shape:

```json
{"ok":true,"rooms":0,"peers":0,"sfu":true,"turn":true}
```

The `sfu` flag reports whether the required SFU App ID and App Secret are configured. The `turn` flag reports whether the TURN key settings are present; an actual credential-generation failure is logged and the app falls back to STUN-only configuration.

## Configuration reference

Settings are read once when the server starts. Restart the process after changing `.env`.

| Name | Default | Description |
|---|---:|---|
| `CF_REALTIME_APP_ID` | empty | Required Cloudflare Realtime SFU app identifier |
| `CF_REALTIME_APP_SECRET` | empty | Required Cloudflare Realtime SFU bearer secret |
| `CF_TURN_KEY_ID` | empty | Optional TURN key identifier |
| `CF_TURN_API_TOKEN` | empty | Optional server-side token used to generate expiring ICE credentials |
| `HOST` | `127.0.0.1` | Address passed to the aiohttp server |
| `PORT` | `9876` | HTTP and WebSocket port |
| `PUBLIC_URL` | empty | Public URL used for canonical metadata, robots, and sitemap output |
| `MAX_PEERS` | `8` | Maximum number of connected peers in one room |
| `RECONNECT_GRACE_SECONDS` | `12` | Disconnect grace period before removing a participant |
| `MAX_RECORDING_MB` | `4096` | Maximum cumulative size accepted for an individual recording |
| `JOINS_PER_MINUTE` | `40` | Per-IP join-attempt limit, held in process memory |
| `KNOCK_SECONDS` | `120` | How long a newcomer can wait for an existing participant to admit them |

The `.env.example` file contains every setting and the defaults used by the current server.

## Runtime files and backups

`server.py` creates runtime data next to itself:

- `database.db`: SQLite index for recording metadata.
- `recordings/`: uploaded recording files.
- `logs/server.log`: application log, with rotating log files.

Back up `database.db` and `recordings/` together. The SQLite file alone does not contain the actual video files. Store backups outside the Git working tree and protect them as sensitive data.

## Updating the app

```bash
git pull --ff-only
source .venv/bin/activate
python -m pip install -r requirements.txt
```

Restart the service after updating code or dependencies. Back up runtime data before production updates.
