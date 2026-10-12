package dev.kwenza.compiler;

import dev.kwenza.context.Result;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** Wraps the JDK's in-process javax.tools.JavaCompiler over a source root -> output dir. */
public final class JavaCompilation {

    /** What javac did: whether it produced classes, and everything it printed. A failed compile is still an Output. */
    public record Output(boolean success, List<String> diagnostics) {
    }

    private JavaCompilation() {
    }

    /**
     * javac's output, or a failure when javac can't run at all: no compiler (a JRE, not a JDK), or
     * a java-version newer than the running JDK. Only file-system errors throw.
     */
    public static Result<Output> compile(Path sourceRoot, Path outputDir, List<Path> classpath, String releaseVersion)
            throws IOException {
        Result<Void> jdk = checkJdkCompatibility(releaseVersion);
        if (jdk instanceof Result.Err<?> err) {
            return err.castErr();
        }
        if (!Files.isDirectory(sourceRoot)) {
            return Result.ok(new Output(true, List.of()));
        }
        List<Path> sourceFiles;
        try (Stream<Path> walk = Files.walk(sourceRoot)) {
            sourceFiles = walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
        if (sourceFiles.isEmpty()) {
            return Result.ok(new Output(true, List.of()));
        }

        Files.createDirectories(outputDir);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return Result.err("No Java compiler available: kwenza is running on a JRE. Run it with a JDK instead.");
        }

        DiagnosticCollector<JavaFileObject> diagnosticsCollector = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager =
                     compiler.getStandardFileManager(diagnosticsCollector, Locale.getDefault(), StandardCharsets.UTF_8)) {
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outputDir.toFile()));
            if (!classpath.isEmpty()) {
                fileManager.setLocation(StandardLocation.CLASS_PATH, classpath.stream().map(Path::toFile).toList());
            }

            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromPaths(sourceFiles);

            List<String> options = new ArrayList<>();
            options.add("--release");
            options.add(releaseVersion);
            // Never discover annotation processors from the classpath: javac's own default differs
            // by JDK (21 runs them, 23+ doesn't), and they'd run inside kwenza's own JVM.
            options.add("-proc:none");

            StringWriter extraOut = new StringWriter();
            JavaCompiler.CompilationTask task = compiler.getTask(
                    new PrintWriter(extraOut), fileManager, diagnosticsCollector, options, null, units);
            boolean success = task.call();

            List<String> diagnostics = new ArrayList<>();
            for (Diagnostic<? extends JavaFileObject> d : diagnosticsCollector.getDiagnostics()) {
                diagnostics.add(formatDiagnostic(d));
            }
            String extra = extraOut.toString();
            if (!extra.isBlank()) {
                diagnostics.add(extra.trim());
            }
            return Result.ok(new Output(success, diagnostics));
        }
    }

    /** javac --release can never target a version newer than the JDK actually running it. */
    private static Result<Void> checkJdkCompatibility(String releaseVersion) {
        int requested;
        try {
            requested = Integer.parseInt(releaseVersion.trim());
        } catch (NumberFormatException e) {
            return Result.ok(); // not a plain integer - let javac itself report on the malformed value
        }
        int running = Runtime.version().feature();
        if (requested > running) {
            return Result.err(
                    "kwenza is running on JDK " + running + ", but [package] java-version = \"" + releaseVersion
                            + "\" requires JDK " + requested + " or newer. Either run kwenza with a JDK "
                            + requested + "+ install, or lower java-version in kwenza.toml to " + running
                            + " or below.");
        }
        return Result.ok();
    }

    private static String formatDiagnostic(Diagnostic<? extends JavaFileObject> d) {
        String source = d.getSource() != null ? d.getSource().getName() : "<unknown>";
        return String.format("%s:%d: %s: %s", source, d.getLineNumber(), d.getKind(), d.getMessage(Locale.getDefault()));
    }
}
