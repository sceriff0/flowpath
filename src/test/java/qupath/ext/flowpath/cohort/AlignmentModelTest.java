package qupath.ext.flowpath.cohort;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.Compartment;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.model.cohort.Alignment;
import qupath.ext.flowpath.model.cohort.Landmarks;
import qupath.ext.flowpath.model.cohort.LogScale;
import qupath.ext.flowpath.testing.Cells;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AlignmentModelTest {

    static final Set<AlignmentModel.ColumnRef> CD8 =
            Set.of(new AlignmentModel.ColumnRef("CD8", Compartment.WHOLE_CELL, Statistic.MEAN));

    /** A slide whose CD8 in asinh(x/100) units is a 30%-positive mixture at 1+shift and 4+shift. */
    static SlideSample slide(String id, long seed, double shift) {
        Random r = new Random(seed);
        double[] raw = new double[3000];
        for (int i = 0; i < raw.length; i++) {
            double mu = (r.nextDouble() < 0.3 ? 4.0 : 1.0) + shift;
            raw[i] = 100 * Math.sinh(mu + 0.3 * r.nextGaussian());
        }
        CellIndex index = Cells.of(raw.length).marker("CD8", raw).marker("CD3", i -> 1.0).build();
        boolean[] clean = Cells.allTrue(raw.length);
        return new SlideSample(id, id + ".tif", index, clean, MarkerStats.compute(index, clean), raw.length, "f-" + id);
    }

    static List<SlideSample> cohort(double outlierShift) {
        List<SlideSample> out = new ArrayList<>();
        out.add(slide("ref", 1, 0.0));
        out.add(slide("s1", 2, 0.05));
        out.add(slide("s2", 3, -0.05));
        out.add(slide("s3", 4, 0.1));
        out.add(slide("odd", 5, outlierShift));
        return out;
    }

    @Disabled("rewritten in Task 3")
    @Test
    void everySlideIsAlignedToTheReferencePerColumn() {
        AlignmentModel m = AlignmentModel.build("ref", cohort(0.0), CD8, AlignmentModel.Cache.empty());
        assertSame(Alignment.identity(), m.alignment("ref", "CD8"));
        Alignment s3 = m.alignment("s3", "CD8");
        assertEquals(Alignment.Kind.AUTO, s3.kind());
        assertEquals(0.1, s3.logShift(), 0.15);
        assertNotNull(m.landmarks("s3", "CD8"));
        assertFalse(m.referenceMissing());
    }

    /**
     * Review Focus 5: the reference slide was deleted from the project. With no reference sample
     * there is nothing to align to, so no slide gets an alignment.
     */
    @Test
    void aMissingReferenceSampleYieldsNoAlignments() {
        AlignmentModel m = AlignmentModel.build("gone", cohort(0.0), CD8, AlignmentModel.Cache.empty());
        assertTrue(m.referenceMissing());
        assertNull(m.alignment("s1", "CD8"));
    }

    @Disabled("rewritten in Task 3")
    @Test
    void unusualStainingIsFlaggedWithADirection() {
        AlignmentModel m = AlignmentModel.build("ref", cohort(1.2), CD8, AlignmentModel.Cache.empty());
        String reason = m.unusualStaining("odd", "CD8");
        assertNotNull(reason);
        assertTrue(reason.matches("Staining \\d+\\.\\d× brighter than typical"), reason);
        assertNull(m.unusualStaining("s1", "CD8"));
        AlignmentModel dim = AlignmentModel.build("ref", cohort(-1.2), CD8, AlignmentModel.Cache.empty());
        assertTrue(dim.unusualStaining("odd", "CD8").endsWith("dimmer than typical"));
    }

    @Test
    void cachedLandmarksAreReusedOnlyForTheSameKeyAndTheCurrentScale() {
        Landmarks planted = new Landmarks(LogScale.LN, 0.5, 3.5);
        Landmarks otherScale = new Landmarks(LogScale.LN1P, 0.5, 3.5);
        AlignmentModel.Cache cache = new AlignmentModel.Cache(
                Map.of("s1", new AlignmentModel.SlideEntry("f-s1", Map.of("CD8", planted)),
                        "s2", new AlignmentModel.SlideEntry("stale", Map.of("CD8", planted)),
                        "s3", new AlignmentModel.SlideEntry("f-s3", Map.of("CD8", otherScale))));
        AlignmentModel m = AlignmentModel.build("ref", cohort(0.0), CD8, cache);
        assertEquals(planted, m.landmarks("s1", "CD8"), "same key, same scale: reused");
        assertNotEquals(planted, m.landmarks("s2", "CD8"), "a changed key re-aligns");
        assertEquals(LogScale.LN, m.landmarks("s3", "CD8").scale(), "landmarks found on another scale are found again");
        assertNotEquals(otherScale, m.landmarks("s3", "CD8"));
        assertEquals("f-s2", m.cache().slides().get("s2").fingerprint());
    }

    /**
     * Final ruling I2: slides landing one at a time, in either order, with the cache carried
     * from each build to the next as the GUI carries it, end on the same cofactor and landmarks
     * — and a build with no cache at all (a headless run, a deleted cache file) equals both.
     */
    @Test
    void theLandmarksDoNotDependOnArrivalOrderOrOnTheCache() {
        List<SlideSample> all = cohort(0.0);
        AlignmentModel referenceLast = incrementally(List.of(all.get(1), all.get(2), all.get(3), all.get(4), all.get(0)));
        AlignmentModel referenceFirst = incrementally(all);
        AlignmentModel cacheless = AlignmentModel.build("ref", all, CD8, AlignmentModel.Cache.empty());
        for (SlideSample s : all) {
            assertEquals(cacheless.landmarks(s.slideId(), "CD8"), referenceLast.landmarks(s.slideId(), "CD8"), s.slideId());
            assertEquals(cacheless.landmarks(s.slideId(), "CD8"), referenceFirst.landmarks(s.slideId(), "CD8"), s.slideId());
            assertTrue(CohortSession.sameAlignment(cacheless.alignment(s.slideId(), "CD8"),
                    referenceLast.alignment(s.slideId(), "CD8")), s.slideId());
        }
    }

    /** Build after each landing, carrying the cache forward, as {@code CohortCoordinator} rescoring does. */
    private static AlignmentModel incrementally(List<SlideSample> arrivals) {
        AlignmentModel.Cache cache = AlignmentModel.Cache.empty();
        AlignmentModel m = null;
        for (int i = 1; i <= arrivals.size(); i++) {
            m = AlignmentModel.build("ref", arrivals.subList(0, i), CD8, cache);
            cache = m.cache();
        }
        return m;
    }

    @Test
    void aColumnAbsentOnASlideHasNoLandmarksAndNoAlignment() {
        CellIndex noCd8 = Cells.of(100).marker("CD3", i -> i).build();
        SlideSample missing = new SlideSample("nocd8", "nocd8.tif", noCd8, Cells.allTrue(100),
                MarkerStats.compute(noCd8), 100, "f");
        List<SlideSample> all = new ArrayList<>(cohort(0.0));
        all.add(missing);
        AlignmentModel m = AlignmentModel.build("ref", all, CD8, AlignmentModel.Cache.empty());
        assertNull(m.landmarks("nocd8", "CD8"));
        assertNull(m.alignment("nocd8", "CD8"));
    }
}
