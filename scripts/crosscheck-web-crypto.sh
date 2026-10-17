#!/usr/bin/env bash
# Cross-check the shared Android crypto (every Android app's) against the web's e2e-crypto TypeScript and real libsodium, both
# ways: the web opens what Android sealed, and Android opens what the web sealed.
#
# Runs the web's actual source files (from the sibling `neutrino` repo — the platform layout is
# load-bearing), so a drift in either implementation fails here. Needs Node 18+, JDK 17, and network
# access for the first `npm install`.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="$(dirname "$here")"
web_src="$repo/../neutrino/web/packages/e2e-crypto/src"
[ -d "$web_src" ] || { echo "missing $web_src — clone wcherry/neutrino beside this repo"; exit 1; }
# Gradle 8.14 runs on JDK 17–24; prefer 17 where macOS can find one.
if jdk17="$(/usr/libexec/java_home -v 17 2>/dev/null)"; then export JAVA_HOME="$jdk17"; fi

work="${TMPDIR:-/tmp}/neutrino-android-crosscheck"
mkdir -p "$work"
rm -rf "$work/src" && cp -R "$web_src" "$work/src"
cp "$here/crosscheck-web-crypto.mjs" "$work/run.mts"
(cd "$work"
 [ -f package.json ] || npm init -y >/dev/null
 [ -d node_modules/libsodium-wrappers ] || npm install --silent libsodium-wrappers@0.7.15 tsx@4 >/dev/null)

(cd "$repo" && ./gradlew -q :neutrino-core:crossCheck -Pargs="emit $work/android.json")
(cd "$work" && node --import tsx run.mts "$work/android.json" "$work/web.json")
(cd "$repo" && ./gradlew -q :neutrino-core:crossCheck -Pargs="verify $work/android.json $work/web.json")
echo "Cross-check complete: the web and Android open each other's keys, files, kits, QR codes, pairing replies and saved places."
