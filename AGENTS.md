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
[docs/contributing/design-principles.adoc](docs/contributing/design-principles.adoc). This section stays the place for the
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

- **`[package] name` is required, with no fallback to the directory's name.** It used to default
  to the containing directory's name, which meant the packaged jar's name came from wherever the
  project happened to be checked out (a renamed clone or a CI workspace path changed the artifact)
  and nothing in `kwenza.toml` showed it. That's the "Transparent: no magic" principle in
  docs/contributing/design-principles.adoc. The directory name is still a good *default*; it
  belongs in `kwenza new`/`kwenza init`, which can write it into the manifest where it's visible,
  not in the parser.

- **Self-hosting.** Once kwenza could compile+package itself, it switched immediately to building
  itself with itself (`kwenza.toml` at the repo root has no dependencies). `bootstrap.sh` exists
  *only* for the very first jar (or disaster recovery) — day-to-day rebuilds go through
  `bin/kwenza package`, not the bootstrap script. Don't reintroduce a permanent non-self-hosted
  build path.

- **`bootstrap.sh` writes to `.bootstrap/`, not `target/`.** It originally didn't - and that was a
  real inconsistency with the line above, not just an inconvenience: `kwenza clean` (correctly, by
  its own contract) deletes `target/` entirely, which meant the *disaster-recovery* jar was itself
  a casualty of the thing it's supposed to recover from, forcing a manual re-run of `bootstrap.sh`
  after every `kwenza clean` on this repo. `bin/kwenza` still prefers the self-hosted
  `target/kwenza-<version>.jar` and only falls back to `.bootstrap/kwenza-bootstrap.jar` when
  that's missing, so this changes nothing about normal usage - it just means the fallback actually
  survives the one command most likely to need it.

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

- **Checksums fail closed.** `MavenRepository.fetchJarAsync` refuses a downloaded jar whose `.sha1`
  is missing, unfetchable or empty, the same as a mismatch, and never writes an unverified jar to
  the local cache. It used to treat a failed `.sha1` fetch as "no checksum, skip it"
  (`.exceptionally(ex -> null)`), which let anyone able to block that one URL switch verification
  off. `Resolver.ensureFromLock` checks every jar against the lock's `sha256` before use, cache
  hits included, and a lock entry with no `sha256` is an error rather than a pass. A *fresh*
  resolution (no lock yet, a changed manifest, or `--update`) never pins a jar nobody has
  verified either: `Resolver.resolve` takes the lock it's replacing, checks any module resolved
  at the same version that lock pinned against the old `sha256` (no network), and sends every
  other jar through `MavenRepository.fetchJarVerifiedAsync`, which checks even a cache hit
  against the *remote* `.sha1`. Never the `.jar.sha1` cached beside the jar: anyone who can
  alter the jar can alter that too, so it would only catch accidental corruption. That costs
  one small request per newly-pinned cached jar, which is fine on a path that's going to the
  network anyway. `ensureMainResolved` passes the merged lock it has just verified, so its
  second resolution still makes no network calls. Expected `.sha1`s always come from the
  repository, never the local cache, for downloads too. There's
  deliberately no opt-out flag yet: Central publishes `.sha1` for every artifact, and a
  `--insecure`-style escape hatch should wait until a real repository without checksums (a
  private registry, say) actually needs one. The mismatch message tells the user to delete the
  cached jar rather than to run `--update`, because a changed jar means the cache is wrong, not the
  lock (released artifacts on Central are immutable). See "Secure by default" in
  docs/contributing/design-principles.adoc.

- **Annotation processors are opt-in, never discovered.** `JavaCompilation` always passes
  `-proc:none`, for main and test compiles alike. Leaving it to javac's default wasn't a real
  option, because that default isn't one behaviour: JDK 21 discovers and runs any processor on
  the classpath (with a warning that this will change), JDK 23+ runs none unless told to. With
  kwenza supporting 21 and newer, the same manifest and lock would have produced different class
  files depending on which JDK happened to be installed. It's also a supply-chain decision, not
  just a consistency one: compilation is in-process, so a processor in any *transitive*
  dependency would run inside kwenza's own JVM with nobody having declared it. Decided before 0.1
  on purpose, since turning discovery off after release would silently change people's builds.
  This matches where the JDK itself went (23+), and Gradle (separate `annotationProcessor`
  configuration since 5.0) and Maven (`annotationProcessorPaths`). `JavaCompilationTest` covers it
  with a real `ServiceLoader`-registered processor on the classpath; that test only goes red on
  JDK 21, which is why CI's JDK matrix keeps 21 in it. Running processors is still wanted (Lombok,
  MapStruct, Dagger, Immutables), and is the leading extensibility route — see the explicit
  `[annotation-processors]` item under Future ideas / Extensibility. Adding it is additive, so it
  can land whenever someone needs it.

- **The name.** "Kwenza" is isiXhosa for "to make" — chosen over several alternatives (including
  "Kiln," dropped after discovering real naming collisions with an existing Java-runtime project and
  a prominent AI-tooling brand of the same name) specifically because it's *not* Java-specific,
  leaving room for kwenza to grow support for other JVM languages later without a rename.

