package qupath.ext.flowpath.cohort;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.Compartment;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.model.cohort.Alignment;
import qupath.ext.flowpath.model.cohort.Landmarks;
import qupath.ext.flowpath.model.cohort.LogScale;
import qupath.ext.flowpath.model.cohort.UniformShift;
import qupath.ext.flowpath.testing.Cells;
import qupath.ext.flowpath.testing.CohortFixtures;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AlignmentModelTest {

    static final Set<AlignmentModel.ColumnRef> CD8 =
            Set.of(new AlignmentModel.ColumnRef("CD8", Compartment.WHOLE_CELL, Statistic.MEAN));
    static final LogScale LN = LogScale.LN;

    /**
     * A log-normal mixture as {@code scripts/uniform_golden.py} draws it: a negative population at
     * e^negMu (sd 0.35) and {@code posFrac} positive at e^posMu (sd 0.30), all times {@code scale}.
     */
    static double[] mixture(long seed, double negMu, double posMu, double posFrac, int n, double scale) {
        Random r = new Random(seed);
        double[] raw = new double[n];
        int pos = (int) (n * posFrac);
        for (int i = 0; i < n; i++) {
            raw[i] = scale * (i < pos ? Math.exp(posMu + 0.30 * r.nextGaussian()) : Math.exp(negMu + 0.35 * r.nextGaussian()));
        }
        return raw;
    }

    static SlideSample sample(String id, double[] cd8) {
        CellIndex index = Cells.of(cd8.length).marker("CD8", cd8).marker("CD3", i -> 1.0).build();
        boolean[] clean = Cells.allTrue(cd8.length);
        return new SlideSample(id, id + ".tif", index, clean, MarkerStats.compute(index, clean), cd8.length, "f-" + id);
    }

    /** A 20%-positive slide drawn with {@code seed}, every value times {@code scale}. */
    static SlideSample slide(String id, long seed, double scale) {
        return sample(id, mixture(seed, 4.0, 6.0, 0.20, 5000, scale));
    }

    /** ref, three typical slides, and "odd" at {@code oddScale}. */
    static List<SlideSample> cohort(double oddScale) {
        List<SlideSample> out = new ArrayList<>();
        out.add(slide("ref", 1, 1.0));
        out.add(slide("s1", 2, 1.05));
        out.add(slide("s2", 3, 0.95));
        out.add(slide("s3", 4, 1.1));
        out.add(slide("odd", 5, oddScale));
        return out;
    }

    static AlignmentModel build(String ref, List<SlideSample> samples) {
        return AlignmentModel.build(ref, samples, CD8, AlignmentModel.Cache.empty(), LN, Map.of());
    }

    static AlignmentModel build(String ref, List<SlideSample> samples, Map<String, Map<String, Double>> peaks) {
        return AlignmentModel.build(ref, samples, CD8, AlignmentModel.Cache.empty(), LN, peaks);
    }

    @Test
    void everySlideIsAlignedToTheReferencePerColumn() {
        AlignmentModel m = build("ref", cohort(1.0));
        assertSame(Alignment.identity(), m.alignment("ref", "CD8"));
        Alignment s3 = m.alignment("s3", "CD8");
        assertEquals(Alignment.Kind.AUTO, s3.kind());
        assertEquals(Math.log(1.1), s3.logShift(), 0.05);
        assertNotNull(m.landmarks("s3", "CD8"));
        assertNotNull(m.grid("CD8"));
        assertEquals(UniformShift.BINS, m.histogram("s3", "CD8").length);
        assertEquals(LN, m.scale());
        assertFalse(m.referenceMissing());
    }

    @Test
    void brighterSlideGetsFactorAboveOneAndMatchesUniformShift() {
        double[] ref = mixture(11, 4.0, 6.0, 0.20, 5000, 1.0);
        double[] bright = ref.clone();
        for (int i = 0; i < bright.length; i++) bright[i] *= 1.6;
        AlignmentModel m = build("ref", List.of(sample("ref", ref), sample("bright", bright)));
        Alignment a = m.alignment("bright", "CD8");
        assertEquals(Alignment.Kind.AUTO, a.kind());
        assertTrue(a.factor() > 1.0);
        assertEquals(Math.log(1.6), a.logShift(), m.grid("CD8").binWidth());
        assertEquals(m.grid("CD8").binWidth(), a.binWidth(), 0.0);

        long[] hRef = m.histogram("ref", "CD8"), hBright = m.histogram("bright", "CD8");
        assertEquals(UniformShift.shiftBins(hBright, hRef), a.shiftBins(), "UniFORM's integer shift, exactly");
    }

    @Test
    void positiveDominantSlideRaisesPeakLock() {
        SlideSample ref = slide("ref", 21, 1.0);
        SlideSample dominant = sample("dom", mixture(22, 4.0, 6.0, 0.85, 5000, 1.0));
        AlignmentModel m = build("ref", List.of(ref, dominant));
        ColumnDiagnostics d = m.diagnostics("dom", "CD8");
        assertEquals(Alignment.Kind.AUTO, m.alignment("dom", "CD8").kind());
        assertTrue(Double.isFinite(d.detectorLogShift()), "both negative peaks were found: " + d);
        assertEquals(0.0, d.detectorLogShift(), 0.2, "the negative peaks agree");
        assertTrue(m.alignment("dom", "CD8").logShift() > 1.0, "the cross-correlation locked onto the positive peak");
        assertTrue(d.peakLock(), d.toString());
    }

    @Test
    void referenceIsIdentityAndCarriesNoFlags() {
        AlignmentModel m = build("ref", cohort(3.0));
        ColumnDiagnostics d = m.diagnostics("ref", "CD8");
        assertSame(Alignment.identity(), m.alignment("ref", "CD8"));
        assertFalse(d.tooFew());
        assertFalse(d.peakLock());
        assertFalse(d.shiftOutlier());
        assertFalse(d.peakPicked());
        assertEquals(0.0, d.detectorLogShift(), 0.0);
        assertEquals(5000, d.usable());
    }

    /** Ruling R3: 3970 of 4000 cells below 1, so 30 usable: too few to align on. */
    @Test
    void subOneSlideIsIdentityTooFewAndCountsOutsideDomain() {
        AlignmentModel m = build("ref", List.of(slide("ref", 31, 1.0), sample("subone", subOne(3970, 4000))));
        ColumnDiagnostics d = m.diagnostics("subone", "CD8");
        assertSame(Alignment.identity(), m.alignment("subone", "CD8"));
        assertTrue(d.tooFew());
        assertEquals(30, d.usable());
        assertEquals(3970, d.outsideDomain());
        assertEquals(3970.0 / 4000, d.outsideFraction(), 1e-12);
        assertTrue(Double.isNaN(d.otsuDiscordance()), "too few: not judged");
        assertFalse(d.peakLock());
    }

    /** Ruling R3: 1000 of 4000 usable is plenty to align on; the fraction outside is still reported. */
    @Test
    void aQuarterUsableIsAlignedAndReportsThreeQuartersOutside() {
        AlignmentModel m = build("ref", List.of(slide("ref", 31, 1.0), sample("subone", subOne(3000, 4000))));
        ColumnDiagnostics d = m.diagnostics("subone", "CD8");
        assertFalse(d.tooFew());
        assertEquals(1000, d.usable());
        assertEquals(0.75, d.outsideFraction(), 1e-12);
        assertEquals(Alignment.Kind.AUTO, m.alignment("subone", "CD8").kind());
    }

    @Test
    void ln1pCountsCellsBelowOne() {
        List<SlideSample> s = List.of(slide("ref", 31, 1.0), sample("subone", subOne(3970, 4000)));
        AlignmentModel m = AlignmentModel.build("ref", s, CD8, AlignmentModel.Cache.empty(), LogScale.LN1P, Map.of());
        ColumnDiagnostics d = m.diagnostics("subone", "CD8");
        assertEquals(0, d.outsideDomain());
        assertEquals(0.0, d.outsideFraction(), 0.0);
        assertEquals(4000, d.usable());
        assertFalse(d.tooFew());
        assertEquals(LogScale.LN1P, m.scale());
    }

    /** {@code below} cells uniform on [0, 0.99), the rest of {@code n} a 20%-positive mixture. */
    static double[] subOne(int below, int n) {
        Random r = new Random(32);
        double[] tail = mixture(33, 4.0, 6.0, 0.20, n - below, 1.0);
        double[] out = new double[n];
        for (int i = 0; i < below; i++) out[i] = 0.99 * r.nextDouble();
        System.arraycopy(tail, 0, out, below, tail.length);
        return out;
    }

    @Test
    void pickedPeakGivesLandmarkShiftFromBins() {
        List<SlideSample> s = cohort(1.0);
        double refL1 = build("ref", s).landmarks("ref", "CD8").l1();
        double picked = Math.exp(refL1) * 1.3;
        AlignmentModel m = build("ref", s, Map.of("s1", Map.of("CD8", picked)));
        Alignment a = m.alignment("s1", "CD8");
        assertEquals(Alignment.Kind.LANDMARK, a.kind());
        assertEquals(1.3, a.factor(), 1.3 * (Math.exp(m.grid("CD8").binWidth()) - 1) + 1e-9);
        assertTrue(m.diagnostics("s1", "CD8").peakPicked());
        assertFalse(m.diagnostics("s1", "CD8").peakLock(), "peak lock judges the automatic mode only");
        assertEquals(refL1, m.referencePeak("CD8"), 0.0, "no reference pick: the detector's L1");
        assertEquals(Alignment.Kind.AUTO, m.alignment("s2", "CD8").kind(), "only the picked slide moves to landmark mode");
    }

    @Test
    void pickedReferencePeakIsUsedWhenPresent() {
        double refPick = 60.0, slidePick = 90.0;
        AlignmentModel m = build("ref", cohort(1.0), Map.of("ref", Map.of("CD8", refPick), "s1", Map.of("CD8", slidePick)));
        UniformShift.Grid g = m.grid("CD8");
        assertEquals(Math.log(refPick), m.referencePeak("CD8"), 1e-12);
        Alignment a = m.alignment("s1", "CD8");
        assertEquals(Alignment.Kind.LANDMARK, a.kind());
        assertEquals(UniformShift.binOf(Math.log(slidePick), g) - UniformShift.binOf(Math.log(refPick), g), a.shiftBins());
        assertSame(Alignment.identity(), m.alignment("ref", "CD8"), "a pick on the reference never moves it");
        assertFalse(m.diagnostics("ref", "CD8").peakPicked());
    }

    /** Review Focus 3: a pick far outside every slide's range is clamped to the last bin. */
    @Test
    void pickedPeakOutsideGridIsClampedAndFinite() {
        AlignmentModel m = build("ref", cohort(1.0), Map.of("s1", Map.of("CD8", 1e9)));
        Alignment a = m.alignment("s1", "CD8");
        assertEquals(Alignment.Kind.LANDMARK, a.kind());
        UniformShift.Grid g = m.grid("CD8");
        assertEquals(UniformShift.BINS - 1 - UniformShift.binOf(m.referencePeak("CD8"), g), a.shiftBins(),
                "the pick lands in the last bin, measured from the reference's L1 bin");
        assertTrue(Double.isFinite(a.factor()));
    }

    /** Rule 3: a pick below 1 has no ln, so the slide is left uncorrected — not auto, not a detector fallback. */
    @Test
    void aPickOutsideTheScaleDomainLeavesTheSlideUncorrected() {
        AlignmentModel m = build("ref", cohort(1.0), Map.of("s1", Map.of("CD8", 0.5)));
        Alignment a = m.alignment("s1", "CD8");
        assertEquals(Alignment.Kind.IDENTITY, a.kind());
        assertEquals(1.0, a.factor(), 0.0);
        assertTrue(m.diagnostics("s1", "CD8").peakPicked(), "the pick exists, it just cannot be binned");
        assertEquals(Alignment.Kind.AUTO, m.alignment("s2", "CD8").kind(), "unpicked slides are unaffected");
    }

    /** Rule 3: a reference pick below 1 makes the reference peak NaN, so every landmark-mode slide stays uncorrected. */
    @Test
    void aReferencePickOutsideTheScaleDomainLeavesEveryPickedSlideUncorrected() {
        AlignmentModel m = build("ref", cohort(1.0), Map.of("ref", Map.of("CD8", 0.5),
                "s1", Map.of("CD8", 70.0), "s3", Map.of("CD8", 80.0)));
        assertTrue(Double.isNaN(m.referencePeak("CD8")), "no detector-L1 fallback when the reference was picked");
        for (String id : List.of("s1", "s3")) {
            Alignment a = m.alignment(id, "CD8");
            assertEquals(Alignment.Kind.IDENTITY, a.kind(), id);
            assertEquals(1.0, a.factor(), 0.0, id);
            assertTrue(m.diagnostics(id, "CD8").peakPicked(), id);
        }
        assertFalse(m.diagnostics("ref", "CD8").peakPicked(), "the reference never reports a pick");
        assertEquals(Alignment.Kind.AUTO, m.alignment("s2", "CD8").kind(), "an unpicked slide still auto-aligns");
    }

    /** Review Focus 2. */
    @Test
    void peaksForUnknownColumnsOrSlidesAreIgnored() {
        List<SlideSample> s = cohort(1.0);
        AlignmentModel plain = build("ref", s);
        AlignmentModel m = build("ref", s, Map.of("nosuch", Map.of("CD8", 50.0), "s1", Map.of("CD99", 50.0)));
        for (SlideSample x : s) {
            assertTrue(CohortSession.sameAlignment(plain.alignment(x.slideId(), "CD8"), m.alignment(x.slideId(), "CD8")),
                    x.slideId());
            assertFalse(m.diagnostics(x.slideId(), "CD8").peakPicked(), x.slideId());
        }
        assertNull(m.alignment("nosuch", "CD8"));
        assertNull(m.diagnostics("nosuch", "CD8"));
    }

    /** Review Focus 5: a pick on s1 is measured against whichever slide is the reference now. */
    @Test
    void changingReferenceRecomputesLandmarkShiftAgainstNewReference() {
        List<SlideSample> s = cohort(1.0);
        Map<String, Map<String, Double>> peaks = Map.of("s1", Map.of("CD8", 70.0));
        AlignmentModel onRef = build("ref", s, peaks);
        AlignmentModel onS3 = build("s3", s, peaks);
        UniformShift.Grid g = onS3.grid("CD8");
        double s3L1 = onS3.landmarks("s3", "CD8").l1();
        assertEquals(s3L1, onS3.referencePeak("CD8"), 0.0);
        assertEquals(UniformShift.binOf(Math.log(70.0), g) - UniformShift.binOf(s3L1, g),
                onS3.alignment("s1", "CD8").shiftBins());
        assertNotEquals(onRef.alignment("s1", "CD8").shiftBins(), onS3.alignment("s1", "CD8").shiftBins(),
                "s3 is 10% brighter than ref, so the shift to it differs");
    }

    /** Review Focus 4. */
    @Test
    void cacheFoundUnderOtherScaleIsNotReused() {
        List<SlideSample> s = cohort(1.0);
        AlignmentModel ln = build("ref", s);
        assertTrue(ln.cache().slides().get("s1").fingerprint().endsWith("|ln"));
        AlignmentModel ln1p = AlignmentModel.build("ref", s, CD8, ln.cache(), LogScale.LN1P, Map.of());
        assertEquals(LogScale.LN1P, ln1p.landmarks("s1", "CD8").scale());
        assertEquals(LogScale.LN1P, ln1p.cache().slides().get("s1").scale());
        assertEquals(AlignmentModel.fingerprint(s.get(1), LogScale.LN1P), ln1p.cache().slides().get("s1").fingerprint());
        assertTrue(ln1p.cache().slides().get("s1").fingerprint().endsWith("|ln1p"));
        assertEquals(AlignmentModel.build("ref", s, CD8, AlignmentModel.Cache.empty(), LogScale.LN1P, Map.of())
                .landmarks("s1", "CD8"), ln1p.landmarks("s1", "CD8"));
    }

    @Test
    void cachedLandmarksAreReusedOnlyForTheSameKeyAndScale() {
        List<SlideSample> s = cohort(1.0);
        Landmarks planted = new Landmarks(LN, 0.5, 3.5);
        AlignmentModel.Cache cache = new AlignmentModel.Cache(Map.of(
                "s1", new AlignmentModel.SlideEntry(AlignmentModel.fingerprint(s.get(1), LN), LN, Map.of("CD8", planted)),
                "s2", new AlignmentModel.SlideEntry("stale|ln", LN, Map.of("CD8", planted)),
                "s3", new AlignmentModel.SlideEntry(s.get(3).cacheKey(), LN, Map.of("CD8", planted))));
        AlignmentModel m = AlignmentModel.build("ref", s, CD8, cache, LN, Map.of());
        assertEquals(planted, m.landmarks("s1", "CD8"), "same key, same scale: reused");
        assertNotEquals(planted, m.landmarks("s2", "CD8"), "a changed key is found again");
        assertNotEquals(planted, m.landmarks("s3", "CD8"), "a fingerprint without the scale is found again");
        assertEquals("f-s2|ln", m.cache().slides().get("s2").fingerprint());
    }

    @Test
    void aSlideEntryHoldsLandmarksOfOneScaleOnly() {
        assertThrows(IllegalArgumentException.class, () -> new AlignmentModel.SlideEntry("f|ln", LN,
                Map.of("CD8", new Landmarks(LogScale.LN1P, 1.0, 2.0))));
    }

    @Test
    void otsuDiscordanceIsLowForAPureBrightnessChange() {
        double[] ref = mixture(41, 4.0, 6.0, 0.20, 5000, 1.0);
        double[] bright = ref.clone();
        for (int i = 0; i < bright.length; i++) bright[i] *= 1.6;
        AlignmentModel m = build("ref", List.of(sample("ref", ref), sample("bright", bright)));
        double disc = m.diagnostics("bright", "CD8").otsuDiscordance();
        assertTrue(disc < 0.10, "discordance " + disc);
    }

    @Test
    void shiftOutlierNeedsThreeSlides() {
        AlignmentModel two = build("ref", List.of(slide("ref", 1, 1.0), slide("s1", 2, 1.05), slide("odd", 5, 3.0)));
        assertFalse(two.diagnostics("odd", "CD8").shiftOutlier(), "two non-reference slides: no spread to judge by");
        assertTrue(Double.isNaN(two.diagnostics("odd", "CD8").cohortMedianLogShift()));

        AlignmentModel four = build("ref", cohort(3.0));
        ColumnDiagnostics odd = four.diagnostics("odd", "CD8");
        assertTrue(odd.shiftOutlier(), odd.toString());
        assertTrue(Double.isFinite(odd.cohortMedianLogShift()));
        for (String id : List.of("s1", "s2", "s3")) assertFalse(four.diagnostics(id, "CD8").shiftOutlier(), id);
    }

    @Test
    void twoRootsOnOneChannelShareOneAlignment() {
        Set<AlignmentModel.ColumnRef> columns = AlignmentModel.columnsOf(CohortFixtures.twoCd8Roots());
        assertEquals(1, columns.size());
        String key = columns.iterator().next().key();
        AlignmentModel m = AlignmentModel.build("ref", cohort(1.0), columns, AlignmentModel.Cache.empty(), LN, Map.of());
        for (SlideSample s : cohort(1.0)) {
            assertNotNull(m.alignment(s.slideId(), key), s.slideId());
            assertNotNull(m.diagnostics(s.slideId(), key), s.slideId());
        }
    }

    /**
     * Review Focus 5 (Task 1 numbering): the reference slide was deleted from the project. With no
     * reference sample there is nothing to align to, so no slide gets an alignment.
     */
    @Test
    void aMissingReferenceSampleYieldsNoAlignments() {
        AlignmentModel m = build("gone", cohort(1.0));
        assertTrue(m.referenceMissing());
        assertNull(m.alignment("s1", "CD8"));
        assertNull(m.diagnostics("s1", "CD8"));
        assertTrue(Double.isNaN(m.referencePeak("CD8")));
    }

    /**
     * Final ruling I2: slides landing one at a time, in either order, with the cache carried
     * from each build to the next as the GUI carries it, end on the same landmarks and
     * alignments — and a build with no cache at all (a headless run, a deleted cache file) equals both.
     */
    @Test
    void theLandmarksDoNotDependOnArrivalOrderOrOnTheCache() {
        List<SlideSample> all = cohort(1.0);
        AlignmentModel referenceLast = incrementally(List.of(all.get(1), all.get(2), all.get(3), all.get(4), all.get(0)));
        AlignmentModel referenceFirst = incrementally(all);
        AlignmentModel cacheless = build("ref", all);
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
            m = AlignmentModel.build("ref", arrivals.subList(0, i), CD8, cache, LN, Map.of());
            cache = m.cache();
        }
        return m;
    }

    @Test
    void aColumnAbsentOnASlideHasNoLandmarksAndNoAlignment() {
        CellIndex noCd8 = Cells.of(100).marker("CD3", i -> i).build();
        SlideSample missing = new SlideSample("nocd8", "nocd8.tif", noCd8, Cells.allTrue(100),
                MarkerStats.compute(noCd8), 100, "f");
        List<SlideSample> all = new ArrayList<>(cohort(1.0));
        all.add(missing);
        AlignmentModel m = build("ref", all);
        assertNull(m.landmarks("nocd8", "CD8"));
        assertNull(m.alignment("nocd8", "CD8"));
        assertNull(m.histogram("nocd8", "CD8"));
        assertNull(m.diagnostics("nocd8", "CD8"));
        assertNull(m.grid("CD99"));
    }
}
