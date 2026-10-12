package dev.kwenza.commands;

import dev.kwenza.context.Reporter;
import dev.kwenza.context.Result;
import dev.kwenza.context.Timing;
import dev.kwenza.manifest.KwenzaManifest;
import dev.kwenza.packager.JarPackager;
import dev.kwenza.resolver.Resolver;
import dev.kwenza.workspace.Project;

import java.io.IOException;
import java.nio.file.Path;

/** {@code kwenza package}: builds, then writes a thin jar with a Class-Path manifest entry. */
public final class PackageCommand {
    private final Project project;
    private final BuildCommand build;
    private final Reporter reporter;

    public PackageCommand(Project project, BuildCommand build, Reporter reporter) {
        this.project = project;
        this.build = build;
        this.reporter = reporter;
    }

    /** @return the jar it wrote */
    public Result<Path> run() throws IOException {
        Result<Resolver.Resolution> compiled = build.main();
        if (compiled instanceof Result.Err<?> err) {
            return err.castErr();
        }
        Resolver.Resolution resolution = compiled.get();
        Path outputJar = project.jarFile();
        KwenzaManifest manifest = project.manifest();
        JarPackager.packageThinJar(project.classesDir(), outputJar,
                new JarPackager.JarIdentity(manifest.name, manifest.version, manifest.mainClass),
                resolution.classpath());
        Timing.mark("packaged");
        reporter.info("package: " + project.relativize(outputJar));
        return Result.ok(outputJar);
    }
}
