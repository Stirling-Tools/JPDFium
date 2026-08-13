package stirling.software.jpdfium.doc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves external CLI tools to an absolute executable path before launching.
 *
 * <p>Launching a command by bare name (e.g. {@code "qpdf"}) makes the JVM search
 * {@code PATH} at execution time, and on some platforms the current directory is
 * implicitly included - an attacker who can plant a file in the working directory
 * could shadow the real tool. Resolving the tool up front to an absolute path and
 * rejecting relative matches removes that ambiguity (CWE-426).
 */
final class ExternalCommand {

    private ExternalCommand() {}

    /**
     * Resolve an executable by name using {@code PATH}, returning the absolute
     * path of the first candidate that exists and is executable. Relative and
     * empty {@code PATH} entries are skipped so the current directory never
     * participates in the search.
     *
     * @return absolute path to the executable, or {@code null} if not found
     */
    static Path resolve(String command) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isEmpty()) return null;

        String[] dirs = pathEnv.split(java.util.regex.Pattern.quote(System.getProperty("path.separator")));
        for (String dir : dirs) {
            if (dir == null || dir.isBlank()) continue;
            Path candidate = Path.of(dir).resolve(command);
            if (candidate.isAbsolute() && Files.isRegularFile(candidate)
                    && Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Build the full command line for launching an external tool, resolving the
     * tool to an absolute path.
     *
     * @param command the bare executable name (e.g. {@code "qpdf"})
     * @param args    the arguments to pass to the tool
     * @return command line for {@link ProcessBuilder}, or {@code null} if the
     *         tool cannot be resolved on {@code PATH}
     */
    static List<String> commandLine(String command, String... args) {
        Path exe = resolve(command);
        if (exe == null) return null;
        List<String> cmd = new ArrayList<>(args.length + 1);
        cmd.add(exe.toAbsolutePath().toString());
        java.util.Collections.addAll(cmd, args);
        return cmd;
    }

    /** Same as {@link #commandLine} but tolerant of {@link IOException} callers. */
    static ProcessBuilder processBuilder(String command, String... args) throws IOException {
        List<String> cmd = commandLine(command, args);
        if (cmd == null) {
            throw new IOException(command + " not found on PATH");
        }
        return new ProcessBuilder(cmd);
    }
}
