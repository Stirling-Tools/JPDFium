package stirling.software.jpdfium.fonts;

import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import stirling.software.jpdfium.doc.PdfIccProfiles;
import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.exception.PdfCorruptException;
import stirling.software.jpdfium.exception.PdfPasswordException;
import stirling.software.jpdfium.panama.FfmHelper;
import stirling.software.jpdfium.panama.JpdfiumH;
import stirling.software.jpdfium.panama.NativeLoader;
import stirling.software.jpdfium.panama.NativeRuntime;
import stirling.software.jpdfium.panama.Symbols;

/**
 * Font conformance audit, embedding/normalization, and PDF/A structure rewriter.
 *
 * <p>Backed by the native QPDF + FreeType bridge ({@code jpdfium_qpdf_audit_fonts}
 * and {@code jpdfium_qpdf_rewrite_fonts}). Handles encrypted PDFs with empty
 * user passwords, recursive font enumeration, donor embedding, FreeType width
 * reconciliation, SFNT cmap/(3,0) synthesis, blank-glyph .notdef repair, and
 * PDF/A-2b structure normalization.
 */
public final class PdfFontRewriter {

    private static final int ERR_INVALID = -1;
    private static final int ERR_PASSWORD = -3;
    private static final int ERR_SIGNED = -13;
    private static final Pattern TRANSFORMS_ARRAY =
            Pattern.compile("\"transforms\":\\[(.*?)]");
    private static final Pattern QUOTED_STRING =
            Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

    private static final class NativeHandles {
        static final MethodHandle AUDIT_HANDLE;
        static final MethodHandle REWRITE_HANDLE;
        static final MemorySegment SRGB_ICC_SEG;
        static final MemorySegment SRGB_ICC_V2_SEG;
        static final MemorySegment CMYK_ICC_SEG;
        static final MemorySegment CMYK_ICC_V2_SEG;

        static {
            MethodHandle audit = null;
            MethodHandle rewrite = null;
            MemorySegment srgbSeg = null;
            MemorySegment srgbV2Seg = null;
            MemorySegment cmykSeg = null;
            MemorySegment cmykV2Seg = null;
            try {
                NativeLoader.ensureLoaded();
                audit = Symbols.downcallOptionalUnguarded(
                        "jpdfium_qpdf_audit_fonts",
                        FunctionDescriptor.of(
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_LONG,
                                ValueLayout.ADDRESS));
                rewrite = Symbols.downcallOptionalUnguarded(
                        "jpdfium_qpdf_rewrite_fonts",
                        FunctionDescriptor.of(
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_LONG,
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_LONG,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_LONG,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS));
                Arena global = Arena.global();
                byte[] srgb = ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData();
                byte[] srgbV2 = PdfIccProfiles.srgbV2();
                byte[] cmyk = PdfIccProfiles.compactCmyk();
                byte[] cmykV2 = PdfIccProfiles.compactCmykV2();
                srgbSeg = global.allocateFrom(ValueLayout.JAVA_BYTE, srgb).asReadOnly();
                srgbV2Seg = global.allocateFrom(ValueLayout.JAVA_BYTE, srgbV2).asReadOnly();
                cmykSeg = global.allocateFrom(ValueLayout.JAVA_BYTE, cmyk).asReadOnly();
                cmykV2Seg = global.allocateFrom(ValueLayout.JAVA_BYTE, cmykV2).asReadOnly();
            } catch (Throwable t) {
                NativeRuntime.rethrowFatal(t);
            }
            AUDIT_HANDLE = audit;
            REWRITE_HANDLE = rewrite;
            SRGB_ICC_SEG = srgbSeg;
            SRGB_ICC_V2_SEG = srgbV2Seg;
            CMYK_ICC_SEG = cmykSeg;
            CMYK_ICC_V2_SEG = cmykV2Seg;
        }
    }

    private PdfFontRewriter() {}

    /** Returns {@code true} when the native font conformance rewriter is loaded. */
    public static boolean isAvailable() {
        return NativeHandles.AUDIT_HANDLE != null && NativeHandles.REWRITE_HANDLE != null;
    }

