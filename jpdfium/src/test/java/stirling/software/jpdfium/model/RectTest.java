package stirling.software.jpdfium.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for {@link Rect} record (pure Java, no native dependency). */
class RectTest {

    @Test
    void ofFactoryCreatesRect() {
        Rect r = Rect.of(1, 2, 3, 4);
        assertEquals(1f, r.x());
        assertEquals(2f, r.y());
        assertEquals(3f, r.width());
        assertEquals(4f, r.height());
    }

    @Test
    void intersectsOverlapping() {
        Rect a = Rect.of(0, 0, 10, 10);
        Rect b = Rect.of(5, 5, 10, 10);
        assertTrue(a.intersects(b));
        assertTrue(b.intersects(a));
    }

    @Test
    void intersectsContained() {
        Rect outer = Rect.of(0, 0, 100, 100);
        Rect inner = Rect.of(10, 10, 5, 5);
        assertTrue(outer.intersects(inner));
        assertTrue(inner.intersects(outer));
    }

    @Test
    void noIntersectSeparatedHorizontally() {
        Rect a = Rect.of(0, 0, 5, 5);
        Rect b = Rect.of(10, 0, 5, 5);
        assertFalse(a.intersects(b));
        assertFalse(b.intersects(a));
    }

    @Test
    void noIntersectSeparatedVertically() {
        Rect a = Rect.of(0, 0, 5, 5);
        Rect b = Rect.of(0, 10, 5, 5);
        assertFalse(a.intersects(b));
        assertFalse(b.intersects(a));
    }

    @Test
    void noIntersectTouchingEdge() {
        // Edge-touching: x + width == o.x  -> NOT overlapping
        Rect a = Rect.of(0, 0, 5, 5);
        Rect b = Rect.of(5, 0, 5, 5);
        assertFalse(a.intersects(b));
    }

    @Test
    void intersectsOverlapByOnePixel() {
        Rect a = Rect.of(0, 0, 5, 5);
        Rect b = Rect.of(4.9f, 0, 5, 5);
        assertTrue(a.intersects(b));
    }

    @Test
    void recordEquality() {
        assertEquals(Rect.of(1, 2, 3, 4), Rect.of(1, 2, 3, 4));
        assertNotEquals(Rect.of(1, 2, 3, 4), Rect.of(1, 2, 3, 5));
    }

    @Test
    void zeroSizeRectInsideLargerIntersects() {
        // A zero-area point at (5,5) is inside (0,0,10,10) - still qualifies
        // because 5 < 10 && 5+0 > 0 in both axes
        Rect zero = Rect.of(5, 5, 0, 0);
        assertTrue(zero.intersects(Rect.of(0, 0, 10, 10)));
    }

    @Test
    void zeroSizeRectOutsideDoesNotIntersect() {
        Rect zero = Rect.of(15, 15, 0, 0);
        assertFalse(zero.intersects(Rect.of(0, 0, 10, 10)));
    }
}
