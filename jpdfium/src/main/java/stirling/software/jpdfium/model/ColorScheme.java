package stirling.software.jpdfium.model;

/**
 * ARGB color scheme for dark-mode or custom rendering.
 *
 * @param pathFillColor   color for filled paths (page background)
 * @param pathStrokeColor color for stroked paths
 * @param textFillColor   color for text fill
 * @param textStrokeColor color for text stroke
 */
public record ColorScheme(int pathFillColor, int pathStrokeColor,
                          int textFillColor, int textStrokeColor) {}
