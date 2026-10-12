package dev.kwenza.commands;

import dev.kwenza.compiler.CompileContext;
import dev.kwenza.context.Reporter;
import dev.kwenza.context.Timing;
import dev.kwenza.manifest.KwenzaManifest;
import dev.kwenza.packager.JarPackager;
import dev.kwenza.resolver.Resolver;
import dev.kwenza.workspace.Project;

import java.nio.file.Path;

/** {@code kwenza package}: builds, then writes a thin jar with a Class-Path manifest entry. */
public final class PackageCommand {
    private final Project project;
    private final CompileContext compile;
    private final Reporter reporter;

    public PackageCommand(Project project, CompileContext compile, Reporter reporter) {
        this.project = project;
        this.compile = compile;
        this.reporter = reporter;
    }

    /** @return the jar it wrote */
    public Path run() throws Exception {
        Resolver.Resolution resolution = compile.ensureCompiled();
        Path outputJar = project.jarFile();
        KwenzaManifest manifest = project.manifest();
        JarPackager.packageThinJar(project.classesDir(), outputJar,
                new JarPackager.JarIdentity(manifest.name, manifest.version, manifest.mainClass),
                resolution.classpath());
        Timing.mark("packaged");
        reporter.info("package: " + project.relativize(outputJar));
        return outputJar;
    }
}
