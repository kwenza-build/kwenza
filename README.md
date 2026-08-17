# kwenza

*"kwenza" — isiXhosa for "to make."*

A simpler, more declarative build tool for the JVM. Where Maven and Gradle carry decades of
flexibility (XML lifecycles, a Groovy/Kotlin scripting DSL, plugin ecosystems), kwenza aims to
stay small and inert: your `kwenza.toml` is data, not a program, and the tool has exactly one way
to do each thing.

It's still fully interoperable with the existing Java ecosystem: real transitive dependency
resolution against Maven Central, a local cache that's byte-for-byte your existing `~/.m2/repository`,
and the standard `src/main/java` directory layout. Existing Java tooling, IDEs, and caches keep working.

The name is deliberately not Java-specific — the door's left open for kwenza to grow support for
other JVM languages (Kotlin, say) later without an awkward rename.

## Status

**Working now:** dependency resolution, compilation, packaging, running, and a lockfile — the full
inner loop for a single-module Java project.

**Not built yet** (tracked as a deliberate Phase 2, not oversights): running tests (JUnit), a
`--fat` uber-jar option, multi-module workspaces, a `[scripts]` task runner, and an `--offline` mode.
See [AGENTS.md](AGENTS.md) for the reasoning behind what's in and out of scope so far.

## Getting started

kwenza builds itself, so the very first jar has to come from a plain `javac`/`jar` bootstrap script:

```bash
git clone <this-repo> && cd kwenza
./bootstrap.sh              # produces target/kwenza-bootstrap.jar
bin/kwenza package          # from here on, kwenza rebuilds itself using itself
```

Put `bin/` on your `PATH` so `kwenza` is just a command:

```bash
echo 'export PATH="$PWD/bin:$PATH"' >> ~/.zshrc   # or wherever your shell profile lives
```

Then, in any project directory containing a `kwenza.toml`:

```bash
kwenza build      # resolve dependencies + compile
kwenza check      # resolve + compile-only, no artifact left behind
kwenza run        # build + execute [package].main-class
kwenza package    # build + produce a runnable thin jar in target/
kwenza clean      # remove target/
kwenza resolve    # refresh kwenza.lock explicitly (add --update to force re-resolution)
```

## The manifest

```toml
[package]
name = "my-app"               # optional - defaults to the containing directory's name
group = "com.example"
version = "0.1.0"
java-version = "21"            # optional, defaults to "21"
main-class = "com.example.Main"

[dependencies]
guava = { module = "com.google.guava:guava", version = "33.2.1-jre" }

[dev-dependencies]
```

Dependency entries use the same shape as Gradle's `libs.versions.toml` catalogs
(`module = "group:artifact"`, `version = "..."`), since that's the notation Java developers
already know. `[dependencies]` vs `[dev-dependencies]` mirrors Cargo, replacing Maven's
`<scope>test</scope>` — dev-dependencies never leak into a consumer's transitive graph.

## Environment variables

| Variable | Effect |
|---|---|
| `KWENZA_REPO` | Override the local dependency cache (default: `~/.m2/repository`) |
| `KWENZA_TIMING` | Set to any value to print a phase-timing breakdown (resolve/compile/package) to stderr |

## How it works

- **Resolution**: real Maven Central GAV → URL mapping, POM parsing including `dependencyManagement`
  / BOM imports and `<parent>` inheritance, Maven-compatible nearest-wins version mediation, and
  concurrent POM-then-JAR fetching. Artifacts are cached at `~/.m2/repository` in Maven's own layout,
  so kwenza and any existing Maven/Gradle install share one warm cache.
- **Lockfile**: `kwenza.lock` pins exact resolved versions + checksums. Builds reuse it by default
  (reproducible, no network) and only re-resolve when the manifest's dependencies actually changed,
  or `--update` is passed.
- **Compilation**: in-process via `javax.tools.JavaCompiler` — no subprocess `javac`.
- **Packaging**: a thin jar (`target/<name>-<version>.jar`) with a `Class-Path` manifest entry
  pointing at the cached dependency jars — no shading, no fat-jar conflicts.
- **Zero external dependencies**: kwenza itself has none, including a small hand-rolled TOML parser —
  it has to be able to bootstrap without needing Maven Central or another build tool already working.

## Project layout

```
kwenza/
  kwenza.toml, kwenza.lock      # kwenza's own manifest (it has no dependencies of its own)
  bootstrap.sh                  # first-ever build, before kwenza can build itself
  bin/kwenza                    # wrapper script - put this on your PATH
  src/main/java/dev/kwenza/
    cli/                        # command dispatch (build/check/run/package/clean/resolve)
    manifest/                   # kwenza.toml + kwenza.lock models
    toml/                       # hand-rolled TOML parser
    resolver/                   # Maven Central resolution, POM parsing, mediation, local cache
    compiler/                   # javax.tools.JavaCompiler wrapper + JDK compatibility check
    packager/                   # MANIFEST.MF + thin-jar writer
  examples/hello-world/         # a small real project using kwenza (Guava dependency)
  benchmarks/hello-world/       # timed kwenza vs Maven vs Gradle comparison, see below
```

## Benchmarks

[`benchmarks/hello-world`](benchmarks/hello-world) times kwenza against Maven and Gradle building
the same trivial app, using fully isolated dependency caches so it's safe to re-run anytime:

```bash
cd benchmarks/hello-world
./run.sh cold     # wipe caches, time one real-network-fetch build per tool
./run.sh warm 10  # ensure caches are populated, time N clean rebuilds
./run.sh both 5   # (default) cold pass, then a warm loop
```

Illustrative results on one machine (single-file project, so this mostly measures fixed tool
overhead, not compile-at-scale throughput):

| Scenario | kwenza | Maven | Gradle |
|---|---|---|---|
| Cold (real network fetch) | ~1s | ~12-14s | ~12-13s |
| Warm median | ~0.75s | ~1.8s | ~0.7s* |

\*Gradle's first warm run pays its daemon's startup cost (~1-4s); it's faster than kwenza once
the daemon is warm, since kwenza launches a fresh JVM on every invocation. That's a known,
deliberate trade-off, not a bug — see [AGENTS.md](AGENTS.md).

## License

MIT — see [LICENSE](LICENSE).
