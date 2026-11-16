# Cloudflare Realtime setup

Open Call uses two separate Cloudflare Realtime services:

- **Realtime SFU** handles the live audio/video publications and subscriptions. Its App ID and App Secret are required.
- **Realtime TURN** provides relay connectivity for browsers that cannot establish a usable direct ICE path. Its credentials are recommended but optional.

The application server makes authenticated Cloudflare API requests. Neither long-lived secret belongs in the browser.

## A. Create the SFU application

1. Sign in to the [Cloudflare dashboard](https://dash.cloudflare.com/).
2. Select the Cloudflare account you intend to use.
3. Open **Realtime → Serverless SFU**.
4. Create an SFU application.
5. Copy the generated **App ID** and **App Secret** and store them in a password manager.
6. Add them to the server's `.env` file:

```dotenv
CF_REALTIME_APP_ID=your_app_id
CF_REALTIME_APP_SECRET=your_app_secret
```

The backend sends requests to `https://rtc.live.cloudflare.com/v1` and authenticates with `Authorization: Bearer <APP_SECRET>`. The source code uses the App ID in the API path. Keep the secret on the server and rotate it if it is exposed.

Official references: [Cloudflare SFU quickstart](https://developers.cloudflare.com/realtime/sfu/get-started/) · [Connection API](https://developers.cloudflare.com/realtime/sfu/api/).

## B. Create TURN credentials (recommended)

1. In the Cloudflare dashboard, open the Realtime TURN service.
2. Create a TURN key.
3. Copy the **TURN key ID** and the corresponding **TURN key API token**.
4. Store them in `.env`:

```dotenv
CF_TURN_KEY_ID=your_turn_key_id
CF_TURN_API_TOKEN=your_turn_key_api_token
```

Open Call uses these values on the backend to call the credential-generation endpoint and obtain temporary ICE server credentials. The browser receives the temporary ICE server configuration; it never receives the long-lived TURN key token.

Cloudflare's current Realtime TURN credential flow requires short-lived credentials with a TTL. Open Call requests a 24-hour TTL and caches the returned ICE server configuration in memory for up to six hours. It also filters returned TURN URLs using port 53 because browser ICE gathering can wait on that port.

Official reference: [Cloudflare — Generate TURN credentials](https://developers.cloudflare.com/realtime/turn/generate-credentials/).

## C. Verify configuration

Restart Open Call and inspect:

```bash
curl -sS http://127.0.0.1:9876/health
```

- `"sfu": true` means both SFU values are present.
- `"turn": true` means both TURN settings are present. This is a configuration indicator, not a guarantee that credential generation or every network path will succeed.

Then test a call from two different networks, for example your home Wi-Fi and a mobile network. A same-machine test is useful for checking the UI, but does not prove TURN relay connectivity.

## Credential handling checklist

- Use separate SFU apps for development and production if you want isolation.
- Keep `.env` out of Git and restrict its file permissions (`chmod 600 .env`).
- Do not paste App Secrets or TURN tokens into issues, screenshots, logs, or frontend code.
- Rotate credentials immediately if they are committed or otherwise exposed.
- Review your Cloudflare account's Realtime limits, pricing, and usage before running a public service.
