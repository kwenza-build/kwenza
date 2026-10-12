package dev.kwenza.resolver;

import dev.kwenza.context.Failure;
import dev.kwenza.context.Result;
import dev.kwenza.manifest.Dependency;
import dev.kwenza.manifest.KwenzaLock;
import dev.kwenza.manifest.KwenzaManifest;
import dev.kwenza.resolver.ArtifactStore.Expectation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves a project's dependencies against a Maven repository: from kwenza.lock when it still
 * matches the manifest, otherwise transitively, with Maven-compatible nearest-wins mediation
 * (honoring dependencyManagement / BOM imports and parent POM inheritance), fetching each level's
 * POMs concurrently and then the winning jars. Every jar comes through {@link ArtifactStore}, so
 * every one is verified. Expected failures are collected, not just the first: one run reports
 * every jar that doesn't match.
 */
public final class Resolver {

    private static final Set<String> TRANSITIVE_SCOPES = Set.of("compile", "runtime");
    private static final Pattern PROPERTY_REF = Pattern.compile("\\$\\{([^}]+)}");

    private final ArtifactStore store;

    public Resolver(ArtifactStore store) {
        this.store = store;
    }

    public record ResolvedArtifact(String moduleId, String group, String artifact, String version,
                                    Path jarPath, Set<String> requestedBy) {
    }

    /**
     * @param artifacts every resolved artifact, each one's jar verified
     * @param lock      the complete lock describing them, manifest hash included
     * @param fromLock  whether this came from an existing kwenza.lock (nothing to write back)
     */
    public record Resolution(List<ResolvedArtifact> artifacts, KwenzaLock lock, boolean fromLock) {
        public List<Path> classpath() {
            List<Path> paths = new ArrayList<>();
            for (ResolvedArtifact a : artifacts) paths.add(a.jarPath());
            return paths;
        }
    }

    private static final class Node {
        String group;
        String artifact;
        String version;
        int depth;
        final Set<String> requestedBy = new LinkedHashSet<>();

        String moduleId() {
            return group + ":" + artifact;
        }

        Gav gav() {
            return new Gav(group, artifact, version);
        }
    }

    /**
     * Both scopes, {@code [dependencies]} and {@code [dev-dependencies]}: from {@code lock} when it
     * still matches the manifest (no POMs, no network if every jar is cached), otherwise a fresh
     * resolution that carries the old lock's pins forward for unchanged versions.
     */
    public Result<Resolution> resolve(KwenzaManifest manifest, KwenzaLock lock) {
        if (lock.isFreshFor(manifest)) {
            return fromLock(lock);
        }
        List<Dependency> all = new ArrayList<>(manifest.dependencies.values());
        all.addAll(manifest.devDependencies.values());
        return resolve(all, lock, KwenzaLock.hashDependencies(manifest));
    }

    /**
     * {@code [dependencies]} only, for everything that ends up on a shipped artifact's classpath,
     * so dev-dependencies never leak into it. Resolved separately (see AGENTS.md on the second
     * resolution), with the merged lock's pins, so any version the merged resolution chose is
     * verified locally.
     */
    public Result<Resolution> resolveMain(KwenzaManifest manifest, KwenzaLock merged) {
        return resolve(manifest.dependencies.values(), merged, merged.manifestHash);
    }

    /** Every locked jar, each checked against its pin. A lock entry with no pin is refused. */
    Result<Resolution> fromLock(KwenzaLock lock) {
        List<CompletableFuture<Result<ResolvedArtifact>>> futures = new ArrayList<>();
        for (KwenzaLock.Entry entry : lock.entries) {
            Gav gav = gav(entry.module, entry.version);
            if (entry.sha256 == null || entry.sha256.isBlank()) {
                futures.add(CompletableFuture.completedFuture(Result.err("kwenza.lock has no sha256 for " + gav
                        + ", so its jar can't be verified. Delete kwenza.lock so the next build regenerates it.")));
                continue;
            }
            futures.add(store.jar(gav, Expectation.pinned(entry.sha256)).thenApply(jar -> jar.map(path ->
                    new ResolvedArtifact(entry.module, gav.group(), gav.artifact(), gav.version(), path,
                            new LinkedHashSet<>(entry.requestedBy)))));
        }
        return Result.all(await(futures)).map(artifacts -> new Resolution(artifacts, lock, true));
    }