- **`kwenza test` forks a JVM to run tests, the same as `run` does for the user's app** — it does
  *not* call the JUnit Platform's launcher API in-process. Two reasons: it keeps kwenza's own
  process free of any JUnit/Mockito classes (nothing to compile against, nothing to reflect
  against, consistent with "zero external dependencies for kwenza itself" above), and it isolates
  kwenza itself from a test calling `System.exit()` — the same reason Maven Surefire and Gradle's
  test workers both fork rather than running in the build's own JVM.
- **If `org.mockito:mockito-core` resolves, `kwenza test` loads it as an explicit `-javaagent` on
  the forked test JVM**, rather than letting Mockito's inline mock maker self-attach after the JVM
  has already started. Self-attaching already warns on JDK21+ and Mockito's own docs (linked from
  that warning) say it stops working entirely in a future JDK — explicit loading is the documented
  fix, and mockito-core's own jar is already a valid agent jar (its manifest declares
  `Premain-Class`), so no extra artifact or configuration is needed from the user. This is a real
  fix for any kwenza user with Mockito as a dev-dependency, not just kwenza's own dogfooding.
  Loading any agent this way makes the JVM print an unrelated, harmless
  `Sharing is only supported for boot loader classes because bootstrap classpath has been
  appended` notice - that's Class Data Sharing noting its startup-class archive doesn't cover
  whatever the agent appended to the boot classpath, universal to any Java agent that does this,
  not fixable without `-Xshare:off` (which would trade away CDS for the *entire* test JVM just to
  silence one line) - not worth chasing.
  Forking doesn't mean giving up parallelism inside a single test run: JUnit Platform's own
  `junit.jupiter.execution.parallel.*` configuration (auto-discovered from a
  `junit-platform.properties` file, which `kwenza test` copies from `src/test/resources` alongside
  the compiled test classes, same as `build` already does for `src/main/resources`) works today
  with zero kwenza-specific flags — see `examples/junit-word-count` for a working example. Running
  disjoint *shards* of a suite across several forked JVMs at once is a different, larger feature,
  deliberately deferred (see below).

- **`kwenza test` checks for compiled test classes and a `ConsoleLauncher`-providing
  dev-dependency itself, before ever spawning a subprocess** (`TestCommand.hasAnyCompiledTestClasses`/
  `hasConsoleLauncher`) — found the hard way: `examples/hello-world` has no test dependencies at
  all, so its classpath has nothing able to load `ConsoleLauncher`, and running `kwenza test`
  there surfaced a raw `Error: Could not find or load main class
  org.junit.platform.console.ConsoleLauncher` instead of the intended "nothing to do" no-op. The
  two checks are deliberately different outcomes, not the same one: zero compiled test classes is
  "nothing to do" (`test: no tests found`, exit 0, matching `build`'s empty-`src/main/java`
  behavior) regardless of whether a launcher is even resolvable; real test classes with no
  launcher dev-dependency is a genuine misconfiguration and fails fast with a specific message
  naming the fix, per "fail fast, with a specific and actionable message" in
  docs/contributing/design-principles.adoc — silently saying "no tests found" there would hide a real mistake
  instead of catching it.

- **No test-framework plugin system.** `kwenza test` always drives
  `org.junit.platform.console.ConsoleLauncher`, full stop — there's no kwenza-side mechanism for
  swapping in a different test runner. This isn't a gap: the JUnit Platform itself already
  discovers whichever test *engine* (Jupiter, Vintage, or a third-party one) is on the classpath via
  `ServiceLoader`, the same mechanism Maven's Surefire (provider auto-detection) and Gradle
  (`useJUnitPlatform()`) both lean on. Supporting another JVM test framework is a matter of it
  shipping a JUnit Platform engine and the user declaring it as a dev-dependency — never a reason
  to build a plugin system of kwenza's own, consistent with the manifest staying data, not a
  program. Confirmed for real with a genuinely third-party, separately-declared engine, not just
  the Jupiter+Vintage pair bundled inside `junit-platform-console-standalone`: see
  `examples/testng-calculator` — TestNG via `org.testng:testng` +
  `org.junit.support:testng-engine` (the JUnit-team-maintained bridge, not TestNG's own bundled
  runner) needs zero kwenza changes and zero extra CLI flags, `--scan-classpath` alone finds it.
  Two real caveats worth knowing if this ever gets extended: `testng.xml` suite files aren't
  supported by that engine at all (plain `@Test`-annotated classes only), and TestNG `groups`
  filtered as JUnit tags can skip `@BeforeGroups`/`@AfterGroups` on some TestNG versions — use the
  engine's `testng.groups`/`testng.excludedGroups` config params instead of tag filters if that
  ever matters. `org.testng:testng` itself depends on `slf4j-api` for its own internal logging but
  ships no binding — without one, SLF4J prints a harmless
  `Failed to load class "org.slf4j.impl.StaticLoggerBinder"` warning and silently falls back to a
  no-op logger anyway; the example adds `org.slf4j:slf4j-nop` (pinned to the same `1.7.36` that
  `testng` itself resolves, to keep version mediation a no-op) as a fourth dev-dependency to make
  that explicit and silence the warning — a real, legitimate optional dependency of TestNG's own
  logging setup, not a kwenza-specific workaround.

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

