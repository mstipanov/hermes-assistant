# Hermes Assistant (beta)

Beta builds ship features before they reach the stable release. The app keeps the same package and
is signed with the same key, so installing a beta replaces the stable build on your phone.

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
