# Hermes Assistant

## 0.55
- **Long jobs in their own sessions.** Work that outlives a turn runs in a separate session; the "Zadaci" group in the session switcher lists those jobs with a live status, and a job that parks on a decision shows "traži odluku".
- **Notifications for job decisions.** The app raises a notification when a job needs an answer, and tapping it opens that job's session so you can reply there. Notifications also arrive as Firebase pushes (project hermes-assistant-hr), so they work with the app in the background.
- **Messages typed while Hermes is answering are queued, not dropped** — they are sent as soon as the turn ends, and the queue stays with the conversation it was typed in.
- **Live transcription in start-stop mode**: dictation streams while you speak instead of being transcribed only at the end.
- **Switching sessions stops the turn you leave**, the turn watchdog measures silence instead of total time, and chat no longer yanks you to the bottom while you read history.
- **Smaller and safer builds**: R8 shrinking takes the APK from ~49 MB to ~38 MB, and the updater now refuses any APK that is not signed with the same key as the installed app.
- Screen capture no longer gets stuck on "busy", file saves/opens ask for confirmation and reject executables, and the audio-only screen no longer redraws every frame.

## 0.45
- English and Croatian user interface. The app follows the system language; English is the default for all other languages.
- Android 13+ shows a per-app language picker under Settings → Apps → Hermes → Language.
- All visible text moved to Android string resources. Technical RPC log lines stay in English.

## 0.44
- Device actions (call, SMS, alarm, opening a link, launching an app, music) now require confirmation in the app. Can be disabled in Settings → Action permissions.
- Files are downloaded only from your own Hermes server; the API token and Cloudflare Access secret can no longer be sent to another host.
- Third-party apps can no longer start listening from the lock screen. The home-screen shortcut now only opens the app; the Quick Settings tile and the assistant key still start listening immediately.
- Updates verify SHA-256 and only allow an APK from hermes-assistant.sting.hr.
- Downloaded files are capped at 200 MB.
- Speech transcripts are no longer written to logcat.
- Release builds are signed with a private key (keystore outside the repository).

## 0.43
- Hermes can now send a file to the phone by itself — a PDF, image, document, or any file it created on the server.
- The file is downloaded over the existing authenticated connection and saved to Download/Hermes.
- The chat shows a card with "Open" and "Share" buttons; if the download fails, it offers "Retry".
- A "File received" notification arrives, so you no longer have to look for files in Telegram.

## 0.42
- In-app update in Settings: check for a new version, show the changes, and install from the app.
- Download via DownloadManager with progress; the Android installer opens automatically.
- If needed, the app takes you to the "Install unknown apps" permission.

## 0.41
- Settings → Update: "Check for updates" button.
- If a new version is available, a changelog and a "Download and install" button are shown.
- The download goes through DownloadManager with progress; the Android installer opens the installation.
- If installing from Hermes is not allowed, the app opens the relevant Android setting.

## 0.40
- Phone voice picker in Settings → Voice → "Phone voice" (all Croatian voices Android offers, local and network).
- "Open phone voice settings" button to install additional voices.
- The choice is remembered and used for replies and "Replay answer".

## 0.39
- Local Android TTS also works for "Replay answer" when the server connection is not active.
- Voice replies are still stripped of Markdown before speaking.

## 0.38
- The app stays compatible if the server, after a Hermes update, does not accept the new `voice` parameter: it automatically retries without it.
- Markdown cleanup before TTS still works independently of the server.

## 0.37
- Voice replies no longer read Markdown: asterisks, brackets, links, tables, lists, and code are removed before TTS.
- Hermes receives an instruction for voice queries to answer in plain spoken text without formatting; typed queries keep Markdown.
- The last answer on the audio-only screen is shown without asterisks and brackets.

## 0.36
- Android TTS picks a local Croatian voice if available; if not, it clearly reports it and falls back to Hermes Gabrijela.
- Better detection of Android TTS completion and handoff to Hermes if the Android voice fails.
- The TTS engine is recorded in diagnostics.

## 0.35
- New TTS mode: local Android voice (Samsung/Google) — starts speaking immediately, without waiting for the server.
- Settings → Voice → "TTS voice": Android (fast) or Hermes Gabrijela (higher-quality neural voice).
- Android TTS speaks sentence by sentence while the answer is still arriving; if a Croatian voice is unavailable, Hermes Gabrijela is used automatically.
- "Replay answer" now uses the selected TTS mode.

