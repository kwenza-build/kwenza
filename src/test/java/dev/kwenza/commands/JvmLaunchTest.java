package dev.kwenza.commands;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pure: what gets printed and what gets run, without starting a JVM. */
class JvmLaunchTest {
    private static final Path ROOT = Path.of("/work/app");
    private static final Path CLASSES = ROOT.resolve("target/classes");
    private static final Path TEST_CLASSES = ROOT.resolve("target/test-classes");

    private static List<Path> jars(int count) {
        return java.util.stream.IntStream.range(0, count).mapToObj(i -> Path.of("/m2/lib" + i + ".jar")).toList();
    }

    @Test
    void statusLineSummarisesTheDependencyJarsByCount() {
        assertEquals("run: java -cp target/classes com.example.Main",
                JvmLaunch.of("run", "com.example.Main", List.of(CLASSES), jars(0)).statusLine(ROOT));
        assertEquals("run: java -cp target/classes (+1 dependency jar) com.example.Main",
                JvmLaunch.of("run", "com.example.Main", List.of(CLASSES), jars(1)).statusLine(ROOT));
        assertEquals("run: java -cp target/classes (+7 dependency jars) com.example.Main",
                JvmLaunch.of("run", "com.example.Main", List.of(CLASSES), jars(7)).statusLine(ROOT));
    }

    @Test
    void statusLineShowsSeveralDirsAndAShortNameForTheMainClass() {
        JvmLaunch launch = JvmLaunch.of("test", "org.junit.platform.console.ConsoleLauncher",
                        List.of(CLASSES, TEST_CLASSES), jars(9))
                .shownAs("ConsoleLauncher");

        assertEquals("test: java -cp target/classes:target/test-classes (+9 dependency jars) ConsoleLauncher",
                launch.statusLine(ROOT));
    }

    @Test
    void commandPutsJvmArgsBeforeTheClasspathAndProgramArgsAfterTheMainClass() {
        Path java = Path.of("/jdk/bin/java");
        JvmLaunch launch = JvmLaunch.of("test", "Main", List.of(CLASSES), jars(1))
                .withJvmArgs(List.of("-javaagent:/m2/agent.jar"))
                .withArgs(List.of("execute", "--disable-banner"));

        assertEquals(List.of("/jdk/bin/java", "-javaagent:/m2/agent.jar",
                        "-cp", CLASSES + File.pathSeparator + "/m2/lib0.jar",
                        "Main", "execute", "--disable-banner"),
                launch.command(java));
    }
}
