package dev.kwenza.resolver;

/** A Maven coordinate: group, artifact, version. */
public record Gav(String group, String artifact, String version) {

    /** {@code group:artifact}, how kwenza.lock and the manifest name a module. */
    public String moduleId() {
        return group + ":" + artifact;
    }

    /** {@code group:artifact:version}, how messages name it. */
    @Override
    public String toString() {
        return moduleId() + ":" + version;
    }
}
