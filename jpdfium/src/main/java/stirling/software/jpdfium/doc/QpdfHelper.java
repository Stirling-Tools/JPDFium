package stirling.software.jpdfium.doc;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import stirling.software.jpdfium.exception.JPDFiumException;

/**
 * Helper for invoking the qpdf CLI tool for operations not available in PDFium.
 *
 * <p>Operations like linearization, object stream optimization, and encryption
 * require qpdf to be installed and on the system PATH.
 */
final class QpdfHelper {

    private QpdfHelper() {}

    /**
     * Check if qpdf is available on the system PATH.
     *
     * @return true if qpdf is found and responds to --version
     */
    static boolean isAvailable() {
        try {
            Process p = ExternalCommand.processBuilder("qpdf", "--version")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Run a qpdf command with the given arguments.
     *
     * @param args qpdf arguments (e.g. "--linearize", "input.pdf", "output.pdf")
     * @throws RuntimeException if qpdf is not available, fails, or times out
     */
    static void run(String... args) {
        List<String> command = ExternalCommand.commandLine("qpdf", args);
        if (command == null) {
            throw new JPDFiumException("qpdf not found on PATH. Install qpdf: " +
                    "apt install qpdf / brew install qpdf / choco install qpdf");
        }

        try {
            Process p = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            byte[] output = p.getInputStream().readAllBytes();
            boolean finished = p.waitFor(120, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                throw new JPDFiumException("qpdf timed out after 120 seconds");
            }
            if (p.exitValue() != 0 && p.exitValue() != 3 && p.exitValue() != 2) {
                // qpdf exit 2 = errors but output created (e.g., recoverable issues)
                // qpdf exit 3 = warnings (success with warnings)
                throw new JPDFiumException("qpdf failed (exit=" + p.exitValue() + "): "
                        + new String(output).trim());
            }
        } catch (IOException e) {
            throw new JPDFiumException("qpdf not found on PATH. Install qpdf: " +
                    "apt install qpdf / brew install qpdf / choco install qpdf", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JPDFiumException("qpdf interrupted", e);
        }
    }
}