## 0.34
- A temporary loss of audio focus (rotation, notification, call) now pauses TTS and resumes it automatically when focus returns.
- Large "Replay answer" button above the orb — replays the last answer without a new turn.
- Rotation no longer recreates the Activity and does not start a new listening session during a reply.

## 0.33
- Rotation no longer recreates MainActivity (configChanges), so TTS and the conversation continue uninterrupted.
- Auto-listen is skipped while Hermes is speaking or a turn is in progress — rotation/relaunch does not steal audio focus.
- "Replay" button on the last answer to replay the whole reply.

## 0.32
- If streaming TTS produces no sound within 5 s, the app automatically plays the whole answer as MP3.
- Prebuffer has a timeout (450 ms), so short answers do not wait for a full buffer.
- More protection for the case where the TTS provider does not send `end`.

## 0.31
- Smoother TTS playback: larger AudioTrack buffer (32 KB) and prebuffering before the first sound.
- PCM writes in a loop (partial writes no longer create gaps).
- At the end it waits for the buffer to drain — the last words are no longer cut off.
- Auto-listen does not start until TTS has actually finished.

## 0.30
- After adding an attachment, the app automatically focuses the text field and opens the keyboard.
- The placeholder changes to "Add a description…".
- Sending an attachment without text still works (default description), but it is now easy to add your own text.

## 0.29
- Chat attachments: camera, gallery, and files (PDF/text/other), with preview and removal.
- Images are sent via `image.attach_bytes` (vision works), PDFs via `pdf.attach`, everything else via `file.attach`.
- The absolute path and an `@file:` reference are added to the prompt so the agent does not have to look for the file.
- 8 MB limit per attachment; images are automatically downscaled to 2048 px / JPEG.

## 0.28
- Android Google STT (SpeechRecognizer) as the default STT — the same engine as Gboard, with no upload and no 1.6 s VAD wait.
- Hermes server Whisper remains the fallback and can be selected in Settings (more private, slower).
- The partial transcript is shown while you speak.
- Automatic fallback to server STT if Google STT has no network/service.
- Shortcut to speech recognition settings.

## 0.27
- Fixed a major bug: `speakThisTurn` was not set in `sendMessage`, so voice turns were silent while the TTS test worked.
- A voice query now reliably starts TTS; typed queries stay textual.
- The RPC log again shows `send message (... voice=…, tts=…)`.

## 0.26
- "Voice reply" / "Text reply" label on the last answer — you can see how the app classified the query.
- TTS retry if streaming playback fails.
- Diagnostics show the TTS status (starting/playing/failed).

## 0.25
- TTS now goes to the media audio stream (USAGE_MEDIA), not Samsung's assistant stream — this fixes silent playback and works better over Bluetooth in the car.
- "Test voice (TTS)" button in Settings.
- Speaker button on the last answer for manual read-aloud.
- Diagnostics show whether TTS is enabled.

## 0.24
- A voice turn now reliably starts TTS even when the answer arrives only through message.complete (without deltas).
- If voice replies are disabled, the app clearly says so for a voice query.
- TTS start/stream/fallback is recorded in "Last RPC log" for diagnostics.
- Diagnostics show tts_enabled, default_view, and continuous_voice.

## 0.23
- Same principle as the question: a typed prompt gets only a text reply, a voice prompt gets TTS.
- Continuous listening still works for voice turns even when TTS is off.
- The description in Settings clearly states that voice is used only for voice questions.

## 0.22
- Large "Stop listening" button on the audio-only screen while the mic is listening.
- "Interrupt" while Hermes is thinking or speaking.
- The button is deliberately large for in-car use.

## 0.21
- Continuous conversation: after the answer and TTS, Hermes automatically reopens the microphone.
- "Keep listening after the answer" toggle in Settings (on by default).
- Auto-relisten works only in the audio-only view; in chat it does not interrupt typing.

## 0.20
- The new "audio only" screen is the default view: large orb, status, and last answer, without a keyboard or transcript.
- Chat is one tap away (chat icon at the top right); returning to voice is one tap.
- Settings lets you choose the default view: Audio only or Chat.
- The theme is always dark and the status-bar insets are fixed — readable in the car too.
- Ideal for the MyCar mirror: add Hermes to "Open apps when mirroring starts".

