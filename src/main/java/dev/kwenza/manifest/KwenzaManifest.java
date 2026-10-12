package dev.kwenza.manifest;

import dev.kwenza.context.Result;
import dev.kwenza.toml.Toml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** The parsed contents of a project's kwenza.toml. */
public final class KwenzaManifest {

    public final String name;
    public final String group;
    public final String version;
    public final String javaVersion;
    public final String mainClass;
    public final String outputDir;
    public final Map<String, Dependency> dependencies;
    public final Map<String, Dependency> devDependencies;

    private KwenzaManifest(String name, String group, String version, String javaVersion, String mainClass,
                          String outputDir, Map<String, Dependency> dependencies,
                          Map<String, Dependency> devDependencies) {
        this.name = name;
        this.group = group;
        this.version = version;
        this.javaVersion = javaVersion;
        this.mainClass = mainClass;
        this.outputDir = outputDir;
        this.dependencies = dependencies;
        this.devDependencies = devDependencies;
    }

    /** Reading the file is the only thing that throws: a disk that can't be read isn't a manifest problem. */
    public static Result<KwenzaManifest> load(Path path) throws IOException {
        return parse(Files.readString(path));
    }

    /** The manifest, or a failure naming the first thing wrong with it. */
    public static Result<KwenzaManifest> parse(String text) {
        return Toml.parse(text).flatMap(root -> {
            try {
                return Result.ok(fromToml(root));
            } catch (Invalid e) {
                return Result.err(e.getMessage());
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static KwenzaManifest fromToml(Map<String, Object> root) {
        Object pkgRaw = root.get("package");
        if (!(pkgRaw instanceof Map)) {
            throw new Invalid("kwenza.toml is missing a [package] table");
        }
        Map<String, Object> pkg = (Map<String, Object>) pkgRaw;

        String name = requireString(pkg, "[package]", "name");
        String group = requireString(pkg, "[package]", "group");
        String version = requireString(pkg, "[package]", "version");
        String javaVersion = pkg.containsKey("java-version") ? requireString(pkg, "[package]", "java-version") : "21";
        String mainClass = pkg.containsKey("main-class") ? requireString(pkg, "[package]", "main-class") : null;
        String outputDir = pkg.containsKey("output-dir") ? requireString(pkg, "[package]", "output-dir") : "target";

        Map<String, String> versions = parseVersions(root);
        Map<String, Dependency> dependencies = parseDependencies(root, "dependencies", versions);
        Map<String, Dependency> devDependencies = parseDependencies(root, "dev-dependencies", versions);

        return new KwenzaManifest(name, group, version, javaVersion, mainClass, outputDir, dependencies, devDependencies);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> parseVersions(Map<String, Object> root) {
        Map<String, String> result = new LinkedHashMap<>();
        Object raw = root.get("versions");
        if (raw == null) {
            return result;
        }
        if (!(raw instanceof Map)) {
            throw new Invalid("[versions] must be a table");
        }
        for (Map.Entry<String, Object> e : ((Map<String, Object>) raw).entrySet()) {
            if (!(e.getValue() instanceof String)) {
                throw new Invalid("[versions]." + e.getKey() + " must be a plain string version");
            }
            result.put(e.getKey(), (String) e.getValue());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Dependency> parseDependencies(Map<String, Object> root, String tableName,
                                                              Map<String, String> versions) {
        Map<String, Dependency> result = new LinkedHashMap<>();
        Object raw = root.get(tableName);
        if (raw == null) {
            return result;
        }
        if (!(raw instanceof Map)) {
            throw new Invalid("[" + tableName + "] must be a table");
        }
        for (Map.Entry<String, Object> e : ((Map<String, Object>) raw).entrySet()) {
            if (!(e.getValue() instanceof Map)) {
                throw new Invalid("Dependency '" + e.getKey()
                        + "' must be an inline table like { module = \"group:artifact\", version = \"1.0\" }");
            }
            Map<String, Object> entry = (Map<String, Object>) e.getValue();
            String module = requireString(entry, "Dependency '" + e.getKey() + "'", "module");
            String depVersion = resolveVersion(e.getKey(), entry, versions);
            int colon = module.indexOf(':');
            if (colon < 0 || module.indexOf(':', colon + 1) >= 0) {
                throw new Invalid("Dependency module '" + module + "' must be in 'group:artifact' form");
            }
            String depGroup = module.substring(0, colon);
            String depArtifact = module.substring(colon + 1);
            result.put(e.getKey(), new Dependency(e.getKey(), depGroup, depArtifact, depVersion));
        }
        return result;
    }

    /** Accepts either version = "1.2.3" directly, or version.ref = "alias" pointing into [versions]. */
    @SuppressWarnings("unchecked")
    private static String resolveVersion(String alias, Map<String, Object> entry, Map<String, String> versions) {
        Object versionValue = entry.get("version");
        if (versionValue instanceof String s) {
            return s;
        }
        if (versionValue instanceof Map) {
            Map<String, Object> versionTable = (Map<String, Object>) versionValue;
            Object refValue = versionTable.get("ref");
            if (!(refValue instanceof String ref)) {
                throw new Invalid(
                        "Dependency '" + alias + "' has a 'version' table but no string 'ref' key");
            }
            String resolved = versions.get(ref);
            if (resolved == null) {
                throw new Invalid("Dependency '" + alias + "' references version.ref = \"" + ref
                        + "\", but [versions] has no such key");
            }
            return resolved;
        }
        throw new Invalid(
                "Dependency '" + alias + "' needs a 'version' (a string, or { ref = \"...\" } into [versions])");
    }

    private static String requireString(Map<String, Object> table, String where, String key) {
        Object v = table.get(key);
        if (v == null) {
            throw new Invalid(where + " is missing required field '" + key + "'");
        }
        if (!(v instanceof String)) {
            throw new Invalid("'" + key + "' in " + where + " must be a string");
        }
        return (String) v;
    }

    /**
     * How the helpers above bail out from any depth; caught in {@link #parse}, never thrown out of
     * this class (the one exception AGENTS.md's "errors are values" rule allows). Stackless, since
     * only the message is ever used.
     */
    private static final class Invalid extends RuntimeException {
        Invalid(String message) {
            super(message, null, false, false);
        }
    }
}
