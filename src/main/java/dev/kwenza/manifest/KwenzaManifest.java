package dev.kwenza.manifest;

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

    public static KwenzaManifest load(Path path) throws IOException {
        Path projectDir = path.toAbsolutePath().getParent();
        String fallbackName = projectDir != null && projectDir.getFileName() != null
                ? projectDir.getFileName().toString() : null;
        return parse(Files.readString(path), fallbackName);
    }

    public static KwenzaManifest parse(String text) {
        return parse(text, null);
    }

    @SuppressWarnings("unchecked")
    public static KwenzaManifest parse(String text, String fallbackName) {
        Map<String, Object> root = Toml.parse(text);
        Object pkgRaw = root.get("package");
        if (!(pkgRaw instanceof Map)) {
            throw new IllegalArgumentException("kwenza.toml is missing a [package] table");
        }
        Map<String, Object> pkg = (Map<String, Object>) pkgRaw;

        String name = pkg.containsKey("name") ? requireString(pkg, "name") : fallbackName;
        if (name == null) {
            throw new IllegalArgumentException(
                    "[package] is missing 'name' and no containing directory name is available to default to");
        }
        String group = requireString(pkg, "group");
        String version = requireString(pkg, "version");
        String javaVersion = pkg.containsKey("java-version") ? requireString(pkg, "java-version") : "21";
        String mainClass = pkg.containsKey("main-class") ? requireString(pkg, "main-class") : null;
        String outputDir = pkg.containsKey("output-dir") ? requireString(pkg, "output-dir") : "target";

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
            throw new IllegalArgumentException("[versions] must be a table");
        }
        for (Map.Entry<String, Object> e : ((Map<String, Object>) raw).entrySet()) {
            if (!(e.getValue() instanceof String)) {
                throw new IllegalArgumentException("[versions]." + e.getKey() + " must be a plain string version");
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
            throw new IllegalArgumentException("[" + tableName + "] must be a table");
        }
        for (Map.Entry<String, Object> e : ((Map<String, Object>) raw).entrySet()) {
            if (!(e.getValue() instanceof Map)) {
                throw new IllegalArgumentException("Dependency '" + e.getKey()
                        + "' must be an inline table like { module = \"group:artifact\", version = \"1.0\" }");
            }
            Map<String, Object> entry = (Map<String, Object>) e.getValue();
            String module = requireString(entry, "module");
            String depVersion = resolveVersion(e.getKey(), entry, versions);
            int colon = module.indexOf(':');
            if (colon < 0 || module.indexOf(':', colon + 1) >= 0) {
                throw new IllegalArgumentException("Dependency module '" + module + "' must be in 'group:artifact' form");
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
                throw new IllegalArgumentException(
                        "Dependency '" + alias + "' has a 'version' table but no string 'ref' key");
            }
            String resolved = versions.get(ref);
            if (resolved == null) {
                throw new IllegalArgumentException("Dependency '" + alias + "' references version.ref = \"" + ref
                        + "\", but [versions] has no such key");
            }
            return resolved;
        }
        throw new IllegalArgumentException(
                "Dependency '" + alias + "' needs a 'version' (a string, or { ref = \"...\" } into [versions])");
    }

    private static String requireString(Map<String, Object> table, String key) {
        Object v = table.get(key);
        if (!(v instanceof String)) {
            throw new IllegalArgumentException("Expected a string value for '" + key + "'");
        }
        return (String) v;
    }
}
