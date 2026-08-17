# AGENTS.md

Context for anyone (human or AI) picking up this project without having been part of the
conversation that designed it. If you're about to change something and find yourself wondering
"why is it done this way instead of the obvious other way," check here first.

## What this is

A simpler, declarative build tool for the JVM: TOML manifest, real Maven Central dependency
resolution, Maven-compatible local cache and JAR conventions. See [README.md](README.md) for the
user-facing pitch and current command surface.

## Design decisions and why

- **TOML, with Gradle-version-catalog-style dependency notation** (`module = "group:artifact"`,
  `version = "..."`) — this is the exact shape Java developers already know from
  `libs.versions.toml`, so there's nothing new to learn for that part. The manifest is deliberately
  kept as inert data with no scripting/plugin DSL — that's the actual thing making Maven/Gradle
  builds hard to reason about, not their XML/Groovy syntax per se.

- **Nearest-wins version mediation, not semver unification.** Central artifacts don't reliably
  follow semver, and the ecosystem's POMs already assume Maven's own nearest-wins/BOM-override
  semantics. `dependencyManagement`/BOM `<scope>import</scope>` and `<parent>` inheritance are
  implemented in `Resolver`/`PomParser` for exactly this reason — skipping them would break a large
  share of real Central artifacts (anything using a BOM: Jackson, Spring, etc.).

- **The local cache is `~/.m2/repository`, byte-for-byte Maven's own layout** (not a new
  kwenza-specific cache dir). This means kwenza and any existing Maven/Gradle install share one warm
  cache, verified for real during development (a `kwenza build` picked up artifacts an existing
  Maven install had already cached, and vice versa). `KWENZA_REPO` overrides this when needed
  (added for safe, isolated benchmarking — see `benchmarks/*/run.sh` — but it's a generally useful
  override, not benchmark-only).

- **Zero external dependencies for kwenza itself**, including a hand-rolled TOML parser
  (`dev.kwenza.toml.Toml`) rather than pulling one in. kwenza has to be able to bootstrap without
  needing Maven Central reachable or another build tool already working — see `bootstrap.sh`. Don't
  add a dependency to kwenza's own `kwenza.toml` without first reconsidering this.

- **In-process compilation and packaging only** (`javax.tools.JavaCompiler`, `java.util.zip`) — no
  subprocess `javac`/`jar` spawning. Keeps behavior consistent across platforms and avoids a whole
  class of PATH/quoting bugs.

- **Thin jar + `Class-Path` manifest entry, not a fat/uber jar, by default.** Simplest correct
  option, matches how a lot of real shops actually deploy, avoids shading conflicts entirely.
  `--fat` is a deliberate Phase 2 addition (see below), not an oversight.

- **Output directory defaults to `target`, and is deliberately overridable** (`[package]
  output-dir`). Considered switching to something on-brand (`made/`, echoing the name's meaning)
  but decided the output directory is one of the few places where matching an existing tool has
  *zero* interop payoff — nothing outside kwenza ever reads it, unlike `src/main/java` (IDEs/humans
  expect it) or `~/.m2/repository` (shared with real Maven/Gradle installs). Kept `target` to match
  Maven/Cargo for now, but made it a one-line override precisely because there's no compatibility
  reason to enforce it project-wide.

- **Self-hosting.** Once kwenza could compile+package itself, it switched immediately to building
  itself with itself (`kwenza.toml` at the repo root has no dependencies). `bootstrap.sh` exists
  *only* for the very first jar (or disaster recovery) — day-to-day rebuilds go through
  `bin/kwenza package`, not the bootstrap script. Don't reintroduce a permanent non-self-hosted
  build path.

- **Reproducible-by-default resolution.** `kwenza.lock` records a hash of the manifest's declared
  dependencies (`KwenzaLock.hashDependencies`); `build`/`run`/`package` reuse the lock and skip
  network resolution entirely unless that hash changed or `--update` is passed. This is the uv/Cargo
  "lockfile wins by default" pattern, not Maven's "always re-resolve, hope the range is stable" one.

- **Fail fast on JDK/`java-version` mismatches.** `javac --release N` silently requires the running
  JDK to be N or newer; kwenza checks this itself (`IncompatibleJdkException` in
  `JavaCompilation`) and gives a specific, actionable message instead of surfacing a raw javac error.

- **The name.** "Kwenza" is isiXhosa for "to make" — chosen over several alternatives (including
  "Kiln," dropped after discovering real naming collisions with an existing Java-runtime project and
  a prominent AI-tooling brand of the same name) specifically because it's *not* Java-specific,
  leaving room for kwenza to grow support for other JVM languages later without a rename.

## Explicitly deferred (Phase 2 — not oversights, don't "helpfully" half-implement these)

- `kwenza test` + JUnit Platform Console Launcher integration
- `kwenza tree` (dependency tree display)
- `--fat` uber-jar packaging
- `[workspace]` multi-module support
- `[scripts]` task runner
- `--offline` mode

## Known, deliberate trade-off: no daemon

kwenza launches a fresh JVM on every single invocation (unlike Gradle's persistent daemon). Measured
directly (see `benchmarks/hello-world` and the phase-timing work behind `KWENZA_TIMING`): kwenza's
own resolve/compile/package logic is fast and stable (~400ms for a trivial project), but a cold JVM
has to class-load ~10x more classes than a bare `java -version` (javac internals, XML parsing, HTTP
client — none covered by the JDK's default Class Data Sharing archive), costing another
~350-400ms every time. That's *why* Gradle's warm daemon edges out kwenza on tiny projects despite
kwenza's simpler internals. A custom AppCDS archive for kwenza's own class footprint would likely
close most of this gap — flagged as a real future optimization, intentionally not built yet.

## Conventions

- Package structure: `dev.kwenza.{cli,manifest,toml,resolver,compiler,packager}`, one clear
  responsibility per package.
- Rebuild via self-hosting: `cd kwenza && bin/kwenza package`. Only reach for `bootstrap.sh` if
  `target/` has no working jar at all.
- `KWENZA_REPO` (dependency cache override) and `KWENZA_TIMING` (phase-timing diagnostics to
  stderr) are real, documented features — see README — not just benchmark plumbing.
- Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/), enforced by
  `.githooks/commit-msg` (a plain shell script, not `commitlint` — deliberately no Node.js/npm
  dependency for a project whose whole point is minimal moving parts). Activate with
  `git config core.hooksPath .githooks`.

## Verification

There's no automated test suite yet (that's part of Phase 2). Until then:

- [`examples/hello-world`](examples/hello-world) — a small real project (one Maven Central
  dependency) used as a manual end-to-end smoke test.
- [`benchmarks/hello-world`](benchmarks/hello-world/run.sh) — safe to re-run anytime
  (`cold`/`warm`/`both` modes), uses fully isolated caches under `.cache/`, never touches your real
  `~/.m2` or `~/.gradle`.
- Any change to the resolver, compiler, or packager should be manually verified against
  `examples/hello-world` (`kwenza clean && kwenza run`) before being considered done.
