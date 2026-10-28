# Hermes Assistant (beta)

Beta builds ship features before they reach the stable release. The app keeps the same package and
is signed with the same key, so installing a beta replaces the stable build on your phone.

## 0.46-beta2
- The first start after a reboot or an app update no longer gets stuck offline: the app keeps retrying the connection with backoff (about two minutes) and reconnects by itself as soon as the phone has a network again.
- When the server really cannot be reached, the error is now a clear "Can't reach the server. Check your internet connection." instead of the raw DNS message.

## 0.46-beta1
- Chat messages can now be selected and copied: long-press a message — yours or Hermes's — then tap Copy, or Select all for the whole message.
- Code blocks, the collapsible reasoning text, system notices, and the latest reply on the voice (audio-only) screen are selectable as well.
