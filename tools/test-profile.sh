#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
test_dir="$(mktemp -d)"
trap 'rm -rf "$test_dir"' EXIT
java_cmd="java"
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then java_cmd="$JAVA_HOME/bin/java"; fi
"$java_cmd" com.sun.tools.javac.Main -encoding UTF-8 -d "$test_dir" \
  "$project_dir/app/src/main/java/com/anxiong/watchlink/WatchProfile.java" \
  "$project_dir/tests/WatchProfileTest.java"
"$java_cmd" -cp "$test_dir" WatchProfileTest
