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
        // 0.75/0.5 rather than 0.7/0.8: PathObject's measurement list stores floats, and
        // an exact-boundary double that is not exactly representable in float32 (0.8, 0.7)
        // rounds on the way in, so an inclusive boundary check against it can miss by an
        // ulp. 0.75 and 0.5 are exact in both precisions.
        qf.setMaxEccentricity(0.75);
        qf.setMinSolidity(0.5);
        qf.setMinTotalIntensity(1000);
        CellIndex idx = index(
                new double[]{50, 200},
                new double[]{0.75, 0.0},
                new double[]{0.5, 1.0},
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
        // 0.25/0.75 rather than 0.2/0.9 for the same float32-representability reason as
        // boundaryValuesPass above.
        qf.setMinEccentricity(0.25);
        qf.setMaxSolidity(0.75);
        qf.setMaxTotalIntensity(5000);
        qf.setMinPerimeter(10);
        qf.setMaxPerimeter(200);
        CellIndex idx = index(
                new double[]{100, 100}, new double[]{0.25, 1.0}, new double[]{0.75, 0.0},
                new double[]{10, 200}, new double[]{5000, 0});
        assertTrue(qf.passes(idx, 0));
        assertTrue(qf.passes(idx, 1));
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
