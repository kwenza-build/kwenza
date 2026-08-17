#!/usr/bin/env bash
# Builds the first working kwenza jar using plain javac + jar, before kwenza can build itself.
set -euo pipefail

cd "$(dirname "$0")"

rm -rf target/bootstrap-classes
mkdir -p target/bootstrap-classes

echo "Compiling kwenza sources..."
javac -d target/bootstrap-classes --release 21 $(find src/main/java -name '*.java')

echo "Packaging target/kwenza-bootstrap.jar..."
jar --create --file target/kwenza-bootstrap.jar --main-class dev.kwenza.cli.Main -C target/bootstrap-classes .

echo "Built target/kwenza-bootstrap.jar"
