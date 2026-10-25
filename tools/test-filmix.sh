#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
classes="$(mktemp -d)"
trap 'rm -rf "$classes"' EXIT
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -encoding UTF-8 -d "$classes" \
  app/src/io/freemytv/FilmixMatch.java tools/tests/FilmixMatchTest.java
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$classes" io.freemytv.FilmixMatchTest
