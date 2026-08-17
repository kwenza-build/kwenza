package dev.kwenza.manifest;

import dev.kwenza.toml.Toml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** The parsed contents of a project's kwenza.lock: one resolved artifact per entry. */
public final class KwenzaLock {

    public static final class Entry {
        public final String module;
        public final String version;
        public final String sha256;
        public final List<String> requestedBy;

        public Entry(String module, String version, String sha256, List<String> requestedBy) {
            this.module = module;
            this.version = version;
            this.sha256 = sha256;
            this.requestedBy = requestedBy;
        }
    }

    public final List<Entry> entries;
    public final String manifestHash;

    public KwenzaLock(List<Entry> entries, String manifestHash) {
        this.entries = entries;
        this.manifestHash = manifestHash;
    }

    public static KwenzaLock empty() {
        return new KwenzaLock(new ArrayList<>(), null);
    }

    /** A stable hash over a manifest's declared dependencies, used to detect staleness. */
    public static String hashDependencies(KwenzaManifest manifest) {
        TreeSet<String> lines = new TreeSet<>();
        for (Dependency d : manifest.dependencies.values()) {
            lines.add("dep:" + d.alias + "=" + d.gav());
        }
        for (Dependency d : manifest.devDependencies.values()) {
            lines.add("dev:" + d.alias + "=" + d.gav());
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String line : lines) {
                digest.update(line.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean isFreshFor(KwenzaManifest manifest) {
        return manifestHash != null && manifestHash.equals(hashDependencies(manifest));
    }

    public Map<String, Entry> byModule() {
        Map<String, Entry> map = new LinkedHashMap<>();
        for (Entry e : entries) {
            map.put(e.module, e);
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    public static KwenzaLock load(Path path) throws IOException {
        if (!Files.exists(path)) {
            return empty();
        }
        Map<String, Object> root = Toml.parse(Files.readString(path));
        Object resolvedRaw = root.get("resolved");
        List<Entry> entries = new ArrayList<>();
        if (resolvedRaw instanceof List) {
            for (Object o : (List<Object>) resolvedRaw) {
                Map<String, Object> m = (Map<String, Object>) o;
                List<String> requestedBy = new ArrayList<>();
                Object rb = m.get("requested-by");
                if (rb instanceof List) {
                    for (Object s : (List<Object>) rb) {
                        requestedBy.add((String) s);
                    }
                }
                entries.add(new Entry((String) m.get("module"), (String) m.get("version"),
                        (String) m.get("sha256"), requestedBy));
            }
        }
        String manifestHash = (String) root.get("manifest-hash");
        return new KwenzaLock(entries, manifestHash);
    }

    public void save(Path path, String manifestHash) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("manifest-hash = ").append(quote(manifestHash)).append("\n\n");
        for (Entry e : entries) {
            sb.append("[[resolved]]\n");
            sb.append("module = ").append(quote(e.module)).append('\n');
            sb.append("version = ").append(quote(e.version)).append('\n');
            sb.append("sha256 = ").append(quote(e.sha256)).append('\n');
            sb.append("requested-by = [");
            for (int i = 0; i < e.requestedBy.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(quote(e.requestedBy.get(i)));
            }
            sb.append("]\n\n");
        }
        Files.writeString(path, sb.toString());
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
