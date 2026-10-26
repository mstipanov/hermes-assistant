# Hermes Assistant

[![Latest release](https://img.shields.io/github/v/release/mstipanov/hermes-assistant?sort=semver&label=release)](https://github.com/mstipanov/hermes-assistant/releases/latest)
[![License: MIT](https://img.shields.io/github/license/mstipanov/hermes-assistant)](LICENSE)

Android glasovni asistent koji kao backend koristi Hermes agenta na vlastitom serveru. Chat sa streaming odgovorima, alati i odobrenja, hrvatski glas, budilica
"Hey Hermes" i izvršavanje akcija na telefonu — sve preko autentificirane veze prema tvom serveru.

Osobni sideload projekt: aplikacija namjerno traži široke dozvole (kontakti, pozivi, instalacija
ažuriranja), pa nije namijenjena Google Playu.

## Što radi

- **Razgovor** — streaming odgovori, tool aktivnost, odobrenja i clarify pitanja preko Hermes
  dashboard WebSocket gatewaya.
- **Glas** — wake word "Hey Hermes" radi lokalno (sherpa-onnx KWS model u appu), STT ide preko
  Google recognizera ili Hermes servera, a odgovor čita Android TTS ili Hermes neuralni glas.
- **Audio-only ekran** — veliki orb i zadnji odgovor, bez tipkovnice; praktično za auto.
- **Akcije na telefonu** — otvaranje aplikacija, glazba, alarm, poziv, SMS i URL-ovi. Osjetljive
  akcije traže potvrdu u appu (može se isključiti u Postavkama).
- **Datoteke** — Hermes može poslati PDF, sliku ili bilo koju datoteku; sprema se u
  `Download/Hermes` i otvara dijeljenjem.
- **Ažuriranja** — provjera verzije, preuzimanje i instalacija iz appa, uz provjeru SHA-256.
- **Zadani asistent** — može preuzeti Android rolu digitalnog asistenta i Quick Settings pločicu.

## Preduvjeti

- Hermes server s uključenim dashboard API-jem (REST + `/api/ws` gateway).
- Android 8.0 (API 26) ili noviji, `arm64-v8a`.
- Za javni pristup: Cloudflare Tunnel i po želji Cloudflare Access service token.

## Instalacija

Preuzmi najnoviji `Hermes-<verzija>.apk` s [Releases](https://github.com/mstipanov/hermes-assistant/releases)
i instaliraj ga (dopusti instalaciju iz nepoznatog izvora).

> **Važno:** verzije do 0.43 distribuirane su debug-potpisanim APK-om. Prva release-potpisana
> verzija (0.44+) ima drugi potpis, pa je prije instalacije potrebno **deinstalirati staru
> aplikaciju**. Time se brišu lokalne postavke i spremljene tajne, ali ne i povijest razgovora na
> serveru.

## Konfiguracija

1. Otvori app i upiši adresu servera (npr. `https://hermes.sting.hr`).
2. Upiši API token ili korisničko ime i lozinku dashboarda.
3. Ako je ispred servera Cloudflare Access, upiši i service token (Client ID + Client Secret).

API token se generira na serveru:

```bash
python3 tools/make_api_key.py --days 90
```

Skripta čita `dashboard.basic_auth` iz `~/.hermes/config.yaml`, piše token u
`~/.hermes-assistant/api.key` (mode 600) i ne ispisuje ga. Token vrijedi dok ne istekne ili dok se
ne rotira `dashboard.basic_auth.secret`.

## Sigurnost i privatnost

- Lozinka, API token i Cloudflare Access tajna spremaju se AES-256-GCM ključem iz Android
  Keystorea; nikad se ne zapisuju u log ni u dijagnostiku (`set`/`missing`).
- Aplikacija šalje vjerodajnice isključivo svom serveru. Datoteke se preuzimaju samo s iste
  domene, čak i ako server vrati apsolutni URL.
- `device.action` (poziv, SMS, alarm, URL, pokretanje aplikacije) zadano traži potvrdu korisnika.
- Vanjske aplikacije ne mogu otvoriti mikrofon preko exported `MainActivity`; slušanje pokreću
  samo komponente unutar appa (pločica, asistentska tipka) ili korisnikov `listenOnOpen`.
- Ažuriranja se primaju samo s `hermes-assistant.sting.hr` i provjerava se SHA-256 ako ga server
  pošalje.
- `android:allowBackup="false"`, nema WebViewa ni dinamičkog izvršavanja koda.
- Preuzete datoteke ograničene su na 200 MB; imena se čiste od path traversal pokušaja.

Aplikacija komunicira s tvojim Hermes serverom. Što server radi s porukama, govorom i datotekama
ovisi o tvojoj Hermes konfiguraciji — ne šalje se ništa trećim stranama osim ako uključiš Google
STT.

## Dozvole

| Dozvola | Zašto |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | veza sa serverom |
| `RECORD_AUDIO`, `FOREGROUND_SERVICE_MICROPHONE` | glasovni unos i wake word |
| `POST_NOTIFICATIONS` | obavijest o primljenoj datoteci i radu servisa |
| `READ_CONTACTS` | pronalaženje kontakta po imenu (opcionalno) |
| `CALL_PHONE` | direktan poziv bez dialera (opcionalno) |
| `SET_ALARM` | postavljanje alarma |
| `REQUEST_INSTALL_PACKAGES` | samoažuriranje APK-a |
| `QUERY_ALL_PACKAGES` | popis i pokretanje instaliranih aplikacija |

## Gradnja

Potrebno: JDK 21, Android SDK 36, Gradle wrapper (9.7.1).

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew :app:assembleDebug
```

APK izlazi u `app/build/outputs/apk/debug/Hermes-<verzija>.apk`.

### Release potpisivanje

`app/build.gradle.kts` čita `keystore.properties` iz korijena repozitorija (datoteka je
gitignorirana). Primjer:

```properties
storeFile=/Users/ti/.hermes-assistant/hermes-release.jks
storePassword=…
keyAlias=hermes
keyPassword=…
```

```bash
./gradlew :app:assembleRelease
```

Bez `keystore.properties` release build ostaje nepotpisan. Keystore i lozinke nikada ne idu u git.

### Objava

Verzija se mijenja u `app/build.gradle.kts` (`versionCode` / `versionName`), doda se unos u
`CHANGELOG.md`, izgradi release APK i priloži ga GitHub releaseu:

```bash
gh release create 0.44 app/build/outputs/apk/release/Hermes-0.44.apk \
  --title "Hermes 0.44" --notes-file /tmp/hermes-0.44-notes.md
```

`<notes>` je odjeljak iz `CHANGELOG.md` s priloženim support footerom iz `SUPPORT.md`, tako da svaki
release nosi linkove za podršku.

## Struktura

- `app/src/main/java/hr/sting/hermes/data` — REST/WebSocket klijent, postavke, sigurno spremište
- `app/src/main/java/hr/sting/hermes/voice` — wake word, STT, TTS, foreground servis
- `app/src/main/java/hr/sting/hermes/device` — akcije na telefonu i primanje datoteka
- `app/src/main/java/hr/sting/hermes/ui` — Compose ekrani
- `app/src/main/assets/kws` — lokalni KWS model i popis ključnih riječi
- `tools/` — pomoćne skripte (mint API tokena, spike testovi)

## Podrži projekt

Ako ti je Hermes Assistant koristan, možeš podržati njegov razvoj:

- ☕ [Buy Me a Coffee](https://buymeacoffee.com/mstipanov)
- 💳 [PayPal](https://paypal.me/mstipanov)

Detalji su u [SUPPORT.md](SUPPORT.md).

## Licenca

MIT — vidi [LICENSE](LICENSE).
