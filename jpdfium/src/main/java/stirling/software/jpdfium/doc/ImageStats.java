package stirling.software.jpdfium.doc;

import java.util.Map;

/**
 * Summary statistics of images in a PDF document.
 */
public record ImageStats(
        int totalImages,
        long totalRawBytes,
        Map<String, Integer> formatBreakdown
) {}
