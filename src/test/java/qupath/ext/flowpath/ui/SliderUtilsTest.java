package qupath.ext.flowpath.ui;

import javafx.scene.control.Slider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import qupath.ext.flowpath.testing.FxTestSupport;

/**
 * Covers the range-proportional "speed" contract shared by every slider in the
 * QC pane and the gate editors. The point of the contract: a slider always takes
 * ~100 steps to cross its range, whether the range is a z-score span (~10) or a
 * raw total-intensity span (~60000), so the feel is consistent across panels.
 */
class SliderUtilsTest {

    private static final double EPS = 1e-9;

    // ---- stepFor: happy path ----

    @Test
    void stepIsOnePercentOfRange() {
        assertEquals(0.1, SliderUtils.stepFor(-5, 5), EPS);      // z-score span 10
        assertEquals(600.0, SliderUtils.stepFor(0, 60000), EPS); // raw total-intensity span
        assertEquals(0.01, SliderUtils.stepFor(0, 1), EPS);      // bounded [0,1] metric
    }

    @Test
    void sameRangeYieldsSameStepRegardlessOfOffset() {
        // "Consistent across panels": a span of 10 steps the same whether it sits
        // at [-5,5] (z-score) or [100,110] (a raw window) — speed tracks range width.
        assertEquals(SliderUtils.stepFor(-5, 5), SliderUtils.stepFor(100, 110), EPS);
    }

    @Test
    void traversingFullRangeTakesAboutHundredSteps() {
        double min = 0, max = 12345;
        double step = SliderUtils.stepFor(min, max);
        assertEquals(100.0, (max - min) / step, 1e-6);
    }

    // ---- stepFor: edge / degenerate ----

    @Test
    void stepIsZeroForZeroRange() {
        assertEquals(0.0, SliderUtils.stepFor(7, 7), EPS);
    }

    @Test
    void stepIsZeroForInvertedRange() {
        assertEquals(0.0, SliderUtils.stepFor(10, 0), EPS);
    }

    // ---- scrolledValue: happy path ----

    @Test
    void scrollUpAddsOneStep() {
        assertEquals(0.1, SliderUtils.scrolledValue(0.0, -5, 5, +1), EPS);
    }

    @Test
    void scrollDownSubtractsOneStep() {
        assertEquals(-0.1, SliderUtils.scrolledValue(0.0, -5, 5, -3.5), EPS); // magnitude ignored, sign used
    }

    @Test
    void scrollMagnitudeDoesNotChangeStepSize() {
        double small = SliderUtils.scrolledValue(0.0, 0, 100, 1);
        double large = SliderUtils.scrolledValue(0.0, 0, 100, 999);
        assertEquals(small, large, EPS);
    }

    // ---- scrolledValue: clamping (edge) ----

    @Test
    void scrollClampsAtMax() {
        assertEquals(5.0, SliderUtils.scrolledValue(4.99, -5, 5, +1), EPS);
    }

    @Test
    void scrollClampsAtMin() {
        assertEquals(-5.0, SliderUtils.scrolledValue(-4.99, -5, 5, -1), EPS);
    }

    // ---- scrolledValue: no-op paths ----

    @Test
    void scrollWithZeroDeltaIsNoOp() {
        assertEquals(2.5, SliderUtils.scrolledValue(2.5, 0, 10, 0), EPS);
    }

    @Test
    void scrollWithDegenerateRangeIsNoOp() {
        assertEquals(2.5, SliderUtils.scrolledValue(2.5, 5, 5, +1), EPS);
    }

    // ---- maxSliderToBound: the off/active decision (pure) ----

    @Test
    void thumbAtTopMeansOff() {
        assertEquals(Double.MAX_VALUE, SliderUtils.maxSliderToBound(50000, 0, 50000));
    }

    @Test
    void thumbWithinTenthOfPercentOfTopMeansOff() {
        // range*0.001 = 50, so 49990 of [0,50000] still reads "off".
        assertEquals(Double.MAX_VALUE, SliderUtils.maxSliderToBound(49990, 0, 50000));
    }

    @Test
    void thumbBelowTopIsAnActiveBound() {
        assertEquals(20000.0, SliderUtils.maxSliderToBound(20000, 0, 50000));
    }

    @Test
    void boundedZeroToOneTopMeansOff() {
        assertEquals(Double.MAX_VALUE, SliderUtils.maxSliderToBound(1.0, 0, 1.0));
    }

    @Test
    void boundedZeroToOneMidIsActiveBound() {
        assertEquals(0.5, SliderUtils.maxSliderToBound(0.5, 0, 1.0));
    }

    @Test
    void degenerateRangeMeansOff() {
        assertEquals(Double.MAX_VALUE, SliderUtils.maxSliderToBound(0, 0, 0));
    }

    // ---- the total-intensity off-re-pin invariant (pure model of updateRanges) ----

    @Test
    void offSliderStaysOffWhenCeilingRaisedAboveOldValue() {
        // The fix: an "off" max slider is re-pinned to the new top before syncing,
        // so it round-trips back to "off" instead of becoming a stale active bound.
        double newCeiling = 88000;            // data max total intensity * 1.1
        double repinnedValue = newCeiling;    // rescaleMaxSlider re-pins because isOff
        assertEquals(Double.MAX_VALUE, SliderUtils.maxSliderToBound(repinnedValue, 0, newCeiling),
                "re-pinned 'off' slider must round-trip to MAX_VALUE");
    }

    @Test
    void documentsBugWhenOffSliderIsNotRepinned() {
        // Without the re-pin, the stale 50000 sits mid-track on the widened range
        // and becomes a real upper bound — exactly the reported bug. Guards the
        // rationale: delete the re-pin and this stops being "off".
        assertEquals(50000.0, SliderUtils.maxSliderToBound(50000, 0, 88000),
                "a non-re-pinned stale value is a (buggy) active bound, not off");
    }

    @Test
    void userSetBoundIsPreservedAcrossCeilingChange() {
        // Not "off", so not re-pinned: an explicit bound survives the range growth.
        assertEquals(20000.0, SliderUtils.maxSliderToBound(20000, 0, 88000));
    }

    // ---- real Slider integration (skips when no display) ----

    @Test
    void applyRangeStepSetsBlockIncrementOnRealSlider() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        FxTestSupport.onFxRun(() -> {
            Slider slider = new Slider(0, 1000, 0);
            SliderUtils.makeRangeFriendly(slider);
            assertEquals(10.0, slider.getBlockIncrement(), EPS);
            // Speed must follow the range when it changes (the QC updateRanges path).
            slider.setMax(50000);
            SliderUtils.applyRangeStep(slider);
            assertEquals(500.0, slider.getBlockIncrement(), EPS);
        });
    }
}
