package dev.kwenza.resolver;

import dev.kwenza.manifest.Dependency;
import dev.kwenza.manifest.KwenzaLock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves a project's dependencies transitively against Maven Central: fetches POMs
 * concurrently, applies Maven-compatible nearest-wins mediation (honoring dependencyManagement
 * / BOM imports and parent POM inheritance), then downloads and verifies the winning jars.
 */
public final class Resolver {

    private static final Set<String> TRANSITIVE_SCOPES = Set.of("compile", "runtime");
    private static final Pattern PROPERTY_REF = Pattern.compile("\\$\\{([^}]+)}");

    private final MavenRepository repo;

    public Resolver(MavenRepository repo) {
        this.repo = repo;
    }

    public record ResolvedArtifact(String moduleId, String group, String artifact, String version,
                                    Path jarPath, Set<String> requestedBy) {
    }

    public record Resolution(List<ResolvedArtifact> artifacts, KwenzaLock lock) {
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
    }

    /** Full transitive resolution against Maven Central. Writes a fresh lockfile's worth of entries. */
    public Resolution resolve(Collection<Dependency> rootDeps) {
        return resolve(rootDeps, KwenzaLock.empty());
    }

    /**
     * Full resolution. {@code previous} is the lock being replaced (or any lock whose pins are
     * already trusted): a module resolved at the same version it pins is checked against that
     * sha256 with no network call, and everything else is checked against the repository's
     * published .sha1 even when it's already in the local cache - so a fresh resolve never pins a
     * jar nobody has verified.
     */
    public Resolution resolve(Collection<Dependency> rootDeps, KwenzaLock previous) {
        Map<String, Node> chosen = new LinkedHashMap<>();
        Map<String, PomParser.ParsedPom> pomCache = new HashMap<>();

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

            Map<String, CompletableFuture<PomParser.ParsedPom>> futures = new LinkedHashMap<>();
            for (Node n : levelWinners.values()) {
                futures.put(n.moduleId(), fetchEffectivePom(n.group, n.artifact, n.version, pomCache));
            }

            List<Node> nextLevel = new ArrayList<>();
            for (Map.Entry<String, CompletableFuture<PomParser.ParsedPom>> e : futures.entrySet()) {
                Node n = levelWinners.get(e.getKey());
                chosen.put(e.getKey(), n);
                PomParser.ParsedPom pom;
                try {
                    pom = e.getValue().join();
                } catch (Exception ex) {
                    throw new ResolutionException("Failed to resolve " + e.getKey() + ":" + n.version + ": " + causeMessage(ex), ex);
                }
                Map<String, String> management = buildManagementMap(pom, pomCache);
                for (PomParser.RawDependency rd : pom.dependencies()) {
                    if (rd.optional()) continue;
                    if (!TRANSITIVE_SCOPES.contains(rd.scope())) continue;
                    String childModuleId = rd.group() + ":" + rd.artifact();
                    String childVersion = rd.version() != null ? rd.version() : management.get(childModuleId);
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

        return download(chosen, previous);
    }

    /**
     * Fast path: reuse an existing lock, only downloading jars missing from the local cache. Every
     * jar - cache hit or fresh download - must still match the lock's recorded sha256 before it's
     * trusted, so a jar altered in ~/.m2 after the lock was written is caught, not used.
     */
    public Resolution ensureFromLock(KwenzaLock lock) {
        List<ResolvedArtifact> artifacts = new ArrayList<>();
        Map<String, CompletableFuture<Path>> futures = new LinkedHashMap<>();
        for (KwenzaLock.Entry entry : lock.entries) {
            String[] parts = splitModule(entry.module);
            futures.put(entry.module, repo.fetchJarAsync(parts[0], parts[1], entry.version));
        }
        for (KwenzaLock.Entry entry : lock.entries) {
            String[] parts = splitModule(entry.module);
            Path jarPath;
            try {
                jarPath = futures.get(entry.module).join();
            } catch (Exception ex) {
                throw new ResolutionException("Failed to fetch locked dependency " + entry.module + ": " + causeMessage(ex), ex);
            }
            verifyAgainstLock(entry, jarPath);
            artifacts.add(new ResolvedArtifact(entry.module, parts[0], parts[1], entry.version, jarPath,
                    new LinkedHashSet<>(entry.requestedBy)));
        }
        return new Resolution(artifacts, lock);
    }

    /** The underlying failure's own message, unwrapped from the CompletionException join() adds. */
    private static String causeMessage(Throwable ex) {
        Throwable cause = ex instanceof CompletionException && ex.getCause() != null ? ex.getCause() : ex;
        return cause.getMessage() != null ? cause.getMessage() : cause.toString();
    }

    private static void verifyAgainstLock(KwenzaLock.Entry entry, Path jarPath) {
        String gav = entry.module + ":" + entry.version;
        if (entry.sha256 == null || entry.sha256.isBlank()) {
            throw new ResolutionException("kwenza.lock has no sha256 for " + gav
                    + ", so its jar can't be verified. Delete kwenza.lock so the next build regenerates it.");
        }
        String actual = sha256Hex(jarPath);
        if (!actual.equalsIgnoreCase(entry.sha256)) {
            throw new ResolutionException("Checksum mismatch for " + gav + ": kwenza.lock expects sha256 "
                    + entry.sha256 + " but " + jarPath + " has " + actual
                    + ". The cached jar has changed since the lock was written; delete it so kwenza"
                    + " re-downloads and re-verifies it.");
        }
    }

    private static String[] splitModule(String moduleId) {
        int colon = moduleId.indexOf(':');
        return new String[]{moduleId.substring(0, colon), moduleId.substring(colon + 1)};
    }

    private Resolution download(Map<String, Node> chosen, KwenzaLock previous) {
        Map<String, KwenzaLock.Entry> pinned = new LinkedHashMap<>();
        for (KwenzaLock.Entry entry : previous.entries) {
            if (entry.sha256 != null && !entry.sha256.isBlank()) {
                pinned.put(entry.module + ":" + entry.version, entry);
            }
        }
        Map<String, CompletableFuture<Path>> jarFutures = new LinkedHashMap<>();
        for (Map.Entry<String, Node> e : chosen.entrySet()) {
            Node n = e.getValue();
            jarFutures.put(e.getKey(), pinned.containsKey(e.getKey() + ":" + n.version)
                    ? repo.fetchJarAsync(n.group, n.artifact, n.version)
                    : repo.fetchJarVerifiedAsync(n.group, n.artifact, n.version));
        }

        List<ResolvedArtifact> artifacts = new ArrayList<>();
        List<KwenzaLock.Entry> lockEntries = new ArrayList<>();
        for (Map.Entry<String, Node> e : chosen.entrySet()) {
            Node n = e.getValue();
            Path jarPath;
            try {
                jarPath = jarFutures.get(e.getKey()).join();
            } catch (Exception ex) {
                throw new ResolutionException("Failed to download " + e.getKey() + ":" + n.version + ": " + causeMessage(ex), ex);
            }
            KwenzaLock.Entry pin = pinned.get(e.getKey() + ":" + n.version);
            if (pin != null) {
                verifyAgainstLock(pin, jarPath);
            }
            artifacts.add(new ResolvedArtifact(e.getKey(), n.group, n.artifact, n.version, jarPath, n.requestedBy));
            lockEntries.add(new KwenzaLock.Entry(e.getKey(), n.version, sha256Hex(jarPath), new ArrayList<>(n.requestedBy)));
        }
        return new Resolution(artifacts, new KwenzaLock(lockEntries, null));
    }

    private CompletableFuture<PomParser.ParsedPom> fetchEffectivePom(String group, String artifact, String version,
                                                                       Map<String, PomParser.ParsedPom> cache) {
        String key = group + ":" + artifact + ":" + version;
        PomParser.ParsedPom cached = cache.get(key);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        return repo.fetchTextAsync(group, artifact, version, "pom").thenCompose(xml -> {
            PomParser.ParsedPom raw = PomParser.parse(xml);
            if (raw.parent() == null) {
                PomParser.ParsedPom resolved = applyProperties(raw, raw.properties());
                cache.put(key, resolved);
                return CompletableFuture.completedFuture(resolved);
            }
            return fetchEffectivePom(raw.parent().group(), raw.parent().artifact(), raw.parent().version(), cache)
                    .thenApply(parentPom -> {
                        Map<String, String> merged = new LinkedHashMap<>(parentPom.properties());
                        merged.putAll(raw.properties());
                        PomParser.ParsedPom resolved = applyProperties(raw, merged);
                        List<PomParser.RawDependency> combinedManaged = new ArrayList<>(resolved.managedDependencies());
                        combinedManaged.addAll(parentPom.managedDependencies());
                        PomParser.ParsedPom withInheritance = new PomParser.ParsedPom(
                                resolved.group(), resolved.artifact(), resolved.version(), resolved.packaging(),
                                resolved.parent(), merged, resolved.dependencies(), combinedManaged);
                        cache.put(key, withInheritance);
                        return withInheritance;
                    });
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

    /** Builds moduleId->version map from a POM's dependencyManagement, expanding any BOM imports. */
    private Map<String, String> buildManagementMap(PomParser.ParsedPom pom, Map<String, PomParser.ParsedPom> pomCache) {
        Map<String, String> map = new LinkedHashMap<>();
        for (PomParser.RawDependency d : pom.managedDependencies()) {
            if ("import".equals(d.scope())) {
                PomParser.ParsedPom bom = fetchEffectivePom(d.group(), d.artifact(), d.version(), pomCache).join();
                Map<String, String> bomManagement = buildManagementMap(bom, pomCache);
                for (Map.Entry<String, String> e : bomManagement.entrySet()) {
                    map.putIfAbsent(e.getKey(), e.getValue());
                }
            } else {
                map.putIfAbsent(d.group() + ":" + d.artifact(), d.version());
            }
        }
        return map;
    }

    private static String sha256Hex(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = Files.readAllBytes(path);
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception e) {
            throw new ResolutionException("Failed to hash " + path, e);
        }
    }
}
