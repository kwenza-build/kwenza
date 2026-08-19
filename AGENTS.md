# AGENTS.md

Context for anyone (human or AI) picking up this project without having been part of the
conversation that designed it. If you're about to change something and find yourself wondering
"why is it done this way instead of the obvious other way," check here first.

## What this is

A simple, declarative build tool for the JVM: TOML manifest, real Maven Central dependency
resolution, Maven-compatible local cache and JAR conventions. See [README.adoc](README.adoc) for
the user-facing pitch and current command surface, and [docs/](docs/) for the full manual.

## Design decisions and why

For the higher-level principles these decisions keep falling out of, see
[docs/design-principles.adoc](docs/design-principles.adoc). This section stays the place for the
specific decision and its specific reasoning; that one is for the pattern.

- **TOML, with Gradle-version-catalog-style dependency notation** (`module = "group:artifact"`,
  `version = "..."`) — this is the exact shape Java developers already know from
  `libs.versions.toml`, so there's nothing new to learn for that part. The manifest is deliberately
  kept as inert data with no scripting/plugin DSL — that's the actual thing making Maven/Gradle
  builds hard to reason about, not their XML/Groovy syntax per se.

- **`[versions]` + `version.ref`** mirrors Gradle's version catalogs exactly (same `version.ref`
  dotted-key syntax, not a kwenza-invented alternative), for the same reason as the dependency
  notation above — zero new syntax for anyone already using Gradle catalogs. Kept intentionally
  simple: only plain version strings in `[versions]`, no Gradle-style rich version objects
  (`strictly`/`prefer`/`reject`) — kwenza doesn't support version ranges at all yet, so there'd be
  nothing for the extra richness to actually do.

- **Nearest-wins version mediation, not semver unification.** Central artifacts don't reliably
  follow semver, and the ecosystem's POMs already assume Maven's own nearest-wins/BOM-override
  semantics. `dependencyManagement`/BOM `<scope>import</scope>` and `<parent>` inheritance are
  implemented in `Resolver`/`PomParser` for exactly this reason — skipping them would break a large
  share of real Central artifacts (anything using a BOM: Jackson, Spring, etc.).

- **The local cache is `~/.m2/repository`, byte-for-byte Maven's own layout** (not a new
  kwenza-specific cache dir). This means kwenza and any existing Maven/Gradle install share one warm
  cache, verified for real during development (a `kwenza build` picked up artifacts an existing
  Maven install had already cached, and vice versa). `KWENZA_REPO` overrides this when needed
  (added for safe, isolated benchmarking — see `benchmarks/Bench.java` — but it's a generally
  useful override, not benchmark-only).

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

- **Multiple commands per invocation** (`kwenza build run`, `kwenza clean build run`) are memoized
  within a single `BuildState` (`Main.java`) so a chain doesn't redo resolve/compile once per
  command that happens to need it — `build run` resolves and compiles exactly once, not twice.
  `clean` explicitly resets `state.compiled`, since it deletes the very output that memoization
  would otherwise assume was still valid. If you add a new command that produces or consumes
  build output, make sure it goes through `ensureResolved`/`ensureCompiled` rather than calling
  `resolve`/`JavaCompilation.compile` directly, or chaining will silently redo work (or worse,
  silently reuse stale output after a `clean`).

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

- **`kwenza test` forks a JVM to run tests, the same as `run` does for the user's app** — it does
  *not* call the JUnit Platform's launcher API in-process. Two reasons: it keeps kwenza's own
  process free of any JUnit/Mockito classes (nothing to compile against, nothing to reflect
  against, consistent with "zero external dependencies for kwenza itself" above), and it isolates
  kwenza itself from a test calling `System.exit()` or Mockito's inline mock maker self-attaching a
  Java agent (already a JDK21+ warning; later JDKs may restrict it further) — the same reason
  Maven Surefire and Gradle's test workers both fork rather than running in the build's own JVM.
  Forking doesn't mean giving up parallelism inside a single test run: JUnit Platform's own
  `junit.jupiter.execution.parallel.*` configuration (auto-discovered from a
  `junit-platform.properties` file, which `kwenza test` copies from `src/test/resources` alongside
  the compiled test classes, same as `build` already does for `src/main/resources`) works today
  with zero kwenza-specific flags — see `examples/word-counter` for a working example. Running
  disjoint *shards* of a suite across several forked JVMs at once is a different, larger feature,
  deliberately deferred (see below).

