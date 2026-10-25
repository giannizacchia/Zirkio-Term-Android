# Zirkio Term (Android)

**English** · [Italiano](README.it.md)

[![APK](https://img.shields.io/badge/APK-download-brightgreen)](https://github.com/giannizacchia/Zirkio-Term-Android/releases/latest)
[![Android](https://img.shields.io/badge/Android-8%2B%20(API%2026)-3ddc84)](https://github.com/giannizacchia/Zirkio-Term-Android/releases/latest)

## Download

Grab the APK from the latest release — no build needed, no store:

**[ZirkioTerm-0.12.0-release.apk](https://github.com/giannizacchia/Zirkio-Term-Android/releases/latest)**

`adb install -r ZirkioTerm-0.12.0-release.apk`, or open the file on the phone and allow
installation from unknown sources. Requires Android 8 (API 26) or newer.
The APK is signed with the debug key on purpose, so `adb install -r` upgrades in
place instead of forcing an uninstall.

## What it does

An SSH client for Android with a real terminal, written in Kotlin with Jetpack
Compose. Mobile companion of [Zirkio-Term](https://github.com/giannizacchia/Zirkio-Term),
the GTK3 terminal for Linux: here the UI is native Android, not a port.

- **SSH terminal**: terminal emulation (VT parser, colour palettes, text view)
  on top of `sshj`, key-based authentication
- **Saved sessions**: name, host, user, description; one-tap connect
- **Keys generated in the app**: pair creation, key upload to the server,
  access verification
- **Phone storage**: column list (name, size, date), search, file operations,
  open with other apps
- **SFTP transfers**: queue with progress, to and from the remote machine
- **Remote shutdown** of a saved machine, with confirmation

## Requirements

- JDK 17
- Android SDK with `compileSdk 35` (Android 15); the app runs from **API 26** (Android 8)
- Gradle 8.13 (bundled in the wrapper, no install needed)

The SDK path goes in `local.properties`, which is **not** versioned:

```properties
sdk.dir=/path/to/Android/Sdk
```

## Build

```bash
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

The `release` build is signed with the **debug** key on purpose: the APK is
sideloaded, so `adb install -r` upgrades the app without uninstalling it. Being
ahead-of-time compiled by ART at install time, it also stays smoother than a
debug build. Shipping to the Play Store would need a real release key, kept
outside the repository.

```bash
./gradlew test    # unit tests in app/src/test
```

## Structure

```
app/src/main/java/org/zirkio/term/
  MainActivity.kt
  term/     terminal parser (Terminale), palettes, view
  dati/     sessions, phone storage, keys, queue, entries, search, commands
  ssh/      sshj connection, SFTP transfers, access, configuration,
            key install, verification, shutdown
  ui/       Compose screens (sessions, keys, queue, bars, terminal)
app/src/test/java/org/zirkio/term/   unit tests
```

## Versions

`versionName 0.12.0`, `versionCode 12` — AGP 8.7.3, Kotlin 2.0.21, Compose,
sshj 0.38.0 with BouncyCastle and eddsa for Ed25519 keys.

## Permissions

- `INTERNET`, `ACCESS_NETWORK_STATE`: SSH and SFTP connections
- `MANAGE_EXTERNAL_STORAGE`: browse the phone storage like a file manager. It is
  granted from system settings on the app's request; everything else works
  without it

## License

MIT — see [LICENSE](LICENSE).
