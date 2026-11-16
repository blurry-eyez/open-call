# Security policy and privacy notes

Open Call is an open-source starting point. It has not been represented as independently security-audited or production-hardened. Deployers are responsible for threat modelling, hardening, monitoring, legal compliance, and user communication.

## Reporting a vulnerability

Please do not publish secrets, personal data, or an exploitable vulnerability in a public issue. Use GitHub's private vulnerability reporting for this repository when enabled. If private reporting is unavailable, contact the repository maintainer through the email address listed on their GitHub profile and include steps to reproduce, impact, and affected commit/version.

Do not include real call recordings, Cloudflare credentials, TURN tokens, or other users' data in a report.

## Important current limitations

Read this section before exposing an instance to the public internet.

### No account authentication

The current application has no user accounts, verified identity, or durable participant authorization. A pairing code is the room's practical join secret. Anyone who knows or guesses the code can attempt to join; when a room is occupied, an existing participant is asked to admit newcomers. There is no separate host/owner role: participants in the room can approve or deny waiting people and toggle the room lock. Use long, random pairing codes and do not advertise them publicly.

### Room state and rate limiting are process-local

Room membership, join-rate counters, waitlists, and peer permissions live in process memory. The join limiter is per process and should not be treated as a complete anti-abuse system. A restart clears active rooms; multiple instances do not share state.

### Cloudflare SFU is part of the media trust boundary

Live media travels from the browser to Cloudflare Realtime SFU and then to other browsers. Open Call does not implement application-level end-to-end encryption between participants. Do not describe calls as end-to-end encrypted. Review Cloudflare's current product terms and privacy documentation for your use case.

### Recording links are not account-protected

Recording downloads use an opaque random recording ID. The download route does not require a logged-in account or current room membership; possession of a valid recording ID is enough to request the file. Treat recording URLs as bearer links and keep them private. The current app does not provide an administrative authentication system for listing, deleting, or managing all recordings.

The UI shows a recording indicator to participants, but this is not a legal consent workflow. Obtain participants' consent where required and implement stronger recording controls before deploying in contexts that need them.

### Recordings and operational data are stored on your server

The browser combines remote tiles and audio into a recording and uploads it to your server. Files are stored under `recordings/`; the index is `database.db`; application logs are under `logs/`. The current implementation does not provide an automatic retention policy, encryption-at-rest feature, or complete data-subject management workflow. Protect the disk and backups, limit access, and define a deletion schedule. The unauthenticated `/health` endpoint also reports active room and participant counts, plus whether credentials are configured.

### Reverse proxy and forwarded headers

Run behind a trusted TLS reverse proxy or tunnel. Keep the Python listener on loopback where possible. Configure the proxy to overwrite forwarded IP/host/scheme headers and prevent direct public access to the origin. Do not rely on client-supplied forwarding headers as identity proof.

## Before a public deployment

- Use strong, unguessable pairing codes.
- Keep `CF_REALTIME_APP_SECRET` and `CF_TURN_API_TOKEN` on the server only.
- Set restrictive file permissions on `.env` and the runtime data directories.
- Use HTTPS and secure WebSocket transport.
- Restrict direct access to the Python origin port.
- Add identity, room authorization, distributed rate limiting, abuse reporting, and operational monitoring appropriate to your users.
- Add authenticated recording management and explicit recording consent where required.
- Set storage quotas and a retention policy; secure backups.
- Review Cloudflare's current Realtime limits, terms, and privacy documentation.

## Secret exposure

If a Cloudflare secret is exposed, revoke or rotate it in Cloudflare immediately. Removing a secret from the latest commit does not remove it from Git history. If a secret was committed, rotate it first, then clean repository history before publishing or sharing the repository.
