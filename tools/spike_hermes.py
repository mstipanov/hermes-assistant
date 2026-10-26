#!/usr/bin/env python3
"""M0 end-to-end spike for the Hermes Android assistant.

Verifies, through the public Cloudflare tunnel:
  1. Cloudflare Access service-token auth (REST + WebSocket upgrade)
  2. Dashboard session auth via a locally minted basic-auth token
     (the app itself will use POST /auth/password-login instead)
  3. WS ticket -> /api/ws JSON-RPC -> session.create -> prompt.submit -> streaming events
  4. /api/audio/speak -> /api/audio/transcribe round trip (Croatian)
  5. /api/audio/speak-stream WebSocket -> raw PCM

Run with the Hermes bundled interpreter (it ships `websockets`):
  ~/.hermes/tools/python-3.14.7+20260901-darwin-arm64/bin/python3 \
      tools/spike_hermes.py

Credentials are read from ~/.hermes-assistant/access.env and the Hermes
config.yaml basic-auth secret; nothing secret is printed.
"""
from __future__ import annotations

import asyncio
import base64
import hashlib
import hmac
import json
import os
import re
import struct
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

import websockets

HOME = Path.home()
ACCESS_ENV = HOME / ".hermes-assistant" / "access.env"
CONFIG_YAML = HOME / ".hermes" / "config.yaml"

BASE = "https://hermes.sting.hr"
WS_BASE = "wss://hermes.sting.hr"


def load_env(path: Path) -> dict[str, str]:
    out: dict[str, str] = {}
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        out[k.strip()] = v.strip().strip('"').strip("'")
    return out


def basic_auth_config() -> tuple[str, bytes]:
    text = CONFIG_YAML.read_text()
    # Only look inside the dashboard.basic_auth block.
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
    # Mirrors plugins/dashboard_auth/basic._resolve_secret: try base64 first,
    # then hex, then raw text. A 64-char hex string is valid base64 too, and
    # Hermes deliberately takes that branch first.
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


def mint_session(key: bytes) -> tuple[str, str, int]:
    now = int(time.time())
    exp = now + 12 * 60 * 60
    at = sign({"sub": "marko", "kind": "access", "exp": exp}, key)
    rt = sign({"sub": "marko", "kind": "refresh", "exp": now + 30 * 24 * 60 * 60}, key)
    return at, rt, exp


class Client:
    def __init__(self, env: dict[str, str], at: str, rt: str) -> None:
        self.env = env
        self.cookie = (
            f"__Host-hermes_session_at={at}; "
            f"__Host-hermes_session_rt={rt}; "
            f"__Host-hermes_session_provider=basic"
        )

    def headers(self, *, json_body: bool = False) -> dict[str, str]:
        h = {
            "CF-Access-Client-Id": self.env["CF_ACCESS_CLIENT_ID"],
            "CF-Access-Client-Secret": self.env["CF_ACCESS_CLIENT_SECRET"],
            "Cookie": self.cookie,
            "Accept": "application/json",
            "User-Agent": "HermesAssistant/0.1 (Android; spike)",
        }
        if json_body:
            h["Content-Type"] = "application/json"
        return h

    def request(self, method: str, path: str, body: dict | None = None, timeout: int = 60):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(
            f"{BASE}{path}", data=data, method=method,
            headers=self.headers(json_body=body is not None),
        )
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                raw = r.read()
                return r.status, raw
        except urllib.error.HTTPError as e:
            return e.code, e.read()

    def json_request(self, method: str, path: str, body: dict | None = None):
        status, raw = self.request(method, path, body)
        try:
            payload = json.loads(raw) if raw else None
        except json.JSONDecodeError:
            payload = raw[:500]
        return status, payload

    def ws_headers(self) -> dict[str, str]:
        return {
            "CF-Access-Client-Id": self.env["CF_ACCESS_CLIENT_ID"],
            "CF-Access-Client-Secret": self.env["CF_ACCESS_CLIENT_SECRET"],
        }


def wav_from_pcm(pcm: bytes, sample_rate: int, path: Path) -> None:
    channels, width = 1, 2
    byte_rate = sample_rate * channels * width
    header = b"RIFF" + struct.pack("<I", 36 + len(pcm)) + b"WAVE"
    header += b"fmt " + struct.pack(
        "<IHHIIHH", 16, 1, channels, sample_rate, byte_rate, channels * width, width * 8
    )
    header += b"data" + struct.pack("<I", len(pcm))
    path.write_bytes(header + pcm)


