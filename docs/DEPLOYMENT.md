# Production deployment

This guide describes a small single-server deployment. It assumes Open Call runs on a Linux host and a reverse proxy terminates HTTPS.

## Deployment shape

```text
Internet
   │ HTTPS + WebSocket (wss)
   ▼
Caddy / Nginx / Cloudflare Tunnel
   │ HTTP + WebSocket on loopback
   ▼
Open Call (127.0.0.1:9876)
   ├── SQLite + recordings + logs on local disk
   └── HTTPS API calls to Cloudflare Realtime

Browsers ── WebRTC media ──> Cloudflare Realtime SFU / TURN
```

## 1. Prepare the host

Install Python 3.10+, Git, and a reverse proxy. Create a dedicated unprivileged service account and choose a persistent application directory. Example paths below use `/opt/open-call`; adjust them to your environment.

```bash
sudo useradd --system --home /opt/open-call --shell /usr/sbin/nologin opencall
sudo mkdir -p /opt/open-call
sudo chown opencall:opencall /opt/open-call
sudo -u opencall git clone https://github.com/blurry-eyez/open-call.git /opt/open-call
```

Create `/opt/open-call/.env`, set the Cloudflare credentials, and restrict permissions:

```bash
sudo install -o opencall -g opencall -m 600 /dev/null /opt/open-call/.env
sudo -u opencall nano /opt/open-call/.env
```

At minimum, set the SFU values and the public URL:

```dotenv
CF_REALTIME_APP_ID=...
CF_REALTIME_APP_SECRET=...
CF_TURN_KEY_ID=...
CF_TURN_API_TOKEN=...
HOST=127.0.0.1
PORT=9876
PUBLIC_URL=https://call.example.com
```

Prepare the virtual environment:

```bash
sudo -u opencall python3 -m venv /opt/open-call/.venv
sudo -u opencall /opt/open-call/.venv/bin/python -m pip install -r /opt/open-call/requirements.txt
```

The service account must be able to write to the project directory because the current app stores its SQLite database, recordings, and logs next to `server.py`. For a stricter filesystem layout, modify the application to use separate writable data directories before deployment.

## 2. Configure a reverse proxy

Example Caddy site:

```caddyfile
call.example.com {
    reverse_proxy 127.0.0.1:9876
}
```

Caddy automatically obtains and renews TLS certificates when DNS and inbound ports are configured correctly. Its reverse proxy supports WebSocket upgrades without a separate WebSocket location block.

For Nginx, configure HTTP/1.1 proxying and the `Upgrade` / `Connection` headers for `/ws`, and set the appropriate forwarded host and scheme headers. The proxy must preserve WebSocket connections and support the app's recording uploads (default maximum client body size is 32 MiB per chunk request).

### Cloudflare Tunnel alternative

A Cloudflare Tunnel can expose the local server without opening an inbound port on the host. Create a tunnel and configure an ingress rule for the public hostname:

```yaml
ingress:
  - hostname: call.example.com
    service: http://127.0.0.1:9876
  - service: http_status:404
```

Run the tunnel using its credentials/configuration from Cloudflare. Keep the Python app bound to loopback and set `PUBLIC_URL=https://call.example.com`. A tunnel handles the browser-to-origin HTTP/WebSocket path; WebRTC media still uses Cloudflare Realtime SFU/TURN as described in the architecture guide.

Set `PUBLIC_URL` to the canonical HTTPS origin. This ensures canonical tags, Open Graph URLs, `robots.txt`, and `sitemap.xml` use the correct host.

## 3. Run with systemd

Create `/etc/systemd/system/open-call.service`:

```ini
[Unit]
Description=Open Call video conferencing server
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=opencall
Group=opencall
WorkingDirectory=/opt/open-call
EnvironmentFile=/opt/open-call/.env
ExecStart=/opt/open-call/.venv/bin/python /opt/open-call/server.py
Restart=always
RestartSec=3
UMask=0077

[Install]
WantedBy=multi-user.target
```

Enable and start:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now open-call
sudo systemctl status open-call
```

Follow logs:

```bash
sudo journalctl -u open-call -f
```

The app also writes rotating logs to `/opt/open-call/logs/server.log`.

## 4. Network and proxy checklist

- Serve the public app over HTTPS. Camera, microphone, and screen capture require a secure browser context outside localhost.
- Keep `HOST=127.0.0.1` when a local reverse proxy connects to the Python process.
- Do not expose port `9876` directly when the proxy is intended to be the only public entry point.
- Ensure the proxy supports WebSocket upgrades on `/ws`.
- Ensure the proxy accepts chunked recording uploads; the app's aiohttp request limit is 32 MiB per request.
- Use a trusted proxy that overwrites/sanitizes forwarded IP and scheme headers. Do not trust arbitrary client-supplied forwarding headers at a publicly reachable origin.
- Set `PUBLIC_URL` explicitly.
- Keep Cloudflare credentials in `.env` or a secrets manager, never in the repository or client assets.

## 5. Backups and operations

Back up these together:

- `database.db`
- `recordings/`

The database stores metadata, not the video bytes. Restoring only the database does not restore the recordings. Protect backups as sensitive personal data and define a retention/deletion policy before enabling recording for real users.

Monitor disk capacity: recording storage can grow quickly. `MAX_RECORDING_MB` limits an individual recording, not the total storage consumed by all recordings. Add a host-level disk quota, monitoring, and a cleanup job appropriate to your deployment.

The health endpoint is:

```bash
curl -fsS https://call.example.com/health
```

It provides basic app state and credential-presence flags. It is not a deep SFU connectivity test.

## Production launch checklist

- [ ] Cloudflare SFU App ID and App Secret configured.
- [ ] TURN key and token configured; tested from different networks.
- [ ] HTTPS is active and valid.
- [ ] `PUBLIC_URL` matches the public hostname.
- [ ] Python port is not directly exposed to the internet.
- [ ] WebSocket upgrade works through the proxy.
- [ ] `.env` is private and not tracked by Git.
- [ ] Recording consent, access, retention, and deletion policies are documented.
- [ ] `database.db`, `recordings/`, and logs have disk-space monitoring and backups.
- [ ] Room-code policy and abuse controls are appropriate for your audience.
- [ ] Cloudflare Realtime usage, plan limits, and billing have been reviewed.