    /**
     * Full resolution of {@code rootDeps}. {@code previous} is the lock being replaced (or any
     * lock whose pins are already trusted): a module resolved at the same version it pins is
     * checked against that sha256, and everything else against the repository's published .sha1.
     */
    Result<Resolution> resolve(Collection<Dependency> rootDeps, KwenzaLock previous, String manifestHash) {
        Map<String, Node> chosen = new LinkedHashMap<>();
        Map<String, PomParser.ParsedPom> pomCache = new ConcurrentHashMap<>();

        List<Node> currentLevel = new ArrayList<>();
        for (Dependency d : rootDeps) {
            Node n = new Node();
            n.group = d.group;
            n.artifact = d.artifact;
            n.version = d.version;
            n.depth = 0;
            n.requestedBy.add("direct");
            currentLevel.add(n);
        }

        while (!currentLevel.isEmpty()) {
            Map<String, Node> levelWinners = new LinkedHashMap<>();
            for (Node n : currentLevel) {
                Node existingChosen = chosen.get(n.moduleId());
                if (existingChosen != null) {
                    existingChosen.requestedBy.addAll(n.requestedBy);
                    continue;
                }
                Node levelWinner = levelWinners.get(n.moduleId());
                if (levelWinner != null) {
                    levelWinner.requestedBy.addAll(n.requestedBy);
                    continue;
                }
                levelWinners.put(n.moduleId(), n);
            }

            List<CompletableFuture<Result<PomParser.ParsedPom>>> futures = new ArrayList<>();
            for (Node n : levelWinners.values()) {
                futures.add(fetchEffectivePom(n.gav(), pomCache)
                        .thenApply(pom -> prefixed(pom, "Failed to resolve " + n.gav() + ": ")));
            }
            Result<List<PomParser.ParsedPom>> poms = Result.all(await(futures));
            if (poms instanceof Result.Err<?> err) {
                return err.castErr();
            }

            List<Node> nextLevel = new ArrayList<>();
            List<Node> winners = new ArrayList<>(levelWinners.values());
            for (int i = 0; i < winners.size(); i++) {
                Node n = winners.get(i);
                chosen.put(n.moduleId(), n);
                PomParser.ParsedPom pom = poms.get().get(i);
                Result<Map<String, String>> management = buildManagementMap(pom, pomCache);
                if (management instanceof Result.Err<?> err) {
                    return err.castErr();
                }
                for (PomParser.RawDependency rd : pom.dependencies()) {
                    if (rd.optional()) continue;
                    if (!TRANSITIVE_SCOPES.contains(rd.scope())) continue;
                    String childModuleId = rd.group() + ":" + rd.artifact();
                    String childVersion = rd.version() != null ? rd.version() : management.get().get(childModuleId);
                    if (childVersion == null) continue;
                    Node child = new Node();
                    child.group = rd.group();
                    child.artifact = rd.artifact();
                    child.version = childVersion;
                    child.depth = n.depth + 1;
                    child.requestedBy.add(n.moduleId());
                    nextLevel.add(child);
                }
            }
            currentLevel = nextLevel;
        }

        return download(chosen, previous, manifestHash);
    }

    private Result<Resolution> download(Map<String, Node> chosen, KwenzaLock previous, String manifestHash) {
        Map<String, KwenzaLock.Entry> pinned = new LinkedHashMap<>();
        for (KwenzaLock.Entry entry : previous.entries) {
            if (entry.sha256 != null && !entry.sha256.isBlank()) {
                pinned.put(entry.module + ":" + entry.version, entry);
            }
        }
        List<CompletableFuture<Result<ResolvedArtifact>>> futures = new ArrayList<>();
        for (Node n : chosen.values()) {
            KwenzaLock.Entry pin = pinned.get(n.moduleId() + ":" + n.version);
            Expectation expected = pin != null ? Expectation.pinned(pin.sha256) : Expectation.PUBLISHED;
            futures.add(store.jar(n.gav(), expected).thenApply(jar -> jar.map(path ->
                    new ResolvedArtifact(n.moduleId(), n.group, n.artifact, n.version, path, n.requestedBy))));
        }
        return Result.all(await(futures)).map(artifacts -> {
            List<KwenzaLock.Entry> entries = new ArrayList<>();
            for (ResolvedArtifact a : artifacts) {
                entries.add(new KwenzaLock.Entry(a.moduleId(), a.version(), sha256Hex(a.jarPath()),
                        new ArrayList<>(a.requestedBy())));
            }
            return new Resolution(artifacts, new KwenzaLock(entries, manifestHash), false);
        });
    }

    private CompletableFuture<Result<PomParser.ParsedPom>> fetchEffectivePom(Gav gav,
                                                                             Map<String, PomParser.ParsedPom> cache) {
        PomParser.ParsedPom cached = cache.get(gav.toString());
        if (cached != null) {
            return CompletableFuture.completedFuture(Result.ok(cached));
        }
        return store.pom(gav).thenCompose(text -> {
            Result<PomParser.ParsedPom> parsed = text.flatMap(PomParser::parse);
            if (!parsed.isOk()) {
                return CompletableFuture.completedFuture(parsed);
            }
            PomParser.ParsedPom raw = parsed.get();
            if (raw.parent() == null) {
                PomParser.ParsedPom resolved = applyProperties(raw, raw.properties());
                cache.put(gav.toString(), resolved);
                return CompletableFuture.completedFuture(Result.ok(resolved));
            }
            Gav parent = new Gav(raw.parent().group(), raw.parent().artifact(), raw.parent().version());
            return fetchEffectivePom(parent, cache).thenApply(parentResult -> parentResult.map(parentPom -> {
                Map<String, String> merged = new LinkedHashMap<>(parentPom.properties());
                merged.putAll(raw.properties());
                PomParser.ParsedPom resolved = applyProperties(raw, merged);
                List<PomParser.RawDependency> combinedManaged = new ArrayList<>(resolved.managedDependencies());
                combinedManaged.addAll(parentPom.managedDependencies());
                PomParser.ParsedPom withInheritance = new PomParser.ParsedPom(
                        resolved.group(), resolved.artifact(), resolved.version(), resolved.packaging(),
                        resolved.parent(), merged, resolved.dependencies(), combinedManaged);
                cache.put(gav.toString(), withInheritance);
                return withInheritance;
            }));
        });
    }

