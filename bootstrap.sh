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

echo "Packaging .bootstrap/kwenza-bootstrap.jar..."
jar --create --file .bootstrap/kwenza-bootstrap.jar --main-class dev.kwenza.cli.Main -C .bootstrap/classes .

echo "Built .bootstrap/kwenza-bootstrap.jar"
