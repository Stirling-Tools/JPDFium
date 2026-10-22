package stirling.software.jpdfium.doc;

import java.io.IOException;
import java.io.InputStream;

import stirling.software.jpdfium.exception.JPDFiumException;

/** Optional ICC profiles bundled with JPDFium for PDF/X output intents. */
public final class PdfIccProfiles {

    /** Human-readable identifier for the bundled generic fallback profile. */
    public static final String COMPACT_CMYK_CONDITION = "JPDFium Compact CMYK";

    private static final String COMPACT_CMYK_RESOURCE = "/icc/JPDFiumCompactCmyk.icc";

    private PdfIccProfiles() {}

    /**
     * Bundled 4-component CMYK output profile for PDF/X output intents.
     *
     * <p>This is a small generic fallback, not a certified printing-condition
     * profile. The returned array is a copy and can be modified by the caller.
     */
    public static byte[] compactCmyk() {
        return Holder.COMPACT_CMYK.clone();
    }

    private static byte[] load(String resource) {
        try (InputStream in = PdfIccProfiles.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new JPDFiumException("Bundled ICC profile is missing: " + resource);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new JPDFiumException("Failed to read bundled ICC profile: " + resource, e);
        }
    }

    private static final class Holder {
        private static final byte[] COMPACT_CMYK = load(COMPACT_CMYK_RESOURCE);
    }
}
