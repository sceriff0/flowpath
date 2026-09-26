package qupath.ext.flowpath.model.cohort;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static qupath.ext.flowpath.model.cohort.LandmarksTest.C;
import static qupath.ext.flowpath.model.cohort.LandmarksTest.mixture;

class AlignmentTest {

    /** Reference: 20% positive. Slide: asinh stretched 1.3x and shifted +0.5, and 70% positive. */
    private static final double STRETCH = 1.3, SHIFT = 0.5;

    private static double[] stretched(double[] raw) {
        double[] out = new double[raw.length];
        for (int i = 0; i < raw.length; i++) out[i] = C * Math.sinh(STRETCH * Landmarks.asinh(raw[i], C) + SHIFT);
        return out;
    }

    @Test
    void landmarkAlignmentRecoversTheThresholdWherePercentileMatchingDoesNot() {
        double[] ref = mixture(10, 6000, 1.0, 4.0, 0.3, 0.2);
        double[] slide = stretched(mixture(11, 6000, 1.0, 4.0, 0.3, 0.7));
        double threshold = C * Math.sinh(2.5);                       // the valley on the reference
        double truth = STRETCH * 2.5 + SHIFT;                         // the valley on the slide, asinh

        Alignment a = Alignment.between(Landmarks.find(ref, null, C), Landmarks.find(slide, null, C));
        assertEquals(Alignment.Kind.TWO_LANDMARK, a.kind());
        assertEquals(truth, Landmarks.asinh(a.apply(threshold), C), 0.2);

        // Percentile matching carries "20% above" across, but this slide is 70% positive.
        double fractionAbove = Arrays.stream(ref).filter(v -> v >= threshold).count() / (double) ref.length;
        double[] sorted = slide.clone();
        Arrays.sort(sorted);
        double percentileMatched = sorted[(int) Math.floor((1 - fractionAbove) * (sorted.length - 1))];
        assertTrue(Math.abs(Landmarks.asinh(percentileMatched, C) - truth) > 0.8,
                "percentile matching lands inside the positive peak, not in the valley");
    }

    @Test
    void positiveDominantSlideDoesNotInvert() {
        Landmarks ref = Landmarks.find(mixture(12, 6000, 1.0, 4.0, 0.3, 0.3), null, C);
        Landmarks slide = Landmarks.find(stretched(mixture(13, 6000, 1.0, 4.0, 0.3, 0.9)), null, C);
        Alignment a = Alignment.between(ref, slide);
        assertTrue(a.stretch() > 0);
        double applied = Landmarks.asinh(a.apply(C * Math.sinh(2.5)), C);
        assertTrue(applied > slide.l1() && applied < slide.l2(), "the cut stays between the slide's peaks");
    }

    @Test
    void onlyL1GivesShiftAndNoL1GivesIdentity() {
        Landmarks ref = new Landmarks(C, 1.0, 4.0);
        Alignment shift = Alignment.between(ref, new Landmarks(C, 1.4, Double.NaN));
        assertEquals(Alignment.Kind.SHIFT, shift.kind());
        assertEquals(0.4, shift.shift(), 1e-12);
        assertEquals(2.9, Landmarks.asinh(shift.apply(C * Math.sinh(2.5)), C), 1e-9);

        assertEquals(Alignment.Kind.IDENTITY, Alignment.between(ref, Landmarks.none(C)).kind());
        assertEquals(Alignment.Kind.IDENTITY, Alignment.between(Landmarks.none(C), ref).kind());
    }

    @Test
    void identityIsExactAndInverseUndoesApply() {
        assertEquals(412.0, Alignment.identity().apply(412.0));
        assertEquals(412.0, Alignment.identity().inverse(412.0));
        Alignment a = Alignment.between(new Landmarks(C, 1.0, 4.0), new Landmarks(C, 1.5, 5.4));
        double previous = Double.NEGATIVE_INFINITY;
        for (double x : new double[]{-300, -1, 0, 3, 250, 9000}) {
            assertEquals(x, a.inverse(a.apply(x)), 1e-7 * Math.max(1, Math.abs(x)));
            assertTrue(a.apply(x) > previous, "monotone increasing");
            previous = a.apply(x);
        }
    }

    @Test
    void aLandmarkPairThatWouldReverseTheAxisFallsBackToShift() {
        Alignment a = Alignment.between(new Landmarks(C, 1.0, 4.0), new Landmarks(C, 1.2, 1.2));
        assertEquals(Alignment.Kind.SHIFT, a.kind());
    }
}
