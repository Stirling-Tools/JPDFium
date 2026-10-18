package stirling.software.jpdfium.redact.pii;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Loads /pii/sentence-corpus.tsv and the ICU goldens beside it. */
final class SentenceCorpus {

    static final String CORPUS_RESOURCE = "/pii/sentence-corpus.tsv";
    static final String GOLDENS_RESOURCE = "/pii/sentence-goldens.tsv";

    /** One corpus case: a stable id plus the text it segments. */
    record Case(String id, String text) {
        @Override
        public String toString() {
            return id;
        }
    }

    private SentenceCorpus() {}

    /** The corpus in file order. */
    static List<Case> load() {
        List<Case> cases = new ArrayList<>();
        for (String line : dataLines(CORPUS_RESOURCE)) {
            int tab = line.indexOf('\t');
            if (tab < 0) throw new IllegalStateException("corpus line without tab: " + line);
            cases.add(new Case(line.substring(0, tab), unescape(line.substring(tab + 1))));
        }
        if (cases.isEmpty()) throw new IllegalStateException("empty corpus");
        return cases;
    }

    /** ICU boundaries (UTF-16 code units, starting at 0 and ending at length) per case id. */
    static Map<String, List<Integer>> loadGoldens() {
        Map<String, List<Integer>> goldens = new LinkedHashMap<>();
        for (String line : dataLines(GOLDENS_RESOURCE)) {
            String[] parts = line.split("\t");
            List<Integer> boundaries = new ArrayList<>(parts.length - 1);
            for (int i = 1; i < parts.length; i++) {
                boundaries.add(Integer.parseInt(parts[i]));
            }
            goldens.put(parts[0], List.copyOf(boundaries));
        }
        if (goldens.isEmpty()) throw new IllegalStateException("empty golden file");
        return goldens;
    }

    /** Golden boundaries as ranges, mirroring what {@code splitSentences} returns. */
    static List<EntityRedactor.Sentence> ranges(List<Integer> boundaries) {
        List<EntityRedactor.Sentence> ranges = new ArrayList<>(boundaries.size() - 1);
        for (int i = 1; i < boundaries.size(); i++) {
            ranges.add(new EntityRedactor.Sentence(boundaries.get(i - 1), boundaries.get(i)));
        }
        return ranges;
    }

    private static List<String> dataLines(String resource) {
        List<String> lines = new ArrayList<>();
        try (InputStream in = SentenceCorpus.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("missing test resource " + resource);
            try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    lines.add(line);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + resource, e);
        }
        return lines;
    }

    static String unescape(String in) {
        StringBuilder out = new StringBuilder(in.length());
        for (int i = 0; i < in.length(); i++) {
            char c = in.charAt(i);
            if (c != '\\') {
                out.append(c);
                continue;
            }
            char escape = in.charAt(++i);
            switch (escape) {
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case '\\' -> out.append('\\');
                case 'u' -> {
                    out.append((char) Integer.parseInt(in.substring(i + 1, i + 5), 16));
                    i += 4;
                }
                default -> throw new IllegalArgumentException("unknown escape \\" + escape);
            }
        }
        return out.toString();
    }
}