    private PomParser.ParsedPom applyProperties(PomParser.ParsedPom raw, Map<String, String> properties) {
        Map<String, String> props = new LinkedHashMap<>(properties);
        props.putIfAbsent("project.version", raw.version());
        props.putIfAbsent("project.groupId", raw.group());
        props.putIfAbsent("project.artifactId", raw.artifact());
        props.putIfAbsent("pom.version", raw.version());

        List<PomParser.RawDependency> deps = new ArrayList<>();
        for (PomParser.RawDependency d : raw.dependencies()) {
            deps.add(substitute(d, props));
        }
        List<PomParser.RawDependency> managed = new ArrayList<>();
        for (PomParser.RawDependency d : raw.managedDependencies()) {
            managed.add(substitute(d, props));
        }
        return new PomParser.ParsedPom(raw.group(), raw.artifact(), raw.version(), raw.packaging(), raw.parent(),
                props, deps, managed);
    }

    private PomParser.RawDependency substitute(PomParser.RawDependency d, Map<String, String> props) {
        return new PomParser.RawDependency(
                resolveProps(d.group(), props),
                resolveProps(d.artifact(), props),
                d.version() == null ? null : resolveProps(d.version(), props),
                d.scope(),
                d.optional());
    }

    private String resolveProps(String text, Map<String, String> props) {
        if (text == null || text.indexOf('$') < 0) return text;
        String result = text;
        for (int pass = 0; pass < 5; pass++) {
            Matcher m = PROPERTY_REF.matcher(result);
            StringBuilder sb = new StringBuilder();
            boolean changed = false;
            while (m.find()) {
                String value = props.get(m.group(1));
                if (value != null) {
                    m.appendReplacement(sb, Matcher.quoteReplacement(value));
                    changed = true;
                } else {
                    m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
                }
            }
            m.appendTail(sb);
            result = sb.toString();
            if (!changed) break;
        }
        return result;
    }

    /** Builds a moduleId->version map from a POM's dependencyManagement, expanding any BOM imports. */
    private Result<Map<String, String>> buildManagementMap(PomParser.ParsedPom pom,
                                                           Map<String, PomParser.ParsedPom> pomCache) {
        Map<String, String> map = new LinkedHashMap<>();
        for (PomParser.RawDependency d : pom.managedDependencies()) {
            if ("import".equals(d.scope())) {
                Gav bomGav = new Gav(d.group(), d.artifact(), d.version());
                Result<PomParser.ParsedPom> bom = prefixed(join(fetchEffectivePom(bomGav, pomCache)),
                        "Failed to resolve BOM " + bomGav + ": ");
                if (bom instanceof Result.Err<?> err) {
                    return err.castErr();
                }
                Result<Map<String, String>> bomManagement = buildManagementMap(bom.get(), pomCache);
                if (bomManagement instanceof Result.Err<?> err) {
                    return err.castErr();
                }
                for (Map.Entry<String, String> e : bomManagement.get().entrySet()) {
                    map.putIfAbsent(e.getKey(), e.getValue());
                }
            } else {
                map.putIfAbsent(d.group() + ":" + d.artifact(), d.version());
            }
        }
        return Result.ok(map);
    }

    private static <T> Result<T> prefixed(Result<T> result, String prefix) {
        if (result instanceof Result.Err<T> err) {
            List<String> problems = new ArrayList<>();
            err.failure().problems().forEach(p -> problems.add(prefix + p));
            return Result.err(new Failure(err.failure().exitCode(), problems));
        }
        return result;
    }

    private static Gav gav(String moduleId, String version) {
        int colon = moduleId.indexOf(':');
        return new Gav(moduleId.substring(0, colon), moduleId.substring(colon + 1), version);
    }

    private static <T> List<T> await(List<CompletableFuture<T>> futures) {
        List<T> results = new ArrayList<>();
        for (CompletableFuture<T> future : futures) {
            results.add(join(future));
        }
        return results;
    }

    /**
     * Expected failures arrive as values, so a future that completed exceptionally is a bug or the
     * file system failing: rethrow the real cause rather than the CompletionException around it.
     */
    private static <T> T join(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            if (e.getCause() instanceof Error cause) {
                throw cause;
            }
            throw e;
        }
    }

    private static String sha256Hex(Path path) {
        try {
            return ArtifactStore.hex("SHA-256", Files.readAllBytes(path));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
