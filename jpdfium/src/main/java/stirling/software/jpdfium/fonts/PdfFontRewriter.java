package stirling.software.jpdfium.fonts;

import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
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
import stirling.software.jpdfium.exception.PdfPasswordException;
import stirling.software.jpdfium.panama.FfmHelper;
import stirling.software.jpdfium.panama.JpdfiumH;
import stirling.software.jpdfium.panama.NativeLoader;
import stirling.software.jpdfium.panama.NativeRuntime;

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

    private static final int ERR_PASSWORD = -3;
    private static final int ERR_SIGNED = -13;
    private static final Pattern TRANSFORMS_ARRAY =
            Pattern.compile("\"transforms\":\\[(.*?)]");
    private static final Pattern QUOTED_STRING =
            Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

    private static final class NativeHandles {
        static final MethodHandle AUDIT_HANDLE;
        static final MethodHandle REWRITE_HANDLE;
        static final byte[] SRGB_ICC;
        static final byte[] CMYK_ICC;
        static final byte[] CMYK_ICC_V2;

        static {
            MethodHandle audit = null;
            MethodHandle rewrite = null;
            byte[] srgb = null;
            byte[] cmyk = null;
            byte[] cmykV2 = null;
            try {
                NativeLoader.ensureLoaded();
                Linker linker = Linker.nativeLinker();
                SymbolLookup lookup = SymbolLookup.loaderLookup();
                audit = lookup.find("jpdfium_qpdf_audit_fonts")
                        .map(addr -> linker.downcallHandle(
                                addr,
                                FunctionDescriptor.of(
                                        ValueLayout.JAVA_INT,
                                        ValueLayout.ADDRESS,
                                        ValueLayout.JAVA_LONG,
                                        ValueLayout.ADDRESS)))
                        .orElse(null);
                rewrite = lookup.find("jpdfium_qpdf_rewrite_fonts")
                        .map(addr -> linker.downcallHandle(
                                addr,
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
                                        ValueLayout.ADDRESS)))
                        .orElse(null);
                srgb = ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData();
                cmyk = PdfIccProfiles.compactCmyk();
                cmykV2 = PdfIccProfiles.compactCmykV2();
            } catch (Throwable t) {
                NativeRuntime.rethrowFatal(t);
            }
            AUDIT_HANDLE = audit;
            REWRITE_HANDLE = rewrite;
            SRGB_ICC = srgb;
            CMYK_ICC = cmyk;
            CMYK_ICC_V2 = cmykV2;
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
            byte[] srgb = NativeHandles.SRGB_ICC;
            byte[] cmyk = (flags & PdfFontRewriteOptions.FLAG_CONVERT_PDFA1B) != 0
                    ? NativeHandles.CMYK_ICC_V2
                    : NativeHandles.CMYK_ICC;
            MemorySegment iccSeg = (wantSrgb && srgb != null)
                    ? arena.allocateFrom(ValueLayout.JAVA_BYTE, srgb)
                    : MemorySegment.NULL;
            long iccLen = (wantSrgb && srgb != null) ? srgb.length : 0L;
            MemorySegment cmykSeg = (wantPdfA && cmyk != null)
                    ? arena.allocateFrom(ValueLayout.JAVA_BYTE, cmyk)
                    : MemorySegment.NULL;
            long cmykLen = (wantPdfA && cmyk != null) ? cmyk.length : 0L;
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
            if (rc == ERR_PASSWORD) {
                throw new PdfPasswordException("PDF requires a password");
            }
            if (rc == ERR_SIGNED) {
                throw new JPDFiumException(
                        "Document is digitally signed; refusing to rewrite unless stripSignatures is enabled");
            }
            if (rc != 0) {
                throw new JPDFiumException("Font rewrite failed with native status " + rc);
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
