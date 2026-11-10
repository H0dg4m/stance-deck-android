# Stance Deck (Android)

Phone side of the stance deck: scan the PC's pairing QR, then press the buttons
from `~/.config/stance/deck.toml`. Wi-Fi, or USB through `adb reverse`.
Protocol: [../docs/protocol.md](../docs/protocol.md), threat model: [../docs/deck.md](../docs/deck.md).

- `core/`: plain JVM Kotlin (Noise IK + XXpsk3 on BouncyCastle, framing, messages, QR). No Android dependencies.
- `app/`: Compose / Material 3 app, package `dev.stance.deck`, minSdk 26. QR scanning with CameraX + ZXing (no Google services). Pasting the code works as a fallback. Several PCs can be paired; the device key is per PC and wrapped by the Android Keystore. The pairing screen sets `FLAG_SECURE`. The connection lives only while a PC screen is visible.

## Build from source

Needs JDK 21 and the Android SDK (platform 37.0, build-tools 35.0.0). Put `sdk.dir=/path/to/sdk` into `android/local.properties`.

```
cd android
./gradlew :core:test                                   # vectors + unit tests
./gradlew :core:test -Dstance.binary=../target/debug/stance   # + live interop against a real `stance deck serve`
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

- `./gradlew :core:test`: Noise vectors, QR parsing, live interop (see above).
- `./gradlew :app:testDebugUnitTest`: reconnect/back-off of `PcConnection` against an in-process fake PC, and Robolectric screenshot smoke tests.
- `./gradlew :app:recordRoborazziDebug`: writes a PNG of every screen state to `app/build/outputs/roborazzi/` (no GPU or emulator needed).
- `tests/e2e-android.sh [stance]` (repo root, optional): emulator + nested Hyprland + `stance deck serve`; pairs the app, presses a layout button, checks the Keep / Revert banner and the automatic revert. Skips when adb, the emulator, an AVD (`STANCE_AVD`, default `stance34`, device pixel_6) or the debug APK are missing.
