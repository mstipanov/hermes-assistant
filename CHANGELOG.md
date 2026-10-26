# Hermes Assistant

## 0.44
- Akcije na telefonu (poziv, SMS, alarm, otvaranje poveznice, pokretanje aplikacije, glazba) sada traže potvrdu u appu. Može se isključiti u Postavkama → Dozvole za akcije.
- Datoteke se preuzimaju isključivo s vlastitog Hermes servera; API token i Cloudflare Access tajna više se ne mogu poslati na drugi host.
- Vanjske aplikacije više ne mogu pokrenuti slušanje preko zaključanog ekrana. Prečac na početnom ekranu sada samo otvara app; Quick Settings pločica i asistentska tipka i dalje odmah slušaju.
- Ažuriranja provjeravaju SHA-256 i dopuštaju APK samo s hermes-assistant.sting.hr.
- Preuzete datoteke ograničene su na 200 MB.
- Transkript govora se više ne ispisuje u logcat.
- Release build potpisuje se vlastitim ključem (keystore izvan repozitorija).

## 0.43
- Hermes sada može sam poslati datoteku na telefon — PDF, sliku, dokument ili bilo koju datoteku koju je napravio na serveru.
- Datoteka se preuzima preko postojeće autentificirane veze i sprema u Download/Hermes.
- U razgovoru se prikaže kartica s gumbima "Otvori" i "Podijeli"; ako preuzimanje padne, nudi "Pokušaj ponovno".
- Dolazi i obavijest "Datoteka primljena", pa datoteke više ne moraš tražiti preko Telegrama.

## 0.42
- Ažuriranje u Postavkama: provjera nove verzije, prikaz promjena i instalacija iz appa.
- Preuzimanje preko DownloadManagera s progresom; Android installer se otvara automatski.
- Ako je potrebno, app vodi na dozvolu "Instaliraj nepoznate aplikacije".

## 0.41
- Postavke → Ažuriranje: gumb "Provjeri ažuriranja".
- Ako je dostupna nova verzija, prikaže se popis promjena i gumb "Preuzmi i instaliraj".
- Preuzimanje ide preko DownloadManagera, s progresom; instalaciju otvara Android installer.
- Ako instalacija iz Hermesa nije dopuštena, app otvori odgovarajuću Android postavku.

## 0.40
- Birač glasa telefona u Postavkama → Glas → "Glas telefona" (svi hrvatski glasovi koje Android nudi, lokalni i mrežni).
- Gumb "Otvori postavke glasa telefona" za instalaciju dodatnih glasova.
- Odabir se pamti i koristi za odgovore i "Ponovi odgovor".

## 0.39
- Lokalni Android TTS radi i za "Ponovi odgovor" kad veza sa serverom nije aktivna.
- Glasovni odgovori se i dalje čiste od Markdowna prije govora.

## 0.38
- App ostaje kompatibilan ako server nakon Hermes updatea ne prihvaća novi `voice` parametar: automatski ponovi bez njega.
- Čišćenje Markdowna prije TTS-a i dalje radi neovisno o serveru.

## 0.37
- Glasovni odgovori više ne čitaju Markdown: zvjezdice, zagrade, linkovi, tablice, popisi i kod se uklanjaju prije TTS-a.
- Hermes na glasovne upite dobiva uputu da odgovori običnim govornim tekstom bez formatiranja; tipkani upiti zadržavaju Markdown.
- Zadnji odgovor na audio-only ekranu prikazuje se bez zvjezdica i zagrada.

## 0.36
- Android TTS bira lokalni hrvatski glas ako postoji; ako ga nema, jasno javi i prebaci na Hermes Gabrijelu.
- Bolja provjera završetka Android TTS-a i prijelaz na Hermes ako Android glas ne radi.
- TTS engine se sprema u dijagnostiku.

