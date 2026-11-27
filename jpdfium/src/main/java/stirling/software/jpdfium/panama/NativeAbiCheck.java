package stirling.software.jpdfium.panama;

import java.lang.foreign.Linker;
import stirling.software.jpdfium.exception.JPDFiumException;

/**
 * Unguarded native bridge ABI verification used during library loading.
 *
 * <p>Kept strictly independent of {@link JpdfiumLib} and {@link NativeLoader} so
 * {@code NativeLoader.ensureLoaded()} can verify the ABI without triggering
 * {@code JpdfiumLib.<clinit>} and forming a class-initialization deadlock cycle
 * when multiple threads concurrently initialize PDFium and QPDF/font APIs.
 */
final class NativeAbiCheck {

    static final int EXPECTED_ABI_VERSION = 3;
    static final long EXPECTED_PTR_SIZE = 8;
    static final long EXPECTED_RECTF_SIZE = 16;
    static final long EXPECTED_RECTF_RIGHT_OFFSET = 8;
    static final long EXPECTED_FILEWRITE_SIZE = 16;

    private NativeAbiCheck() {}

    static int abiVersionUnguarded() {
        return JpdfiumH.jpdfium_abi_version();
    }

    static long abiQueryUnguarded(int query) {
        return JpdfiumH.jpdfium_abi_query(query);
    }

    static void verify(String platform) {
        checkAbiCompatible(platform, abiVersionUnguarded(), abiQueryUnguarded(0),
                abiQueryUnguarded(1), abiQueryUnguarded(2), abiQueryUnguarded(3),
                abiQueryUnguarded(4), abiQueryUnguarded(5), abiQueryUnguarded(6),
                abiQueryUnguarded(7), abiQueryUnguarded(8), abiQueryUnguarded(9),
                abiQueryUnguarded(10), abiQueryUnguarded(11));
    }

    static void checkAbiCompatible(String platform, int version, long ptrSize, long rectfSize,
            long rightOff) {
        checkAbiCompatible(platform, version, ptrSize, rectfSize, rightOff, abiQueryUnguarded(3),
                abiQueryUnguarded(4), abiQueryUnguarded(5), abiQueryUnguarded(6),
                abiQueryUnguarded(7), abiQueryUnguarded(8), abiQueryUnguarded(9),
                abiQueryUnguarded(10), abiQueryUnguarded(11));
    }

    static void checkAbiCompatible(String platform, int version, long ptrSize, long rectfSize,
            long rightOff, long ulongSize, long leftOff, long bottomOff, long topOff,
            long matrixSize, long fileWriteSize, long fileWriteVersion, long hasSkia,
            long hasQpdf) {
        if (version != EXPECTED_ABI_VERSION) {
            throw new JPDFiumException(
                    "Unsupported native bridge ABI version " + version + " (expected "
                            + EXPECTED_ABI_VERSION + " on " + platform + ")");
        }
        if (ptrSize != EXPECTED_PTR_SIZE) {
            throw new JPDFiumException("Unsupported native pointer width " + ptrSize + " (expected "
                    + EXPECTED_PTR_SIZE + " on " + platform + ")");
        }
        if (rectfSize != EXPECTED_RECTF_SIZE) {
            throw new JPDFiumException("Unsupported FS_RECTF size " + rectfSize + " (expected "
                    + EXPECTED_RECTF_SIZE + " on " + platform + ")");
        }
        if (rightOff != EXPECTED_RECTF_RIGHT_OFFSET) {
            throw new JPDFiumException("Unsupported FS_RECTF.right offset " + rightOff + " (expected "
                    + EXPECTED_RECTF_RIGHT_OFFSET + " on " + platform + ")");
        }
        long expectedUlong = Linker.nativeLinker().canonicalLayouts().get("long").byteSize();
        if (ulongSize != expectedUlong) {
            throw new JPDFiumException("Unsupported native unsigned long width " + ulongSize
                    + " (expected " + expectedUlong + " on " + platform + ")");
        }
        if (leftOff != 0) {
            throw new JPDFiumException("Unsupported FS_RECTF.left offset " + leftOff
                    + " (expected 0 on " + platform + ")");
        }
        if (topOff != 4) {
            throw new JPDFiumException("Unsupported FS_RECTF.top offset " + topOff
                    + " (expected 4 on " + platform + ")");
        }
        if (bottomOff != 12) {
            throw new JPDFiumException("Unsupported FS_RECTF.bottom offset " + bottomOff
                    + " (expected 12 on " + platform + ")");
        }
        if (matrixSize != 24) {
            throw new JPDFiumException("Unsupported FS_MATRIX size " + matrixSize
                    + " (expected 24 on " + platform + ")");
        }
        if (fileWriteVersion != 1) {
            throw new JPDFiumException("Unsupported FPDF_FILEWRITE version " + fileWriteVersion
                    + " (expected 1 per fpdf_save.h on " + platform + ")");
        }
        if (fileWriteSize != EXPECTED_FILEWRITE_SIZE) {
            throw new JPDFiumException("Unsupported FPDF_FILEWRITE size " + fileWriteSize
                    + " (expected " + EXPECTED_FILEWRITE_SIZE + " on " + platform + ")");
        }
        if (hasSkia < 0 || hasSkia > 1 || hasQpdf < 0 || hasQpdf > 1) {
            throw new JPDFiumException("Unsupported feature-identity probe hasSkia=" + hasSkia
                    + " hasQpdf=" + hasQpdf + " on " + platform
                    + " (bridge predates feature probes; rebuild natives)");
        }
    }
}
