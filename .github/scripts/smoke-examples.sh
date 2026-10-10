#!/usr/bin/env bash
# The manual end-to-end checks from AGENTS.md's "Verification" section, scripted so CI runs them
# on every push. Expects kwenza's own jar to already be built (bin/kwenza package).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
KWENZA="$ROOT/bin/kwenza"

# Prints a jar's Class-Path manifest attribute on one line. MANIFEST.MF wraps lines at 72 bytes
# with a leading-space continuation, so a plain grep could miss a name split across two lines.
class_path_of() {
    unzip -p "$1" META-INF/MANIFEST.MF | tr -d '\r' \
        | awk '/^ /{ printf "%s", substr($0, 2); next } { printf "\n%s", $0 } END { print "" }' \
        | grep '^Class-Path:' || true
}

echo "::group::hello-world: clean run"
cd "$ROOT/examples/hello-world"
"$KWENZA" clean run
echo "::endgroup::"

echo "::group::junit-word-count: clean test, then package without dev-dependencies"
cd "$ROOT/examples/junit-word-count"
"$KWENZA" clean test
"$KWENZA" package
jar="$(ls target/*.jar | head -1)"
class_path="$(class_path_of "$jar")"
echo "$jar -> ${class_path:-<no Class-Path>}"
if grep -qiE 'junit|mockito' <<<"$class_path"; then
    echo "::error::$jar leaks dev-dependencies onto its Class-Path: $class_path"
    exit 1
fi
echo "::endgroup::"

echo "::group::testng-calculator: TestNG engine discovered"
cd "$ROOT/examples/testng-calculator"
output="$("$KWENZA" clean test --verbose 2>&1)"
echo "$output"
if ! grep -q 'TestNG' <<<"$output"; then
    echo "::error::no TestNG engine in the test tree - third-party JUnit Platform engine discovery broke"
    exit 1
fi
echo "::endgroup::"

echo "All example smoke checks passed."
