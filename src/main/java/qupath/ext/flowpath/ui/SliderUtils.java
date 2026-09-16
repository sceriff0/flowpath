package qupath.ext.flowpath.ui;

import javafx.scene.control.Slider;

/**
 * Shared slider behavior so every panel (quality filter, gate editors) moves at
 * the same speed relative to its range and responds to the mouse the same way.
 *
 * <p>Keyboard arrows, track clicks, and scroll-wheel notches all move a fixed
 * fraction of the slider's current range, so a slider always takes ~100 steps to
 * traverse end-to-end regardless of whether its units are z-scores (range ~10) or
 * raw total-intensity sums (range ~60000). This keeps "speed with respect to
 * range" consistent across the QC pane and the gate editors, where increments
 * were previously either a stale {@code range/100} or a fixed {@code 0.01}.
 */
public final class SliderUtils {

    private SliderUtils() {}

    /** Fraction of the full range moved per keyboard step, track click, or scroll notch. */
    static final double STEP_FRACTION = 0.01;

    /**
     * Pure step computation: the value distance moved per keyboard step, track
     * click, or scroll notch for a slider spanning {@code [min, max]}. Returns 0
     * for a non-positive (degenerate) range. Extracted so the "speed with respect
     * to range" contract is unit-testable without a JavaFX toolkit.
     */
    static double stepFor(double min, double max) {
        double range = max - min;
        return range > 0 ? range * STEP_FRACTION : 0;
    }

    /**
     * Map an upper-bound ("max") slider position to a filter bound. A thumb within
     * 0.1% of the top means "off" → {@link Double#MAX_VALUE} (no upper bound);
     * anything lower is a literal active bound. The 0.1% tolerance works for both
     * bounded ([0,1]) and wide ([0,50000+]) ranges. Pure, so the "stays off after
     * the range grows" invariant is unit-testable without a JavaFX toolkit.
     */
    static double maxSliderToBound(double value, double min, double max) {
        double range = max - min;
        if (range <= 0 || value >= max - range * 0.001) {
            return Double.MAX_VALUE;
        }
        return value;
    }

    /**
     * Pure scroll computation: the new, range-clamped value after a scroll notch
     * of the given {@code delta} (sign matters, magnitude does not). Returns
     * {@code current} unchanged for a degenerate range or a zero delta.
     */
    static double scrolledValue(double current, double min, double max, double delta) {
        double step = stepFor(min, max);
        if (step <= 0 || delta == 0) return current;
        double v = current + (delta > 0 ? step : -step);
        return Math.max(min, Math.min(max, v));
    }

    /**
     * Set the block increment to a fixed fraction of the current range.
     * Call again after changing {@code min}/{@code max} so the step tracks the new range.
     */
    public static void applyRangeStep(Slider slider) {
        double step = stepFor(slider.getMin(), slider.getMax());
        if (step > 0) {
            slider.setBlockIncrement(step);
        }
    }

    /** Enable scroll-wheel adjustment: one notch moves the value by one range step. */
    public static void enableScrollControl(Slider slider) {
        slider.setOnScroll(e -> {
            if (slider.isDisabled()) return;
            double delta = e.getDeltaY() != 0 ? e.getDeltaY() : e.getDeltaX();
            double v = scrolledValue(slider.getValue(), slider.getMin(), slider.getMax(), delta);
            if (v != slider.getValue()) {
                slider.setValue(v);
                e.consume();
            }
        });
    }

    /** Apply both range-proportional stepping and scroll-wheel control to a slider. */
    public static void makeRangeFriendly(Slider slider) {
        applyRangeStep(slider);
        enableScrollControl(slider);
    }
}
