# CLAUDE.md — neutrino_shared_android

Part of the Neutrino platform — read the root `../CLAUDE.md` coupling rules first. This is the
Android counterpart of `neutrino_shared_ios`: account identity, OAuth, the keyring and the
**fifth E2EE implementation**, shared by every Neutrino Android app (Drive, Calendar).

**Consumed by local path, unversioned.** Each app's `settings.gradle.kts` has
`includeBuild("../neutrino_shared_android")`, so editing this repo changes every app the moment it
rebuilds. After a change, build and test the dependents too, not just this repo:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew :neutrino-core:test :neutrino-android:assembleDebug
./scripts/crosscheck-web-crypto.sh                       # needs Node and ../neutrino/web
(cd ../neutrino_drive_android_mobile && ./gradlew :core:test :app:assembleDebug)
(cd ../neutrino_calendar_android_mobile && ./gradlew :core:test :app:assembleDebug)
```

- `:neutrino-core` is plain JVM (`com.neutrino.shared.core.{api,crypto,keys}`) with
  `testFixtures` (`FakeServer`) the apps' tests reuse. `:neutrino-android` holds the Keystore
  storage, app lock, `NeutrinoAccount`, the brand/theme and the sign-in and key screens.
- Crypto (`core/crypto/`) is a wire format shared with the web, iOS, macOS, Windows and the
  server: any change there is a coordinated multi-repo change, and the crosscheck must pass both
  ways.
- Each app is a `NeutrinoAppConfig` in `NeutrinoApps` (`core/api/Http.kt`): its prefix and OAuth
  client id must match a `neutrino/migrations/*_oauth__*` row. Never change or reuse a prefix.
- Network bodies are read on `Dispatchers.IO` inside the client; keep it that way — apps call
  these from the main thread.
- Design notes: `agent_docs/shared-android.md`.