- **`docs/` is an Antora component; the site that publishes it lives in a separate repo.**
  The manual sits in `docs/modules/ROOT/pages/` with `docs/antora.yml` (unversioned,
  `version: ~`) so it stays versioned with the code it describes, while the playbook, UI overlay,
  home page and Pages workflow live in
  [kwenza-build/kwenza-dev](https://github.com/kwenza-build/kwenza-dev), so Node never enters
  this repo (see "Zero external dependencies" above). Page-to-page links are `xref:`, which both
  Antora and GitHub's renderer resolve; links out of `docs/` (AGENTS.md, `examples/`,
  `benchmarks/`) are absolute GitHub URLs, because Antora only sees `docs/`. `docs/research/` and
  `docs/contributing/` are outside `modules/`, so they're never published. Architecture and
  design principles exist twice on purpose: short, user-facing pages on the site, and the long
  versions with the reasoning, history and open questions in `docs/contributing/`, which is what
  this file's references point at. A change to a principle or to how kwenza works updates both. The home page's example manifest and terminal
  session are symlinks under `docs/modules/ROOT/examples/` into `examples/hello-world`, same
  no-drift reasoning as the benchmark symlinks above; `kwenza-run.txt` there is a real
  `kwenza run` transcript that `smoke-examples.sh` diffs against kwenza's actual output on every
  CI run. The design research behind all of this is
  [docs/research/kwenza-dev-design.adoc](docs/research/kwenza-dev-design.adoc).

- **`kwenza check` (a compile-only pass into a scratch directory, no artifact) was added, then
  removed.** It was modeled on `cargo check`, but the reason `cargo check` earns its keep doesn't
  carry over: it exists because `rustc` codegen is slow enough that skipping it is a real, distinct
  speedup over `cargo build`. `javac` doesn't have that gap — kwenza's own compile step is already
  fast (see "Fast is not a trade-off to apologize for" in docs/contributing/design-principles.adoc) — so a
  separate check-only command was solving a problem kwenza doesn't have, just because another
  tool's ecosystem has a command with that name. The `check` name is free again for something that
  would actually earn it — Checkstyle-style verification is the leading candidate, see "Future
  ideas" below — but that would be a different feature, not a resurrection of this one.

- **`Main.java` split into `BuildState` + one class per CLI command** (`BuildCommand`,
  `ResolveCommand`, `PackageCommand`, `RunCommand`, `TestCommand`, `CleanCommand`). It used to do
  everything itself — arg parsing, dispatch, the resolve/compile memoization state, and every
  command's full logic — as ~300 lines of private static methods, which isn't testable at a sane
  seam. `BuildState` is the deep module here: a small interface
  (`ensureResolved`/`ensureMainResolved`/`ensureCompiled`/`ensureTestCompiled`/`invalidate`) hiding
  all the resolve/compile/memoization/lock-freshness logic, constructed once per invocation and
  shared across whichever commands are chained. Each `*Command` is a thin, independently testable
  `run(BuildState, ...)`. `RunCommand`/`TestCommand` throw a
  `CommandFailedException` (carrying just an exit code) instead of calling `System.exit` directly
  when their spawned subprocess fails, since the subprocess has already printed whatever it needed
  to via `inheritIO()` — `Main` propagates that code silently from one place, rather than every
  command deciding on its own to terminate the process. `Main` itself is now just: parse args,
  build one `BuildState`, dispatch each command in the chain to its class.

## Roadmap

[ROADMAP.adoc](ROADMAP.adoc) is the ordered work list (what to tackle next, ticked off as it
lands). The sections below hold the *reasoning* for those items; the roadmap holds only the
*order*. When you finish a roadmap item, tick it off in the same PR.

## Explicitly deferred (Phase 2 — not oversights, don't "helpfully" half-implement these)

- `kwenza tree` (dependency tree display)
- `--fat` uber-jar packaging
- `[workspace]` multi-module support
- `[scripts]` task runner
- `--offline` mode. Decided already, so it isn't redesigned from scratch: offline means "verified
  from the lock, or refused". A lock-backed build already makes no network calls (jars are
  checked against the lock's `sha256` locally), and `--update` can still carry the old lock's
  pins forward for unchanged versions. But a cached jar the lock doesn't pin can only be
  verified against the remote `.sha1` (see "Checksums fail closed" above), so offline it's an
  error naming the jar, not a fallback. Don't fall back to the `.jar.sha1` cached beside it
  (that only catches corruption, not tampering), and don't trust the cache the way Maven's
  offline mode does (that reopens the gap the checksum work closed). Today, without the flag, a
  fresh resolve with no network fails the same way, just with a raw connection error; the
  `--offline` work should turn that into a clear "can't verify X offline" message.
- `kwenza test` parallelism (`--test-threads` / multi-process sharding across several forked
  JVMs, the equivalent of Gradle's `maxParallelForks` or Surefire's `forkCount`) — see the
  "`kwenza test` forks a JVM..." decision above for what's already possible today without it.
  Same fork in the road as the "own test console output" item under "Future ideas": both need
  kwenza to stop delegating to `ConsoleLauncher`'s CLI and own a Launcher-API runner, so scope them
  together rather than as two separate line items.

