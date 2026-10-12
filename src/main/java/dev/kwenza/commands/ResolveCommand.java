package dev.kwenza.commands;

import dev.kwenza.context.Reporter;
import dev.kwenza.context.Result;
import dev.kwenza.context.Timing;
import dev.kwenza.manifest.KwenzaLock;
import dev.kwenza.resolver.ResolveContext;
import dev.kwenza.resolver.Resolver;
import dev.kwenza.workspace.Project;

import java.io.IOException;

/**
 * {@code kwenza resolve}: makes sure kwenza.lock matches the manifest, resolving only if it
 * doesn't, without compiling or packaging anything. Also how every other command gets its
 * dependencies: {@link #resolved()} and {@link #mainResolved()} resolve at most once per
 * invocation, remembered in the shared {@link ResolveContext}.
 */
public final class ResolveCommand {
    private final Project project;
    private final ResolveContext state;
    private final Resolver resolver;
    private final Reporter reporter;

    public ResolveCommand(Project project, ResolveContext state, Resolver resolver, Reporter reporter) {
        this.project = project;
        this.state = state;
        this.resolver = resolver;
        this.reporter = reporter;
    }

    public Result<Void> run() throws IOException {
        return resolved().map(resolution -> null);
    }

    /** Both scopes, from kwenza.lock when it matches the manifest; writes the lock when it didn't. */
    public Result<Resolver.Resolution> resolved() throws IOException {
        return state.merged().get(() -> {
            Result<KwenzaLock> lock = KwenzaLock.load(project.lockFile());
            if (lock instanceof Result.Err<?> err) {
                return err.castErr();
            }
            boolean fresh = lock.get().isFreshFor(project.manifest());
            reporter.info(fresh
                    ? "resolve: using locked dependencies (kwenza.lock)"
                    : "resolve: fetching dependencies from Maven Central...");
            Result<Resolver.Resolution> resolution;
            try (var timing = Timing.phase(fresh ? "resolve (from lock)" : "resolve (network)")) {
                resolution = resolver.resolve(project.manifest(), lock.get());
            }
            if (resolution instanceof Result.Ok<Resolver.Resolution> ok && !ok.value().fromLock()) {
                ok.value().lock().save(project.lockFile());
                report(ok.value());
            }
            return resolution;
        });
    }

    /** {@code [dependencies]} only, for anything shipped: dev-dependencies never reach it. */
    public Result<Resolver.Resolution> mainResolved() throws IOException {
        Result<Resolver.Resolution> merged = resolved();
        if (merged instanceof Result.Err<?> err) {
            return err.castErr();
        }
        return state.main().get(() -> {
            try (var timing = Timing.phase("resolve main classpath")) {
                return resolver.resolveMain(project.manifest(), merged.get().lock());
            }
        });
    }

    private void report(Resolver.Resolution resolution) {
        for (Resolver.ResolvedArtifact artifact : resolution.artifacts()) {
            boolean direct = artifact.requestedBy().contains("direct");
            String via = direct ? "direct" : "via " + String.join(", ", artifact.requestedBy());
            reporter.detail("  " + artifact.moduleId() + ":" + artifact.version() + "  (" + via + ")");
        }
        reporter.info("resolve: " + resolution.artifacts().size() + " artifact(s) resolved -> "
                + project.lockFile().getFileName());
    }
}
