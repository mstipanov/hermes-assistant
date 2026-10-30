# Hermes Assistant (beta)

Beta builds ship features before they reach the stable release. The app keeps the same package and
is signed with the same key, so installing a beta replaces the stable build on your phone.

## 0.55-beta15
- **The app reports its push token and version.** `device_status` now returns `fcm_token`, `app_version` and `version_code`, so Hermes can see which build the phone runs and send a notification without any extra server endpoint.
- Removed the dead token-upload path that pointed at a push hostname that does not exist.

## 0.55-beta14
- **"Zadaci" stay fresh.** The session list refreshes every 60 seconds while connected, so a job that finished stops showing "radi" and a parked job shows "traži odluku".
- **The app tells you when a job needs a decision**, and tapping the notification opens that job's session so you can answer it right there.
- **Firebase Cloud Messaging is wired in** (project hermes-assistant-hr): the app registers a push token, so job notifications can arrive as real pushes instead of waiting for the next app open.

## 0.55-beta13
- **Messages typed while Hermes is answering are queued, not dropped.** Keep typing while a turn runs: a row above the composer shows what is waiting (with an X to drop it) and the message is sent as soon as the turn ends. The queue belongs to the conversation it was typed in — switching sessions drops it rather than posting it elsewhere.

## 0.55-beta12
- **Files need confirmation.** `save_file` and `open_file` now ask before they run, refuse payloads over 8 MB, only accept a non-executable MIME allowlist (images, text, PDF, audio, video, archives, office documents) and never hand an APK or another executable to a viewer.
- **The audio-only screen no longer recomposes every frame.** The orb's pulse is read in the draw phase and only runs while listening or speaking, so an idle screen schedules no frames at all (it used to re-sanitise the last reply and rebuild two gradients 60 times a second).
- **Screen capture can no longer get stuck on "busy"**: if the consent dialog goes away without an answer, the pending request is failed instead of blocking every later capture until the app restarts.

## 0.55-beta11
- **Switching sessions stops the turn you are leaving.** The abandoned turn no longer keeps running (and spending) on the server, a message typed during the switch can no longer land in the previous session, and two quick taps no longer race — the newest switch wins.
- **The watchdog measures silence, not total time.** A turn that streams for ten minutes, or that waits on an approval, is no longer declared dead halfway through; if the watchdog does fire, it also stops the server turn so the next message is not rejected with "active turn lease".
- **Chat no longer yanks you to the bottom** while you are scrolled up reading history; new messages follow the bottom only when you are already there.
- Job rows in "Zadaci" now show **"needs a decision"** (red) when a job parks on a `DECISION:` question.

## 0.55-beta10
- **"Zadaci" in the session switcher.** Sessions started outside the phone — a long CLI job run, a kanban worker, a headless run — now appear in their own group above the conversations, with a running/done status, and open like any other chat. Long work can run there while the phone conversation stays free.
- **Attachment thumbnails are decoded off the main thread** (downsampled to the 42 dp slot), so adding a photo no longer blocks the UI or materialises a full-resolution bitmap.

## 0.55-beta9
- **Stop works at once.** The stop button clears the turn in the UI immediately (no more "still answering" spinner that never ends) and sends the interrupt with a short timeout instead of waiting on a 35 s Ready handshake.
- **Screen capture works on Android 14+.** The media projection now registers its required callback; without it every capture failed with "Must register a callback before starting capture".
- **Fewer crashes in the wake-word service.** Stopping the service while the detector is mid-frame no longer frees native memory still in use, and a microphone error can no longer kill the process and leave the wake loop dead.
- **Speech never outlives a cancelled turn.** A text-to-speech request that finishes after stop/barge-in is dropped instead of played, and the two fallback paths can no longer speak the same reply twice.
- **No reconnect storm** after the server closes the socket cleanly: reconnects now back off (with jitter) instead of re-authenticating in a tight loop.
- **"Do Not Disturb" refuses an empty request** instead of silently turning DND off.
- Settings: the keyboard no longer covers the connection fields, and each page opens scrolled to the top.
- Two last untranslated strings (tool fallback, "Wake word model") are now localized.

## 0.55-beta8
- The APK is about 23% smaller (37.9 MB instead of 49.2 MB): code shrinking (R8) and resource shrinking are now enabled for the release and beta builds, with keep rules for the sherpa-onnx JNI classes.
- The updater now verifies that the downloaded APK is the same package **signed with the same key** as the installed app before handing it to the installer. The distributor publishes no checksum, so this is the check that actually stops a swapped or re-signed build; a failing APK is deleted and the install is aborted.

## 0.55-beta7
- Start–stop mode now transcribes **live while you speak**: partial text streams into the orb like in auto mode, and tapping send only ends the recording and hands the final transcript to Hermes.
- While a start–stop recording runs, the orb shows a **send arrow** instead of a stop square — it no longer looks like it will discard the sentence.
- With Google STT the dictation streams on the phone itself (segments are appended when Google pauses, so a pause never cuts the message); with Hermes server STT it uses the server's live stream when available, otherwise it transcribes the recording after you stop.

## 0.55-beta6
- "Show alarms" no longer fails with "Permission Denial" on phones whose clock app does not export the alarm list: Hermes now falls back to opening the clock app itself.
- "Set alarm" falls back to the pre-filled create-alarm screen when the silent insert is refused.
- Background/blocked activity starts now return an actionable message ("open Hermes and try again") instead of the raw Android denial.

## 0.55-beta5
- Session actions moved off the main voice screen into **Settings → Conversations**: new session and the list of previous sessions are no longer one stray tap away from the orb.
- The Conversations page shows the current session, a "New session" button and every previous session; picking one closes Settings and reopens that conversation.

