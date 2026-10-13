package stirling.software.jpdfium.doc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.panama.NativeRuntime;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PdfEncryptionCopyUnavailableTest {
    @TempDir Path directory;

    @Test
    void missingNativeSupportDoesNotCreateOutput() {
        assumeTrue(NativeRuntime.isStub(), "Requires the bundle without QPDFJob");
        assertFalse(PdfSecurity.isCopyEncryptionSupported());
        Path output = directory.resolve("output.pdf");
        assertThrows(JPDFiumException.class, () -> PdfSecurity.copyEncryption(
                directory.resolve("input.pdf"), output, directory.resolve("source.pdf"), "password"));
        assertFalse(Files.exists(output));
    }
}
