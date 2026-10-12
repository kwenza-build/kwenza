package dev.kwenza.resolver;

import dev.kwenza.context.GlobalContext;
import dev.kwenza.context.Reporter;
import dev.kwenza.context.Timing;
import dev.kwenza.manifest.Dependency;
import dev.kwenza.manifest.KwenzaLock;
import dev.kwenza.workspace.Project;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A single invocation's dependency resolution, memoized so a command chain (e.g.
 * {@code kwenza build run}) resolves exactly once, not once per command that needs it. Owns the
 * "is {@code kwenza.lock} still fresh?" decision, so no command has to know about the lock at all.
 * One instance per invocation, shared across every command in its chain.
 */
public final class ResolveContext {

    private final Project project;
    private final GlobalContext global;

    private Resolver.Resolution resolution;      // dependencies + dev-dependencies merged, backed by kwenza.lock
    private Resolver.Resolution mainResolution;  // dependencies only - derived locally, never hits the network on its own

    public ResolveContext(Project project, GlobalContext global) {
        this.project = project;
        this.global = global;
    }

    /**
     * Both scopes merged, from the lock when it's fresh and from Maven Central otherwise.
     * Resolves at most once per invocation - later calls reuse the result.
     */
    public Resolver.Resolution ensureResolved() throws IOException {
        if (resolution == null) {
            KwenzaLock existing = KwenzaLock.load(project.lockFile());
            resolution = existing.isFreshFor(project.manifest())
                    ? fromLock(existing)
                    : fresh(existing);
        }
        return resolution;
    }

    /**
     * Resolves the dependencies-only subset, used for everything that ends up on a shipped
     * artifact's classpath (build/package/run) so dev-dependencies never leak into it.
     * Never touches the network on its own: {@code ensureResolved} above has already guaranteed
     * every POM/jar it needs is in the local cache, since dependencies is a subset of
     * dependencies + dev-dependencies.
     */
    public Resolver.Resolution ensureMainResolved() throws IOException {
        ensureResolved();
        if (mainResolution == null) {
            Resolver resolver = new Resolver(MavenRepository.central(global.localRepo()));
            // The merged lock was verified moments ago, so its pins cover every jar this needs.
            mainResolution = resolver.resolve(project.manifest().dependencies.values(), resolution.lock());
        }
        return mainResolution;
    }

    private Resolver.Resolution fromLock(KwenzaLock lock) {
        if (!lock.entries.isEmpty()) {
            reporter().info("resolve: using locked dependencies (kwenza.lock)");
        }
        Resolver.Resolution loaded = new Resolver(MavenRepository.central(global.localRepo())).ensureFromLock(lock);
        Timing.mark("resolved (from lock)");
        return loaded;
    }

    private Resolver.Resolution fresh(KwenzaLock previous) throws IOException {
        reporter().info("resolve: fetching dependencies from Maven Central...");
        List<Dependency> allDeps = new ArrayList<>();
        allDeps.addAll(project.manifest().dependencies.values());
        allDeps.addAll(project.manifest().devDependencies.values());
        Resolver.Resolution fresh = new Resolver(MavenRepository.central(global.localRepo())).resolve(allDeps, previous);
        fresh.lock().save(project.lockFile(), KwenzaLock.hashDependencies(project.manifest()));
        for (Resolver.ResolvedArtifact artifact : fresh.artifacts()) {
            boolean direct = artifact.requestedBy().contains("direct");
            String via = direct ? "direct" : "via " + String.join(", ", artifact.requestedBy());
            reporter().detail("  " + artifact.moduleId() + ":" + artifact.version() + "  (" + via + ")");
        }
        reporter().info("resolve: " + fresh.artifacts().size() + " artifact(s) resolved -> " + project.lockFile().getFileName());
        Timing.mark("resolved (network)");
        return fresh;
    }

    private Reporter reporter() {
        return global.reporter();
    }
}
