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

`NeutrinoAppConfig` carries the slug, display name, prefix (`nd`, `ncal`, `ndoc`), OAuth client id
(`neutrino-android`, `neutrino-calendar-android`, `neutrino-docs-android`) and Keystore alias. Prefs are
`<prefix>.settings`. Android has no cross-app keychain, so each app holds its own session and its
own copy of the keyring — bringing the key to a second app is a pairing/kit/key code, as on a new
device. `AccountEndToEndTest.eachAppSignsInWithItsOwnClientId` checks a server rejects an
unregistered id.

## OOXML (`.docx`) — added for Docs, 2026-10-16

`core/ooxml` reads and writes Neutrino Docs' storage format: a `.docx` whose body maps to and
from the ProseMirror/TipTap JSON tree the web edits (`DocModel` — a `DocNode` tree plus layout
`meta`), with what Word has no place for (theme, image refs, cross-references, placeholders,
field-code display, column layouts) in `customXml/item1.xml` as JSON. It is a port of the web's
`web/apps/web/src/lib/ooxml/docx/{mapping,read,write}.ts` and iOS's `NeutrinoOOXML`, with its own
zip (deterministic: stored order and timestamps fixed, so equal documents are equal bytes) and its
own XML parser (a DOCTYPE is skipped unread and no entity is ever resolved — a `.docx` is untrusted input).

It lives here rather than in the Docs app so the Sheets/Slides ports can add `xlsx`/`pptx` beside
it on the same `OoxmlPackage`. It is a format shared with the web and iOS: a change is a
multi-client change. `scripts/crosscheck-web-docx.sh` runs the web's own reader and writer
against this one in both directions (it drops a probe test into the web's `__tests__/ooxml/` for
the run and removes it after); keep it passing.

## Threading

`NeutrinoApi.text`/`openBlob` and the `AuthClient` calls read response bodies on
`Dispatchers.IO`. The OkHttp `await` suspends without blocking, but the coroutine resumes on the
caller's dispatcher, and Compose callers are on Main — reading a body there throws
`NetworkOnMainThreadException` (found creating a Calendar event on the emulator).

## Verification

`./gradlew :neutrino-core:test` (111 tests as of the Docs port, 68 of them the docx codec;
before it, 41: web-generated vectors incl. chunked secretstream from
libsodium-wrappers 0.7.15 via `scripts/generate-chunked-stream-vectors.mjs`, keys, place
envelopes, auth flows against `FakeServer`) and `scripts/crosscheck-web-crypto.sh` (both ways
against the web's own `e2e-crypto`, incl. saved places). Drive's and Calendar's suites and live
tests ran green on top of it on 2026-10-16. `scripts/crosscheck-web-docx.sh`: 7 documents each
way, web ↔ Android, pass.

## Offline (2026-10-19, `feature/offline-support`)

- `NeutrinoApi.send` retries transient failures under `retryPolicy` (`core/api/RetryPolicy.kt`):
  never-connected errors and 429/503 for any method; timeouts, resets and 408/502/504 for
  GET/HEAD only, because a repeated save would come back as a false content-version conflict.
  `Retry-After` is honoured, capped at 8 s. The same rules as iOS `RetryPolicy`. OkHttp's own
  `retryOnConnectionFailure` is still on underneath; `RetryTest` turns it off to see this layer.
- `core/sync/ConnectivityBannerState` decides the banner's copy (same words as iOS);
  `android/ui/ConnectivityBanner` draws it; `android/net/NetworkMonitor` replaces each app's
  connectivity callback and, unlike them, reads the initial state — the default-network callback
  says nothing when an app starts offline.

## Fixed in the Docs change

The sign-in screen saved an edited server address only on **Sign in**, so **Create an account**
registered on the default server. `LoginScreen` now saves it on the way to registration — found
registering on the emulator, where the account landed on the hosted server instead of the local one.