## 0.35
- Novi TTS način: lokalni Android glas (Samsung/Google) — počinje govoriti odmah, bez čekanja servera.
- Postavke → Glas → "TTS glas": Android (brzo) ili Hermes Gabrijela (kvalitetniji neuralni glas).
- Android TTS govori rečenicu po rečenicu dok odgovor još stiže; ako hrvatski glas nije dostupan, automatski se koristi Hermes Gabrijela.
- "Ponovi odgovor" sada koristi odabrani TTS način.

## 0.34
- Privremeni gubitak audio focusa (rotacija, notifikacija, poziv) sada pauzira TTS i automatski ga nastavlja kad focus vrati.
- Veliki gumb "Ponovi odgovor" iznad orbа — ponovno pušta zadnji odgovor bez novog govora.
- Rotacija ne rekreira Activity i ne pokreće novi listening tijekom odgovora.

## 0.33
- Rotacija više ne rekreira MainActivity (configChanges), pa TTS i razgovor nastavljaju bez prekida.
- Auto-listen se preskače dok Hermes govori ili dok je turn u tijeku — rotacija/relaunch ne otima audio focus.
- Gumb "Ponovi" na zadnjem odgovoru za ponovno puštanje cijelog odgovora.

## 0.32
- Ako streaming TTS ne proizvede zvuk u 5 s, app automatski pusti cijeli odgovor kao MP3.
- Prebuffer ima timeout (450 ms), pa kratki odgovori ne čekaju puni buffer.
- Više zaštite za slučaj da provider TTS-a ne pošalje `end`.

## 0.31
- Glađa TTS reprodukcija: veći AudioTrack buffer (32 KB) i prebuffer prije prvog zvuka.
- Pisanje PCM-a u petlji (djelomični write više ne pravi rupe).
- Na kraju se čeka da se buffer isprazni — zadnje riječi se više ne režu.
- Auto-listen se ne pokreće dok TTS stvarno ne završi.

## 0.30
- Nakon dodavanja priloga app automatski fokusira tekstualno polje i otvara tipkovnicu.
- Placeholder se mijenja u "Dodaj objašnjenje…".
- Slanje s prilogom i bez teksta i dalje radi (default opis), ali sada je lako dodati vlastiti tekst.

## 0.29
- Prilozi u chatu: kamera, galerija i datoteke (PDF/tekst/ostalo), s pregledom i uklanjanjem.
- Slika se šalje kroz `image.attach_bytes` (vision radi), PDF kroz `pdf.attach`, ostalo kroz `file.attach`.
- Uz datoteku se u prompt dodaje apsolutni put i `@file:` referenca, da agent ne mora tražiti datoteku.
- Ograničenje 8 MB po prilogu; slike se automatski smanjuju na 2048 px / JPEG.

## 0.28
- Android Google STT (SpeechRecognizer) kao zadani STT — isti engine kao Gboard, bez uploada i bez 1,6 s VAD čekanja.
- Hermes server Whisper ostaje fallback i može se odabrati u Postavkama (privatnije, sporije).
- Djelomični transkript se prikazuje dok govoriš.
- Automatski fallback na server STT ako Google STT nema mrežu/servis.
- Prečac na postavke prepoznavanja govora.

## 0.27
- Ispravljen glavni bug: `speakThisTurn` se nije postavljao u `sendMessage`, pa su glasovni turnovi bili tihi dok je test TTS-a radio.
- Glasovni upit sada pouzdano pokreće TTS; tipkani ostaje tekstualni.
- U RPC logu se opet vidi `send message (... voice=…, tts=…)`.

## 0.26
- Oznaka "Glasovni odgovor" / "Tekstualni odgovor" na zadnjem odgovoru — vidi se kako je app klasificirao upit.
- TTS retry ako streaming reprodukcija padne.
- Diagnostika prikazuje TTS status (starting/playing/failed).

