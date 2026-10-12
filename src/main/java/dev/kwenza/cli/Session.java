package dev.kwenza.cli;

import dev.kwenza.cli.help.Help;
import dev.kwenza.commands.BuildCommand;
import dev.kwenza.commands.ResolveCommand;
import dev.kwenza.compiler.CompileContext;
import dev.kwenza.context.GlobalContext;
import dev.kwenza.context.Reporter;
import dev.kwenza.context.Result;
import dev.kwenza.context.Timing;
import dev.kwenza.resolver.ArtifactStore;
import dev.kwenza.resolver.LocalRepository;
import dev.kwenza.resolver.MavenRepository;
import dev.kwenza.resolver.ResolveContext;
import dev.kwenza.resolver.Resolver;
import dev.kwenza.workspace.Project;

import java.io.IOException;

/**
 * Everything one invocation's commands share, and the one place it's wired together: the
 * project, the state chained commands share (one {@link ResolveContext} and one
 * {@link CompileContext}, which is what makes {@code kwenza build run} resolve and compile once),
 * and the one {@link MavenRepository} (whose HTTP client only exists once something is fetched).
 * Commands are cheap and made fresh for each use; the state they work on lives here. Lazy, so
 * commands that need no project ({@code help}, {@code version}) work outside one.
 */
public final class Session {
    private final GlobalContext global;
    private final Help help;
    private final ResolveContext resolveState = new ResolveContext();
    private final CompileContext compileState = new CompileContext();

    private Project project;
    private MavenRepository remote;

    public Session(GlobalContext global, Help help) {
        this.global = global;
        this.help = help;
    }

    public Reporter reporter() {
        return global.reporter();
    }

    public Help help() {
        return help;
    }

    /**
     * Loads the project in the working directory, once; a failure if there's no usable kwenza.toml
     * there. {@code Cli} calls this before the first command that {@code needsProject()}.
     */
    public Result<Void> loadProject() throws IOException {
        if (project == null) {
            Result<Project> loaded = Project.load(global.workingDir());
            Timing.mark("manifest-loaded");
            if (loaded instanceof Result.Err<?> err) {
                return err.castErr();
            }
            project = loaded.get();
        }
        return Result.ok();
    }

    /** The loaded project; asking before {@link #loadProject()} succeeded is a bug. */
    public Project project() {
        if (project == null) {
            throw new IllegalStateException("project() before loadProject()");
        }
        return project;
    }

    /** What's been compiled so far, for {@code clean} to forget. */
    public CompileContext compileState() {
        return compileState;
    }

    public ResolveCommand resolveCommand() {
        if (remote == null) {
            remote = MavenRepository.central();
        }
        Resolver resolver = new Resolver(new ArtifactStore(new LocalRepository(global.localRepo()), remote));
        return new ResolveCommand(project(), resolveState, resolver, reporter());
    }

    public BuildCommand buildCommand() {
        return new BuildCommand(project(), compileState, resolveCommand(), reporter());
    }
}
