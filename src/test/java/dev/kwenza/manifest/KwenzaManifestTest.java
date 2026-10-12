package dev.kwenza.manifest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
                """);

        assertEquals("my-app", manifest.name);
        assertEquals("com.example", manifest.group);
        assertEquals("0.1.0", manifest.version);
    }

    @Test
    void missingNameIsAnError() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> KwenzaManifest.parse("""
                        [package]
                        group = "com.example"
                        version = "0.1.0"
                        """));

        assertEquals("[package] is missing required field 'name'", e.getMessage());
    }

    @Test
    void optionalFieldsDefaultWhenOmitted() {
        KwenzaManifest manifest = KwenzaManifest.parse(MINIMAL);

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
                """);

        assertEquals("17", manifest.javaVersion);
        assertEquals("com.example.Main", manifest.mainClass);
        assertEquals("build", manifest.outputDir);
    }

    @Test
    void missingPackageTableIsAnError() {
        assertThrows(IllegalArgumentException.class, () -> KwenzaManifest.parse("name = \"kwenza\""));
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
                """);

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
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> KwenzaManifest.parse("""
                [package]
                name = "my-app"
                group = "com.example"
                version = "0.1.0"

                [dependencies]
                bad = { module = "com.google.guava", version = "33.2.1-jre" }
                """));

        assertTrue(e.getMessage().contains("group:artifact"), "unexpected message: " + e.getMessage());
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
                """);

        assertEquals("33.2.1-jre", manifest.dependencies.get("guava").version);
    }

    @Test
    void versionRefPointingAtAMissingKeyIsAnError() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> KwenzaManifest.parse("""
                [package]
                name = "my-app"
                group = "com.example"
                version = "0.1.0"

                [dependencies]
                guava = { module = "com.google.guava:guava", version.ref = "nope" }
                """));

        assertTrue(e.getMessage().contains("nope"), "unexpected message: " + e.getMessage());
    }
}