- **No test-framework plugin system.** `kwenza test` always drives
  `org.junit.platform.console.ConsoleLauncher`, full stop — there's no kwenza-side mechanism for
  swapping in a different test runner. This isn't a gap: the JUnit Platform itself already
  discovers whichever test *engine* (Jupiter, Vintage, or a third-party one) is on the classpath via
  `ServiceLoader`, the same mechanism Maven's Surefire (provider auto-detection) and Gradle
  (`useJUnitPlatform()`) both lean on. Supporting another JVM test framework is a matter of it
  shipping a JUnit Platform engine and the user declaring it as a dev-dependency — never a reason
  to build a plugin system of kwenza's own, consistent with the manifest staying data, not a
  program.

- **`resolve()` still merges `[dependencies]` and `[dev-dependencies]` into one `Resolution` for
  `kwenza.lock`, but `build`/`package`/`run` now use a second, dependencies-only
  resolution** (`ensureMainResolved` in `Main.java`) so dev-dependencies (JUnit, Mockito, ...)
  never leak onto a shipped artifact's classpath. This is a pragmatic fix, not the "real" one:
  Maven/Gradle tag a single resolved graph with per-node scope membership, which would mean
  teaching `Resolver.java`'s `Node`/`ResolvedArtifact` about which root set (`dependencies` vs.
  `devDependencies`) each artifact is reachable from. `ensureMainResolved` sidesteps that by
  re-running `Resolver.resolve` with just the main deps — cheap in practice because
  `MavenRepository` already caches every POM/jar it fetches to `~/.m2`, so the second pass never
  touches the network on its own, but it is real duplicated in-memory graph mediation work per
  invocation. Worth revisiting with proper scope-tagging if that ever shows up in profiling, or if
  `[workspace]` multi-module support (also deferred) ends up needing the same distinction anyway.

- **`benchmarks/*/{kwenza,maven,gradle}/src` are symlinks into the matching `examples/*/src`, never
  copies.** Found the hard way: `examples/hello-world` and its benchmark copy had already silently
  diverged (different printed message) before this convention existed. Symlinking the source tree
  makes that class of drift structurally impossible rather than something to remember to keep in
  sync by hand. Deliberately scoped to `src/` only — the manifest
  (`kwenza.toml`/`pom.xml`/`build.gradle.kts`) and `kwenza.lock` stay local per benchmark variant,
  because a `cold` benchmark run has to delete `kwenza.lock` to force a real re-resolution as part
  of what it measures, and a directory-level symlink would make that delete the example's own
  committed lockfile as a side effect. See `benchmarks/README.adoc` for the full convention.

- **`kwenza check` (a compile-only pass into a scratch directory, no artifact) was added, then
  removed.** It was modeled on `cargo check`, but the reason `cargo check` earns its keep doesn't
  carry over: it exists because `rustc` codegen is slow enough that skipping it is a real, distinct
  speedup over `cargo build`. `javac` doesn't have that gap — kwenza's own compile step is already
  fast (see "Fast is not a trade-off to apologize for" in docs/design-principles.adoc) — so a
  separate check-only command was solving a problem kwenza doesn't have, just because another
  tool's ecosystem has a command with that name. The `check` name is free again for something that
  would actually earn it — Checkstyle-style verification is the leading candidate, see "Future
  ideas" below — but that would be a different feature, not a resurrection of this one.

## Explicitly deferred (Phase 2 — not oversights, don't "helpfully" half-implement these)

- `kwenza tree` (dependency tree display)
- `--fat` uber-jar packaging
- `[workspace]` multi-module support
- `[scripts]` task runner
- `--offline` mode
- `kwenza test` parallelism (`--test-threads` / multi-process sharding across several forked
  JVMs, the equivalent of Gradle's `maxParallelForks` or Surefire's `forkCount`) — see the
  "`kwenza test` forks a JVM..." decision above for what's already possible today without it.

