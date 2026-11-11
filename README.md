# Stance Deck (Android)

Phone remote for [stance](https://github.com/H0dg4m/stance-hypr), the bar-first display tool for Hyprland.
This repository is the Android app; the PC side (`stance deck serve`) lives in the stance repository.

Phone side of the stance deck: scan the PC's pairing QR, then press the buttons
from `~/.config/stance/deck.toml`. Wi-Fi, or USB through `adb reverse`.
Protocol: [docs/deck.md in stance](https://github.com/H0dg4m/stance-hypr/blob/main/docs/deck.md), threat model: same page. Test vectors: `protocol/deck-vectors.json` (copied from stance's `tests/fixtures/`; CI checks they are identical).

Protocol v2 (stance 0.6+): `hello` carries `proto` and `caps`; a v2 app answers once per connection with `caps` (this app offers `clip.text`, `file.push` and, on Android 10+, `file.pull`; the PC grants only what its `transfer.toml` allows). An older PC without `proto` is handled exactly as before. Binary data frames (`0x00`, sid u32 BE, payload) carry transfers. See ADR 0003/0004 and `docs/protocol.md` §3.7 in stance.

## Clipboard and files (stance 0.7+, app 0.3.0)

Everything is off on the PC until the user turns it on in `~/.config/stance/transfer.toml` (`stance deck transfer put --data JSON`, see docs/deck.md in stance). The app only ever acts on a PC screen that is open (the connection lives only then).

- **Send clipboard** (button on the PC screen): reads the clipboard only when you press it and sends it as `clip.text` (UTF-8, up to 1 MiB). The PC applies it only if `clip_from_phone` is `"notify"`; if not, the app says "Clipboard sharing is off on the PC".
- **Send file**: opens the system file picker (Storage Access Framework, no storage permission needed), up to 512 MiB. The PC user has to accept it on the PC (`files_from_phone = "ask"`, 60 s); progress and the PC's answer are shown on the PC screen. The file is hashed (SHA-256) first and verified by the PC.
- **Clipboard from the PC** (`clip_to_phone`): text you copy on the PC is set as the phone's clipboard while a PC screen is open, with a "Clipboard from <PC>" message. Nothing is applied while the app is closed or in the background.
- **Files from the PC** (`stance deck send FILE --to PHONE`): the app asks (PC name, cleaned file name, size; Accept / Decline; declined automatically after 60 s). An accepted file is streamed to a temp file in the app cache, size and SHA-256 are verified, then it is saved to `Downloads/stance/` with MediaStore. It is never opened automatically. Android 9 and older (API < 29) would need the legacy storage permission, so there the app does not offer `file.pull` at all.
- **Share target**: "Share" from another app to Stance Deck (text, or a single file) shows a card on the PC screen (the PC list first if several PCs are paired); text goes to the PC clipboard, files to the PC as above. Nothing is sent before you press the button.

Limits: two open streams, 16 KB frames, no resume (a lost connection fails the transfer), one file sent at a time, images are not supported yet (stance 0.7.1), and no background service: leaving the app ends transfers. Tested only against the real `stance` binary on loopback and fakes, not yet on a real phone.

- `core/`: plain JVM Kotlin (Noise IK + XXpsk3 on BouncyCastle, framing, messages, QR). No Android dependencies.
- `app/`: Compose / Material 3 app, package `dev.stance.deck`, minSdk 26. QR scanning with CameraX + ZXing (no Google services). Pasting the code works as a fallback. Several PCs can be paired; the device key is per PC and wrapped by the Android Keystore. The pairing screen sets `FLAG_SECURE`. The connection lives only while a PC screen is visible.

## Build from source

Needs JDK 21 and the Android SDK (platform 37.0, build-tools 35.0.0). Put `sdk.dir=/path/to/sdk` into `local.properties`.

```
./gradlew :core:test                                   # vectors + unit tests
./gradlew :core:test -Dstance.binary=/path/to/stance   # + live interop against a real `stance deck serve`
./gradlew :app:assembleDebug                           # app/build/outputs/apk/debug/app-debug.apk
```

The live tests start `stance deck serve --loopback` with isolated XDG directories and no Hyprland
variables. They skip themselves without a binary.

## Release APK

`:app:assembleRelease` signs with the key named in `~/Android/keys/stance-deck.properties`
(or the file in `$STANCE_KEYSTORE_PROPS`): `storeFile`, `storePassword`, `keyAlias` (default `stance-deck`),
`keyPassword` (default: the store password). Without it the APK is unsigned. Create a key with
`keytool -genkeypair -keystore stance-deck.jks -alias stance-deck -keyalg RSA -keysize 4096 -validity 9125`.
Keep the key: an app signed with another key cannot update an installed one. It is never committed.

## USB

```
stance deck serve --loopback --port 47800
adb reverse tcp:47800 tcp:47800
```
then pair with a code whose host is `127.0.0.1`.

## Tests

- `./gradlew :core:test`: Noise vectors, QR parsing, the stream state machine (mirrors stance's `stream.rs` tests), name cleaning, live interop (see above).
- `./gradlew :app:testDebugUnitTest`: reconnect/back-off and transfers of `PcConnection` against an in-process fake PC, Robolectric screenshot smoke tests, and (with `-Dstance.binary=/path/to/stance`) `LiveTransferTest`: clipboard and files both ways against a real `stance deck serve --loopback` with fake `wl-copy` / `notify-send`.
- `./gradlew :app:recordRoborazziDebug`: writes a PNG of every screen state to `app/build/outputs/roborazzi/` (no GPU or emulator needed).
- `tests/e2e-android.sh [stance]` (repo root, optional): emulator + nested Hyprland + `stance deck serve`; pairs the app, presses a layout button, checks the Keep / Revert banner and the automatic revert. Skips when adb, the emulator, an AVD (`STANCE_AVD`, default `stance34`, device pixel_6) or the debug APK are missing.