    /**
     * Recursively audits all fonts in the PDF across pages, Form XObjects,
     * Patterns, annotation appearance streams, and AcroForm /DR.
     *
     * @param pdf input PDF bytes
     * @return JSON array describing every enumerated font object
     */
    public static String auditJson(byte[] pdf) {
        if (pdf == null || pdf.length == 0) {
            throw new IllegalArgumentException("pdf must not be empty");
        }
        if (NativeHandles.AUDIT_HANDLE == null) {
            throw new JPDFiumException("Native jpdfium_qpdf_audit_fonts is not available");
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment inSeg = arena.allocateFrom(ValueLayout.JAVA_BYTE, pdf);
            MemorySegment jsonOut = arena.allocate(ValueLayout.ADDRESS);
            int rc = (int) NativeHandles.AUDIT_HANDLE.invokeExact(inSeg, (long) pdf.length, jsonOut);
            if (rc == ERR_INVALID) {
                throw new PdfCorruptException("Invalid/corrupt PDF - font rewrite input");
            }
            if (rc == ERR_PASSWORD) {
                throw new PdfPasswordException("PDF requires a password");
            }
            if (rc != 0) {
                throw new JPDFiumException("Font audit failed with native status " + rc);
            }
            MemorySegment strPtr = jsonOut.get(ValueLayout.ADDRESS, 0);
            if (strPtr.equals(MemorySegment.NULL)) return "[]";
            try {
                return FfmHelper.readNativeString(strPtr, StandardCharsets.UTF_8);
            } finally {
                JpdfiumH.jpdfium_free_string(strPtr);
            }
        } catch (JPDFiumException e) {
            throw e;
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            throw new JPDFiumException("Font audit failed", t);
        }
    }

    /**
     * Runs the font conformance and PDF/A rewriter.
     *
     * @param pdf     input PDF bytes
     * @param options rewrite options (null uses {@link PdfFontRewriteOptions#defaults()})
     * @return rewritten PDF bytes and structured transform report
     */
    public static FontRewriteResult rewrite(byte[] pdf, PdfFontRewriteOptions options) {
        if (pdf == null || pdf.length == 0) {
            throw new IllegalArgumentException("pdf must not be empty");
        }
        if (NativeHandles.REWRITE_HANDLE == null) {
            throw new JPDFiumException("Native jpdfium_qpdf_rewrite_fonts is not available");
        }
        PdfFontRewriteOptions opts = options == null ? PdfFontRewriteOptions.defaults() : options;
        return rewriteWithFlags(pdf, opts.toNativeFlags(), opts.donorFontDirs());
    }

