# Changelog

All notable changes to kwenza are recorded in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and kwenza follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

Everything below will ship in kwenza's first release, 0.1.0.

### Added

#### The build

- `kwenza.toml` is the project manifest, written in TOML. It is pure data, with no scripting or
  plugin DSL.
  - `[package]` takes `name`, `group`, `version`, `java-version` (default `21`), `main-class` and
    `output-dir` (default `target`). `name`, `group` and `version` are required.
  - `[dependencies]` and `[dev-dependencies]` use the same notation as Gradle version catalogs:
    `module = "group:artifact"` with an exact `version`.
  - `[versions]` holds shared version strings that dependencies refer to with `version.ref`, also
    as in Gradle version catalogs. A `version.ref` with no matching entry is an error, reported
    before any resolution starts.
- Dependency resolution against Maven Central:
  - transitive dependencies, with Maven's nearest-wins version mediation;
  - `<parent>` POM inheritance;
  - `dependencyManagement`, including BOM imports.
- Downloaded artifacts go into `~/.m2/repository`, in exactly Maven's layout, so kwenza shares one
  cache with any existing Maven or Gradle install. Set `KWENZA_REPO` to use a different directory.
- `kwenza.lock` makes builds reproducible by default.
  - It records every resolved artifact with its exact version, a SHA-256 checksum, and which
    dependency pulled it in.
  - Builds reuse the lock without touching the network until the declared dependencies change, or
    until `--update` is passed.
- Dev-dependencies stay off the classpath of `build`, `run` and `package`, and out of packaged jars.
- Source and resources follow Maven's standard layout: `src/main/java`, `src/main/resources`,
  `src/test/java` and `src/test/resources`.

#### Commands

- `kwenza build` resolves dependencies and compiles `src/main/java` in-process, copying
  `src/main/resources` alongside the classes.
- `kwenza run` builds, then runs `[package] main-class` in a fresh JVM. It prints the exact `java`
  command it runs, and exits with the program's exit code.
- `kwenza test` builds, compiles `src/test/java`, and runs the tests in a forked JVM through the JUnit
  Platform Console Launcher.
  - It works with any JUnit Platform engine (Jupiter, Vintage, or a third-party engine such as
    the TestNG engine) with no kwenza configuration.
  - When `mockito-core` is a dependency, it loads Mockito as an explicit `-javaagent`. This avoids
    the self-attach warning on JDK 21 and newer.
  - A project with no tests exits cleanly. A project with tests but no Console Launcher
    dependency fails with a message naming the dependency to add.
- `kwenza package` builds and writes a thin, runnable jar whose `Class-Path` manifest entry points
  at the cached dependency jars.
- `kwenza resolve` refreshes `kwenza.lock` on its own, without compiling.
- `kwenza clean` deletes the output directory.
- Several commands can run in one invocation, such as `kwenza clean build run`. Resolution and
  compilation happen at most once per invocation, and `clean` correctly forces a recompile later in
  the same chain.
- `--update` forces a fresh resolution against Maven Central.
- `--verbose` prints each resolved dependency, and the full `java` command for `run` and `test`.
- kwenza's own status lines go to stderr, so `kwenza run`'s stdout is only the program's output.
  They are coloured in a terminal and plain when piped, or when `NO_COLOR` is set.
- `KWENZA_TIMING` prints a phase-by-phase timing breakdown to stderr.
- kwenza fails fast with a specific message when `java-version` needs a newer JDK than the one
  running kwenza.

#### Project

- kwenza builds itself: `bootstrap.sh` produces the first jar, and after that kwenza packages
  itself.
- kwenza has no external dependencies of its own, including its TOML parser.
- Examples: `hello-world`, `junit-word-count` (JUnit 5 and Mockito) and `testng-calculator`
  (TestNG through the JUnit Platform).
- Benchmarks that compare kwenza with Maven and Gradle, cold and warm, using isolated caches.
- Documentation: Getting Started, Manifest Reference, CLI Reference, Architecture, and Design
  Principles, published with a home page at https://kwenza.dev.
- Released under the Apache License 2.0.

### Security

- Every jar kwenza puts on a classpath is verified, and verification fails closed.
  - A newly downloaded jar must match Maven Central's published `.sha1`. A missing, unreachable
    or empty checksum is rejected, the same as a mismatch.
  - A jar that is already in the local cache must match its `sha256` in `kwenza.lock`.
  - A lock entry with no `sha256` is an error.
- Annotation processors are never discovered from the classpath, on any JDK. javac's own default
  differs by version (JDK 21 runs any processor a dependency ships, JDK 23 and newer don't), and
  kwenza compiles in-process, so a processor in any transitive dependency would otherwise run
  inside kwenza itself. Declaring processors explicitly is planned for a later release.

[Unreleased]: https://github.com/kwenza-build/kwenza/commits/main
