package qupath.ext.flowpath.umap.ui;

import javafx.scene.paint.Color;

/**
 * Shared color scale mapping for marker expression visualization.
 * Used by both MarkerOverlayCanvas and ColorScaleLegend.
 */
public final class ColorScaleMapper {

    public enum Scale { BLUE_WHITE_RED, VIRIDIS }

    private ColorScaleMapper() {}

    /**
     * Map a normalized value [0,1] to a color on the given scale.
     *
     * @param t     normalized value (clamped to [0,1])
     * @param scale the color scale to use
     * @param alpha opacity (0.0-1.0)
     */
    public static Color map(double t, Scale scale, double alpha) {
        t = Math.max(0, Math.min(1, t));

        if (scale == Scale.BLUE_WHITE_RED) {
            if (t < 0.5) {
                double s = t * 2;
                return Color.color(s, s, 1.0, alpha);
            } else {
                double s = (t - 0.5) * 2;
                return Color.color(1.0, 1.0 - s, 1.0 - s, alpha);
            }
        } else {
            // Viridis-like: purple -> blue -> teal -> green -> yellow
            if (t < 0.25) {
                double s = t / 0.25;
                return Color.color(lerp(0.27, 0.13, s), lerp(0.0, 0.14, s), lerp(0.33, 0.42, s), alpha);
            } else if (t < 0.5) {
                double s = (t - 0.25) / 0.25;
                return Color.color(lerp(0.13, 0.15, s), lerp(0.14, 0.40, s), lerp(0.42, 0.44, s), alpha);
            } else if (t < 0.75) {
                double s = (t - 0.5) / 0.25;
                return Color.color(lerp(0.15, 0.45, s), lerp(0.40, 0.68, s), lerp(0.44, 0.19, s), alpha);
            } else {
                double s = (t - 0.75) / 0.25;
                return Color.color(lerp(0.45, 0.99, s), lerp(0.68, 0.91, s), lerp(0.19, 0.15, s), alpha);
            }
        }
    }

    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }
}
