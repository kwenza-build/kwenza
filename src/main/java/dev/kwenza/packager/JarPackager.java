package dev.kwenza.packager;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/**
 * Builds a Maven/Gradle-style thin jar: project classes/resources only, with a
 * Class-Path manifest entry pointing at the resolved dependency jars in the local cache.
 */
public final class JarPackager {

    private JarPackager() {
    }

    /**
     * What a jar's MANIFEST.MF says about it, besides its Class-Path. {@code title}/{@code version}
     * become {@code Implementation-Title}/{@code Implementation-Version}, which is how a program
     * can find its own version at runtime ({@code Package.getImplementationVersion()}). Any field
     * may be null, and is then left out.
     */
    public record JarIdentity(String title, String version, String mainClass) {
    }

    public static Path packageThinJar(Path classesDir, Path outputJar, JarIdentity identity, List<Path> classpathJars)
            throws IOException {
        Files.createDirectories(outputJar.getParent());

        Manifest manifest = new Manifest();
        Attributes attrs = manifest.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (identity.title() != null) {
            attrs.put(Attributes.Name.IMPLEMENTATION_TITLE, identity.title());
        }
        if (identity.version() != null) {
            attrs.put(Attributes.Name.IMPLEMENTATION_VERSION, identity.version());
        }
        if (identity.mainClass() != null) {
            attrs.put(Attributes.Name.MAIN_CLASS, identity.mainClass());
        }
        if (!classpathJars.isEmpty()) {
            StringBuilder cp = new StringBuilder();
            for (Path jar : classpathJars) {
                if (cp.length() > 0) cp.append(' ');
                cp.append(jar.toUri());
            }
            attrs.put(Attributes.Name.CLASS_PATH, cp.toString());
        }

        try (OutputStream fileOut = Files.newOutputStream(outputJar);
             JarOutputStream jarOut = new JarOutputStream(fileOut, manifest)) {
            if (Files.isDirectory(classesDir)) {
                try (Stream<Path> walk = Files.walk(classesDir)) {
                    List<Path> files = walk.filter(Files::isRegularFile).toList();
                    for (Path file : files) {
                        String entryName = classesDir.relativize(file).toString().replace('\\', '/');
                        jarOut.putNextEntry(new JarEntry(entryName));
                        Files.copy(file, jarOut);
                        jarOut.closeEntry();
                    }
                }
            }
        }
        return outputJar;
    }
}
