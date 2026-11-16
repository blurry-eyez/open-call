#!/usr/bin/env bash
# Start Open Call from this project directory.
# The app stores its database, recordings and logs beside server.py.
set -Eeuo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

PYTHON_BIN="${PYTHON:-python3}"
if ! command -v "$PYTHON_BIN" >/dev/null 2>&1; then
  echo "Error: $PYTHON_BIN was not found. Install Python 3.10 or newer." >&2
  exit 1
fi

if [[ ! -d .venv ]]; then
  "$PYTHON_BIN" -m venv .venv
fi

# shellcheck disable=SC1091
source .venv/bin/activate
python -m pip install --disable-pip-version-check -q -r requirements.txt
exec python server.py