## 0.19
- Hermes can now control the phone through the `android_device` tool:
  launching apps, playing music, media play/pause/next, contacts and the dialer,
  direct calls (with permission), SMS composer, alarms, and URLs.
- Contacts and call permissions in Settings.
- Actions work over the existing WS; the app must be connected to the same session.

## 0.18
- Automatic recovery from "session not found" after a dashboard restart.
- Reconnect now uses the persistent stored session ID, not the old runtime ID.
- If the session disappears, the app reopens or creates a new one and retries the prompt once.

## 0.17
- "Last RPC log" in Settings: every connect/resume/prompt/event is logged and can be copied.
- Persistent red error bar instead of a short snackbar message.
- Watchdog: if there is no response for 35 s, report an error and unblock sending.
- Better diagnostics for the case where the prompt does not reach Hermes.

## 0.16
- The microphone now uses the raw MIC source (Samsung VOICE_RECOGNITION returned silence).
- The "Microphone" and "Hey Hermes" tests record a fixed 6 s, without VAD, and show the peak level.
- If peak < 300, the app clearly says the microphone is not producing a signal.

## 0.15
- "Open in listening mode" option — the app listens immediately when opened (e.g. Samsung double press → Open app → Hermes).
- The Quick Settings tile and home shortcut also go straight to listening.

## 0.14
- Visible "Enable Hey Hermes" banner on the chat screen (requests the microphone and starts the service).
- If the service stops, the banner offers "Start".
- Gain for quiet microphones before the KWS model (3-4x for quiet speech).

## 0.13
- Fixed voice-interaction metadata: the assist gesture / long press now actually opens Hermes.
- The session service and recognition service use fully qualified class names (Android previously rejected them).
- The Quick Settings tile and home shortcut remain as alternatives.

## 0.12
- "Hermes" Quick Settings tile — one tap opens the app and starts listening.
- Home-screen shortcut (long-press the icon → "Listen to Hermes").
- Both work even when the Bixby key does not allow a third-party assistant.

## 0.11
- Hands-free now immediately requests microphone permission and starts the service without waiting for "Save".
- Voice diagnostics in Settings: service, KWS model, permission, last detection, STT test.
- "Test microphone" and "Test Hey Hermes" buttons.
- Better error reporting for KWS and the microphone service.

## 0.10
- Better recording for STT: adaptive VAD based on room noise, a longer pause, and no audio effects that distort speech.
- Server-side STT now uses Croatian, the medium Whisper model, and a Croatian prompt with names.
- Recognition of "Hermes", "Home Assistant", "Telegram", etc. is significantly more accurate.

## 0.9
- "Set up" button on the chat screen when Hermes is not the default assistant.
- Direct shortcuts to "Default apps", "Side key", and "Assist & voice input".
- Automatic discovery of the Samsung side-key settings activity.

## 0.8
- Hermes can be set as the default Android digital assistant (long press the side/home key).
- The assistant gesture opens the app and immediately starts listening.
- Instructions for Samsung One UI and the Google/Bixby hotword limitation.

## 0.7
- Trim tokens and Access secrets (a trailing newline from a file no longer breaks login).
- Cloudflare Access fields are now visible and required for hermes.sting.hr.
- Clear errors when the app is not connected; "Test connection" and "Reconnect" in Settings.
- Diagnostics with a "Copy diagnostics" button.

## 0.6
- Refreshed Telegram history no longer erases visible messages in the app.
- More stable switching between Telegram and the app during the same conversation.

## 0.5
- Telegram session sharing is enabled by default; disable it in Settings if you want a separate conversation.

## 0.4
- Fixed saving the Telegram session when history does not load in the app.
- Faster opening of large Telegram sessions (history stays on the server; the app does not download it).
- Better message about the number of messages in the background.

## 0.3
- Sharing the same session with the Telegram bot (history is refreshed before every question).
- Clear message when Telegram is currently holding the session.
- "Telegram" label in the conversation list.

## 0.2
- API token login (bearer token) instead of a password.
- Smaller APK: arm64-v8a only.
- First public version for installation via hermes-assistant.sting.hr.

## 0.1
- First version: connecting to Hermes via Cloudflare Access and an API token.
- Chat with streaming replies, tool activity, approvals, and clarify questions.
- Conversation list, new chat, pulling old sessions.
- Voice input (server STT) and streaming Croatian TTS.
- Support for a wake-word model and a background service (in progress).
