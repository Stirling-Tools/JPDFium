/**
 * Core API: open, render, edit, split, merge, and save PDF documents.
 *
 * <p>{@link stirling.software.jpdfium.PdfDocument} is the starting point. It
 * opens a PDF from a file or byte array and hands out its pages. Around it:
 * {@link stirling.software.jpdfium.PdfRenderer} rasterises pages, {@link
 * stirling.software.jpdfium.PdfPipeline} runs page-level work in streaming or
 * parallel mode, {@link stirling.software.jpdfium.PdfMerge} and {@link
 * stirling.software.jpdfium.PdfSplit} combine and divide documents, and {@link
 * stirling.software.jpdfium.SvgConverter} turns SVG into PDF.
 *
 * <p>A single JVM serialises PDFium on a global lock. When genuine parallelism
 * is needed, {@link stirling.software.jpdfium.PdfProcessPool} runs work in warm
 * worker processes instead.
 *
 * @see stirling.software.jpdfium.PdfDocument
 * @see stirling.software.jpdfium.PdfRenderer
 */
package stirling.software.jpdfium;