## 0.25
- TTS sada ide na media audio stream (USAGE_MEDIA), ne na Samsungov assistant stream — time se rješava tiha reprodukcija i bolje radi preko Bluetootha u autu.
- Gumb "Testiraj glas (TTS)" u Postavkama.
- Zvučnik na zadnjem odgovoru za ručno čitanje naglas.
- Dijagnostika prikazuje je li TTS uključen.

## 0.24
- Glasovni turn sada pouzdano pokreće TTS i kad odgovor stigne samo kroz message.complete (bez delta).
- Ako su Glasovni odgovori isključeni, app to jasno kaže na glasovni upit.
- TTS početak/stream/fallback se bilježi u "Zadnji RPC log" za dijagnostiku.
- Diagnostika prikazuje tts_enabled, default_view i continuous_voice.

## 0.23
- Isti način kao pitanje: tipkani prompt dobiva samo tekstualni odgovor, glasovni dobiva TTS.
- Kontinuirano slušanje i dalje radi za glasovne turnove i kad je TTS isključen.
- Opis u Postavkama jasno kaže da se glas koristi samo za glasovna pitanja.

## 0.22
- Veliki gumb "Zaustavi slušanje" na audio-only ekranu dok mik sluša.
- "Prekini" dok Hermes razmišlja ili govori.
- Gumb je namjerno velik zbog korištenja u autu.

## 0.21
- Kontinuirani razgovor: nakon odgovora i TTS-a Hermes automatski ponovno otvara mikrofon.
- Prekidač "Nastavi slušati nakon odgovora" u Postavkama (zadano uključeno).
- Auto-relisten radi samo u audio-only prikazu; u chatu ne prekida tipkanje.

## 0.20
- Novi "audio only" ekran je zadani prikaz: veliki orb, status i zadnji odgovor, bez tipkovnice i transkripta.
- Chat je dostupan jednim dodirom (ikona razgovora gore desno); povratak na glas jednim dodirom.
- U Postavkama se bira zadani prikaz: Audio only ili Razgovor.
- Tema je uvijek tamna i ispravljeni su status-bar inseti — čitljivo i u autu.
- Idealno za MyCar mirror: dodaj Hermes u "Open apps when mirroring starts".

## 0.19
- Hermes sada može upravljati telefonom preko toola `android_device`:
  otvaranje aplikacija, puštanje glazbe, media play/pause/next, kontakti i dialer,
  direktni pozivi (uz dozvolu), SMS composer, alarmi i URL-ovi.
- Dozvole za kontakte i pozive u Postavkama.
- Akcije rade preko postojećeg WS-a; app mora biti povezan na istu sesiju.

## 0.18
- Automatsko oporavljanje od "session not found" nakon restarta dashboarda.
- Reconnect sada koristi trajni stored session ID, ne stari runtime ID.
- Ako sesija nestane, app je ponovno otvori ili stvori novu i ponovi prompt jednom.

## 0.17
- "Zadnji RPC log" u Postavkama: svaki connect/resume/prompt/event se bilježi i može kopirati.
- Trajna crvena traka s greškom umjesto kratke snackbar poruke.
- Watchdog: ako nema odgovora 35 s, javi grešku i odblokiraj slanje.
- Bolja dijagnostika za slučaj da prompt ne stigne do Hermesa.

## 0.16
- Mikrofon sada koristi raw MIC izvor (Samsung VOICE_RECOGNITION je vraćao tišinu).
- Testovi "Mikrofon" i "Hey Hermes" snimaju fiksno 6 s, bez VAD-a, i prikazuju peak razinu.
- Ako je peak < 300, app jasno kaže da mikrofon ne daje signal.

## 0.15
- Opcija "Otvori u načinu slušanja" — app odmah sluša kad se otvori (npr. Samsung dvostruki pritisak → Open app → Hermes).
- Quick Settings pločica i home shortcut također vode direktno u slušanje.

