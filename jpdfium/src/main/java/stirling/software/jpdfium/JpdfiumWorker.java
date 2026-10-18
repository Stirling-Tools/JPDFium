package stirling.software.jpdfium;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Warm worker entry for {@link PdfProcessPool}: a persistent JVM that runs {@link JpdfiumCli} jobs
 * off a line protocol. Public only so a forked JVM can launch it.
 */
public final class JpdfiumWorker {

    private JpdfiumWorker() {}

    public static void main(String[] args) throws Exception {
        // Keep the real stdout for the control protocol; send everything an
        // operation prints to stderr instead.
        PrintStream control = System.out;
        System.setOut(System.err);
        BufferedReader in =
                new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        control.println("READY");
        control.flush();

        while (true) {
            String header = in.readLine();
            if (header == null) {
                return; // parent closed the pipe
            }
            int count;
            try {
                count = Integer.parseInt(header.trim());
            } catch (NumberFormatException e) {
                control.println("ERR 2 malformed frame header");
                control.flush();
                continue;
            }
            if (count == 0) {
                return; // quit
            }
            if (count < 0) {
                control.println("ERR 2 malformed frame header");
                control.flush();
                continue;
            }
            String[] argv = new String[count];
            String decodeError = null;
            for (int i = 0; i < count; i++) {
                String line = in.readLine();
                if (line == null) {
                    return; // parent closed the pipe
                }
                try {
                    argv[i] = unescape(line);
                } catch (IOException e) {
                    // Keep consuming the rest of the frame so the stream stays in sync, then report
                    // the failure without exiting the worker.
                    decodeError = e.getMessage();
                }
            }
            if (decodeError != null) {
                control.println("ERR 2 " + decodeError);
                control.flush();
                continue;
            }
            try {
                int rc = JpdfiumCli.run(argv);
                if (Boolean.getBoolean("jpdfium.pool.debug")) {
                    System.err.println(
                            "WORKER pid="
                                    + ProcessHandle.current().pid()
                                    + " rc="
                                    + rc
                                    + " argv="
                                    + Arrays.toString(argv));
                }
                control.println("OK " + rc);
            } catch (VirtualMachineError e) {
                // Fatal: report so the parent sees a reply, then let the process die so the pool
                // replaces it. The JVM and native state cannot be trusted after this.
                control.println("ERR 70 " + oneLine(e));
                control.flush();
                throw e;
            } catch (Throwable t) {
                if (Boolean.getBoolean("jpdfium.pool.debug")) {
                    System.err.println("WORKER EXC " + oneLine(t));
                }
                control.println("ERR 70 " + oneLine(t));
            }
            control.flush();
        }
    }

    /** Escape a single argv token so it occupies exactly one line. */
    static String escape(String token) {
        StringBuilder sb = new StringBuilder(token.length() + 4);
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    static String unescape(String line) throws IOException {
        int n = line.length();
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            char c = line.charAt(i);
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (i + 1 >= n) {
                throw new IOException("dangling escape in worker frame");
            }
            char next = line.charAt(++i);
            switch (next) {
                case '\\' -> sb.append('\\');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                default -> throw new IOException("bad escape in worker frame: \\" + next);
            }
        }
        return sb.toString();
    }

    private static String oneLine(Throwable t) {
        String message = t.getMessage();
        String text = t.getClass().getSimpleName() + (message == null ? "" : ": " + message);
        return text.replace('\n', ' ').replace('\r', ' ');
    }
}
