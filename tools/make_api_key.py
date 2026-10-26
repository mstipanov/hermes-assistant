#!/usr/bin/env python3
"""Mint a long-lived Hermes dashboard bearer token for the Android app.

The dashboard's basic-auth provider signs stateless access tokens with
``dashboard.basic_auth.secret``. The same token is accepted as
``Authorization: Bearer <token>`` on every REST route and as ``?token=<token>``
on the WebSocket upgrade, which makes it a convenient "API key" for a personal
mobile client.

The token grants the same access as the dashboard password. Keep the file at
mode 600, never commit it, and rotate ``dashboard.basic_auth.secret`` to revoke
every issued token at once.

Usage:
  ~/.hermes/tools/python-3.14.7+20260901-darwin-arm64/bin/python3 \
      tools/make_api_key.py --days 365

Writes ~/.hermes-assistant/api.key (chmod 600) and prints only the path and
expiry, never the token itself.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import os
import re
import time
from pathlib import Path

HOME = Path.home()
CONFIG = HOME / ".hermes" / "config.yaml"
OUT = HOME / ".hermes-assistant" / "api.key"


def load_basic_auth() -> tuple[str, bytes]:
    text = CONFIG.read_text()
    m = re.search(
        r"^dashboard:\n(?:^[ \t].*\n)*?^[ \t]{2}basic_auth:\n((?:^[ \t]{4}.*\n)+)",
        text,
        re.M,
    )
    if not m:
        raise SystemExit("dashboard.basic_auth block not found in config.yaml")
    block = m.group(1)
    user = re.search(r"^[ \t]+username:\s*(\S+)", block, re.M)
    secret = re.search(r"^[ \t]+secret:\s*([0-9a-fA-F]{32,})", block, re.M)
    if not user or not secret:
        raise SystemExit("dashboard.basic_auth username/secret not found")
    raw = secret.group(1)
    # Mirrors plugins/dashboard_auth/basic._resolve_secret: base64 first, then hex.
    key = raw.encode()
    for decoder in (base64.b64decode, bytes.fromhex):
        try:
            decoded = decoder(raw)
            if len(decoded) >= 16:
                key = decoded
                break
        except (ValueError, TypeError):
            pass
    return user.group(1), key


def sign(payload: dict, key: bytes) -> str:
    raw = json.dumps(payload, separators=(",", ":")).encode()
    sig = hmac.new(key, raw, hashlib.sha256).digest()
    return base64.urlsafe_b64encode(raw + sig).decode()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--days", type=int, default=365, help="token lifetime in days")
    parser.add_argument("--user", default=None, help="dashboard user id")
    args = parser.parse_args()

    user, key = load_basic_auth()
    if args.user:
        user = args.user
    now = int(time.time())
    exp = now + args.days * 86_400
    token = sign(
        {
            "sub": user,
            "kind": "access",
            "exp": exp,
            "app": "hermes-android",
        },
        key,
    )

    OUT.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    OUT.write_text(token + "\n")
    os.chmod(OUT, 0o600)
    expiry = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(exp))
    print(f"API key written to {OUT} (user={user}, expires={expiry})")
    print("Copy the file contents into the app's 'API token' field.")


if __name__ == "__main__":
    main()
