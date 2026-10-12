package dev.kwenza.commands;

import dev.kwenza.compiler.CompileContext;
import dev.kwenza.compiler.JavaCompilation;
import dev.kwenza.context.Reporter;
import dev.kwenza.context.Result;
import dev.kwenza.context.Timing;
import dev.kwenza.resolver.Resolver;
import dev.kwenza.workspace.Project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * {@code kwenza build}: resolves dependencies and compiles src/main/java, copying
 * src/main/resources alongside. Produces no artifact. Also how run, package and test get a
 * compiled project: {@link #main()} and {@link #tests()} compile at most once per invocation,
 * remembered in the shared {@link CompileContext}, until a {@code clean} forgets it.
 */
public final class BuildCommand {
    private final Project project;
    private final CompileContext state;
    private final ResolveCommand resolve;
    private final Reporter reporter;

    public BuildCommand(Project project, CompileContext state, ResolveCommand resolve, Reporter reporter) {
        this.project = project;
        this.state = state;
        this.resolve = resolve;
        this.reporter = reporter;
    }

    public Result<Void> run() throws IOException {
        return main().map(resolution -> null);
    }

    /**
     * src/main/java compiled against {@code [dependencies]} only. Returns that dependencies-only
     * resolution, which is what anything shipped (package, run) puts on its classpath.
     */
    public Result<Resolver.Resolution> main() throws IOException {
        return state.main().get(() -> {
            Result<Resolver.Resolution> mainOnly = resolve.mainResolved();
            if (mainOnly instanceof Result.Err<?> err) {
                return err.castErr();
            }
            Path classes = project.classesDir();
            Result<Void> compiled;
            try (var timing = Timing.phase("compile")) {
                compiled = compile(project.mainSources(), classes, mainOnly.get().classpath(), "Compilation failed");
            }
            if (compiled instanceof Result.Err<?> err) {
                return err.castErr();
            }
            copyResources(project.mainResources(), classes);
            reporter.info("compile: " + project.relativize(classes));
            return mainOnly;
        });
    }

    /**
     * src/test/java, compiled after main against both scopes (main code and test code alike may
     * reference either). Returns the merged resolution, for the test JVM's classpath.
     */
    public Result<Resolver.Resolution> tests() throws IOException {
        Result<Resolver.Resolution> main = main();
        if (main instanceof Result.Err<?> err) {
            return err.castErr();
        }
        return state.tests().get(() -> {
            Result<Resolver.Resolution> merged = resolve.resolved();
            if (merged instanceof Result.Err<?> err) {
                return err.castErr();
            }
            Path testClasses = project.testClassesDir();
            List<Path> classpath = new ArrayList<>();
            classpath.add(project.classesDir());
            classpath.addAll(merged.get().classpath());
            Result<Void> compiled;
            try (var timing = Timing.phase("compile tests")) {
                compiled = compile(project.testSources(), testClasses, classpath, "Test compilation failed");
            }
            if (compiled instanceof Result.Err<?> err) {
                return err.castErr();
            }
            Files.createDirectories(testClasses);
            copyResources(project.testResources(), testClasses);
            reporter.info("compile: " + project.relativize(testClasses));
            return merged;
        });
    }

    /** javac's diagnostics go out either way (warnings too); a failed compile becomes {@code failureMessage}. */
    private Result<Void> compile(Path sources, Path output, List<Path> classpath, String failureMessage)
            throws IOException {
        Result<JavaCompilation.Output> result =
                JavaCompilation.compile(sources, output, classpath, project.manifest().javaVersion);
        if (result instanceof Result.Err<?> err) {
            return err.castErr();
        }
        result.get().diagnostics().forEach(reporter::diagnostic);
        return result.get().success() ? Result.ok() : Result.err(failureMessage);
    }

    private static void copyResources(Path resourcesDir, Path outputDir) throws IOException {
        if (!Files.isDirectory(resourcesDir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(resourcesDir)) {
            List<Path> files = walk.filter(Files::isRegularFile).toList();
            for (Path file : files) {
                Path dest = outputDir.resolve(resourcesDir.relativize(file));
                Files.createDirectories(dest.getParent());
                Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
