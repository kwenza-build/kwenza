package dev.kwenza.compiler;

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

    public static final class Result {
        public final boolean success;
        public final List<String> diagnostics;

        Result(boolean success, List<String> diagnostics) {
            this.success = success;
            this.diagnostics = diagnostics;
        }
    }

    private JavaCompilation() {
    }

    public static Result compile(Path sourceRoot, Path outputDir, List<Path> classpath, String releaseVersion)
            throws IOException {
        checkJdkCompatibility(releaseVersion);
        if (!Files.isDirectory(sourceRoot)) {
            return new Result(true, List.of());
        }
        List<Path> sourceFiles;
        try (Stream<Path> walk = Files.walk(sourceRoot)) {
            sourceFiles = walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
        if (sourceFiles.isEmpty()) {
            return new Result(true, List.of());
        }

        Files.createDirectories(outputDir);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("No system Java compiler available - run Kwenza with a JDK, not a JRE");
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
            return new Result(success, diagnostics);
        }
    }

    /** javac --release can never target a version newer than the JDK actually running it. */
    private static void checkJdkCompatibility(String releaseVersion) {
        int requested;
        try {
            requested = Integer.parseInt(releaseVersion.trim());
        } catch (NumberFormatException e) {
            return; // not a plain integer - let javac itself report on the malformed value
        }
        int running = Runtime.version().feature();
        if (requested > running) {
            throw new IncompatibleJdkException(
                    "kwenza is running on JDK " + running + ", but [package] java-version = \"" + releaseVersion
                            + "\" requires JDK " + requested + " or newer. Either run kwenza with a JDK "
                            + requested + "+ install, or lower java-version in kwenza.toml to " + running
                            + " or below.");
        }
    }

    private static String formatDiagnostic(Diagnostic<? extends JavaFileObject> d) {
        String source = d.getSource() != null ? d.getSource().getName() : "<unknown>";
        return String.format("%s:%d: %s: %s", source, d.getLineNumber(), d.getKind(), d.getMessage(Locale.getDefault()));
    }
}
