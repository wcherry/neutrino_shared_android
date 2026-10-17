# neutrino_shared_android — design notes

**Created:** 2026-10-16, extracted from `neutrino_drive_android_mobile` when Calendar for Android
was built (branch `feature/android-calendar-client` in Drive, Calendar and `neutrino`).

## Why a separate repo, consumed by path

The same reasons as `neutrino_shared_ios`: every Neutrino Android app needs the same account,
OAuth, keyring and E2EE code, and those must agree byte for byte with the other clients. A Gradle
composite build (`includeBuild("../neutrino_shared_android")`) builds it from source with no
publishing step and no version to drift — the trade-off being that a change reaches every app at
once, so the dependents must be rebuilt and tested with it.

## What moved out of Drive

- `core/api`: `AuthClient`, the HTTP plumbing (`await`, `apiJson`, `httpError`, `ApiException`),
  the base `NeutrinoApi` (now `open`; Drive and Calendar subclass it), the shared DTOs.
- `core/crypto`: all of it. Added for Calendar: `PlaceEnvelope` (saved places, as the web's
  `place.ts`), chunked secretstream decrypt with REKEY (Calendar attachments uploaded by the web
  are chunked), `FileCrypto.decryptFile(…, chunkSize)`.
- `core/keys`: all of it, incl. `KeyManager` (was Drive's `Keys.kt`).
- Android: Keystore `SecureStore` + session/keyring stores, `AppLock`, the sign-in and key
  screens, common Compose pieces, and `NeutrinoAccount` (the per-app graph root the screens read
  through `LocalNeutrinoAccount`).

Drive keeps its own endpoints (sharing, trash, versions, search) in a `NeutrinoApi` subclass and a
`DriveBrand`; `core/api/AppConfig.kt` there maps to `NeutrinoApps.drive`.

## Per-app identity

`NeutrinoAppConfig` carries the slug, display name, prefix (`nd`, `ncal`), OAuth client id
(`neutrino-android`, `neutrino-calendar-android`) and Keystore alias. Prefs are
`<prefix>.settings`. Android has no cross-app keychain, so each app holds its own session and its
own copy of the keyring — bringing the key to a second app is a pairing/kit/key code, as on a new
device. `AccountEndToEndTest.eachAppSignsInWithItsOwnClientId` checks a server rejects an
unregistered id.

## Threading

`NeutrinoApi.text`/`openBlob` and the `AuthClient` calls read response bodies on
`Dispatchers.IO`. The OkHttp `await` suspends without blocking, but the coroutine resumes on the
caller's dispatcher, and Compose callers are on Main — reading a body there throws
`NetworkOnMainThreadException` (found creating a Calendar event on the emulator).

## Verification

`./gradlew :neutrino-core:test` (41 tests: web-generated vectors incl. chunked secretstream from
libsodium-wrappers 0.7.15 via `scripts/generate-chunked-stream-vectors.mjs`, keys, place
envelopes, auth flows against `FakeServer`) and `scripts/crosscheck-web-crypto.sh` (both ways
against the web's own `e2e-crypto`, incl. saved places). Drive's and Calendar's suites and live
tests ran green on top of it on 2026-10-16.
