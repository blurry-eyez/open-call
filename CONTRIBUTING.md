# Contributing

Thanks for taking an interest in Open Call. Contributions that improve reliability, documentation, accessibility, privacy, security, and self-hosting are welcome.

## Before opening an issue

- Search existing issues to avoid duplicates.
- Include your OS, Python version, browser/version, deployment type, and the commit you are using.
- For connection problems, include relevant redacted log lines and whether TURN is configured.
- Never attach `.env`, API secrets, call recordings, personal data, or unredacted network identifiers.

## Development setup

```bash
git clone https://github.com/blurry-eyez/open-call.git
cd open-call
cp .env.example .env
# Add test Cloudflare credentials to .env
python3 -m venv .venv
source .venv/bin/activate
python -m pip install -r requirements.txt
python server.py
```

The app has no frontend build step. Most client code currently lives in `static/index.html`.

## Before submitting a pull request

```bash
python -m unittest discover -s tests -v
python -m py_compile server.py
git diff --check
```

For a functional change, test with at least two independent browser participants. If you change media handling, test both camera/microphone and screen sharing. If you change recordings, test chunk ordering, stop/finalization, download, and the configured size limit.

## Pull request expectations

- Explain the problem and the behaviour change.
- Keep changes focused; avoid unrelated formatting churn in the large HTML file.
- Update the relevant docs and `.env.example` when configuration changes.
- Add or update automated tests for server-side logic where practical.
- Include screenshots for user-facing visual changes when useful, but do not include private call content.
- Call out security, privacy, migration, or deployment implications.

## Scope notes

Open Call is intentionally a lightweight self-hostable project. Discuss major architectural changes before investing in a large implementation. Do not add third-party credentials or tracking scripts to the public client without explaining the privacy impact.