## Also on the radar (surfaced later, not yet triaged against the above)

- **`--project <path>`** — every command currently only operates on the current working directory
  (see `docs/cli-reference.adoc`'s opening line). Small and standalone; a plausible easy first PR.
- **AppCDS for kwenza's own cold-start** — see "Known, deliberate trade-off: no daemon" below. A
  quantified, already-scoped performance item, not a new investigation.
- **Checksum verification fails open, not closed.** `MavenRepository.fetchJarAsync` silently skips
  `.sha1` verification if fetching the checksum itself fails (`.exceptionally(ex -> null)`), and
  `kwenza.lock`'s per-entry `sha256` is written but never read back anywhere — `ensureFromLock`
  trusts a cached jar from `~/.m2/repository` with no verification at all. See "Secure by default"
  in docs/design-principles.adoc. A real gap to close (fail closed by default, actually verify
  cache hits against the lock), not a speculative future feature.
- **Annotation-processor discovery is uncontrolled.** `JavaCompilation` invokes `javac` without
  `-proc:none`, so whether a processor bundled in a resolved dependency runs automatically during
  `kwenza build` depends on the running JDK's own default behavior — not a deliberate kwenza
  decision either way yet. Worth resolving explicitly once extensibility gets designed for real.

## Future ideas (unprioritized, not yet scoped)

Bigger and vaguer than anything above — these haven't been triaged, sized, or committed to. Don't
treat absence from "Explicitly deferred" as "not planned," and don't treat presence here as "will
definitely happen." See [docs/design-principles.adoc](docs/design-principles.adoc) for the
principles these should be weighed against once any of them gets scoped for real.

Delivery & distribution
- First-class Docker/OCI image output as a `[package]` target, alongside thin/fat jar — containers,
  not zips or tarballs, are the primary way most people actually ship applications now.
- Publishing to Maven-compatible registries (Central, private/custom registries), including auth —
  the publish-side mirror of the existing consume-side `KWENZA_REPO` override.

Developer experience
- IDE support: IntelliJ IDEA and Eclipse integration, flagged as important to land relatively
  early rather than as a late nice-to-have.
- `kwenza new` — scaffold a new project quickly.
- A `kwenza.dev` website (domain already owned) — simple to start.
- Lean on version managers (asdf, mise, sdkman) for "which kwenza/JDK for this project" instead of
  a per-project wrapper script/jar the way Gradle and Maven each ship one — the cargo/rustup and
  dotnet model of feeling bundled with the platform rather than vendored per-repo.
- Semver-and-dependency-resolution semantics that feel intuitive to developers already fluent in
  Maven/Gradle's model, including SNAPSHOT-style pre-release handling.
- **Open question: minimum supported Java version.** Some future features imply a floor — JPMS
  support (below) doesn't make sense below Java 9, for instance — but wide adoption, including by
  older projects, pulls toward supporting Java 8 or earlier. Genuinely undecided, and the two pulls
  are in real tension rather than easily split; needs a deliberate choice, not a default.
- **Maybe: an auto-migration tool from Maven/Gradle projects.** Attractive if it works, but real
  Maven/Gradle builds lean heavily on plugin-specific and DSL-scripted customization that has no
  clean data-shaped equivalent in kwenza (see "the manifest is data" in
  docs/design-principles.adoc) — unclear whether the achievable subset (straightforward POMs/build
  scripts) is worth the effort relative to how much real-world usage actually stays that simple.
  Parked as an idea, not a commitment.

Extensibility
- A `build-dependencies`-style mechanism (Cargo-inspired) for custom build-time logic — explicitly
  *not* Cargo's `build.rs` arbitrary-scripting model. See "Extensibility through JVM discovery" in
  docs/design-principles.adoc for the leading shape (annotation processors,
  `ServiceLoader`-discovered participants resolved as ordinary dependencies).
- Builtin support for standard, commonly-expected Java tooling that's painful to wire up in
  Gradle/Maven — Checkstyle is the concrete example. A plausible future home for the `check`
  command name (see the removed-`check` decision above) once actually built — a different feature
  from the `check` that was removed, not a resurrection of it.

Compiler & language
- Official Java Platform Module System (`module-info.java`) support, done properly rather than
  bolted on — Maven and Gradle both predate JPMS and never fully absorbed it into their own
  multi-module systems, a real opportunity for kwenza to differentiate. Distinct axis from
  `[workspace]` (module boundaries vs. multiple manifests in one repo) — they compose, they don't
  substitute for each other.
- Multi-JVM-language support (Kotlin, Scala, ...) — see "Multi-JVM-language support is a real
  direction" in docs/design-principles.adoc. Java-first: a real direction, not near-term work.

Performance
- Incremental compilation — a real unknown, and potentially in tension with the current in-process
  `javax.tools.JavaCompiler`-only decision, since that API doesn't give a source-level dependency
  graph for free the way Gradle's own incremental compiler (or Eclipse's ECJ, which IDEs use) does.
  Instrument first (extend `KWENZA_TIMING`) before deciding whether to build one — same
  evidence-first method as the no-daemon finding below.
