package qupath.ext.flowpath.model;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.testing.Cells;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every case here used to go through the deprecated positional
 * {@code passes(area, eccentricity, solidity, totalIntensity, perimeter)}. That method took
 * five bare doubles with no connection to a real export, so it could express states production
 * cannot reach — a {@code NaN} total intensity, for one: {@link CellIndex} sums total intensity
 * across markers and never produces {@code NaN} for it, only a real number (0 for no markers).
 * Ported onto {@link QualityFilter#passes(CellIndex, int)} with {@link Cells} fixtures, which
 * pins the same behaviour against the column resolution production actually uses: area and the
 * lowercase {@code "area"} key, {@code Eccentricity}/{@code Solidity}/{@code Perimeter} resolved
 * case-insensitively, and total intensity as the sum of a single marker's value.
 */
class QualityFilterTest {

    /** One cell per row: area, eccentricity, solidity, perimeter and a single marker's value
     *  (which is total intensity, since it is the only marker). */
    private static CellIndex index(double[] area, double[] eccentricity, double[] solidity,
                                    double[] perimeter, double[] totalIntensity) {
        return Cells.of(area.length)
                .marker("Marker", totalIntensity)
                .area(area)
                .morphology("Eccentricity", eccentricity)
                .morphology("Solidity", solidity)
                .morphology("Perimeter", perimeter)
                .build();
    }

    @Test
    void defaultFilterPassesEverything() {
        var qf = new QualityFilter();
        CellIndex idx = index(new double[]{100}, new double[]{0.5}, new double[]{0.9},
                new double[]{0.0}, new double[]{5000});
        assertTrue(qf.passes(idx, 0));
    }

    @Test
    void rejectsAreaBelowMin() {
        var qf = new QualityFilter();
        qf.setMinArea(50);
        CellIndex idx = index(new double[]{10}, new double[]{0.5}, new double[]{0.9},
                new double[]{0.0}, new double[]{5000});
        assertFalse(qf.passes(idx, 0));
    }

    @Test
    void rejectsAreaAboveMax() {
        var qf = new QualityFilter();
        qf.setMaxArea(200);
        CellIndex idx = index(new double[]{300}, new double[]{0.5}, new double[]{0.9},
                new double[]{0.0}, new double[]{5000});
        assertFalse(qf.passes(idx, 0));
    }

    @Test
    void rejectsEccentricityAboveMax() {
        var qf = new QualityFilter();
        qf.setMaxEccentricity(0.8);
        CellIndex idx = index(new double[]{100}, new double[]{0.95}, new double[]{0.9},
                new double[]{0.0}, new double[]{5000});
        assertFalse(qf.passes(idx, 0));
    }

    @Test
    void rejectsSolidityBelowMin() {
        var qf = new QualityFilter();
        qf.setMinSolidity(0.7);
        CellIndex idx = index(new double[]{100}, new double[]{0.5}, new double[]{0.3},
                new double[]{0.0}, new double[]{5000});
        assertFalse(qf.passes(idx, 0));
    }

    @Test
    void rejectsTotalIntensityBelowMin() {
        var qf = new QualityFilter();
        qf.setMinTotalIntensity(1000);
        CellIndex idx = index(new double[]{100}, new double[]{0.5}, new double[]{0.9},
                new double[]{0.0}, new double[]{500});
        assertFalse(qf.passes(idx, 0));
    }

    /**
     * A cell missing area/eccentricity/solidity/perimeter entirely (the export never carried
     * those measurements for it) must not be rejected by a range over any of them — the "NaN
     * passes" rule from the class doc. Total intensity is deliberately not exercised here: it
     * is a computed sum, never NaN in production, so there is no real-data case to pin.
     */
    @Test
    void nanValuesAreSkipped() {
        var qf = new QualityFilter();
        qf.setMinArea(50);
        qf.setMaxEccentricity(0.8);
        qf.setMinSolidity(0.7);
        CellIndex idx = Cells.of(2)
                .area(new double[]{100, 100}).absentOn(i -> i == 1)
                .morphology("Eccentricity", new double[]{0.5, 0.5}).absentOn(i -> i == 1)
                .morphology("Solidity", new double[]{0.9, 0.9}).absentOn(i -> i == 1)
                .build();
        assertTrue(qf.passes(idx, 1), "cell with no morphology measurements at all must pass");
    }

    @Test
    void boundaryValuesPass() {
        var qf = new QualityFilter();
        qf.setMinArea(50);
        qf.setMaxArea(200);
        // 0.8/0.7: PathObject's measurement list stores floats, and neither is exactly
        // representable in float32, so the double literal here and the float32 value
        // CellIndex reads back round to different bit patterns. This used to miss the
        // boundary by an ulp when Range.accepts compared at double precision; it now
        // compares at float precision (see its javadoc) for exactly this reason, so the
        // values that used to have to be dodged (0.75/0.5, exact in both precisions) are
        // usable here directly.
        qf.setMaxEccentricity(0.8);
        qf.setMinSolidity(0.7);
        qf.setMinTotalIntensity(1000);
        CellIndex idx = index(
                new double[]{50, 200},
                new double[]{0.8, 0.0},
                new double[]{0.7, 1.0},
                new double[]{0.0, 0.0},
                new double[]{1000, 9999});
        assertTrue(qf.passes(idx, 0));
        assertTrue(qf.passes(idx, 1));
    }

    @Test
    void rejectsEccentricityBelowMin() {
        var qf = new QualityFilter();
        qf.setMinEccentricity(0.3);
        CellIndex idx = index(
                new double[]{100, 100}, new double[]{0.1, 0.5}, new double[]{0.9, 0.9},
                new double[]{0.0, 0.0}, new double[]{5000, 5000});
        assertFalse(qf.passes(idx, 0));
        assertTrue(qf.passes(idx, 1));
    }

    @Test
    void rejectsSolidityAboveMax() {
        var qf = new QualityFilter();
        qf.setMaxSolidity(0.8);
        CellIndex idx = index(
                new double[]{100, 100}, new double[]{0.5, 0.5}, new double[]{0.95, 0.7},
                new double[]{0.0, 0.0}, new double[]{5000, 5000});
        assertFalse(qf.passes(idx, 0));
        assertTrue(qf.passes(idx, 1));
    }

    @Test
    void rejectsTotalIntensityAboveMax() {
        var qf = new QualityFilter();
        qf.setMaxTotalIntensity(3000);
        CellIndex idx = index(
                new double[]{100, 100}, new double[]{0.5, 0.5}, new double[]{0.9, 0.9},
                new double[]{0.0, 0.0}, new double[]{5000, 2000});
        assertFalse(qf.passes(idx, 0));
        assertTrue(qf.passes(idx, 1));
    }

    @Test
    void rejectsPerimeterBelowMin() {
        var qf = new QualityFilter();
        qf.setMinPerimeter(10);
        CellIndex idx = index(
                new double[]{100, 100}, new double[]{0.5, 0.5}, new double[]{0.9, 0.9},
                new double[]{5, 15}, new double[]{5000, 5000});
        assertFalse(qf.passes(idx, 0));
        assertTrue(qf.passes(idx, 1));
    }

    @Test
    void rejectsPerimeterAboveMax() {
        var qf = new QualityFilter();
        qf.setMaxPerimeter(100);
        CellIndex idx = index(
                new double[]{100, 100}, new double[]{0.5, 0.5}, new double[]{0.9, 0.9},
                new double[]{150, 50}, new double[]{5000, 5000});
        assertFalse(qf.passes(idx, 0));
        assertTrue(qf.passes(idx, 1));
    }

    @Test
    void boundaryValuesIncludeNewFields() {
        var qf = new QualityFilter();
        // 0.2/0.9: exercises the same float-precision comparison as boundaryValuesPass above,
        // with values that are not exact in float32.
        qf.setMinEccentricity(0.2);
        qf.setMaxSolidity(0.9);
        qf.setMaxTotalIntensity(5000);
        qf.setMinPerimeter(10);
        qf.setMaxPerimeter(200);
        CellIndex idx = index(
                new double[]{100, 100}, new double[]{0.2, 1.0}, new double[]{0.9, 0.0},
                new double[]{10, 200}, new double[]{5000, 0});
        assertTrue(qf.passes(idx, 0));
        assertTrue(qf.passes(idx, 1));
    }

    /**
     * Directly pins {@link QualityFilter.Range#accepts} against the ulp mismatch the fixture
     * comments above describe: 0.7 is not exactly representable in float32, so a stored
     * measurement of "0.7" -- a float32 value widened to double by {@code CellIndex} -- is a
     * different double than the literal {@code 0.7} an inclusive bound is typed as. Comparing
     * at double precision (the last assertion) is exactly the bug; {@code accepts} must not
     * reproduce it.
     */
    @Test
    void anInclusiveBoundMatchesAFloat32ValueAtThatBoundExactly() {
        double storedAsFloat = (double) 0.7f;
        QualityFilter.Range min = new QualityFilter.Range(0.7, Double.POSITIVE_INFINITY);
        QualityFilter.Range max = new QualityFilter.Range(Double.NEGATIVE_INFINITY, 0.7);

        assertTrue(min.accepts(storedAsFloat), "an inclusive min of 0.7 must match a float32 0.7");
        assertTrue(max.accepts(storedAsFloat), "an inclusive max of 0.7 must match a float32 0.7");
        assertFalse(storedAsFloat >= 0.7,
                "sanity: comparing at double precision is the exact failure this guards against");
    }

    @Test
    void deepCopyIncludesNewFields() {
        var qf = new QualityFilter();
        qf.setMinEccentricity(0.3);
        qf.setMaxSolidity(0.8);
        qf.setMaxTotalIntensity(3000);
        qf.setMinPerimeter(10);
        qf.setMaxPerimeter(200);
        var copy = qf.deepCopy();
        assertEquals(0.3, copy.range(QualityFilter.ECCENTRICITY).min());
        assertEquals(0.8, copy.range(QualityFilter.SOLIDITY).max());
        assertEquals(3000, copy.range(QualityFilter.TOTAL_INTENSITY).max());
        assertEquals(10, copy.range(QualityFilter.PERIMETER).min());
        assertEquals(200, copy.range(QualityFilter.PERIMETER).max());
    }
}
