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
    public final Map<String, Dependency> dependencies;
    public final Map<String, Dependency> devDependencies;

    private KwenzaManifest(String name, String group, String version, String javaVersion, String mainClass,
                          Map<String, Dependency> dependencies, Map<String, Dependency> devDependencies) {
        this.name = name;
        this.group = group;
        this.version = version;
        this.javaVersion = javaVersion;
        this.mainClass = mainClass;
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

        Map<String, Dependency> dependencies = parseDependencies(root, "dependencies");
        Map<String, Dependency> devDependencies = parseDependencies(root, "dev-dependencies");

        return new KwenzaManifest(name, group, version, javaVersion, mainClass, dependencies, devDependencies);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Dependency> parseDependencies(Map<String, Object> root, String tableName) {
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
            String depVersion = requireString(entry, "version");
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

    private static String requireString(Map<String, Object> table, String key) {
        Object v = table.get(key);
        if (!(v instanceof String)) {
            throw new IllegalArgumentException("Expected a string value for '" + key + "'");
        }
        return (String) v;
    }
}
