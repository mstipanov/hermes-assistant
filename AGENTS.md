# AGENTS.md — Hermes Assistant

Android voice client (Kotlin + Compose) for a Hermes agent running on the user's own server.
The app talks to the Hermes **dashboard WebSocket gateway** (`/api/ws`, REST alongside it):
streaming chat, tool activity, approvals, clarify questions, Croatian TTS/STT, the local
"Hey Hermes" wake word, and device actions executed on the phone.

Personal sideload project — no Play Store, no Google Play services requirement, broad permissions
are intentional (calls, contacts, in-app APK installation).

## Build, test, publish

```bash
./gradlew :app:assembleDebug                 # fast loop, no signing
./gradlew :app:testDebugUnitTest             # unit tests (none yet — the build is the gate)
./gradlew :app:assembleBeta -PbetaNumber=8   # beta → app/build/outputs/apk/beta/Hermes-0.55-beta8.apk
./gradlew :app:assembleRelease               # stable → app/build/outputs/apk/release/
```

There is **no upload step** for the in-app updater. `~/IdeaProjects/AndroidAppDistributor`
(port 8095) serves whatever APK sits in each app's `apkDir` and reads `output-metadata.json`
for the version:

- beta channel → `https://hermes-beta.sting.hr` (`CHANGELOG-beta.md`)
- stable channel → `https://hermes-assistant.sting.hr` (`CHANGELOG.md`)

Verify a publish locally before blaming Cloudflare:
`curl -s -H 'Host: hermes-beta.sting.hr' http://127.0.0.1:8095/version`

**Every published build is also tagged and released on GitHub**, because the README, the release
badge and the installation instructions send people there. Push the branch first, then attach the
APK to a release named exactly like the version (`--prerelease` for betas):

```bash
git push origin main
gh release create 0.55 app/build/outputs/apk/release/Hermes-0.55.apk \
  --title "Hermes 0.55" --notes-file <notes>
```

`<notes>` is the `CHANGELOG.md` section followed by the support footer from `SUPPORT.md`. The
GitHub release is a download mirror, not the update channel: the updater reads the distributor
hostname only. Never bump `versionCode` backwards — the updater compares `versionCode` alone, so a
same-code rebuild is invisible to installed users.

## Versions and changelog

Follow the `development` skill: `0.55-beta7` → `0.55-beta8` for the next beta, release drops the
suffix (`0.55`), and `versionCode` is a separate counter that increases for **every** published
build. The beta suffix comes from `-PbetaNumber=N`; the base `versionName` stays at the upcoming
release.

Every published build gets a new section at the **top** of the matching changelog, headed by the
exact version name. Shipped sections are never edited or renumbered.

## Language

- UI text lives in `res/values/strings.xml` (English, default) and `res/values-hr/strings.xml`
  (Croatian). Every user-visible string goes through resources — no hardcoded text.
- Code, comments, commit messages, logs and changelogs are English.

## Layout

- `HermesController.kt` — session state machine, gateway RPC, turn lifecycle
- `data/` — settings, session store, update checker
- `voice/` — wake word (sherpa-onnx KWS), `AudioRecorder`, `LiveStt`, STT/TTS bridges
- `device/` — Android action handlers (apps, media, alarms, calls, SMS, files, status)
- `ui/` — Compose screens (chat, audio-only orb, settings, approvals, action permissions)

## Long jobs (the "Zadaci" group)

Long work runs in its own session so the phone conversation stays free. Start one with the CLI and
the job becomes a pickable session in the app:

```bash
hermes chat -q "<task>" --oneshot --source cli --in ~/IdeaProjects/hermes-assistant
```

`--source cli` matters: the gateway stores it as `local`, which is listed. A plain `hermes -z` run is
stored as `oneshot`, and `oneshot`/`kanban`/`tool` are on the gateway's internal deny-list
(`INTERNAL_LISTING_SOURCES`) — those sessions never reach the app. The app groups `local`/`cli`/
`kanban` sessions into "Zadaci" and shows `running` while `ended_at` is null.

A job must not bump the version, touch a shipped changelog section, or publish — it leaves the tree
changed and reports; the parent session reviews the diff and publishes.

## Device actions: two sides must match

Every action exists twice — in `device/` here and in the Hermes server plugin
`~/.hermes/plugins/android-device/tools.py` (schema + alias map). Adding or renaming one means
editing both, and the server only picks it up after the **dashboard backend** restarts
(`launchctl kickstart -k gui/$(id -u)/ai.hermes.dashboard`) — a stale schema rejects the action
with "not one of [...]" while everything else keeps working.

## Related skills (read before touching build/publish)

- `android-project` — Gradle stack, distributor, emulator/AVD, screenshots, logcat
- `android-app-publishing` — the release loop for the family of apps
- `development` — version numbering, beta vs release, changelog rules
- `cloudflare-tunnel` — hostnames and Access policies
