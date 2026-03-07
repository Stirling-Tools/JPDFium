package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.redact.RedactionSession;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SAMPLE 12 - Two-phase (Mark → Commit) redaction with font normalization.
 *
 * <p>Same word list as S06/S11, but uses the low-level {@link RedactionSession}
 * API that keeps the document handle open across multiple mark/commit cycles:
 * <ol>
 *   <li><b>Normalize</b> — Fix broken fonts so text extraction is reliable</li>
 *   <li><b>Mark</b> — Create REDACT annotations (zero content mutation)</li>
 *   <li><b>Review</b> — Query pending redactions before committing</li>
 *   <li><b>Commit</b> — Burn marks via Object Fission (content destroyed)</li>
 *   <li><b>Second cycle</b> — Mark + commit more words (no reload needed)</li>
 *   <li><b>Save</b> — Incremental save (only changed objects)</li>
 * </ol>
 *
 * <p><strong>VM Options required in IntelliJ:</strong>
 * {@code --enable-native-access=ALL-UNNAMED}
 */
public class S12_TwoPhaseRedact {

    /** First batch of words to redact. */
    private static final String[] BATCH_1 = {
            "Hello", "World", "Overview", "Dummy", "Redaction",
            "Introduction", "Bold", "10", "item", "Gradient",
            "Row", "brown", "fox", "Item"
    };

    /** Second batch of words (committed in a second cycle, no reload). */
    private static final String[] BATCH_2 = {
            "Size", "Languages", "Rot", "confidential", "custom",
            "Scale", "6789", "Consider", "Employ", "VM", "certificat"
    };

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();

        System.out.printf("S12_TwoPhaseRedact  |  %d PDF(s)  |  batch1=%d words  batch2=%d words%n",
                inputs.size(), BATCH_1.length, BATCH_2.length);

        for (int fi = 0; fi < inputs.size(); fi++) {
            Path input = inputs.get(fi);
            SampleBase.pdfHeader("S12_TwoPhaseRedact", input, fi + 1, inputs.size());
            Path output = SampleBase.out("two-phase-redact", input).resolve(input.getFileName());

            try (RedactionSession session = RedactionSession.open(input)) {
                // 1. Font normalization
                var fontResult = session.normalizeFonts();
                System.out.printf("  fonts: %d processed, %d toUnicode fixed, %d widths repaired%n",
                        fontResult.fontsProcessed(), fontResult.toUnicodeFixed(),
                        fontResult.widthsRepaired());

                // 2. Mark first batch + commit
                int marked1 = session.markWords(BATCH_1, 0xFF000000, 0f,
                        false, false, false);
                System.out.printf("  batch1: %d marks, pending=%d%n",
                        marked1, session.totalPendingRedactions());
                var result1 = session.commitAll();
                System.out.printf("  commit1: %d committed across %d page(s)%n",
                        result1.totalCommitted(), result1.pagesAffected());

                // 3. Mark second batch + commit (no reload!)
                int marked2 = session.markWords(BATCH_2, 0xFF000000, 0f,
                        false, false, false);
                System.out.printf("  batch2: %d marks%n", marked2);
                var result2 = session.commitAll();
                System.out.printf("  commit2: %d committed across %d page(s)%n",
                        result2.totalCommitted(), result2.pagesAffected());

                // 4. Save
                session.save(output);
            }

            produced.add(output);
        }

        SampleBase.done("S12_TwoPhaseRedact", produced.toArray(Path[]::new));
    }
}
