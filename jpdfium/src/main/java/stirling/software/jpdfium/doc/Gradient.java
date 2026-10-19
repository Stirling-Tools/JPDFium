package stirling.software.jpdfium.doc;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A shading definition reachable from a page's resources, with colour stops already evaluated.
 *
 * <p>Coordinates are in PDF device space; the caller flips them into SVG space.
 */
final class Gradient {

    /** Shading type 2: colours vary along the axis {@code (x0,y0) -> (x1,y1)}. */
    static final int AXIAL = 2;

    /** Shading type 3: colours vary between two circles. */
    static final int RADIAL = 3;

    private static final int COORDS = 4;

    final int index;
    final int kind;
    final boolean extendStart;
    final boolean extendEnd;
    final float[] coords;
    final float[] quad;
    final float[] stops;

    private Gradient(int index, int kind, boolean extendStart, boolean extendEnd, float[] coords,
            float[] quad, float[] stops) {
        this.index = index;
        this.kind = kind;
        this.extendStart = extendStart;
        this.extendEnd = extendEnd;
        this.coords = coords;
        this.quad = quad;
        this.stops = stops;
    }

    int stopCount() {
        return stops.length / COORDS;
    }

    float minX() {
        return Math.min(Math.min(quad[0], quad[2]), Math.min(quad[4], quad[6]));
    }

    float minY() {
        return Math.min(Math.min(quad[1], quad[3]), Math.min(quad[5], quad[7]));
    }

    float maxX() {
        return Math.max(Math.max(quad[0], quad[2]), Math.max(quad[4], quad[6]));
    }

    float maxY() {
        return Math.max(Math.max(quad[1], quad[3]), Math.max(quad[5], quad[7]));
    }

    /** One painted shape recorded from the page content stream, in PDF device space. */
    static final class Paint {

        final float[] bbox;
        final int gradient;
        final String blend;

        Paint(float[] bbox, int gradient, String blend) {
            this.bbox = bbox;
            this.gradient = gradient;
            this.blend = blend;
        }

        float minX() {
            return bbox[0];
        }

        float minY() {
            return bbox[1];
        }

        float maxX() {
            return bbox[2];
        }

        float maxY() {
            return bbox[3];
        }
    }

    /** Everything the native extractor reports for one page. */
    static final class Records {

        final List<Gradient> gradients = new ArrayList<>();
        final List<Paint> paints = new ArrayList<>();
    }

    /**
     * Parse the compact payload from {@code jpdfium_page_gradients}. Bad records are skipped, so a
     * native change can never break page output.
     */
    static Records parse(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return new Records();
        }
        return parseText(new String(payload, StandardCharsets.UTF_8));
    }

    /**
     * Parse the multi-page payload from {@code jpdfium_page_gradients_file}, which marks each
     * section with {@code #PAGE <index>}. A payload without any marker yields an empty map.
     */
    static Map<Integer, Records> parseAll(byte[] payload) {
        Map<Integer, Records> pages = new LinkedHashMap<>();
        if (payload == null || payload.length == 0) {
            return pages;
        }
        String text = new String(payload, StandardCharsets.UTF_8);
        int index = Integer.MIN_VALUE;
        StringBuilder section = new StringBuilder();
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#PAGE ")) {
                int next = parseInt(trimmed.substring(6).trim(), Integer.MIN_VALUE);
                if (next == Integer.MIN_VALUE) {
                    continue;
                }
                if (index != Integer.MIN_VALUE) {
                    pages.put(index, parseText(section.toString()));
                }
                index = next;
                section.setLength(0);
            } else if (index != Integer.MIN_VALUE) {
                section.append(line).append('\n');
            }
        }
        if (index != Integer.MIN_VALUE) {
            pages.put(index, parseText(section.toString()));
        }
        return pages;
    }

    private static Records parseText(String text) {
        Records records = new Records();
        if (text.isEmpty()) {
            return records;
        }
        List<Gradient> list = records.gradients;
        int kind = 0;
        boolean extendStart = false;
        boolean extendEnd = false;
        float[] coords = null;
        float[] quad = null;
        float[] pending = new float[64];
        int pendingCount = 0;
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] tokens = trimmed.split("\\s+");
            if (tokens[0].equals("G") && tokens.length >= 19) {
                if (coords != null && quad != null && pendingCount >= 8) {
                    list.add(build(list.size(), kind, extendStart, extendEnd, coords, quad,
                            pending, pendingCount));
                }
                kind = parseInt(tokens[1], 0);
                extendStart = parseInt(tokens[2], 0) != 0;
                extendEnd = parseInt(tokens[3], 0) != 0;
                coords = new float[6];
                for (int i = 0; i < 6; i++) {
                    coords[i] = parseFloat(tokens[4 + i]);
                }
                quad = new float[8];
                for (int i = 0; i < 8; i++) {
                    quad[i] = parseFloat(tokens[10 + i]);
                }
                pending = new float[64];
                pendingCount = 0;
            } else if (tokens[0].equals("S") && tokens.length >= 4 && coords != null) {
                if (pendingCount + 4 > pending.length) {
                    pending = java.util.Arrays.copyOf(pending, pending.length * 2);
                }
                pending[pendingCount++] = parseFloat(tokens[1]);
                pending[pendingCount++] = parseFloat(tokens[2]);
                pending[pendingCount++] = parseFloat(tokens[3]);
                pending[pendingCount++] = parseFloat(tokens[4]);
            } else if (tokens[0].equals("P") && tokens.length >= 7) {
                float[] bbox = new float[4];
                for (int i = 0; i < 4; i++) {
                    bbox[i] = parseFloat(tokens[1 + i]);
                }
                records.paints.add(new Paint(bbox, parseInt(tokens[5], -1),
                        tokens[6].equals("-") ? null : tokens[6]));
            }
        }
        if (coords != null && quad != null && pendingCount >= 8) {
            list.add(build(list.size(), kind, extendStart, extendEnd, coords, quad, pending,
                    pendingCount));
        }
        return records;
    }

    private static Gradient build(int index, int kind, boolean extendStart, boolean extendEnd,
            float[] coords, float[] quad, float[] stops, int count) {
        return new Gradient(index, kind, extendStart, extendEnd, coords, quad,
                java.util.Arrays.copyOf(stops, count));
    }

    private static int parseInt(String token, int fallback) {
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static float parseFloat(String token) {
        try {
            return Float.parseFloat(token);
        } catch (NumberFormatException e) {
            return Float.NaN;
        }
    }
}