    /**
     * Runs the native font conformance and structure rewriter with explicit native flags.
     */
    public static FontRewriteResult rewriteWithFlags(byte[] pdf, int flags, List<Path> donorFontDirs) {
        if (pdf == null || pdf.length == 0) {
            throw new IllegalArgumentException("pdf must not be empty");
        }
        if (NativeHandles.REWRITE_HANDLE == null) {
            throw new JPDFiumException("Native jpdfium_qpdf_rewrite_fonts is not available");
        }
        StringBuilder dirs = new StringBuilder();
        if (donorFontDirs != null) {
            for (Path p : donorFontDirs) {
                if (!dirs.isEmpty()) dirs.append('\n');
                dirs.append(p.toAbsolutePath());
            }
        }
        boolean wantPdfA = (flags & (PdfFontRewriteOptions.FLAG_CONVERT_PDFA1B
                | PdfFontRewriteOptions.FLAG_CONVERT_PDFA2B
                | PdfFontRewriteOptions.FLAG_CONVERT_PDFA3B)) != 0;
        boolean wantSrgb = wantPdfA || (flags & PdfFontRewriteOptions.FLAG_INJECT_DEFAULT_RGB) != 0;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment inSeg = arena.allocateFrom(ValueLayout.JAVA_BYTE, pdf);
            MemorySegment dirsSeg = dirs.isEmpty()
                    ? MemorySegment.NULL
                    : arena.allocateFrom(dirs.toString(), StandardCharsets.UTF_8);
            MemorySegment srgbSeg = (flags & PdfFontRewriteOptions.FLAG_CONVERT_PDFA1B) != 0
                    ? NativeHandles.SRGB_ICC_V2_SEG
                    : NativeHandles.SRGB_ICC_SEG;
            MemorySegment cmykProfileSeg = (flags & PdfFontRewriteOptions.FLAG_CONVERT_PDFA1B) == 0
                    ? NativeHandles.CMYK_ICC_SEG
                    : NativeHandles.CMYK_ICC_V2_SEG;
            MemorySegment iccSeg = (wantSrgb && srgbSeg != null)
                    ? srgbSeg
                    : MemorySegment.NULL;
            long iccLen = (wantSrgb && srgbSeg != null) ? srgbSeg.byteSize() : 0L;
            MemorySegment cmykSeg = (wantPdfA && cmykProfileSeg != null)
                    ? cmykProfileSeg
                    : MemorySegment.NULL;
            long cmykLen = (wantPdfA && cmykProfileSeg != null) ? cmykProfileSeg.byteSize() : 0L;
            MemorySegment outPtr = arena.allocate(ValueLayout.ADDRESS);
            MemorySegment outLen = arena.allocate(ValueLayout.JAVA_LONG);
            MemorySegment reportPtr = arena.allocate(ValueLayout.ADDRESS);

            int rc = (int) NativeHandles.REWRITE_HANDLE.invokeExact(
                    inSeg,
                    (long) pdf.length,
                    flags,
                    dirsSeg,
                    iccSeg,
                    iccLen,
                    cmykSeg,
                    cmykLen,
                    outPtr,
                    outLen,
                    reportPtr);
            if (rc == ERR_INVALID) {
                throw new PdfCorruptException("Invalid/corrupt PDF - font rewrite input");
            }
            if (rc == ERR_PASSWORD) {
                throw new PdfPasswordException("PDF requires a password");
            }
            if (rc == ERR_SIGNED) {
                throw new JPDFiumException(
                        "Document is digitally signed; refusing to rewrite unless stripSignatures is enabled",
                        ERR_SIGNED);
            }
            if (rc != 0) {
                throw new JPDFiumException("Font rewrite failed with native status " + rc, rc);
            }

            MemorySegment bufPtr = outPtr.get(ValueLayout.ADDRESS, 0);
            long len = outLen.get(ValueLayout.JAVA_LONG, 0);
            if (bufPtr.equals(MemorySegment.NULL) || len <= 0) {
                throw new JPDFiumException("Font rewrite returned an empty buffer");
            }
            byte[] outBytes;
            try {
                outBytes = bufPtr.reinterpret(len).toArray(ValueLayout.JAVA_BYTE);
            } finally {
                JpdfiumH.jpdfium_free_buffer(bufPtr);
            }

            String reportJson = "{}";
            MemorySegment repStr = reportPtr.get(ValueLayout.ADDRESS, 0);
            if (!repStr.equals(MemorySegment.NULL)) {
                try {
                    reportJson = FfmHelper.readNativeString(repStr, StandardCharsets.UTF_8);
                } finally {
                    JpdfiumH.jpdfium_free_string(repStr);
                }
            }
            List<String> transforms = parseTransforms(reportJson);
            return new FontRewriteResult(outBytes, reportJson, transforms, !transforms.isEmpty());
        } catch (JPDFiumException e) {
            throw e;
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            throw new JPDFiumException("Font rewrite failed", t);
        }
    }

    private static List<String> parseTransforms(String reportJson) {
        List<String> out = new ArrayList<>();
        Matcher am = TRANSFORMS_ARRAY.matcher(reportJson);
        if (am.find()) {
            Matcher sm = QUOTED_STRING.matcher(am.group(1));
            while (sm.find()) {
                out.add(sm.group(1));
            }
        }
        return out;
    }
}
