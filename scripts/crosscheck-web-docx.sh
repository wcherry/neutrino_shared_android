#!/usr/bin/env bash
# Cross-check the shared Android `.docx` reader and writer (neutrino-core `ooxml/`) against the web's
# `web/apps/web/src/lib/ooxml/docx`, both ways: the web reads what Android wrote as it reads its own
# package of the same document, and Android reads what the web wrote as it reads its own.
#
# Runs the web's actual sources through the web's own vitest (the sibling `neutrino` repo — the
# platform layout is load-bearing), so a drift in either implementation fails here. Needs Node 18+
# with `neutrino/web` dependencies installed, and JDK 17. The test file is copied into the web test
# tree for the run and removed afterwards, whatever happens.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="$(dirname "$here")"
web="$repo/../neutrino/web"
[ -d "$web/apps/web/src/lib/ooxml/docx" ] || { echo "missing $web — clone wcherry/neutrino beside this repo"; exit 1; }
[ -d "$web/node_modules" ] || { echo "run npm install in $web first"; exit 1; }
if jdk17="$(/usr/libexec/java_home -v 17 2>/dev/null)"; then export JAVA_HOME="$jdk17"; fi

work="${TMPDIR:-/tmp}/neutrino-android-docx-crosscheck"
mkdir -p "$work"
probe="$web/apps/web/src/__tests__/ooxml/zz-android-docx-crosscheck.test.ts"
trap 'rm -f "$probe"' EXIT

(cd "$repo" && ./gradlew -q :neutrino-core:docxCrossCheck -Pargs="emit $work/android.json")
cp "$here/crosscheck-web-docx.test.ts" "$probe"
(cd "$web" && NEUTRINO_DOCX_CROSSCHECK_IN="$work/android.json" NEUTRINO_DOCX_CROSSCHECK_OUT="$work/web.json" \
  npx vitest run --config vitest.config.ts "$probe")
(cd "$repo" && ./gradlew -q :neutrino-core:docxCrossCheck -Pargs="verify $work/android.json $work/web.json")
echo "Cross-check complete: the web and Android read each other's .docx packages as they read their own."
