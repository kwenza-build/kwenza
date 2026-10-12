#!/usr/bin/env bash
# Builds the first working kwenza jar using plain javac + jar, before kwenza can build itself.
#
# Deliberately writes outside target/: this is kwenza's own disaster-recovery jar (see
# AGENTS.md), and `kwenza clean` wipes target/ entirely - a bootstrap jar that lived there
# wouldn't survive the very command it exists to recover from.
set -euo pipefail

cd "$(dirname "$0")"

rm -rf .bootstrap/classes
mkdir -p .bootstrap/classes

echo "Compiling kwenza sources..."
javac -d .bootstrap/classes --release 21 $(find src/main/java -name '*.java')

# Resources too (the manual pages `kwenza help <command>` prints). `cp -L` copies what the
# symlinks under src/main/resources point at, same as kwenza's own build does.
cp -RL src/main/resources/. .bootstrap/classes/

# Stamped the way `kwenza package` stamps it, so `kwenza --version` works from this jar too.
version="$(sed -n 's/^version = "\(.*\)"$/\1/p' kwenza.toml | head -1)"
printf 'Implementation-Title: kwenza\nImplementation-Version: %s\n' "$version" > .bootstrap/MANIFEST.MF

echo "Packaging .bootstrap/kwenza-bootstrap.jar..."
jar --create --file .bootstrap/kwenza-bootstrap.jar --manifest .bootstrap/MANIFEST.MF \
    --main-class dev.kwenza.cli.Main -C .bootstrap/classes .

echo "Built .bootstrap/kwenza-bootstrap.jar"
