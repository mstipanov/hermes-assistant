# Hermes Assistant

[![Latest release](https://img.shields.io/github/v/release/mstipanov/hermes-assistant?sort=semver&label=release)](https://github.com/mstipanov/hermes-assistant/releases/latest)
[![License: MIT](https://img.shields.io/github/license/mstipanov/hermes-assistant)](LICENSE)

An Android voice assistant backed by a Hermes agent running on your own server. Streaming chat
replies, tools and approvals, Croatian voice, the "Hey Hermes" wake word, and device actions — all
over an authenticated connection to your server.

Personal sideload project: the app intentionally requests broad permissions (contacts, calls,
update installation), so it is not intended for Google Play.

## Features

- **Chat** — streaming replies, tool activity, approvals, and clarify questions over the Hermes
  dashboard WebSocket gateway.
- **Voice** — the "Hey Hermes" wake word runs locally (sherpa-onnx KWS model bundled in the app),
  STT uses Google's recognizer or the Hermes server, and replies are spoken by Android TTS or the
  Hermes neural voice.
- **Audio-only screen** — a large orb and the latest answer, no keyboard; practical in the car.
- **Device actions** — launching apps, music, alarms, calls, SMS, and URLs. Sensitive actions
  require confirmation in the app (can be disabled in Settings).
- **Files** — Hermes can send a PDF, image, or any other file; it is saved to `Download/Hermes` and
  opened or shared from the chat.
- **Updates** — version check, download, and in-app installation with SHA-256 verification.
- **Default assistant** — can take over the Android digital assistant role and the Quick Settings
  tile.
- **English and Croatian UI** — the app follows the system language, with a per-app language
  picker on Android 13+.

## Requirements

- A Hermes server with the dashboard API enabled (REST + the `/api/ws` gateway).
- Android 8.0 (API 26) or newer, `arm64-v8a`.
- For public access: a Cloudflare Tunnel and optionally a Cloudflare Access service token.

## Installation

Download the latest `Hermes-<version>.apk` from
[Releases](https://github.com/mstipanov/hermes-assistant/releases) and install it (allow
installation from unknown sources).

> **Important:** versions up to 0.43 were distributed as a debug-signed APK. The first
> release-signed version (0.44+) uses a different signature, so **uninstall the old app first**.
> This erases local settings and stored secrets, but not the conversation history on the server.

## Configuration

1. Open the app and enter the server URL (e.g. `https://hermes.sting.hr`).
2. Enter an API token, or the dashboard username and password.
3. If the server is behind Cloudflare Access, enter the service token (Client ID + Client Secret).

Generate an API token on the server:

```bash
python3 tools/make_api_key.py --days 90
```

The script reads `dashboard.basic_auth` from `~/.hermes/config.yaml`, writes the token to
`~/.hermes-assistant/api.key` (mode 600), and never prints it. The token stays valid until it
expires or until `dashboard.basic_auth.secret` is rotated.

## Security and privacy

- The password, API token, and Cloudflare Access secret are stored with an AES-256-GCM key from the
  Android Keystore; they are never written to logs or diagnostics (`set`/`missing` only).
- The app sends credentials only to its own server. Files are downloaded only from the same origin,
  even if the server returns an absolute URL.
- `device.action` (call, SMS, alarm, URL, launching an app) requires user confirmation by default.
- Third-party apps cannot open the microphone through the exported `MainActivity`; listening can
  only be started by in-app components (the tile, the assistant key) or the user's `listenOnOpen`
  setting.
- Updates are accepted only from `hermes-assistant.sting.hr`, and SHA-256 is verified when the
  server provides it.
- `android:allowBackup="false"`, no WebView, and no dynamic code execution.
- Downloaded files are capped at 200 MB; filenames are sanitized against path traversal.

The app talks to your Hermes server. What the server does with messages, speech, and files depends
on your Hermes configuration — nothing is sent to third parties unless you enable Google STT.

## Permissions

| Permission | Why |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | connection to the server |
| `RECORD_AUDIO`, `FOREGROUND_SERVICE_MICROPHONE` | voice input and wake word |
| `POST_NOTIFICATIONS` | file received and foreground service notifications |
| `READ_CONTACTS` | finding a contact by name (optional) |
| `CALL_PHONE` | direct calls without the dialer (optional) |
| `SET_ALARM` | setting alarms |
| `REQUEST_INSTALL_PACKAGES` | in-app APK updates |
| `QUERY_ALL_PACKAGES` | listing and launching installed apps |

## Build

Requires JDK 21, Android SDK 36, and the Gradle wrapper (9.7.1).

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/Hermes-<version>.apk`.

### Release signing

`app/build.gradle.kts` reads `keystore.properties` from the repository root (the file is
gitignored). Example:

```properties
storeFile=/Users/you/.hermes-assistant/hermes-release.jks
storePassword=…
keyAlias=hermes
keyPassword=…
```

```bash
./gradlew :app:assembleRelease
```

Without `keystore.properties` the release build stays unsigned. The keystore and passwords never go
into git.

### Publishing

The version is changed in `app/build.gradle.kts` (`versionCode` / `versionName`), an entry is added
to `CHANGELOG.md`, the release APK is built, and it is attached to a GitHub release:

```bash
./gradlew :app:assembleRelease
git push origin main
gh release create 0.55 app/build/outputs/apk/release/Hermes-0.55.apk \
  --title "Hermes 0.55" --notes-file /tmp/hermes-0.55-notes.md
```

Beta builds use the same command with the beta APK and `--prerelease`, e.g.
`gh release create 0.55-beta16 app/build/outputs/apk/beta/Hermes-0.55-beta16.apk --prerelease …`.

`<notes>` is the `CHANGELOG.md` section followed by the support footer from `SUPPORT.md`, so every
release carries the support links.

The GitHub release is a download mirror. The in-app updater never reads GitHub: it downloads from
the channel hostname (`hermes-assistant.sting.hr`, or `hermes-beta.sting.hr` for betas), which is
served by `~/IdeaProjects/AndroidAppDistributor` out of `app/build/outputs/apk/<release|beta>` and
re-scans that directory on every request.

### Beta channel

Beta builds live on their own hostname (`https://hermes-beta.sting.hr`) and in their own output
directory, so a beta can never be served as the stable release and stable users never see one:

```bash
./gradlew :app:assembleBeta -PbetaNumber=1   # app/build/outputs/apk/beta/Hermes-<version>-beta1.apk
```

The distributor scans `app/build/outputs/apk/beta` and serves it at `hermes-beta.sting.hr`, which is
also where a beta build's in-app updater looks (`BuildConfig.UPDATE_BASE_URL`). Publish the same APK
as a GitHub pre-release, with the `CHANGELOG-beta.md` section as the notes.

Versions follow the shared rule (`development` skill): a new version bumps the minor and starts at
`-beta1` (e.g. `0.55` → next work is `0.56-beta1`), another build of the same version only bumps the
beta number (`0.55-beta1` → `0.55-beta2`), and the release drops the suffix (`0.55`). `versionCode`
is a separate counter and must be **+1 for every published build**, betas included, or the updater
will not offer it.

## Structure

- `app/src/main/java/hr/sting/hermes/data` — REST/WebSocket client, settings, secure storage
- `app/src/main/java/hr/sting/hermes/voice` — wake word, STT, TTS, foreground service
- `app/src/main/java/hr/sting/hermes/device` — device actions and file receiving
- `app/src/main/java/hr/sting/hermes/ui` — Compose screens
- `app/src/main/assets/kws` — local KWS model and keyword list
- `tools/` — helper scripts (API token minting, spike tests)

## Support the project

If Hermes Assistant is useful to you, you can support its development:

- ☕ [Buy Me a Coffee](https://buymeacoffee.com/mstipanov)
- 💳 [PayPal](https://paypal.me/mstipanov)

Details are in [SUPPORT.md](SUPPORT.md).

## License

MIT — see [LICENSE](LICENSE).