## 0.14
- Vidljivi "Uključi Hey Hermes" banner na chat ekranu (traži mikrofon i pokreće servis).
- Ako servis stane, banner nudi "Pokreni".
- Pojačanje tihog mikrofona prije KWS modela (3-4x za tihi govor).

## 0.13
- Ispravljena voice-interaction metadata: assist gesta / dugi pritisak sada stvarno otvara Hermesa.
- Session service i recognition service koriste puna imena klasa (Android ih je prije odbijao).
- Quick Settings pločica i home shortcut ostaju kao alternative.

## 0.12
- Quick Settings pločica "Hermes" — jedan dodir otvara app i počinje slušati.
- Shortcut na home screenu (dugi pritisak na ikonu → "Slušaj Hermesa").
- Oba rade i kad Bixby tipka ne dopušta third-party asistenta.

## 0.11
- Hands-free sada odmah traži dozvolu za mikrofon i pokreće servis bez čekanja na "Spremi".
- Glasovna diagnostika u Postavkama: servis, KWS model, dozvola, zadnja detekcija, STT test.
- Gumbi "Testiraj mikrofon" i "Testiraj Hey Hermes".
- Bolje prijavljivanje grešaka KWS-a i mikrofon servisa.

## 0.10
- Bolje snimanje za STT: adaptivni VAD prema šumu prostorije, dulja pauza i bez audio efekata koji izobličuju govor.
- Server-side STT sada koristi hrvatski jezik, medium Whisper model i hrvatski prompt s imenima.
- Prepoznavanje "Hermes", "Home Assistant", "Telegram" i sl. je znatno točnije.

## 0.9
- Gumb "Postavi" na chat ekranu kad Hermes nije zadani asistent.
- Izravni prečaci na "Zadane aplikacije", "Side tipka" i "Assist & voice input".
- Automatsko prepoznavanje Samsung side-key settings aktivnosti.

## 0.8
- Hermes se može postaviti kao zadani Android digitalni asistent (dugi pritisak na side/home tipku).
- Asistentov gesture otvara app i odmah pokreće slušanje.
- Upute za Samsung One UI i ograničenje Google/Bixby hotworda.

## 0.7
- Trim tokena i Access tajni (trailing newline iz datoteke više ne ruši prijavu).
- Cloudflare Access polja su sada vidljiva i obavezna za hermes.sting.hr.
- Jasne greške kad app nije povezan; "Testiraj vezu" i "Ponovno poveži" u Postavkama.
- Diagnostika s gumbom "Kopiraj diagnostiku".

## 0.6
- Osvježena Telegram povijest ne briše vidljive poruke u appu.
- Stabilnije prebacivanje između Telegrama i appa tijekom istog razgovora.

## 0.5
- Dijeljenje Telegram sesije uključeno po defaultu; isključi u Postavkama ako želiš odvojen razgovor.

## 0.4
- Ispravljeno čuvanje Telegram sesije kad se povijest ne učitava u app.
- Brže otvaranje velikih Telegram sesija (povijest ostaje na serveru, app je ne prenosi).
- Bolja poruka o broju poruka u pozadini.

## 0.3
- Dijeljenje iste sesije s Telegram botom (povijest se osvježi prije svakog pitanja).
- Jasna poruka kad Telegram upravo drži sesiju.
- Oznaka "Telegram" u popisu razgovora.

## 0.2
- API token prijava (bearer token) umjesto lozinke.
- Manji APK: samo arm64-v8a.
- Prva javna verzija za instalaciju preko hermes-assistant.sting.hr.

## 0.1
- Prva verzija: povezivanje na Hermes preko Cloudflare Accessa i API tokena.
- Chat sa streaming odgovorima, tool aktivnošću, odobrenjima i clarify pitanjima.
- Popis razgovora, novi chat, povlačenje starih sesija.
- Glasovni unos (server STT) i streaming hrvatski TTS.
- Podrška za wake word model i pozadinski servis (u izradi).