async def test_ws_rpc(client: Client) -> None:
    print("\n[3] WS ticket + JSON-RPC + agent turn")
    status, payload = client.json_request("POST", "/api/auth/ws-ticket", {})
    assert status == 200, f"ws-ticket -> {status} {payload}"
    ticket = payload["ticket"]
    print(f"    ticket minted (ttl={payload['ttl_seconds']}s)")

    url = f"{WS_BASE}/api/ws?ticket={ticket}"
    async with websockets.connect(
        url, additional_headers=client.ws_headers(), open_timeout=20,
        ping_interval=20, ping_timeout=30, max_size=8 * 1024 * 1024,
    ) as ws:
        ready = json.loads(await asyncio.wait_for(ws.recv(), 20))
        assert ready["params"]["type"] == "gateway.ready", ready
        print("    gateway.ready OK")

        next_id = 1

        async def rpc(method: str, params: dict) -> dict:
            nonlocal next_id
            rid = next_id
            next_id += 1
            await ws.send(json.dumps({"jsonrpc": "2.0", "id": rid, "method": method, "params": params}))
            while True:
                frame = json.loads(await asyncio.wait_for(ws.recv(), 90))
                if frame.get("id") == rid and ("result" in frame or "error" in frame):
                    if "error" in frame:
                        raise RuntimeError(f"{method} -> {frame['error']}")
                    return frame["result"]
                if frame.get("method") and str(frame.get("id", "")).startswith("srq-"):
                    # Server -> client request during the turn. Decline politely.
                    await ws.send(json.dumps({
                        "jsonrpc": "2.0", "id": frame["id"],
                        "error": {"code": 4404, "message": "spike client does not render prompts"},
                    }))
                # otherwise: a notification event; ignore here

        await rpc("client.capabilities", {"server_requests": True})
        print("    client.capabilities OK")

        created = await rpc("session.create", {"cwd": str(HOME), "source": "mobile-spike"})
        sid = created["session_id"]
        print(f"    session.create OK  sid={sid}  stored={created.get('stored_session_id')}")

        await ws.send(json.dumps({
            "jsonrpc": "2.0", "id": next_id, "method": "prompt.submit",
            "params": {"session_id": sid, "text": "Reci samo: bok", "surface": "mobile"},
        }))
        next_id += 1

        text_parts: list[str] = []
        tool_events: list[str] = []
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            frame = json.loads(await asyncio.wait_for(ws.recv(), 180))
            if frame.get("method") and str(frame.get("id", "")).startswith("srq-"):
                print(f"    server request: {frame.get('method')}")
                await ws.send(json.dumps({
                    "jsonrpc": "2.0", "id": frame["id"],
                    "error": {"code": 4404, "message": "spike client does not render prompts"},
                }))
                continue
            params = frame.get("params") or {}
            etype = params.get("type")
            if etype == "message.delta":
                text_parts.append(str((params.get("payload") or {}).get("text", "")))
            elif etype == "message.complete":
                print("    message.complete OK")
                break
            elif etype in {"tool.start", "tool.complete"}:
                name = (params.get("payload") or {}).get("name")
                tool_events.append(f"{etype}:{name}")
            elif etype == "error":
                print(f"    ERROR event: {params.get('payload')}")
                break
        answer = "".join(text_parts).strip()
        print(f"    streamed chars={len(answer)} answer={answer[:120]!r}")
        if tool_events:
            print(f"    tool events: {tool_events[:8]}")
        assert answer, "no streamed assistant text"
        print("[3] PASS")


async def test_audio(client: Client) -> None:
    print("\n[4] TTS -> STT round trip (Croatian)")
    status, payload = client.json_request(
        "POST", "/api/audio/speak", {"text": "Bok, ja sam Hermes. Kako mogu pomoći?"}
    )
    assert status == 200, f"speak -> {status} {payload}"
    data_url = payload["data_url"]
    print(f"    speak OK provider={payload.get('provider')} mime={payload['mime_type']} "
          f"bytes={len(data_url)}")

    status, payload = client.json_request(
        "POST", "/api/audio/transcribe",
        {"data_url": data_url, "mime_type": payload["mime_type"]},
    )
    assert status == 200, f"transcribe -> {status} {payload}"
    print(f"    transcribe OK provider={payload.get('provider')} text={payload.get('transcript')!r}")
    assert payload.get("transcript"), "empty transcript"
    print("[4] PASS")


async def test_tts_stream(client: Client) -> None:
    print("\n[5] WS /api/audio/speak-stream (PCM)")
    status, payload = client.json_request("POST", "/api/auth/ws-ticket", {})
    assert status == 200, payload
    url = f"{WS_BASE}/api/audio/speak-stream?ticket={payload['ticket']}"
    pcm = bytearray()
    rate = 0
    async with websockets.connect(
        url, additional_headers=client.ws_headers(), open_timeout=20,
        ping_interval=20, ping_timeout=30, max_size=16 * 1024 * 1024,
    ) as ws:
        await ws.send(json.dumps({"text": "Bok, ja sam Hermes. Razumijem hrvatski."}))
        await ws.send(json.dumps({"done": True}))
        while True:
            msg = await asyncio.wait_for(ws.recv(), 60)
            if isinstance(msg, bytes):
                pcm.extend(msg)
                continue
            obj = json.loads(msg)
            if obj.get("type") == "start":
                rate = int(obj["sample_rate"])
            elif obj.get("type") == "end":
                break
            elif obj.get("type") == "fallback":
                print("    provider fallback requested (no streamed audio)")
    out = Path("/tmp/hermes-tts-stream.wav")
    wav_from_pcm(bytes(pcm), rate or 24000, out)
    print(f"    PCM bytes={len(pcm)} rate={rate} -> {out}")
    assert len(pcm) > 1000, "no PCM produced"
    print("[5] PASS")


async def main() -> None:
    env = load_env(ACCESS_ENV)
    user, key = basic_auth_config()
    at, rt, _ = mint_session(key)
    client = Client(env, at, rt)

    print("[1] Access service token + dashboard session")
    status, payload = client.json_request("GET", "/api/auth/me")
    assert status == 200, f"auth/me -> {status} {payload}"
    print(f"    auth/me OK user={payload.get('user_id')!r} provider={payload.get('provider')!r}")
    print("[1] PASS")

    print("\n[2] Audio REST routes reachable")
    status, payload = client.json_request("GET", "/api/audio/voice-config")
    print(f"    voice-config -> {status}")
    assert status == 200, f"voice-config -> {status} {payload}"
    print("[2] PASS")

    await test_ws_rpc(client)
    await test_audio(client)
    await test_tts_stream(client)
    print("\nM0 SPIKE PASSED")


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        sys.exit(130)
