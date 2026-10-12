package dev.kwenza.compiler;

import dev.kwenza.context.KwenzaException;
import dev.kwenza.context.Reporter;
import dev.kwenza.context.Timing;
import dev.kwenza.resolver.ResolveContext;
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
 * A single invocation's compilation state, memoized so a command chain (e.g.
 * {@code kwenza build run}) compiles exactly once, not once per command that needs it. Resolves
 * through the {@link ResolveContext} it's given, so callers never have to resolve first. One
 * instance per invocation, shared across every command in its chain.
 */
public final class CompileContext {

    private final Project project;
    private final ResolveContext resolve;
    private final Reporter reporter;

    private boolean compiled;
    private boolean testCompiled;

    public CompileContext(Project project, ResolveContext resolve, Reporter reporter) {
        this.project = project;
        this.resolve = resolve;
        this.reporter = reporter;
    }

    /**
     * Whatever was compiled before this point is gone now (a {@code clean} deleted it) - the next
     * ensureCompiled/ensureTestCompiled call in the chain must redo it, not reuse it.
     */
    public void invalidate() {
        compiled = false;
        testCompiled = false;
    }

    /**
     * Compiles {@code src/main/java} against the dependencies-only classpath, at most once per
     * invocation. Returns that dependencies-only resolution, which is what anything shipped
     * (package, run) puts on its classpath.
     */
    public Resolver.Resolution ensureCompiled() throws Exception {
        Resolver.Resolution mainOnly = resolve.ensureMainResolved();
        if (compiled) {
            return mainOnly;
        }
        Path mainClasses = project.classesDir();
        JavaCompilation.Result result = JavaCompilation.compile(
                project.mainSources(), mainClasses, mainOnly.classpath(), project.manifest().javaVersion);
        Timing.mark("compiled");
        printDiagnostics(result.diagnostics);
        if (!result.success) {
            throw new KwenzaException("Compilation failed");
        }
        copyResources(project.mainResources(), mainClasses);
        reporter.info("compile: " + project.relativize(mainClasses));
        compiled = true;
        return mainOnly;
    }

    /**
     * Compiles {@code src/test/java} at most once per invocation, compiling main first. Needs both
     * scopes on the classpath (main code and test code alike may reference either), so it returns
     * the merged resolution, not the dependencies-only one.
     */
    public Resolver.Resolution ensureTestCompiled() throws Exception {
        ensureCompiled();
        Resolver.Resolution merged = resolve.ensureResolved();
        if (testCompiled) {
            return merged;
        }
        Path testClasses = project.testClassesDir();
        List<Path> testClasspath = new ArrayList<>();
        testClasspath.add(project.classesDir());
        testClasspath.addAll(merged.classpath());
        JavaCompilation.Result result = JavaCompilation.compile(
                project.testSources(), testClasses, testClasspath, project.manifest().javaVersion);
        Timing.mark("test-compiled");
        printDiagnostics(result.diagnostics);
        if (!result.success) {
            throw new KwenzaException("Test compilation failed");
        }
        Files.createDirectories(testClasses);
        copyResources(project.testResources(), testClasses);
        reporter.info("compile: " + project.relativize(testClasses));
        testCompiled = true;
        return merged;
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

    private static void printDiagnostics(List<String> diagnostics) {
        for (String d : diagnostics) {
            System.err.println(d);
        }
    }
}