- Watch mode (compile + test on file change) — a plausible differentiator versus the JS ecosystem's
  watch tooling, but needs *some* long-lived warm process to be fast enough to be worth it. If ever
  built, that's an explicit, opt-in, dev-loop-only exception to "no daemon," not a reversal of it
  for normal one-shot builds.

Validation strategy
- Retool real, complex, dependency-light Java projects with kwenza manifests as a stress test, in
  increasing order of difficulty: https://github.com/aeron-io/agrona[agrona] (no deps, likely
  single module) → https://github.com/aeron-io/simple-binary-encoding[SBE] (adds a real dependency
  plus codegen) → https://github.com/aeron-io/aeron[aeron] (adds multi-module, so this one is
  blocked on `[workspace]` landing first — doubling as its acceptance test). Vert.x/Spring are a
  much later, much larger stretch goal (custom Gradle plugins, heavy codegen) once the tool is
  further along.
- Mechanical note for if/when the above gets tried via git submodules: kwenza currently has no
  source-directory override (`src/main/java` is fixed, unlike the overridable `output-dir`), so a
  `kwenza.toml` outside a pristine upstream submodule checkout can't yet point back into that
  submodule's sources — either add that override, or accept tracking a fork/branch instead of the
  pristine upstream repo.

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
- Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/) — by
  convention, not tooling-enforced. A `commit-msg` hook was tried and dropped: git never tracks
  `.git/config`, so `core.hooksPath` can't ship "on by default" for every clone without either a
  Node/Python dependency (Husky, pre-commit) or `bootstrap.sh` reaching beyond its actual job of
  bootstrapping the build. Unenforced-for-some-contributors was judged worse than not enforced at
  all, so it's just a documented convention now.

## Verification

There's no automated test suite for kwenza itself yet. Until then:

- [`examples/hello-world`](examples/hello-world) — a small real project (one Maven Central
  dependency) used as a manual end-to-end smoke test for resolve/compile/package/run.
- [`examples/word-counter`](examples/word-counter) — a small library (no `main-class`) with real
  JUnit 5 and Mockito tests, used as the manual end-to-end smoke test for `kwenza test`
  specifically: dev-dependency resolution, `src/test/java` compilation, and that main/test
  classpath scoping actually holds (`kwenza package` on it must not embed JUnit/Mockito).
- [`benchmarks/`](benchmarks/README.adoc) (`java Bench.java`) — safe to re-run anytime
  (`cold`/`warm`/`both` modes, per scenario or all of them), uses fully isolated caches under each
  scenario's `.cache/`, never touches your real `~/.m2` or `~/.gradle`.
- Any change to the resolver, compiler, or packager should be manually verified against
  `examples/hello-world` (`kwenza clean && kwenza run`) before being considered done. Any change
  touching `kwenza test`, dev-dependency resolution, or main/test classpath scoping should
  additionally be verified against `examples/word-counter` (`kwenza clean && kwenza test`, and a
  `kwenza package` check that its jar's `Class-Path` doesn't include JUnit/Mockito).
