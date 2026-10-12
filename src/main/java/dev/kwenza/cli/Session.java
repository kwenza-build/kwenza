package dev.kwenza.cli;

import dev.kwenza.cli.help.Help;
import dev.kwenza.compiler.CompileContext;
import dev.kwenza.context.GlobalContext;
import dev.kwenza.context.Reporter;
import dev.kwenza.context.Result;
import dev.kwenza.context.Timing;
import dev.kwenza.resolver.ResolveContext;
import dev.kwenza.workspace.Project;

import java.io.IOException;

/**
 * Everything one invocation's commands share, created on first use: a chain like
 * {@code kwenza build run} gets one {@link ResolveContext} and one {@link CompileContext}, which
 * is what makes it resolve and compile once. Lazy so commands that need no project
 * ({@code help}, {@code version}) work outside one. This is the only place the contexts are wired
 * together; the commands themselves just take the ones they need.
 */
public final class Session {
    private final GlobalContext global;
    private final Help help;

    private Project project;
    private ResolveContext resolve;
    private CompileContext compile;

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

    public ResolveContext resolve() {
        if (resolve == null) {
            resolve = new ResolveContext(project(), global);
        }
        return resolve;
    }

    public CompileContext compile() {
        if (compile == null) {
            compile = new CompileContext(project(), resolve(), reporter());
        }
        return compile;
    }
}
