package dev.kwenza.manifest;

/** One declared dependency: an alias mapped to a Maven GAV coordinate. */
public final class Dependency {
    public final String alias;
    public final String group;
    public final String artifact;
    public final String version;

    public Dependency(String alias, String group, String artifact, String version) {
        this.alias = alias;
        this.group = group;
        this.artifact = artifact;
        this.version = version;
    }

    public String moduleId() {
        return group + ":" + artifact;
    }

    public String gav() {
        return group + ":" + artifact + ":" + version;
    }
}