## 0.55-beta4
- Settings is now a short menu instead of one long scroll: **Connection**, **Voice**, **Assistant & permissions**, **Updates** and **Diagnostics**, each opening its own page with a back arrow.
- The connection page holds the server/token/Access fields together with Save, Test connection and Reconnect; voice tests stay on the Voice page; the permission matrix lives under Assistant & permissions; logs, diagnostics and sign out under Diagnostics.
- Every entry shows a one-line summary (server URL, STT/microphone mode, version, available update).

## 0.55-beta3
- Telegram session sharing is gone: no "Share session with Telegram" switch, no automatic resume of the bot's session and no history sync. The app's conversations are now completely separate, and Telegram sessions are no longer listed in the app.
- New history button on the main voice screen: it opens a sheet with the previous sessions, so you can jump back into an older conversation (or start a new one) without going through the chat drawer.
- Starting a new session while Hermes is answering stops that turn first.

## 0.55-beta2
- New-session button on the main voice screen (the "+" next to chat and settings), so a fresh conversation can be started without digging through the drawer.
- Starting a new session while Hermes is answering now stops that turn first instead of leaving it running in the background.

## 0.55-beta1
- The orb no longer drops to "Ready" while Hermes is still working: a `tool.start` keeps the turn running, so the orb keeps breathing and the status stays "Thinking…" while scripts and tools execute.
- The turn timeout no longer fires after 35 s of tool execution; it now waits 10 minutes of total silence and says the answer may be incomplete instead of claiming the turn ended.
- Interim `message.complete` frames (auto-continue / multi-step turns) re-arm the running state when the next message or tool starts.

## 0.54-beta1
- Device actions accept natural aliases: `battery` / `status` / `wifi` / `network` for `device_status`, `torch`/`lamp`, `timer`, `alarm`, `sms`, `call`, `screenshot`, `clipboard`, `notifications`, `ringer`, `dnd`, `media`, `volume`, `contacts`, `map`, `directions`.
- So "kolika je baterija" now answers with battery level and charging state instead of "the bridge cannot read the battery".

## 0.53-beta1
- Fixed the "Modify system settings" permission being greyed out on Android: the app was missing the `WRITE_SETTINGS` declaration, so the brightness action could never be enabled. Added `ACCESS_NOTIFICATION_POLICY` for Do Not Disturb too.

## 0.52-beta1
- New **Settings → Action permissions** matrix: every device action can be set to **Allow** (silent), **Confirm** (asks first) or **Deny**, plus one place to grant the Android access the actions need (contacts, phone, SMS, calendar, location, notifications, Do Not Disturb, system settings).
- Hermes can now use 37 device actions: send/read SMS, dial and direct calls, contacts, timers, alarms, navigation and maps, web search, text sharing, voice command, vibration, flashlight, speaking, device status, media and volume, calendar read/add, saving and opening files, clipboard read/write, notifications, location, Do Not Disturb, ringer, brightness, screen capture and more.
- Sensitive actions (calls, SMS, calendar writes, location, DND, brightness, notifications, clipboard, screen capture) ask for confirmation by default; the old "Confirm device actions" switch still works as a master fallback.
- Screen capture uses Android's own consent dialog every time and saves the PNG into Pictures/Hermes.

## 0.51-beta1
- Landscape is now built around the orb: it sits in the middle of the screen at full size, the status, mode switch and buttons are to its left, the last answer to its right.
- While the microphone is listening (or the answer is being transcribed/thought about) the orb icon turns into a Stop symbol, so the separate "Stop listening" button is gone in both orientations.
- Portrait keeps the same layout as before.

## 0.50-beta1
- Landscape: the orb is large again — it keeps the portrait size instead of shrinking; status, mode switch and buttons sit in the column next to it, the last answer stays on the right.
- The Send/Cancel buttons fit the narrower landscape column.

## 0.49-beta1
- The microphone mode switch (Automatic / Start–stop) is now on the main voice screen, right under the status, so it can be changed on the fly without opening Settings.
- New landscape layout for the voice screen: orb, status and the mode switch on the left, the last answer and any error/approval cards on the right — nothing gets cut off or hidden in landscape any more.
- The choice is remembered and applies to the big orb, the chat mic, "Hey Hermes", the Quick Settings tile and the assistant key.

## 0.48-beta1
- New microphone mode in Settings → Microphone: **Automatic** (as before — recording ends after a short pause) and **Start–stop** (recording keeps going until you tap the big orb again, then it is sent). Start–stop uses the local recorder and Hermes STT, so a pause to think can never cut the sentence in half.
- In Start–stop mode the orb shows "Recording…" with a Send/Cancel pair, and while a capture is running the mic/orb tap finishes it instead of discarding it.
- Continuous listening is skipped in Start–stop mode — the microphone never re-opens by itself.

## 0.47-beta1
- The app now has a real beta channel: "Check for updates" in a beta build finds new betas and installs them from hermes-beta.sting.hr. Stable builds keep checking the stable channel only.
- The first start after a reboot or an app update retries the connection by itself — and reconnects as soon as the network is back — instead of staying offline until "Connect" is tapped.
- Chat messages can be selected and copied; when the server really is unreachable the error is a clear message instead of the raw DNS text.

## 0.46-beta2
- The first start after a reboot or an app update no longer gets stuck offline: the app keeps retrying the connection with backoff (about two minutes) and reconnects by itself as soon as the phone has a network again.
- When the server really cannot be reached, the error is now a clear "Can't reach the server. Check your internet connection." instead of the raw DNS message.

## 0.46-beta1
- Chat messages can now be selected and copied: long-press a message — yours or Hermes's — then tap Copy, or Select all for the whole message.
- Code blocks, the collapsible reasoning text, system notices, and the latest reply on the voice (audio-only) screen are selectable as well.
