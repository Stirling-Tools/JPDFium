package stirling.software.jpdfium.redact;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

// Stage profiler measuring text acquisition, prefilter search, and mutation.
final class RedactionPipelineProfiler {

    record PageTiming(
            int pageIndex,
            int charCount,
            boolean isDirty,
            int candidateCount,
            int patternsPassedToNative,
            int actualHits,
            long textAcquisitionNs,
            long prefilterSearchNs,
            long nativeMatchAndMutateNs,
            long auditNs
    ) {}

    private long setupNs;
    private long textAcquisitionTotalNs;
    private long prefilterSearchTotalNs;
    private long nativeMatchAndMutateTotalNs;
    private long auditTotalNs;
    private long outputNs;
    private long totalWallNs;
    private long memoryAllocatedBytes;

    private final List<PageTiming> pageTimings = new ArrayList<>();

    void recordSetup(long ns) {
        this.setupNs = ns;
    }

    void addPageTiming(PageTiming timing) {
        pageTimings.add(timing);
        textAcquisitionTotalNs += timing.textAcquisitionNs();
        prefilterSearchTotalNs += timing.prefilterSearchNs();
        nativeMatchAndMutateTotalNs += timing.nativeMatchAndMutateNs();
        auditTotalNs += timing.auditNs();
    }

    void recordOutput(long ns) {
        this.outputNs = ns;
    }

    void recordTotalWall(long ns) {
        this.totalWallNs = ns;
    }

    void recordMemory(long bytes) {
        this.memoryAllocatedBytes = bytes;
    }

    long setupNs() {
        return setupNs;
    }

    long textAcquisitionTotalNs() {
        return textAcquisitionTotalNs;
    }

    long prefilterSearchTotalNs() {
        return prefilterSearchTotalNs;
    }

    long nativeMatchAndMutateTotalNs() {
        return nativeMatchAndMutateTotalNs;
    }

    long auditTotalNs() {
        return auditTotalNs;
    }

    long outputNs() {
        return outputNs;
    }

    long totalWallNs() {
        return totalWallNs;
    }

    long memoryAllocatedBytes() {
        return memoryAllocatedBytes;
    }

    List<PageTiming> pageTimings() {
        return Collections.unmodifiableList(pageTimings);
    }

    int cleanPageCount() {
        int count = 0;
        for (PageTiming pt : pageTimings) {
            if (!pt.isDirty()) {
                count++;
            }
        }
        return count;
    }

    int dirtyPageCount() {
        int count = 0;
        for (PageTiming pt : pageTimings) {
            if (pt.isDirty()) {
                count++;
            }
        }
        return count;
    }

    private static double percentile(List<Long> values, double p) {
        if (values.isEmpty()) {
            return 0.0;
        }
        int idx = (int) Math.ceil((p / 100.0) * values.size()) - 1;
        idx = Math.max(0, Math.min(idx, values.size() - 1));
        return values.get(idx) / 1_000_000.0;
    }

    String generateReport() {
        List<Long> textAcqList = new ArrayList<>();
        List<Long> prefilterList = new ArrayList<>();
        List<Long> mutateList = new ArrayList<>();

        for (PageTiming pt : pageTimings) {
            textAcqList.add(pt.textAcquisitionNs());
            prefilterList.add(pt.prefilterSearchNs());
            if (pt.isDirty()) {
                mutateList.add(pt.nativeMatchAndMutateNs());
            }
        }
        Collections.sort(textAcqList);
        Collections.sort(prefilterList);
        Collections.sort(mutateList);

        StringBuilder sb = new StringBuilder(512);
        sb.append("=== REDACTION PIPELINE STAGE DECOMPOSITION ===\n");
        sb.append(String.format(Locale.ROOT, "Total Wall Time:          %.2f ms%n", totalWallNs / 1_000_000.0));
        sb.append(String.format(Locale.ROOT, "  1. Setup:               %.2f ms%n", setupNs / 1_000_000.0));
        sb.append(String.format(Locale.ROOT, "  2. Text Acquisition:    %.2f ms (all %d pages)%n",
                textAcquisitionTotalNs / 1_000_000.0, pageTimings.size()));
        sb.append(String.format(Locale.ROOT, "  3. Prefilter Search:    %.2f ms%n", prefilterSearchTotalNs / 1_000_000.0));
        sb.append(String.format(Locale.ROOT, "  4. Native Mutation:     %.2f ms (%d dirty pages)%n",
                nativeMatchAndMutateTotalNs / 1_000_000.0, dirtyPageCount()));
        sb.append(String.format(Locale.ROOT, "  5. Output:              %.2f ms%n", outputNs / 1_000_000.0));
        sb.append(String.format(Locale.ROOT, "Pages: %d total (%d clean, %d dirty)%n",
                pageTimings.size(), cleanPageCount(), dirtyPageCount()));
        sb.append(String.format(Locale.ROOT, "Per-Page Text Acq (ms):   p50=%.3f, p95=%.3f, p99=%.3f%n",
                percentile(textAcqList, 50), percentile(textAcqList, 95), percentile(textAcqList, 99)));
        sb.append(String.format(Locale.ROOT, "Per-Page Prefilter (ms):  p50=%.3f, p95=%.3f, p99=%.3f%n",
                percentile(prefilterList, 50), percentile(prefilterList, 95), percentile(prefilterList, 99)));
        if (!mutateList.isEmpty()) {
            sb.append(String.format(Locale.ROOT, "Dirty-Page Mutate (ms):   p50=%.3f, p95=%.3f, p99=%.3f%n",
                    percentile(mutateList, 50), percentile(mutateList, 95), percentile(mutateList, 99)));
        }
        if (memoryAllocatedBytes > 0) {
            sb.append(String.format(Locale.ROOT, "Memory Allocated:         %.2f MB%n",
                    memoryAllocatedBytes / (1024.0 * 1024.0)));
        }
        sb.append("==============================================");
        return sb.toString();
    }
}
