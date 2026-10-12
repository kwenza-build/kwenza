package dev.kwenza.commands;

import dev.kwenza.compiler.CompileContext;
import dev.kwenza.context.Reporter;
import dev.kwenza.context.Result;
import dev.kwenza.resolver.Resolver;
import dev.kwenza.workspace.Project;

import java.io.IOException;
import java.util.List;

/** {@code kwenza run}: builds, then executes [package].main-class in a fresh java subprocess. */
public final class RunCommand {
    private final Project project;
    private final CompileContext compile;
    private final Reporter reporter;

    public RunCommand(Project project, CompileContext compile, Reporter reporter) {
        this.project = project;
        this.compile = compile;
        this.reporter = reporter;
    }

    public Result<Void> run() throws IOException, InterruptedException {
        return run(ProcessBuilder.Redirect.INHERIT);
    }

    /** @param outputRedirect INHERIT for real CLI use; tests pass DISCARD so a real nested subprocess's own console output never bleeds into the outer test run. */
    public Result<Void> run(ProcessBuilder.Redirect outputRedirect) throws IOException, InterruptedException {
        String mainClass = project.manifest().mainClass;
        if (mainClass == null) {
            return Result.err("kwenza.toml has no [package] main-class set - required for `kwenza run`");
        }
        Result<Resolver.Resolution> compiled = compile.ensureCompiled();
        if (compiled instanceof Result.Err<?> err) {
            return err.castErr();
        }
        JvmLaunch launch = JvmLaunch.of("run", mainClass, List.of(project.classesDir()), compiled.get().classpath());
        // A non-zero exit is the program's own outcome, already reported by the program itself.
        return new Jvm(project.root(), reporter, outputRedirect).run(launch);
    }
}
