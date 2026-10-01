package qupath.ext.flowpath.model.cohort;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class LandmarksTest {

    static final LogScale LN = LogScale.LN;

    /** Raw values whose natural log is a two-Gaussian mixture. */
    static double[] mixture(long seed, int n, double negMu, double posMu, double sd, double fractionPositive) {
        Random r = new Random(seed);
        double[] raw = new double[n];
        for (int i = 0; i < n; i++) {
            double mu = r.nextDouble() < fractionPositive ? posMu : negMu;
            raw[i] = Math.exp(mu + sd * r.nextGaussian());
        }
        return raw;
    }

    @Test
    void twoPopulationsGiveL1AtTheNegativeAndL2AtThePositivePeak() {
        Landmarks lm = Landmarks.find(mixture(1, 5000, 1.0, 4.0, 0.3, 0.3), null, LN);
        assertEquals(1.0, lm.l1(), 0.15);
        assertEquals(4.0, lm.l2(), 0.15);
    }

    /** The mode of pan-CK on a tumour-rich slide is the POSITIVE peak; L1 must still be the negative one. */
    @Test
    void positiveDominantSlideStillPicksTheNegativePeakAsL1() {
        Landmarks lm = Landmarks.find(mixture(2, 5000, 1.0, 4.0, 0.3, 0.85), null, LN);
        assertEquals(1.0, lm.l1(), 0.15, "L1 is the lowest prominent peak, not the tallest");
        assertEquals(4.0, lm.l2(), 0.15);
    }

    @Test
    void aSinglePopulationHasL1ButNoL2() {
        Landmarks lm = Landmarks.find(mixture(3, 5000, 2.0, 2.0, 0.4, 0.0), null, LN);
        assertEquals(2.0, lm.l1(), 0.15);
        assertFalse(lm.hasL2());
    }

    @Test
    void tooFewOrConstantValuesHaveNoL1() {
        assertFalse(Landmarks.find(mixture(4, 20, 1.0, 4.0, 0.3, 0.3), null, LN).hasL1());
        double[] constant = new double[1000];
        Arrays.fill(constant, 250.0);
        assertFalse(Landmarks.find(constant, null, LN).hasL1());
    }

    @Test
    void aDensityThatOnlyFallsFromItsLowestValueHasNoL1() {
        Random r = new Random(5);
        double[] raw = new double[5000];
        for (int i = 0; i < raw.length; i++) raw[i] = Math.exp(-Math.log(1 - r.nextDouble()));
        assertFalse(Landmarks.find(raw, null, LN).hasL1(), "no clear negative peak — not corrected");
    }

    @Test
    void nanAndMaskedCellsAreIgnored() {
        double[] raw = mixture(6, 4000, 1.0, 4.0, 0.3, 0.5);
        boolean[] mask = new boolean[raw.length];
        for (int i = 0; i < raw.length; i++) mask[i] = Math.log(raw[i]) < 2.5;
        raw[0] = Double.NaN;
        Landmarks lm = Landmarks.find(raw, mask, LN);
        assertEquals(1.0, lm.l1(), 0.15);
        assertFalse(lm.hasL2(), "the masked-out positive population is not seen");
    }

    @Test
    void countIsZeroOneOrTwo() {
        assertEquals(0, Landmarks.none(LN).count());
        assertEquals(1, new Landmarks(LN, 1.0, Double.NaN).count());
        assertEquals(2, new Landmarks(LN, 1.0, 3.0).count());
        assertEquals(0, new Landmarks(LN, Double.NaN, 3.0).count(), "an L2 without an L1 is nothing");
    }

    @Test
    void valuesOutsideTheScaleDomainAreIgnored() {
        double[] raw = mixture(7, 4000, 1.0, 4.0, 0.3, 0.3);
        for (int i = 0; i < 200; i++) raw[i] = i % 2 == 0 ? 0.5 : -3.0;   // below 1: outside LN
        Landmarks lm = Landmarks.find(raw, null, LN);
        assertEquals(1.0, lm.l1(), 0.2);
        assertEquals(LN, lm.scale());
        double[] u = Landmarks.toLog(new double[]{0.5, Math.E, Double.NaN}, null, LN);
        assertTrue(Double.isNaN(u[0]));
        assertEquals(1.0, u[1], 1e-12);
        assertTrue(Double.isNaN(u[2]));
    }
}
