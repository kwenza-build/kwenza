#!/usr/bin/env bash
# Timed comparison of kwenza vs Gradle vs Maven building the same hello-world app.
#
# Uses fully isolated, project-local dependency caches for all three tools
# (never touches ~/.m2 or ~/.gradle) so this is safe to re-run at any time,
# including repeatedly as kwenza grows new features.
#
# Usage: ./run.sh [cold|warm|both] [warm-runs]
#   cold  - wipe the isolated dependency caches, time ONE real-network-fetch build per tool
#   warm  - make sure caches are populated (priming silently if needed), time N clean rebuilds
#   both  - (default) cold pass first, then warm loop reusing the cache it just populated
#   warm-runs defaults to 5, ignored in cold mode
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CACHE="$HERE/.cache"
MODE="${1:-both}"
WARM_RUNS="${2:-5}"
LOG=/tmp/kwenza-bench-out.log

case "$MODE" in
    cold|warm|both) ;;
    *) echo "error: unknown mode '$MODE' (expected cold, warm, or both)" >&2; exit 1 ;;
esac

KWENZA_JAR="$(ls "$HERE"/../../target/kwenza-[0-9]*.jar 2>/dev/null | tail -1)"
if [ -z "${KWENZA_JAR:-}" ]; then
    echo "error: no kwenza jar found. Build kwenza first (bin/kwenza package or bootstrap.sh)." >&2
    exit 1
fi

mkdir -p "$CACHE/kwenza-m2" "$CACHE/maven-m2" "$CACHE/gradle-home"

median() {
    printf '%s\n' "$@" | sort -n | awk '{a[NR]=$1} END{print (NR%2==1)?a[(NR+1)/2]:(a[NR/2]+a[NR/2+1])/2}'
}
min() {
    printf '%s\n' "$@" | sort -n | head -1
}

# Times "$@", printing elapsed seconds; exits with the command's own status/log on failure.
timed() {
    local start end status elapsed
    start=$(date +%s.%N)
    "$@" >"$LOG" 2>&1
    status=$?
    end=$(date +%s.%N)
    elapsed=$(echo "$end $start" | awk '{printf "%.2f", $1-$2}')
    if [ $status -ne 0 ]; then
        echo "FAILED (${elapsed}s) - see $LOG" >&2
        tail -30 "$LOG" >&2
        exit 1
    fi
    echo "$elapsed"
}

# Wipes dependency caches so the next build is a genuine cold, real-network-fetch build.
# Gradle's wrapper distribution (the gradle binary itself) is deliberately preserved -
# re-downloading a ~120MB tool install isn't part of what we're measuring.
clear_caches() {
    rm -rf "${CACHE:?}/kwenza-m2"/* "$CACHE/maven-m2"/*
    rm -rf "$CACHE/gradle-home/caches" "$CACHE/gradle-home/daemon"
    mkdir -p "$CACHE/kwenza-m2" "$CACHE/maven-m2" "$CACHE/gradle-home"
}

clean_outputs() {
    rm -rf "$HERE/kwenza/target" "$HERE/kwenza/kwenza.lock"
    rm -rf "$HERE/maven/target"
    rm -rf "$HERE/gradle/build"
}

# One untimed build per tool, just to populate the caches - used by standalone `warm` mode
# so it's correct even on a fresh checkout that's never run `cold` first.
prime_caches() {
    cd "$HERE/kwenza"
    KWENZA_REPO="$CACHE/kwenza-m2" java -jar "$KWENZA_JAR" package >"$LOG" 2>&1 || { echo "prime failed (kwenza) - see $LOG" >&2; exit 1; }
    cd "$HERE/maven"
    ./mvnw -q -Dmaven.repo.local="$CACHE/maven-m2" package >"$LOG" 2>&1 || { echo "prime failed (maven) - see $LOG" >&2; exit 1; }
    cd "$HERE/gradle"
    GRADLE_USER_HOME="$CACHE/gradle-home" ./gradlew build >"$LOG" 2>&1 || { echo "prime failed (gradle) - see $LOG" >&2; exit 1; }
}

run_cold() {
    echo "=== COLD (wiping isolated caches, real network fetch) ==="
    clear_caches
    clean_outputs

    cd "$HERE/kwenza"
    e=$(timed env KWENZA_REPO="$CACHE/kwenza-m2" java -jar "$KWENZA_JAR" package)
    echo "kwenza  cold: ${e}s"

    cd "$HERE/maven"
    e=$(timed ./mvnw -q -Dmaven.repo.local="$CACHE/maven-m2" package)
    echo "maven   cold: ${e}s"

    cd "$HERE/gradle"
    e=$(timed env GRADLE_USER_HOME="$CACHE/gradle-home" ./gradlew --no-daemon build)
    echo "gradle  cold: ${e}s"
}

run_warm() {
    echo "=== WARM (dependency caches populated, clean rebuild x $WARM_RUNS) ==="

    echo "-- kwenza --"
    kwenza_times=()
    for i in $(seq 1 "$WARM_RUNS"); do
        cd "$HERE/kwenza"
        rm -rf target
        e=$(timed env KWENZA_REPO="$CACHE/kwenza-m2" java -jar "$KWENZA_JAR" package)
        kwenza_times+=("$e")
        echo "  run $i: ${e}s"
    done

    echo "-- maven --"
    maven_times=()
    for i in $(seq 1 "$WARM_RUNS"); do
        cd "$HERE/maven"
        rm -rf target
        e=$(timed ./mvnw -q -Dmaven.repo.local="$CACHE/maven-m2" package)
        maven_times+=("$e")
        echo "  run $i: ${e}s"
    done

    echo "-- gradle (with daemon, warm after first) --"
    gradle_times=()
    for i in $(seq 1 "$WARM_RUNS"); do
        cd "$HERE/gradle"
        rm -rf build
        e=$(timed env GRADLE_USER_HOME="$CACHE/gradle-home" ./gradlew build)
        gradle_times+=("$e")
        echo "  run $i: ${e}s"
    done
    env GRADLE_USER_HOME="$CACHE/gradle-home" "$HERE/gradle/gradlew" --stop >"$LOG" 2>&1 || true

    echo
    echo "=== SUMMARY (warm, min / median over $WARM_RUNS runs) ==="
    echo "kwenza: min=$(min "${kwenza_times[@]}")s median=$(median "${kwenza_times[@]}")s"
    echo "maven:  min=$(min "${maven_times[@]}")s median=$(median "${maven_times[@]}")s"
    echo "gradle: min=$(min "${gradle_times[@]}")s median=$(median "${gradle_times[@]}")s (first run pays Gradle daemon startup cost)"
}

case "$MODE" in
    cold)
        run_cold
        ;;
    warm)
        echo "Priming caches (untimed)..."
        prime_caches
        echo
        run_warm
        ;;
    both)
        run_cold
        echo
        run_warm
        ;;
esac
