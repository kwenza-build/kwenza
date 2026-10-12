package dev.kwenza.manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KwenzaLockTest {

    private static final String MANIFEST_TEXT = """
            [package]
            name = "my-app"
            group = "com.example"
            version = "0.1.0"

            [dependencies]
            guava = { module = "com.google.guava:guava", version = "33.2.1-jre" }
            """;

    @Test
    void hashDependenciesIsStableForTheSameManifest() {
        KwenzaManifest manifest = KwenzaManifest.parse(MANIFEST_TEXT).get();

        assertEquals(KwenzaLock.hashDependencies(manifest), KwenzaLock.hashDependencies(manifest));
    }

    @Test
    void hashDependenciesChangesWhenADependencyVersionChanges() {
        KwenzaManifest original = KwenzaManifest.parse(MANIFEST_TEXT).get();
        KwenzaManifest changed = KwenzaManifest.parse(MANIFEST_TEXT.replace("33.2.1-jre", "33.3.0")).get();

        assertFalse(KwenzaLock.hashDependencies(original).equals(KwenzaLock.hashDependencies(changed)));
    }

    @Test
    void hashDependenciesIsUnaffectedByDeclarationOrder() {
        KwenzaManifest manifest = KwenzaManifest.parse(MANIFEST_TEXT).get();
        KwenzaManifest reordered = KwenzaManifest.parse("""
                [package]
                name = "my-app"
                group = "com.example"
                version = "0.1.0"

                [dev-dependencies]
                junit = { module = "org.junit.jupiter:junit-jupiter", version = "6.1.3" }

                [dependencies]
                guava = { module = "com.google.guava:guava", version = "33.2.1-jre" }
                """).get();
        KwenzaManifest sameDeclaredInOrder = KwenzaManifest.parse("""
                [package]
                name = "my-app"
                group = "com.example"
                version = "0.1.0"

                [dependencies]
                guava = { module = "com.google.guava:guava", version = "33.2.1-jre" }

                [dev-dependencies]
                junit = { module = "org.junit.jupiter:junit-jupiter", version = "6.1.3" }
                """).get();

        assertEquals(KwenzaLock.hashDependencies(reordered), KwenzaLock.hashDependencies(sameDeclaredInOrder));
    }

    @Test
    void aFreshManifestWithNoDependenciesHasAStableHashToo() {
        KwenzaManifest manifest = KwenzaManifest.parse(MANIFEST_TEXT.replace(
                "[dependencies]\nguava = { module = \"com.google.guava:guava\", version = \"33.2.1-jre\" }", "")).get();

        assertEquals(KwenzaLock.hashDependencies(manifest), KwenzaLock.hashDependencies(manifest));
    }

    @Test
    void isFreshForIsTrueOnlyWhenTheHashMatchesTheCurrentManifest() {
        KwenzaManifest manifest = KwenzaManifest.parse(MANIFEST_TEXT).get();
        KwenzaLock fresh = new KwenzaLock(List.of(), KwenzaLock.hashDependencies(manifest));
        KwenzaLock stale = new KwenzaLock(List.of(), "not-the-real-hash");
        KwenzaLock withNoHashAtAll = KwenzaLock.empty();

        assertTrue(fresh.isFreshFor(manifest));
        assertFalse(stale.isFreshFor(manifest));
        assertFalse(withNoHashAtAll.isFreshFor(manifest));
    }

    @Test
    void saveThenLoadRoundTripsEveryEntryField(@TempDir Path tempDir) throws IOException {
        List<KwenzaLock.Entry> entries = new ArrayList<>();
        entries.add(new KwenzaLock.Entry("com.google.guava:guava", "33.2.1-jre", "abc123", List.of("direct")));
        entries.add(new KwenzaLock.Entry("com.google.guava:failureaccess", "1.0.2", "def456",
                List.of("com.google.guava:guava")));
        KwenzaLock lock = new KwenzaLock(entries, "the-manifest-hash");
        Path lockPath = tempDir.resolve("kwenza.lock");

        lock.save(lockPath);
        KwenzaLock loaded = KwenzaLock.load(lockPath).get();

        assertEquals("the-manifest-hash", loaded.manifestHash);
        assertEquals(2, loaded.entries.size());
        KwenzaLock.Entry guava = loaded.entries.get(0);
        assertEquals("33.2.1-jre", guava.version);
        assertEquals("abc123", guava.sha256);
        assertEquals(List.of("direct"), guava.requestedBy);
        assertEquals(List.of("com.google.guava:guava"), loaded.entries.get(1).requestedBy);
    }

    @Test
    void loadingAMissingLockFileReturnsAnEmptyLockRatherThanThrowing(@TempDir Path tempDir) throws IOException {
        KwenzaLock lock = KwenzaLock.load(tempDir.resolve("does-not-exist.lock")).get();

        assertTrue(lock.entries.isEmpty());
        assertFalse(lock.isFreshFor(KwenzaManifest.parse(MANIFEST_TEXT).get()));
    }

    @Test
    void savingQuotesAndEscapesSpecialCharactersInEntryValues(@TempDir Path tempDir) throws IOException {
        List<KwenzaLock.Entry> entries = List.of(
                new KwenzaLock.Entry("com.example:weird\"name", "1.0", "sha", List.of("direct")));
        KwenzaLock lock = new KwenzaLock(entries, "hash");
        Path lockPath = tempDir.resolve("kwenza.lock");

        lock.save(lockPath);
        KwenzaLock loaded = KwenzaLock.load(lockPath).get();

        assertEquals("com.example:weird\"name", loaded.entries.get(0).module);
    }

    @Test
    void aMalformedLockIsAFailureThatSaysHowToRecover(@TempDir Path tempDir) throws IOException {
        Path lockPath = tempDir.resolve("kwenza.lock");
        Files.writeString(lockPath, "[[resolved]]\nmodule = 42\n");

        String problem = KwenzaLock.load(lockPath).failure().problems().get(0);

        assertTrue(problem.contains("kwenza.lock"), problem);
        assertTrue(problem.contains("Delete it"), problem);
    }

    @Test
    void aLockThatIsNotValidTomlIsAFailureToo(@TempDir Path tempDir) throws IOException {
        Path lockPath = tempDir.resolve("kwenza.lock");
        Files.writeString(lockPath, "manifest-hash = \"unterminated\n");

        assertFalse(KwenzaLock.load(lockPath).isOk());
    }
}
