# Troubleshooting

## The page loads, but calls do not start

Check:

```bash
curl -sS http://127.0.0.1:9876/health
```

The response must contain `"sfu":true`. If it is false, set `CF_REALTIME_APP_ID` and `CF_REALTIME_APP_SECRET` in `.env`, then restart the server.

Check the server log:

```bash
tail -f logs/server.log
```

Look for Cloudflare SFU errors, invalid credentials, or network failures.

## No camera or microphone prompt appears

- Use HTTPS on deployed hosts. Plain HTTP is only suitable for local development on `localhost` / `127.0.0.1`.
- Check browser site permissions for camera and microphone.
- Close other apps or tabs that may be using the device.
- Confirm the selected camera and microphone in Settings.

## Participants can join but cannot see or hear each other

- Configure TURN credentials; without them this app only returns STUN configuration.
- Test from different networks. Two tabs on the same computer do not prove NAT traversal works.
- Check outbound firewall rules and whether the browser network blocks WebRTC UDP/TCP traffic.
- Inspect Cloudflare Realtime usage and errors in the Cloudflare dashboard.
- Use the in-call diagnostics to compare network stats and selected connection mode.

## A participant stays in the waiting state

A participant who enters an occupied room must be admitted by someone already in the room. The waiting request expires after `KNOCK_SECONDS` (120 seconds by default). A locked room rejects new participants immediately.

## WebSocket disconnects behind a reverse proxy

- Ensure the proxy supports WebSocket upgrades on `/ws`.
- Check idle/read timeout settings in the proxy and any tunnel layer.
- Verify the public URL is HTTPS and the browser connects to `wss://`.
- Watch the app log while reconnecting.

## Recording does not start or save

- Check that the browser supports `MediaRecorder` and the chosen MIME type.
- Check disk space and write permission for `recordings/` and `database.db`.
- `MAX_RECORDING_MB` is the per-recording size cap.
- Check that the reverse proxy permits recording chunk uploads. The app accepts requests up to 32 MiB.
- Recordings are uploaded to the host; a nearly full disk or interrupted connection can prevent finalization.

## Files and logs

- Server log: `logs/server.log` (and rotated copies).
- Recording index: `database.db`.
- Recording files: `recordings/`.

These are created automatically next to `server.py`. Do not delete the database while expecting existing recording metadata to remain available.
