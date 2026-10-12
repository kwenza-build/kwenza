package dev.kwenza.manifest;

import dev.kwenza.context.Result;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KwenzaManifestTest {

    private static final String MINIMAL = """
            [package]
            name = "my-app"
            group = "com.example"
            version = "0.1.0"
            """;

    @Test
    void requiredFieldsAreReadFromThePackageTable() {
        KwenzaManifest manifest = KwenzaManifest.parse("""
                [package]
                name = "my-app"
                group = "com.example"
                version = "0.1.0"
                """).get();

        assertEquals("my-app", manifest.name);
        assertEquals("com.example", manifest.group);
        assertEquals("0.1.0", manifest.version);
    }

    @Test
    void missingNameIsAnError() {
        String problem = problem(KwenzaManifest.parse("""
                        [package]
                        group = "com.example"
                        version = "0.1.0"
                        """));

        assertEquals("[package] is missing required field 'name'", problem);
    }

    @Test
    void optionalFieldsDefaultWhenOmitted() {
        KwenzaManifest manifest = KwenzaManifest.parse(MINIMAL).get();

        assertEquals("21", manifest.javaVersion);
        assertEquals("target", manifest.outputDir);
        assertNull(manifest.mainClass);
    }

    @Test
    void optionalFieldsAreReadWhenPresent() {
        KwenzaManifest manifest = KwenzaManifest.parse("""
                [package]
                name = "my-app"
                group = "com.example"
                version = "0.1.0"
                java-version = "17"
                main-class = "com.example.Main"
                output-dir = "build"
                """).get();

        assertEquals("17", manifest.javaVersion);
        assertEquals("com.example.Main", manifest.mainClass);
        assertEquals("build", manifest.outputDir);
    }

    @Test
    void missingPackageTableIsAnError() {
        assertFalse(KwenzaManifest.parse("name = \"kwenza\"").isOk());
    }

    @Test
    void dependenciesAndDevDependenciesAreParsedSeparately() {
        KwenzaManifest manifest = KwenzaManifest.parse("""
                [package]
                name = "my-app"
                group = "com.example"
                version = "0.1.0"

                [dependencies]
                guava = { module = "com.google.guava:guava", version = "33.2.1-jre" }

                [dev-dependencies]
                mockito = { module = "org.mockito:mockito-core", version = "5.23.0" }
                """).get();

        assertEquals(1, manifest.dependencies.size());
        assertEquals(1, manifest.devDependencies.size());
        Dependency guava = manifest.dependencies.get("guava");
        assertEquals("com.google.guava", guava.group);
        assertEquals("guava", guava.artifact);
        assertEquals("33.2.1-jre", guava.version);
        assertEquals("org.mockito:mockito-core:5.23.0", manifest.devDependencies.get("mockito").gav());
    }

    @Test
    void aDependencyModuleMustHaveExactlyOneColon() {
        String problem = problem(KwenzaManifest.parse("""
                [package]
                name = "my-app"
                group = "com.example"
                version = "0.1.0"

                [dependencies]
                bad = { module = "com.google.guava", version = "33.2.1-jre" }
                """));

        assertTrue(problem.contains("group:artifact"), "unexpected message: " + problem);
    }

    @Test
    void versionRefResolvesAgainstTheVersionsTable() {
        KwenzaManifest manifest = KwenzaManifest.parse("""
                [package]
                name = "my-app"
                group = "com.example"
                version = "0.1.0"

                [versions]
                guava = "33.2.1-jre"

                [dependencies]
                guava = { module = "com.google.guava:guava", version.ref = "guava" }
                """).get();

        assertEquals("33.2.1-jre", manifest.dependencies.get("guava").version);
    }

    @Test
    void versionRefPointingAtAMissingKeyIsAnError() {
        String problem = problem(KwenzaManifest.parse("""
                [package]
                name = "my-app"
                group = "com.example"
                version = "0.1.0"

                [dependencies]
                guava = { module = "com.google.guava:guava", version.ref = "nope" }
                """));

        assertTrue(problem.contains("nope"), "unexpected message: " + problem);
    }

    /** The single problem a manifest that shouldn't parse fails with. */
    private static String problem(Result<KwenzaManifest> result) {
        assertEquals(1, result.failure().problems().size(), result.failure().problems().toString());
        return result.failure().problems().get(0);
    }
}
