package qupath.ext.flowpath.model.cohort;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class LandmarksTest {

    static final double C = 100.0;

    /** Raw values whose asinh(x/C) is a two-Gaussian mixture. */
    static double[] mixture(long seed, int n, double negMu, double posMu, double sd, double fractionPositive) {
        Random r = new Random(seed);
        double[] raw = new double[n];
        for (int i = 0; i < n; i++) {
            double mu = r.nextDouble() < fractionPositive ? posMu : negMu;
            raw[i] = C * Math.sinh(mu + sd * r.nextGaussian());
        }
        return raw;
    }

    @Test
    void twoPopulationsGiveL1AtTheNegativeAndL2AtThePositivePeak() {
        Landmarks lm = Landmarks.find(mixture(1, 5000, 1.0, 4.0, 0.3, 0.3), null, C);
        assertEquals(1.0, lm.l1(), 0.15);
        assertEquals(4.0, lm.l2(), 0.15);
    }

    /** The mode of pan-CK on a tumour-rich slide is the POSITIVE peak; L1 must still be the negative one. */
    @Test
    void positiveDominantSlideStillPicksTheNegativePeakAsL1() {
        Landmarks lm = Landmarks.find(mixture(2, 5000, 1.0, 4.0, 0.3, 0.85), null, C);
        assertEquals(1.0, lm.l1(), 0.15, "L1 is the lowest prominent peak, not the tallest");
        assertEquals(4.0, lm.l2(), 0.15);
    }

    @Test
    void aSinglePopulationHasL1ButNoL2() {
        Landmarks lm = Landmarks.find(mixture(3, 5000, 2.0, 2.0, 0.4, 0.0), null, C);
        assertEquals(2.0, lm.l1(), 0.15);
        assertFalse(lm.hasL2());
    }

    @Test
    void tooFewOrConstantValuesHaveNoL1() {
        assertFalse(Landmarks.find(mixture(4, 20, 1.0, 4.0, 0.3, 0.3), null, C).hasL1());
        double[] constant = new double[1000];
        Arrays.fill(constant, 250.0);
        assertFalse(Landmarks.find(constant, null, C).hasL1());
    }

    @Test
    void aDensityThatOnlyFallsFromItsLowestValueHasNoL1() {
        Random r = new Random(5);
        double[] raw = new double[5000];
        for (int i = 0; i < raw.length; i++) raw[i] = C * Math.sinh(-Math.log(1 - r.nextDouble()));
        assertFalse(Landmarks.find(raw, null, C).hasL1(), "no clear negative peak — not corrected");
    }

    @Test
    void nanAndMaskedCellsAreIgnored() {
        double[] raw = mixture(6, 4000, 1.0, 4.0, 0.3, 0.5);
        boolean[] mask = new boolean[raw.length];
        for (int i = 0; i < raw.length; i++) mask[i] = Landmarks.asinh(raw[i], C) < 2.5;
        raw[0] = Double.NaN;
        Landmarks lm = Landmarks.find(raw, mask, C);
        assertEquals(1.0, lm.l1(), 0.15);
        assertFalse(lm.hasL2(), "the masked-out positive population is not seen");
    }

    @Test
    void cofactorIsTheMedianMagnitudeOfFiniteNonZeroValues() {
        assertEquals(3.0, Landmarks.cofactor(new double[]{1, -2, 3, 4, 5, Double.NaN, 0}), 1e-12);
        assertEquals(1.0, Landmarks.cofactor(new double[]{0, 0}), 1e-12);
    }

    /** Ruling C6 / M3: an even count takes the mean of the two middle values, as CohortStats.median does. */
    @Test
    void cofactorUsesTheOneCohortMedian() {
        double[] values = {-1, 2, 3, 10, Double.POSITIVE_INFINITY};
        assertEquals(2.5, Landmarks.cofactor(values), 1e-12);
        assertEquals(CohortStats.median(new double[]{1, 2, 3, 10}), Landmarks.cofactor(values), 0.0);
    }

    @Test
    void asinhAndSinhAreInverses() {
        for (double x : new double[]{-500, -1, 0, 0.5, 42, 1e6}) {
            assertEquals(x, Landmarks.sinh(Landmarks.asinh(x, C), C), 1e-9 * Math.max(1, Math.abs(x)));
        }
    }
}
