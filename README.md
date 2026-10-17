# neutrino_shared_android

Shared code for the Neutrino Android apps — the Android counterpart of `neutrino_shared_ios`.

| Module | Package | What |
|---|---|---|
| `neutrino-core` (JVM) | `com.neutrino.shared.core.api` | `NeutrinoAppConfig`/`NeutrinoApps`, `AuthClient` (password + 2FA → OAuth PKCE, refresh), `NeutrinoApi` (open base client: token path, Drive files, keys, profile) |
| | `com.neutrino.shared.core.crypto` | libsodium-compatible crypto on BouncyCastle: sealed boxes, secretstream (one-shot and chunked), file/metadata encryption, saved-place envelopes |
| | `com.neutrino.shared.core.keys` | Keyring, recovery kit, pairing, PIN key code, key file, `KeyManager`, `DeviceKeyCheck` |
| | test fixtures | `FakeServer` — an in-memory Neutrino server for the apps' tests |
| `neutrino-android` | `com.neutrino.shared.android` | `NeutrinoAccount`, `AccountSettings`, Keystore-sealed storage, app lock, `NeutrinoBrand`/`NeutrinoTheme`, sign-in and key screens |

## Use from an app

The app must be a sibling of this repo:

```kotlin
// settings.gradle.kts
includeBuild("../neutrino_shared_android")
// module build.gradle.kts
implementation("com.neutrino.shared:neutrino-core")      // or neutrino-android
testImplementation(testFixtures("com.neutrino.shared:neutrino-core"))
```

## Build

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew :neutrino-core:test :neutrino-android:assembleDebug
./scripts/crosscheck-web-crypto.sh   # both ways against ../neutrino/web/packages/e2e-crypto
```
