package stirling.software.jpdfium.model;

public record Rect(float x, float y, float width, float height) {

    public static Rect of(float x, float y, float w, float h) {
        return new Rect(x, y, w, h);
    }

    public boolean intersects(Rect o) {
        return x < o.x + o.width  && x + width  > o.x
            && y < o.y + o.height && y + height > o.y;
    }
}
