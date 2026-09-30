package qupath.ext.flowpath.model;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.engine.CleanMask;
import qupath.ext.flowpath.ingest.DetectionIngest;
import qupath.ext.flowpath.ingest.IngestOptions;
import qupath.ext.flowpath.testing.MirageSample;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The one accessor the cohort's readers (landmarks, All slides curves, review coverage,
 * boundary band) ask instead of {@code isFinite}: on MIRAGE's sample, cell 2 vanishes from the
 * CD3/CD8 inputs and stays in the PANCK/DAPI ones.
 */
class MeasuredColumnRoundQcTest {

    static MarkerStats stats(CellIndex index, boolean filtered) {
        QualityFilter f = new QualityFilter();
        if (filtered) f.setMin("qcround/nuclear_retention", 0.5);
        CleanMask clean = CleanMask.of(index, f, false, List.of());
        return MarkerStats.compute(index, clean.combined(), clean.rounds());
    }

    @Test
    void aFailedRoundsValueIsNotAMeasurement() throws Exception {
        CellIndex index = DetectionIngest.read(MirageSample.cells(), IngestOptions.none()).index();
        MarkerStats stats = stats(index, true);
        for (String roundMarker : List.of("CD3", "CD8")) {
            MeasuredColumn c = index.column(roundMarker, null, Statistic.MEAN, stats);
            assertTrue(c.isMeasured(0), roundMarker);
            assertFalse(c.isMeasured(1), roundMarker);
            assertArrayEquals(new boolean[]{true, false}, c.withoutRoundFailures(null), roundMarker);
            assertEquals(12.0 == c.valueAt(1) || 8.0 == c.valueAt(1), true, "the raw value is kept");
        }
        for (String reference : List.of("PANCK", "DAPI")) {
            MeasuredColumn c = index.column(reference, null, Statistic.MEAN, stats);
            assertTrue(c.isMeasured(1), reference);
            boolean[] mask = {true, true};
            assertSame(mask, c.withoutRoundFailures(mask), reference);
        }
    }

    @Test
    void withoutARoundRangeEveryFiniteValueIsMeasured() throws Exception {
        CellIndex index = DetectionIngest.read(MirageSample.cells(), IngestOptions.none()).index();
        MeasuredColumn cd3 = index.column("CD3", null, Statistic.MEAN, stats(index, false));
        assertTrue(cd3.isMeasured(1));
    }
}
