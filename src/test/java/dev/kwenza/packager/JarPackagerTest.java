package dev.kwenza.packager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JarPackagerTest {

    @TempDir
    Path tempDir;

    @Test
    void includesEveryFileUnderClassesDirAsAJarEntry() throws IOException {
        Path classesDir = tempDir.resolve("classes");
        writeFile(classesDir.resolve("com/example/Main.class"), "fake-bytecode");
        writeFile(classesDir.resolve("com/example/Helper.class"), "more-fake-bytecode");
        Path outputJar = tempDir.resolve("out/app-1.0.jar");

        JarPackager.packageThinJar(classesDir, outputJar, null, List.of());

        try (JarFile jar = new JarFile(outputJar.toFile())) {
            assertTrue(jar.getEntry("com/example/Main.class") != null);
            assertTrue(jar.getEntry("com/example/Helper.class") != null);
        }
    }

    @Test
    void setsTheMainClassManifestAttributeWhenProvided() throws IOException {
        Path classesDir = tempDir.resolve("classes");
        Files.createDirectories(classesDir);
        Path outputJar = tempDir.resolve("app.jar");

        JarPackager.packageThinJar(classesDir, outputJar, "com.example.Main", List.of());

        try (JarFile jar = new JarFile(outputJar.toFile())) {
            Manifest manifest = jar.getManifest();
            assertEquals("com.example.Main", manifest.getMainAttributes().getValue("Main-Class"));
        }
    }

    @Test
    void omitsTheMainClassAttributeWhenNoMainClassIsSet() throws IOException {
        Path classesDir = tempDir.resolve("classes");
        Files.createDirectories(classesDir);
        Path outputJar = tempDir.resolve("lib.jar");

        JarPackager.packageThinJar(classesDir, outputJar, null, List.of());

        try (JarFile jar = new JarFile(outputJar.toFile())) {
            assertNull(jar.getManifest().getMainAttributes().getValue("Main-Class"));
        }
    }

    @Test
    void classPathManifestEntryListsEveryJarAsAFileUri() throws IOException {
        Path classesDir = tempDir.resolve("classes");
        Files.createDirectories(classesDir);
        Path outputJar = tempDir.resolve("app.jar");
        Path dep1 = tempDir.resolve("guava.jar");
        Path dep2 = tempDir.resolve("failureaccess.jar");

        JarPackager.packageThinJar(classesDir, outputJar, null, List.of(dep1, dep2));

        try (JarFile jar = new JarFile(outputJar.toFile())) {
            String classPath = jar.getManifest().getMainAttributes().getValue("Class-Path");
            assertTrue(classPath.contains(dep1.toUri().toString()), classPath);
            assertTrue(classPath.contains(dep2.toUri().toString()), classPath);
        }
    }

    @Test
    void omitsTheClassPathAttributeEntirelyWhenThereAreNoDependencies() throws IOException {
        Path classesDir = tempDir.resolve("classes");
        Files.createDirectories(classesDir);
        Path outputJar = tempDir.resolve("app.jar");

        JarPackager.packageThinJar(classesDir, outputJar, null, List.of());

        try (JarFile jar = new JarFile(outputJar.toFile())) {
            assertNull(jar.getManifest().getMainAttributes().getValue("Class-Path"));
        }
    }

    @Test
    void aMissingClassesDirectoryProducesAnEmptyJarRatherThanFailing() throws IOException {
        Path outputJar = tempDir.resolve("empty.jar");

        Path result = JarPackager.packageThinJar(tempDir.resolve("no-such-dir"), outputJar, null, List.of());

        assertEquals(outputJar, result);
        assertTrue(Files.exists(outputJar));
    }

    private static void writeFile(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