## Also on the radar (surfaced later, not yet triaged against the above)

- **CI-compatible test reports via `--reports-dir`.** `kwenza test` forks `ConsoleLauncher` already;
  adding `--reports-dir=<dir>` to that invocation makes `ConsoleTestExecutor` register a
  `LegacyXmlReportGeneratingListener`, which writes one `TEST-<class>.xml` per test class in the
  legacy Ant-JUnit schema that Jenkins/GitHub Actions/GitLab already parse (same lineage as
  Surefire's and Gradle's XML). Flag-only: no Launcher API, no reflection, no new dependency, so it
  doesn't touch "zero external dependencies" or "no test-framework plugin system" above. One more
  flag, `--config=junit.platform.reporting.open.xml.enabled=true`, also writes JUnit's Open Test
  Reporting XML into the same directory, but OTR's schema is still pre-1.0 and no mainstream CI
  parses it natively, so legacy XML is the interop-relevant one. Open choices: output directory
  (under `[package] output-dir`, e.g. `target/test-results`) and whether it's on by default.
  HTML reports are *not* covered by this: every ecosystem surveyed treats HTML as a downstream tool
  (Maven's `maven-surefire-report-plugin`; Gradle only because it owns its listener). Sourcing in
  [docs/research/junit-launching-and-reporting.adoc](docs/research/junit-launching-and-reporting.adoc).
- **`--project <path>`** — every command currently only operates on the current working directory
  (see `docs/modules/ROOT/pages/cli-reference.adoc`'s opening line). Small and standalone; a plausible easy first PR.
- **AppCDS for kwenza's own cold-start — tried, works, real but size-dependent win.** A
  `-Xlog:class+load` trace confirmed the "no daemon" hypothesis below directly: a bare `java
  -version` loads 383 classes (all covered by the JDK's default CDS archive); a `kwenza package`
  on `examples/hello-world` loads 4029, of which 2751 aren't covered by that default archive —
  1164 alone are `com.sun.tools` (javac internals). A second, previously-unconfirmed hypothesis
  also checked out: `BuildState` constructs `MavenRepository.central(...)` (and with it a
  `java.net.http.HttpClient`) unconditionally, even on the from-lock path where nothing gets
  fetched — that pulls in ~250 `sun.security.ssl`/`javax.net.ssl`/`javax.crypto` classes every
  invocation for no reason. Small, separate, still-open fix; not done as part of this.

  Mechanism: `-XX:+AutoCreateSharedArchive -XX:SharedArchiveFile=<path>` (available since JDK 19,
  so covered by kwenza's own JDK 21 floor — `bootstrap.sh` compiles kwenza itself with `--release
  21`). One flag pair, no separate dump/train step, no classlist to maintain — genuinely the
  simplest of the mechanisms considered. First run creates the archive at roughly baseline cost;
  every run after reuses it. **Gotcha found the hard way: don't pre-seed the file with
  `-XX:ArchiveClassesAtExit` and then hand it to `AutoCreateSharedArchive` — on this JDK (Zulu
  25) that combination reports `shared class paths mismatch` on *every* subsequent run and never
  recovers, even though the jar's mtime never changed. Let `AutoCreateSharedArchive` own the
  archive's entire lifecycle from a nonexistent file; it then self-heals correctly across real
  jar-content changes (confirmed by rebuilding kwenza's own jar mid-experiment: the next run
  detects the stale archive, silently regenerates it at exit with a JVM-level stderr warning,
  and every run after that is clean and fast again).** One archive works for every project kwenza
  builds — verified against both `examples/hello-world` and `examples/word-counter` — because
  kwenza's own JVM classpath is always just the single `-jar` entry regardless of which project
  it's building; only the spawned subprocess for `run`/`test` sees the project's own dependency
  jars, on a separate JVM this doesn't touch. Also confirmed safe on a real cold run (deleted
  `kwenza.lock`, forced an actual Maven Central fetch over TLS under the archive) and against
  kwenza's own real self-test suite (`package test`, all 75 tests) — no correctness regressions
  found anywhere.

  Speed, measured via `KWENZA_TIMING` (N=10-15 per config, `examples/hello-world`, warm/from-lock
  `package`): total time drops from ~420ms to ~285ms, a consistent ~32% reduction — almost
  entirely from the `compile` phase (~415ms → ~195ms, since `com.sun.tools` is the single biggest
  non-shared chunk) plus a smaller cut to the resolve phase (from the `HttpClient`/TLS classes
  above). Wall-clock (`/usr/bin/time`, N=8, includes JVM process launch overhead `KWENZA_TIMING`
  doesn't see) drops from a ~0.77s median to a stable ~0.65s, bringing kwenza close to
  `benchmarks/hello-world`'s own Gradle-warm-daemon number (~0.60-0.61s) instead of trailing it by
  25-30% — i.e. this measurably closes most of the actual gap this item exists to close.

  **But the win shrinks with project size, and this matters:** the same test against kwenza's own
  self-build (`package test`, 25 main + 15 test source files, real compile work rather than one
  trivial class) showed *no measurable difference* (~530ms compile phase either way). CDS speeds
  up one-time class *loading*, a fixed cost independent of how much actual javac work follows; for
  a trivial project that fixed cost dominates, for a real one it doesn't. Net: worth having for
  the tiny-project cold-start case this whole trade-off is about (exactly where Gradle's warm
  daemon currently wins), not something that also speeds up kwenza's own larger builds.

  Where the flag would live depends on what actually launches kwenza. A self-re-exec inside
  `Main` (detect via `RuntimeMXBean.getInputArguments()` that the flag is missing, `ProcessBuilder`
  itself into a second JVM that has it) was measured and works, but costs a real extra JVM boot
  (~50ms wall-clock on this project, keeping about two-thirds of the raw win rather than all of
  it) purely to avoid putting an optimization flag somewhere other than `Main.java`. Once kwenza
  has a real installed-distribution story (see "Package-manager distribution" under Future ideas
  / Delivery below) the better answer is simpler: the installed artifact is a jar plus a launch
  script either way (the same `bin/kwenza` shape, just generated at package time instead of
  hand-written), so the flag belongs in *that* script, no `Main.java` involvement or extra JVM hop
  needed - it's install-environment tuning, not application behavior, same category as any other
  JVM flag a packaged launcher already sets. The re-exec idea only earns its keep if kwenza is
  ever invoked some other way that skips a controlled launch script entirely.

  **Decision: not adopted for now.** The win is real but shrinks to nothing as project size grows
  (measured directly above, on kwenza's own self-build vs `hello-world`), and kwenza's own
  codebase is still small - not the size regime this would need to prove itself in. Revisit this
  measurement as kwenza's own source tree (and ideally a real user project) grows large enough
  that the class-loading-only fix might matter again, rather than assuming it stays negligible
  forever. See the "no daemon" reconsideration under Future ideas / Performance below for the
  more likely direction if cold-start ever becomes a real complaint before then.
- **Thin jars aren't portable yet.** `JarPackager` writes each dependency's absolute `file:` URI
  in the local cache into `Class-Path` (`file:///Users/<you>/.m2/repository/...`), so a packaged
  jar only runs where it was built, which undercuts "matches how a lot of real shops actually
  deploy" under the thin-jar decision above. The usual fix, and the one to adopt: copy the
  resolved jars into `<output-dir>/lib/` and write relative entries (`lib/guava-33.2.1-jre.jar`),
  which `Class-Path` resolves against the jar's own location. That's what Maven setups do with
  `maven-dependency-plugin` plus the jar plugin's `classpathPrefix`, and what Gradle's
  `application` distribution lays out. The output directory then ships as is (zip it, or `COPY`
  it into an image, which also fits the OCI idea under Future ideas), with none of a fat jar's
  shading problems. Details to settle: file names can collide across groups (two artifacts both
  called `core-1.0.jar`), so detect that and fall back to a group-qualified name rather than
  silently overwrite; stale jars from an earlier dependency set must not linger in `lib/` (clear
  it on each package); copying is cheap at this size, but hard links are an option if it ever
  isn't; and `run` should keep using the cache directly, since it never needs a portable layout.
- **Console output: aligned status labels and meaningful colour.** Today `ConsoleReporter` prints
  `step: detail` lines (`resolve: ...`, `compile: ...`, `run: ...`) in one colour per level
  (cyan info, red error, grey detail), and a `run`/`test` subprocess's own output follows on the
  very next line. Cargo's model is worth copying: the step label is a verb right-aligned in a
  fixed-width column, so every line's detail starts at the same column and a long build scans
  as a list, and the label is the coloured, bold part rather than the whole line. Open choices:
  keep the colon (`   compile: target/classes`) or drop it as Cargo does; which colours mean
  what (one for step labels, red for errors, dim for detail, perhaps one more for the final
  "finished" line); whether to print a closing summary line with timing. Two constraints carry
  over: colour stays off when stderr isn't a terminal or `NO_COLOR` is set (as now), and a blank
  line should separate kwenza's own output from whatever `run`/`test` launch, so the program's
  output never reads as kwenza's. This changes `examples/hello-world/kwenza-run.txt`, which
  kwenza.dev's home page shows and `smoke-examples.sh` checks, so re-capture it in the same
  change. The home page could then show colour too, if the transcript keeps it in a form the
  site can render.

## Future ideas (unprioritized, not yet scoped)

Bigger and vaguer than anything above — these haven't been triaged, sized, or committed to. Don't
treat absence from "Explicitly deferred" as "not planned," and don't treat presence here as "will
definitely happen." See [docs/contributing/design-principles.adoc](docs/contributing/design-principles.adoc) for the
principles these should be weighed against once any of them gets scoped for real.

Delivery & distribution
- First-class Docker/OCI image output as a `[package]` target, alongside thin/fat jar — containers,
  not zips or tarballs, are the primary way most people actually ship applications now.
- Publishing to Maven-compatible registries (Central, private/custom registries), including auth —
  the publish-side mirror of the existing consume-side `KWENZA_REPO` override.
- **Real cross-platform distribution for kwenza itself — the standard menu most dev tools offer:**
  Homebrew (macOS/Linux), a `curl | sh` one-liner installer (the rustup/deno/uv pattern), and a
  genuine Windows path (winget/Scoop/Chocolatey, or at minimum a native Windows launcher) — not
  just one of these. Currently untackled entirely — `docs/modules/ROOT/pages/getting-started.adoc`'s only install path
  is `git clone` + `bootstrap.sh`, a source install, and `bin/kwenza` is a bash script with no
  Windows equivalent at all today, which is the more fundamental gap underneath this: every
  distribution channel needs *some* generated launch script, and right now there's only one
  platform's shape of it. A packaged install would still be jar + a launch script per platform
  (same shape as today's `bin/kwenza`, just generated per channel instead of hand-written) rather
  than a bundled JVM, consistent with kwenza not vendoring a toolchain of its own - but *which* JDK
  each generated script points at is a real, undecided question: trust the user's own
  `JAVA_HOME`/`PATH` (matches "lean on version managers" below, no channel-specific JDK opinion)
  versus a package manager pinning its own JDK dependency and baking an absolute path into the
  script (more robust against multiple installed JDKs, more magic) - and Windows has its own
  version of this question (`JAVA_HOME` vs whatever's on `PATH` vs the registry). `bin/kwenza`
  itself doesn't reference `JAVA_HOME` today, only bare `java` off `PATH` - whatever gets decided
  here should also settle whether that changes for the dev-loop script, not just packaged installs.
  See also the version-manager (asdf/mise) item under Developer experience below - a version
  manager plugin is the same underlying idea, just shaped to that tool's own plugin API.
- IDE support: IntelliJ IDEA and Eclipse integration, flagged as important to land relatively
  early rather than as a late nice-to-have.
- `kwenza new` — scaffold a new project quickly.
- A `kwenza.dev` website (domain already owned) — simple to start.
- Lean on version managers (asdf, mise, sdkman) for "which kwenza/JDK for this project" instead of
  a per-project wrapper script/jar the way Gradle and Maven each ship one — the cargo/rustup and
  dotnet model of feeling bundled with the platform rather than vendored per-repo. Concretely, this
  isn't automatic — asdf and mise each need someone to actually author and publish a plugin (an
  `asdf-kwenza` plugin repo following asdf's plugin API; a mise registry entry/backend) before
  either tool knows how to install or version-switch kwenza at all. Same underlying gap as the
  cross-platform distribution item above (jar + a launch script, generated per channel) - a plugin
  is just the asdf/mise-shaped version of that same launch script.
- Semver-and-dependency-resolution semantics that feel intuitive to developers already fluent in
  Maven/Gradle's model, including SNAPSHOT-style pre-release handling.
- **Open question: minimum supported Java version.** Some future features imply a floor — JPMS
  support (below) doesn't make sense below Java 9, for instance — but wide adoption, including by
  older projects, pulls toward supporting Java 8 or earlier. Genuinely undecided, and the two pulls
  are in real tension rather than easily split; needs a deliberate choice, not a default.
- **Maybe: an auto-migration tool from Maven/Gradle projects.** Attractive if it works, but real
  Maven/Gradle builds lean heavily on plugin-specific and DSL-scripted customization that has no
  clean data-shaped equivalent in kwenza (see "the manifest is data" in
  docs/contributing/design-principles.adoc) — unclear whether the achievable subset (straightforward POMs/build
  scripts) is worth the effort relative to how much real-world usage actually stays that simple.
  Parked as an idea, not a commitment.

Extensibility
- A `build-dependencies`-style mechanism (Cargo-inspired) for custom build-time logic — explicitly
  *not* Cargo's `build.rs` arbitrary-scripting model. See "Extensibility through JVM discovery" in
  docs/contributing/design-principles.adoc for the leading shape (annotation processors,
  `ServiceLoader`-discovered participants resolved as ordinary dependencies).
- **Explicitly declared annotation processors** (the opt-in to the "annotation processors are
  opt-in, never discovered" decision above). Leading shape: an `[annotation-processors]` manifest
  section using the same `module`/`version`/`version.ref` notation as `[dependencies]`, resolved,
  locked and checksum-verified exactly like them, then handed to javac as its own processor path
  (`--processor-path` plus `-proc:full`, replacing `-proc:none` only when the section is
  non-empty). Never added to the compile classpath or a packaged jar's `Class-Path`, the same
  scoping rule dev-dependencies already follow. Open questions: whether test compiles get their
  own processor set (Gradle's `testAnnotationProcessor`) or share one; how javac's `-A` processor
  options are expressed as data; and that processors still run in-process, inside kwenza's own
  JVM, so declaring one is an explicit trust decision the docs should say out loud. Reserve the
  section name in the CLI/manifest review so it fits alongside `[workspace]` and `[scripts]`.
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
  direction" in docs/contributing/design-principles.adoc. Java-first: a real direction, not near-term work.

Testing & reporting
- **Own test console output levels and format, instead of `ConsoleLauncher`'s presets.**
  `ConsoleLauncher`'s `--details`/`--details-theme`/`--color-palette` are presets, not an extension
  point; Gradle can format its own output because it registers its own `TestExecutionListener` on
  `LauncherFactory.create()`. Gradle's default is deliberately near-silent (failures only), and
  people routinely have to raise `testLogging` by hand; `ConsoleLauncher`'s default (`tree`) is the
  noisier opposite. Current `ConsoleLauncher` output is judged good enough for now. If this is ever
  built: kwenza's own code can't compile against `org.junit.platform.launcher.*`, but a small
  runner main class in the forked JVM can implement `TestExecutionListener` through
  `java.lang.reflect.Proxy` (needs only the interface's `Class`, found at runtime), at the cost of
  verbose reflective discovery-request building; the alternative is a separately built helper jar
  with a real JUnit dependency, simpler code but a new artifact to build, version and distribute.
  Reflection's per-call overhead is nanoseconds against a few thousand listener callbacks, dwarfed
  by the JVM fork itself, so it shouldn't drive the choice. Whatever shape it takes, keep an
  always-emitted final summary line independent of how verbose the live stream is.
- HTML test reports, same gate as above: a kwenza-owned listener plus renderer (Gradle's model), or
  shelling out to Open Test Reporting's separate CLI converter (a new runtime dependency).

Performance
- Prefer return codes over exceptions for any new hot-path logic (a per-test listener callback,
  resolver loops over many versions). Exceptions cost mostly in `fillInStackTrace()`, which only
  matters when they fire per iteration; kwenza's current exceptions (`CliException`,
  `CommandFailedException`, `IncompatibleJdkException`) fire at most once per invocation, so there's
  nothing to change today. A direction to weigh when scoping hot paths, not a refactor of existing
  code.
- Incremental compilation — a real unknown, and potentially in tension with the current in-process
  `javax.tools.JavaCompiler`-only decision, since that API doesn't give a source-level dependency
  graph for free the way Gradle's own incremental compiler (or Eclipse's ECJ, which IDEs use) does.
  Instrument first (extend `KWENZA_TIMING`) before deciding whether to build one — same
  evidence-first method as the no-daemon finding below.
- Watch mode (compile + test on file change) — a plausible differentiator versus the JS ecosystem's
  watch tooling, but needs *some* long-lived warm process to be fast enough to be worth it. If ever
  built, that's an explicit, opt-in, dev-loop-only exception to "no daemon," not a reversal of it
  for normal one-shot builds.
- **Reconsider "no daemon" itself, not just as the watch-mode carve-out above — raised directly by
  the AppCDS finding above.** CDS only removes fixed one-time class-loading cost, which is why its
  win shrinks toward zero as a project's real compile work grows; a genuinely warm process that
  dispatches build/compile/test work itself (or launches the user's `run`/`test` JVM from an
  already-booted one) sidesteps the cold-boot tax regardless of project size instead - the same
  reason Gradle's daemon keeps beating kwenza on tiny projects specifically. Real tension with
  "no daemon" as a blanket policy (this is bigger than the opt-in dev-loop exception above) and
  with "transparent, no magic" (a background process is a new thing to discover, reason about, and
  kill) - needs a deliberate decision, not a default. Worth a concrete spike if cold-start on a
  real project ever becomes an actual complaint, likely instead of chasing AppCDS further rather
  than alongside it.

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

- Package structure: `dev.kwenza.{cli,commands,manifest,toml,resolver,compiler,packager}`, one
  clear responsibility per package. `cli` is deliberately thin - argv/flag parsing, dispatch,
  top-level exception-to-exit-code translation, and `ConsoleReporter` (`Main.java`, no tests of
  its own - there's nothing left in it worth testing at its own seam). `commands` is where that
  dispatches to: `BuildState` (the deep module - resolve/compile/memoization behind a small
  interface) and one class per CLI command (`BuildCommand`, `TestCommand`, `PackageCommand`,
  `RunCommand`, `CleanCommand`, `ResolveCommand`), plus the `Reporter` interface, `Timing`, and
  `CliException`/`CommandFailedException` it and `cli` both need. Dependency direction is one-way,
  `cli` → `commands` → {`resolver`,`compiler`,`packager`,`manifest`}: `commands` never depends
  back on `cli`, deliberately, so it stays testable and reusable independent of argv parsing.

- **`Status` (static, printed directly to stderr) became `Reporter` (an interface) +
  `ConsoleReporter` (the real, console-specific implementation).** `BuildState` and the
  `*Command` classes had genuinely CLI-specific code baked in - not argv parsing, but ANSI colors
  and `System.console()` terminal detection, real console I/O all the same. `Reporter` (in
  `commands`, next to what depends on it) is the seam; `ConsoleReporter` (in `cli`, where the
  concrete "is this a real terminal" decision actually belongs) is the one production adapter,
  constructed once in `Main` and threaded through `BuildState`'s constructor to every command.
  Tests inject `Mockito.mock(Reporter.class)` instead - void methods do nothing by default, so
  it's silent without needing to redirect `System.err` (the `QuietConsole` JUnit extension this
  replaced), and any test that cares what got reported can `verify(...)` on it instead of
  string-matching captured output. `CliException`/`CommandFailedException` didn't need to move
  anywhere for this - they were already plain data (a message, an exit code) with no console
  coupling of their own; they stay in `commands` simply because that's where they're thrown from,
  which `cli.Main` (the catcher) imports from - same one-way dependency as everything else here.
  `Timing` still writes to stderr directly and hasn't had the same treatment - it's a no-op unless
  `KWENZA_TIMING` is set, so it never caused the test-noise problem this was solving; worth the
  same treatment later if that changes, not urgent now.
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
- **Every PR brings the project's records up to date in the same PR.** A user-facing change (a
  new or changed command, flag, manifest field, behaviour, or a fix a user would notice) adds an
  entry under `## [Unreleased]` in [CHANGELOG.md](CHANGELOG.md), using Keep a Changelog's
  categories (Added, Changed, Deprecated, Removed, Fixed, Security). A PR that finishes a
  [ROADMAP.adoc](ROADMAP.adoc) item ticks it (`[x]`). Docs-only, CI and internal-refactor PRs need
  no changelog entry. The checklist in `.github/pull_request_template.md` asks for both, so a
  reviewer catches a miss.
- New work follows TDD: agree the seam under test before writing anything (don't test unconfirmed
  internals), red before green, one seam/one test/one minimal change per cycle - not writing a pile
  of tests before any of the matching implementation exists. Mock only real external-API
  boundaries (`MavenRepository` for the network, `Reporter` for console output - never kwenza's
  own domain collaborators). See `src/test/java` for the shape this takes in practice, one test
  class per module mirroring the main package layout.
- Tests that call `BuildState`/the `*Command` classes directly and in-process pass
  `Mockito.mock(Reporter.class)` (via `CommandTestSupport.newState`) rather than a real
  `ConsoleReporter` - a mock's void methods do nothing by default, so status lines
  (`reporter.info/error/detail(...)`) never print into the outer `kwenza test` run's own console,
  indistinguishable from real build activity happening at the top level otherwise.
  `RunCommand`/`TestCommand` separately accept an explicit `ProcessBuilder.Redirect` (defaulting
  to `INHERIT` for real CLI use; tests pass `DISCARD`) for the same reason, one level down at the
  real subprocess they spawn - muting `Reporter` doesn't touch that subprocess's own console
  report, a different stream entirely.
- `@Tag("integration")` marks the only two test classes that spawn a real OS subprocess
  (`RunCommandTest`, `TestCommandTest`) - genuinely slower and more moving parts than everything
  else, which stays in-process (real `javac`/zip I/O, or `MavenRepository` mocked) even where it
  isn't mocked. `kwenza test` doesn't filter by tag yet - it's metadata for now, not wired to a
  flag, since nobody's hit a real need to run just one subset.

## Verification

kwenza tests itself: `bin/kwenza test` runs a real JUnit 5 suite under `src/test/java`, mirroring
the main package layout one test class per module (`toml/TomlTest`, `resolver/ResolverTest`,
`commands/BuildStateTest`, and so on). Run it the same way after any change: `bin/kwenza package` (to
pick up new/changed test sources into the self-hosted jar) then `bin/kwenza test`. CI
(`.github/workflows/ci.yml`) runs that suite plus the three example checks below
(`.github/scripts/smoke-examples.sh`) from a clean bootstrap on every push and PR. If you add or
change one of those checks, keep the script in sync with this list. Beyond that:

- [`examples/hello-world`](examples/hello-world) — a small real project (one Maven Central
  dependency) used as a manual end-to-end smoke test for resolve/compile/package/run. CI also
  checks that `kwenza run`'s output still matches its committed `kwenza-run.txt`, which
  kwenza.dev's home page shows; re-capture it (`kwenza clean && kwenza run > kwenza-run.txt
  2>&1`) whenever that output deliberately changes.
- [`examples/junit-word-count`](examples/junit-word-count) — a small library (no `main-class`) with real
  JUnit 5 and Mockito tests, used as the manual end-to-end smoke test for `kwenza test`
  specifically: dev-dependency resolution, `src/test/java` compilation, and that main/test
  classpath scoping actually holds (`kwenza package` on it must not embed JUnit/Mockito).
- [`examples/testng-calculator`](examples/testng-calculator) — a small library with a plain TestNG test class
  (no `testng.xml`, no groups), the concrete proof behind the "no test-framework plugin system"
  decision above: `kwenza test --verbose` on it must show a `TestNG` line in the engine tree.
- [`benchmarks/`](benchmarks/README.adoc) (`java Bench.java`) — safe to re-run anytime
  (`cold`/`warm`/`both` modes, per scenario or all of them), uses fully isolated caches under each
  scenario's `.cache/`, never touches your real `~/.m2` or `~/.gradle`.
- Any change to the resolver, compiler, or packager should be manually verified against
  `examples/hello-world` (`kwenza clean && kwenza run`) before being considered done. Any change
  touching `kwenza test`, dev-dependency resolution, or main/test classpath scoping should
  additionally be verified against `examples/junit-word-count` (`kwenza clean && kwenza test`, and a
  `kwenza package` check that its jar's `Class-Path` doesn't include JUnit/Mockito).
