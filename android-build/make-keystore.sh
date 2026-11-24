#!/usr/bin/env bash
# Creates your permanent APK signing key (once), prints its SHA-1 for Google Cloud,
# and prints the GitHub secrets to set. The key is stored OUTSIDE the repo.
set -euo pipefail
DIR="${KEYSTORE_DIR:-$HOME/.open-call-keystore}"
KS="$DIR/release.jks"; ALIAS="opencall"
mkdir -p "$DIR"; chmod 700 "$DIR"
if [ -f "$KS" ]; then echo "Keystore already exists: $KS (not overwritten)"; PASS=$(cat "$DIR/password.txt")
else
  PASS=$(openssl rand -hex 16); echo -n "$PASS" > "$DIR/password.txt"; chmod 600 "$DIR/password.txt"
  keytool -genkeypair -keystore "$KS" -alias "$ALIAS" -keyalg RSA -keysize 2048 -validity 36500 \
    -storepass "$PASS" -keypass "$PASS" -dname "CN=Open Call"
  echo "Created $KS  (BACK THIS FOLDER UP: losing it means users can't update the app)"
fi
base64 -w0 "$KS" > "$DIR/keystore.b64"
echo; echo "=== SHA-1 / SHA-256 (add the SHA-1 to your Google Cloud ANDROID OAuth client) ==="
keytool -list -v -keystore "$KS" -alias "$ALIAS" -storepass "$PASS" | grep -E "SHA1:|SHA256:"
echo; echo "=== GitHub secrets to set (repo -> Settings -> Secrets and variables -> Actions) ==="
echo "KEYSTORE_BASE64   = contents of $DIR/keystore.b64"
echo "KEYSTORE_PASSWORD = $PASS"
echo "KEY_ALIAS         = $ALIAS"
echo "KEY_PASSWORD      = $PASS"
if command -v gh >/dev/null; then
  echo; echo "With the GitHub CLI (run inside the repo):"
  echo "  gh secret set KEYSTORE_BASE64 < $DIR/keystore.b64"
  echo "  gh secret set KEYSTORE_PASSWORD --body \"$PASS\""
  echo "  gh secret set KEY_ALIAS --body $ALIAS"
  echo "  gh secret set KEY_PASSWORD --body \"$PASS\""
fi
