package stirling.software.jpdfium;

import stirling.software.jpdfium.model.FontName;

/**
 * Header and footer configuration for PDF documents.
 *
 * <pre>{@code
 * HeaderFooter hf = HeaderFooter.builder()
 *     .footer("{page} of {pages}")
 *     .header("Case No. 2025-CV-1234")
 *     .font(FontName.HELVETICA).size(9)
 *     .margin(36)
 *     .build();
 *
 * HeaderFooterApplier.apply(doc, hf);
 * }</pre>
 *
 * <p>Template variables supported in header/footer text:
 * <ul>
 *   <li>{@code {page}} - current page number (1-based)</li>
 *   <li>{@code {pages}} - total page count</li>
 *   <li>{@code {date}} - current date (ISO format)</li>
 * </ul>
 */
public final class HeaderFooter {

    private final String header;
    private final String footer;
    private final FontName fontName;
    private final float fontSize;
    private final int argbColor;
    private final float margin;

    private HeaderFooter(String header, String footer, FontName fontName,
                          float fontSize, int argbColor, float margin) {
        this.header = header;
        this.footer = footer;
        this.fontName = fontName;
        this.fontSize = fontSize;
        this.argbColor = argbColor;
        this.margin = margin;
    }

    public String header() { return header; }
    public String footer() { return footer; }
    public FontName fontName() { return fontName; }
    public float fontSize() { return fontSize; }
    public int argbColor() { return argbColor; }
    public float margin() { return margin; }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String header;
        private String footer;
        private FontName fontName = FontName.HELVETICA;
        private float fontSize = 9;
        private int argbColor = 0xFF000000;
        private float margin = 36;

        private Builder() {}

        public Builder font(FontName fontName) { this.fontName = fontName; return this; }
        public Builder font(String fontName) { this.fontName = FontName.fromName(fontName); return this; }
        public Builder footer(String footer) { this.footer = footer; return this; }
        public Builder header(String header) { this.header = header; return this; }
        public Builder size(float fontSize) { this.fontSize = fontSize; return this; }
        public Builder color(int argbColor) { this.argbColor = argbColor; return this; }
        public Builder margin(float margin) { this.margin = margin; return this; }

        public HeaderFooter build() {
            if (header == null && footer == null) {
                throw new IllegalStateException("At least a header or footer must be specified");
            }
            return new HeaderFooter(header, footer, fontName, fontSize, argbColor, margin);
        }
    }
}
