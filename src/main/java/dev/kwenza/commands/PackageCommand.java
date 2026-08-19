package dev.kwenza.commands;

import dev.kwenza.packager.JarPackager;
import dev.kwenza.resolver.Resolver;

import java.nio.file.Path;

/** `kwenza package` - builds, then writes a thin jar with a Class-Path manifest entry. */
public final class PackageCommand {
    private PackageCommand() {
    }

    public static Path run(BuildState state) throws Exception {
        Resolver.Resolution resolution = state.ensureCompiled();
        Path classes = state.targetDir().resolve("classes");
        Path outputJar = state.targetDir().resolve(
                state.manifest().name + "-" + state.manifest().version + ".jar");
        JarPackager.packageThinJar(classes, outputJar, state.manifest().mainClass, resolution.classpath());
        Timing.mark("packaged");
        state.reporter().info("package: " + state.projectRoot().relativize(outputJar));
        return outputJar;
    }
}
