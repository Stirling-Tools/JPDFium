package stirling.software.jpdfium;

import stirling.software.jpdfium.model.FontName;
import stirling.software.jpdfium.model.Position;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * Watermark configuration for text or image watermarks.
 *
 * <pre>{@code
 * // Text watermark
 * Watermark wm = Watermark.text("CONFIDENTIAL")
 *     .font(FontName.HELVETICA).size(72).color(0x40FF0000)
 *     .rotation(45).opacity(0.25f)
 *     .position(Position.CENTER)
 *     .build();
 *
 * // Image watermark
 * Watermark logo = Watermark.image(Path.of("logo.png"))
 *     .opacity(0.15f).position(Position.BOTTOM_RIGHT).margin(36)
 *     .build();
 * }</pre>
 */
public final class Watermark {

    enum Type { TEXT, IMAGE }

    private final Type type;
    private final String text;
    private final BufferedImage image;
    private final FontName fontName;
    private final float fontSize;
    private final int argbColor;
    private final float rotation;
    private final float opacity;
    private final Position position;
    private final float margin;

    private Watermark(Type type, String text, BufferedImage image,
                      FontName fontName, float fontSize, int argbColor,
                      float rotation, float opacity, Position position, float margin) {
        this.type = type;
        this.text = text;
        this.image = image;
        this.fontName = fontName;
        this.fontSize = fontSize;
        this.argbColor = argbColor;
        this.rotation = rotation;
        this.opacity = opacity;
        this.position = position;
        this.margin = margin;
    }

    /** Create a text watermark builder. */
    public static TextBuilder text(String text) {
        return new TextBuilder(text);
    }

    /** Create an image watermark builder from a file path. */
    public static ImageBuilder image(Path imagePath) throws IOException {
        return new ImageBuilder(ImageIO.read(imagePath.toFile()));
    }

    /** Create an image watermark builder from a BufferedImage. */
    public static ImageBuilder image(BufferedImage image) {
        return new ImageBuilder(image);
    }

    Type type() { return type; }
    String text() { return text; }
    BufferedImage image() { return image; }
    FontName fontName() { return fontName; }
    float fontSize() { return fontSize; }
    int argbColor() { return argbColor; }
    float rotation() { return rotation; }
    float opacity() { return opacity; }
    Position position() { return position; }
    float margin() { return margin; }

    public static final class TextBuilder {
        private final String text;
        private FontName fontName = FontName.HELVETICA;
        private float fontSize = 72;
        private int argbColor = 0x40FF0000;
        private float rotation = 45;
        private float opacity = 0.25f;
        private Position position = Position.CENTER;
        private float margin = 0;

        private TextBuilder(String text) {
            this.text = text;
        }

        public TextBuilder font(FontName fontName) { this.fontName = fontName; return this; }
        public TextBuilder font(String fontName) { this.fontName = FontName.fromName(fontName); return this; }
        public TextBuilder size(float fontSize) { this.fontSize = fontSize; return this; }
        public TextBuilder color(int argbColor) { this.argbColor = argbColor; return this; }
        public TextBuilder rotation(float degrees) { this.rotation = degrees; return this; }
        public TextBuilder opacity(float opacity) { this.opacity = Math.max(0, Math.min(1, opacity)); return this; }
        public TextBuilder position(Position position) { this.position = position; return this; }
        public TextBuilder margin(float margin) { this.margin = margin; return this; }

        public Watermark build() {
            int alpha = (int) (opacity * 255) & 0xFF;
            int colorWithOpacity = (alpha << 24) | (argbColor & 0x00FFFFFF);
            return new Watermark(Type.TEXT, text, null, fontName, fontSize,
                    colorWithOpacity, rotation, opacity, position, margin);
        }
    }

    public static final class ImageBuilder {
        private final BufferedImage image;
        private float opacity = 0.15f;
        private Position position = Position.CENTER;
        private float margin = 0;

        private ImageBuilder(BufferedImage image) {
            this.image = image;
        }

        public ImageBuilder opacity(float opacity) { this.opacity = Math.max(0, Math.min(1, opacity)); return this; }
        public ImageBuilder position(Position position) { this.position = position; return this; }
        public ImageBuilder margin(float margin) { this.margin = margin; return this; }
        public ImageBuilder scale(float scale) { // default: 30% of page width
            float scale1 = Math.max(0.01f, Math.min(1, scale));
            return this; }

        public Watermark build() {
            int alpha = (int) (opacity * 255) & 0xFF;
            int argb = (alpha << 24) | 0x00FFFFFF;
            return new Watermark(Type.IMAGE, null, image, null, 0,
                    argb, 0, opacity, position, margin);
        }
    }
}
