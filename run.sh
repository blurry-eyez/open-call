#!/usr/bin/env bash
# Start Open Call from the project folder (all data stays in this folder).
cd "$(dirname "$0")"
[ -d .venv ] || python3 -m venv .venv
. .venv/bin/activate
pip install -q -r requirements.txt
exec python server.py
