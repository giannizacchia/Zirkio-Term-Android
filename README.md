# Zirkio Term (Android)

Client SSH per Android con un terminale vero, scritto in Kotlin con Jetpack
Compose. Compagna mobile di [Zirkio-Term](https://github.com/giannizacchia/Zirkio-Term),
il terminale GTK3 per Linux: qui l'interfaccia e' nativa Android, non un port.

## Cosa fa

- **Terminale SSH**: emulazione del terminale (parser VT, tavolozze di colori,
  vista con rendering del testo) sopra `sshj`, con autenticazione a chiave
- **Sessioni salvate**: nome, host, utente e descrizione; connessione rapida
- **Chiavi generate in app**: creazione della coppia, invio della chiave al
  server, verifica dell'accesso
- **Memoria del telefono**: elenco a colonne (nome, dimensione, data), ricerca,
  operazioni sui file e apertura con altre app
- **Trasferimenti SFTP**: coda dei trasferimenti con avanzamento, da e verso
  la macchina remota
- **Spegnimento remoto** di una macchina salvata, con conferma

## Requisiti

- JDK 17
- Android SDK con `compileSdk 35` (Android 15); l'app gira da **API 26** (Android 8)
- Gradle 8.13 (incluso nel wrapper, non serve installarlo)

Il percorso dell'SDK va in `local.properties`, che **non** e' versionato:

```properties
sdk.dir=/percorso/Android/Sdk
```

## Compilare

```bash
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

La build `release` e' firmata con la chiave di **debug**, di proposito: l'APK si
installa a mano, e cosi' `adb install -r` aggiorna l'app senza disinstallarla.
Essendo compilata in anticipo da ART all'installazione, resta piu' fluida della
build debug. Per distribuire su Play Store servirebbe una chiave di release
vera, tenuta fuori dal repository.

```bash
./gradlew test    # test unitari in app/src/test
```

## Struttura

```
app/src/main/java/org/zirkio/term/
  MainActivity.kt
  term/     parser del terminale (Terminale), tavolozze, vista
  dati/     sessioni, memoria del telefono, chiavi, coda, voci, ricerca, comandi
  ssh/      collegamento sshj, trasferimenti SFTP, accesso, configurazione,
            installazione chiave, verifica, spegnimento
  ui/       schermate Compose (sessioni, chiavi, coda, barre, terminale)
app/src/test/java/org/zirkio/term/   test unitari
```

## Versioni

`versionName 0.12.0`, `versionCode 12` — AGP 8.7.3, Kotlin 2.0.21, Compose,
sshj 0.38.0 con BouncyCastle ed eddsa per le chiavi Ed25519.

## Permessi

- `INTERNET`, `ACCESS_NETWORK_STATE`: connessioni SSH e SFTP
- `MANAGE_EXTERNAL_STORAGE`: sfogliare la memoria del telefono come un gestore
  di file. Va concesso dalle impostazioni di sistema, su richiesta dell'app;
  senza, il resto funziona lo stesso

## Licenza

MIT — vedi [LICENSE](LICENSE).
