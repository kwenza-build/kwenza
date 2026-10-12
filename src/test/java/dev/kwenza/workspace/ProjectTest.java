package dev.kwenza.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectTest {

    @Test
    void aDirectoryWithNoManifestIsAFailureNamingTheDirectory(@TempDir Path root) throws IOException {
        String problem = Project.load(root).failure().problems().get(0);

        assertTrue(problem.contains("No kwenza.toml found in " + root), problem);
    }

    @Test
    void manifestProblemsSayWhichFileTheyAreIn(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("kwenza.toml"), "[package]\ngroup = \"com.example\"\nversion = \"1\"\n");

        String problem = Project.load(root).failure().problems().get(0);

        assertEquals("kwenza.toml: [package] is missing required field 'name'", problem);
    }

    @Test
    void layoutPathsFollowTheManifestsOutputDir(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("kwenza.toml"), """
                [package]
                name = "app"
                group = "com.example"
                version = "1.0"
                output-dir = "out"
                """);

        Project project = Project.load(root).get();

        assertEquals(root.resolve("out/classes"), project.classesDir());
        assertEquals(root.resolve("out/app-1.0.jar"), project.jarFile());
    }
}
