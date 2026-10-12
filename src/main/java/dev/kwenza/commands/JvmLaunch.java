package dev.kwenza.commands;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * One JVM to fork, as data: what goes on its classpath, which class it runs, and how kwenza
 * describes it on its status line. Pure, so the exact status line (which kwenza.dev's home page
 * shows, and CI diffs) and the exact command are tested without starting anything; {@link Jvm}
 * does the starting.
 *
 * @param label     the status line's step name: {@code run}, {@code test}
 * @param mainClass the class to run
 * @param shownAs   how the status line names it ({@code ConsoleLauncher} rather than its full name)
 * @param dirs      the project's own class directories, shown on the status line
 * @param jars      dependency jars, summarised on the status line as a count
 * @param jvmArgs   options for the JVM itself (agents, flags), before {@code -cp}
 * @param args      arguments for the program, after the main class
 */
record JvmLaunch(String label, String mainClass, String shownAs, List<Path> dirs, List<Path> jars,
                 List<String> jvmArgs, List<String> args) {

    static JvmLaunch of(String label, String mainClass, List<Path> dirs, List<Path> jars) {
        return new JvmLaunch(label, mainClass, mainClass, List.copyOf(dirs), List.copyOf(jars), List.of(), List.of());
    }

    JvmLaunch shownAs(String name) {
        return new JvmLaunch(label, mainClass, name, dirs, jars, jvmArgs, args);
    }

    JvmLaunch withJvmArgs(List<String> jvmArgs) {
        return new JvmLaunch(label, mainClass, shownAs, dirs, jars, List.copyOf(jvmArgs), args);
    }

    JvmLaunch withArgs(List<String> args) {
        return new JvmLaunch(label, mainClass, shownAs, dirs, jars, jvmArgs, List.copyOf(args));
    }

    /**
     * {@code run: java -cp target/classes (+7 dependency jars) com.example.Main}: dirs relative to
     * {@code root} and joined with {@code :} on every platform (it's a summary, not a command).
     */
    String statusLine(Path root) {
        List<String> shownDirs = new ArrayList<>();
        for (Path dir : dirs) {
            shownDirs.add(root.relativize(dir).toString());
        }
        int count = jars.size();
        String deps = count == 0 ? "" : " (+" + count + " dependency jar" + (count == 1 ? "" : "s") + ")";
        return label + ": java -cp " + String.join(":", shownDirs) + deps + " " + shownAs;
    }

    /** The full command: {@code java}, JVM args, {@code -cp} with every dir and jar, the main class, program args. */
    List<String> command(Path java) {
        List<String> classpath = new ArrayList<>();
        dirs.forEach(dir -> classpath.add(dir.toString()));
        jars.forEach(jar -> classpath.add(jar.toString()));
        List<String> command = new ArrayList<>();
        command.add(java.toString());
        command.addAll(jvmArgs);
        command.add("-cp");
        command.add(String.join(File.pathSeparator, classpath));
        command.add(mainClass);
        command.addAll(args);
        return command;
    }
}
