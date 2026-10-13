package stirling.software.jpdfium.doc;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.panama.NativeRuntime;
import stirling.software.jpdfium.panama.QpdfLib;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PdfEncryptionCopyTest {
    private static final String USER = " reader password ";
    private static final String OWNER = " owner password ";
    @TempDir Path directory;

    @BeforeEach
    void requireRealBundle() {
        assumeTrue(NativeRuntime.isFull(), "Encryption correctness requires real natives");
        assertTrue(PdfSecurity.isCopyEncryptionSupported(), "Full bundles must expose the QPDFJob API");
    }

    private Path pdf(String name, boolean encrypted, int bits, boolean aes, String user, String owner, int pages)
            throws Exception {
        Path path = directory.resolve(name);
        try (var doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                var page = new PDPage();
                page.setRotation(90);
                doc.addPage(page);
            }
            doc.getDocumentInformation().setTitle(name);
            if (encrypted) {
                var permissions = new AccessPermission();
                permissions.setCanPrint(false);
                permissions.setCanExtractContent(false);
                permissions.setCanModifyAnnotations(false);
                var policy = new StandardProtectionPolicy(owner, user, permissions);
                policy.setEncryptionKeyLength(bits);
                policy.setPreferAES(aes);
                doc.protect(policy);
            }
            doc.save(path.toFile());
        }
        return path;
    }

    private Path source() throws Exception {
        return pdf("source.pdf", true, 256, true, USER, OWNER, 1);
    }

    private Path processed() throws Exception {
        return pdf("processed.pdf", false, 256, true, USER, OWNER, 3);
    }

    @ParameterizedTest
    @CsvSource({"40,false,false", "40,false,true", "128,false,false", "128,false,true",
            "128,true,false", "128,true,true", "256,true,false", "256,true,true"})
    void keepsBothPasswordsAndPermissionsUsingEitherCredential(int bits, boolean aes, boolean owner)
            throws Exception {
        Path source = pdf("source.pdf", true, bits, aes, USER, OWNER, 1);
        Path input = processed();
        byte[] sourceBytes = Files.readAllBytes(source);
        byte[] inputBytes = Files.readAllBytes(input);
        Path output = directory.resolve("protected.pdf");
        PdfSecurity.copyEncryption(input, output, source, owner ? OWNER : USER);

        assertThrows(InvalidPasswordException.class, () -> Loader.loadPDF(output.toFile()));
        assertThrows(InvalidPasswordException.class, () -> Loader.loadPDF(output.toFile(), "wrong"));
        try (var original = Loader.loadPDF(source.toFile(), USER);
             var reader = Loader.loadPDF(output.toFile(), USER);
             var administrator = Loader.loadPDF(output.toFile(), OWNER)) {
            assertTrue(reader.isEncrypted());
            assertFalse(reader.getCurrentAccessPermission().isOwnerPermission());
            assertFalse(reader.getCurrentAccessPermission().canPrint());
            assertFalse(reader.getCurrentAccessPermission().canExtractContent());
            assertFalse(reader.getCurrentAccessPermission().canModifyAnnotations());
            assertTrue(administrator.getCurrentAccessPermission().isOwnerPermission());
            assertEquals(original.getEncryption().getPermissions(), reader.getEncryption().getPermissions());
            assertEquals(original.getEncryption().getRevision(), reader.getEncryption().getRevision());
            assertEquals(bits, reader.getEncryption().getLength());
            assertArrayEquals(original.getEncryption().getOwnerKey(), reader.getEncryption().getOwnerKey());
            assertArrayEquals(original.getEncryption().getUserKey(), reader.getEncryption().getUserKey());
            assertEquals(3, reader.getNumberOfPages());
            assertEquals(90, reader.getPage(0).getRotation());
            assertEquals("processed.pdf", reader.getDocumentInformation().getTitle());
        }
        assertArrayEquals(sourceBytes, Files.readAllBytes(source));
        assertArrayEquals(inputBytes, Files.readAllBytes(input));
        assertNoStagingFiles();
    }

    @ParameterizedTest
    @CsvSource({"128,false", "128,true", "256,false", "256,true"})
    void preservesEscapedAndUnicodePasswordsAndPaths(int bits, boolean owner) throws Exception {
        String user = bits == 256 ? " café\"読者\" " : " café\n\"reader\" ";
        String admin = bits == 256 ? " 管理者\\\"owner\" " : " résumé\\\n\"owner\" ";
        Path source = pdf("保護 café.pdf", true, bits, true, user, admin, 1);
        Path output = directory.resolve("résultat 保護.pdf");
        PdfSecurity.copyEncryption(processed(), output, source, owner ? admin : user);
        try (var reader = Loader.loadPDF(output.toFile(), user);
             var administrator = Loader.loadPDF(output.toFile(), admin)) {
            assertFalse(reader.getCurrentAccessPermission().canPrint());
            assertTrue(administrator.getCurrentAccessPermission().isOwnerPermission());
        }
        assertNoStagingFiles();
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrongPassword", "plainSource", "invalidInput", "missingSource", "encryptedInput"})
    void failuresPreserveExistingDestination(String failure) throws Exception {
        Path source = source();
        Path input = processed();
        String password = USER;
        switch (failure) {
            case "wrongPassword" -> password = "wrong";
            case "plainSource" -> source = input;
            case "invalidInput" -> Files.writeString(input, "not a PDF");
            case "missingSource" -> source = directory.resolve("missing.pdf");
            case "encryptedInput" -> input = source;
            default -> throw new AssertionError(failure);
        }
        Path output = directory.resolve("existing.pdf");
        byte[] original = Files.readAllBytes(pdf("original.pdf", false, 256, true, USER, OWNER, 2));
        Files.write(output, original);
        Path actualInput = input;
        Path actualSource = source;
        String actualPassword = password;
        assertThrows(JPDFiumException.class,
                () -> PdfSecurity.copyEncryption(actualInput, output, actualSource, actualPassword));
        assertArrayEquals(original, Files.readAllBytes(output));
        assertNoStagingFiles();
    }

    @Test
    void failedCopyDoesNotCreateDestination() throws Exception {
        Path output = directory.resolve("absent.pdf");
        Path input = processed();
        Path source = source();
        assertThrows(JPDFiumException.class, () -> PdfSecurity.copyEncryption(input, output, source, "wrong"));
        assertFalse(Files.exists(output));
        assertNoStagingFiles();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void refusesOverwritingEitherInput(boolean useSource) throws Exception {
        Path source = source();
        Path input = processed();
        Path output = useSource ? source : input;
        byte[] original = Files.readAllBytes(output);
        assertThrows(JPDFiumException.class, () -> PdfSecurity.copyEncryption(input, output, source, USER));
        assertArrayEquals(original, Files.readAllBytes(output));
        assertNoStagingFiles();
    }

    @Test
    void refusesOutputAliasingSourceThroughAHardLink() throws Exception {
        Path source = source();
        Path input = processed();
        Path output = directory.resolve("alias.pdf");
        Files.createLink(output, source);
        byte[] original = Files.readAllBytes(source);
        assertThrows(JPDFiumException.class, () -> PdfSecurity.copyEncryption(input, output, source, USER));
        assertArrayEquals(original, Files.readAllBytes(source));
        assertArrayEquals(original, Files.readAllBytes(output));
        assertNoStagingFiles();
    }

    @Test
    void rejectsNulInsteadOfTruncatingPassword() throws Exception {
        Path source = source();
        Path input = processed();
        Path output = directory.resolve("absent.pdf");
        assertThrows(IllegalArgumentException.class,
                () -> PdfSecurity.copyEncryption(input, output, source, USER + '\0' + "suffix"));
        assertFalse(Files.exists(output));
    }

    @Test
    void copiesOwnerProtectionWithAnEmptyOpeningPassword() throws Exception {
        Path source = pdf("source.pdf", true, 256, true, "", OWNER, 1);
        Path output = directory.resolve("protected.pdf");
        PdfSecurity.copyEncryption(processed(), output, source, "");
        try (var reader = Loader.loadPDF(output.toFile());
             var administrator = Loader.loadPDF(output.toFile(), OWNER)) {
            assertTrue(reader.isEncrypted());
            assertFalse(reader.getCurrentAccessPermission().canPrint());
            assertTrue(administrator.getCurrentAccessPermission().isOwnerPermission());
        }
    }

    @Test
    void concurrentJobsKeepTheirOwnPasswords() throws Exception {
        Path input = processed();
        var sources = new ArrayList<Path>();
        for (int i = 0; i < 4; i++) sources.add(pdf("source-" + i + ".pdf", true, 256, true, USER + i, OWNER + i, 1));
        try (var executor = Executors.newFixedThreadPool(4)) {
            var jobs = new ArrayList<Future<?>>();
            for (int i = 0; i < sources.size(); i++) {
                int index = i;
                jobs.add(executor.submit(() -> {
                    Path output = directory.resolve("protected-" + index + ".pdf");
                    PdfSecurity.copyEncryption(input, output, sources.get(index), USER + index);
                    try (var reader = Loader.loadPDF(output.toFile(), USER + index);
                         var administrator = Loader.loadPDF(output.toFile(), OWNER + index)) {
                        assertEquals(3, reader.getNumberOfPages());
                        assertTrue(administrator.getCurrentAccessPermission().isOwnerPermission());
                    }
                    return null;
                }));
            }
            for (Future<?> job : jobs) job.get();
        }
        assertNoStagingFiles();
    }

    @Test
    void interruptedAdmissionDoesNotPublish() throws Exception {
        Path input = processed();
        Path source = source();
        Path output = directory.resolve("absent.pdf");
        int oldBound = QpdfLib.setMaxConcurrency(1);
        try {
            Thread.currentThread().interrupt();
            assertThrows(JPDFiumException.class, () -> PdfSecurity.copyEncryption(input, output, source, USER));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
            QpdfLib.setMaxConcurrency(oldBound);
        }
        assertFalse(Files.exists(output));
        assertNoStagingFiles();
    }

    private void assertNoStagingFiles() throws Exception {
        try (var paths = Files.list(directory)) {
            assertTrue(paths.noneMatch(path -> path.getFileName().toString().startsWith(".jpdfium-save-")));
        }
    }
}
