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

    public static Path packageThinJar(Path classesDir, Path outputJar, String mainClass, List<Path> classpathJars)
            throws IOException {
        Files.createDirectories(outputJar.getParent());

        Manifest manifest = new Manifest();
        Attributes attrs = manifest.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (mainClass != null) {
            attrs.put(Attributes.Name.MAIN_CLASS, mainClass);
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
