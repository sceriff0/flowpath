package qupath.ext.flowpath.cohort;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.model.cohort.Landmarks;
import qupath.ext.flowpath.testing.Cells;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Final ruling I1: every cohort reading — landmarks, flags, rules, curves, crops — uses the clean
 * mask of the tree being <em>scored</em> (its quality filter and ROI), not the one the slides were
 * sampled under; and a slide's cached landmarks are keyed on those inputs, so a filter change
 * misses the cache and anything else hits it.
 */
class CohortCleanScopeTest {

    static final int N = 2000;
    static final int POSITIVES = 600;
    static final double NEG_PEAK = 100 * Math.sinh(1.0);
    static final double VALLEY = 100 * Math.sinh(2.5);

    /**
     * One slide: the cells {@code positive} picks are the CD8+ population (asinh(x/100) near
     * 4 + shift) with area 100, the rest CD8− (near 1 + shift) with area 50, laid out along x = i.
     */
    static SlideSource slide(String id, long seed, double shift, IntPredicate positive, PathObject... annotations) {
        Random r = new Random(seed);
        double[] raw = new double[N];
        for (int i = 0; i < N; i++) raw[i] = 100 * Math.sinh((positive.test(i) ? 4.0 : 1.0) + shift + 0.3 * r.nextGaussian());
        Cells cells = Cells.of(N).atGrid(1, 0).marker("CD3", i -> 1.0).marker("CD8", raw)
                .area(i -> positive.test(i) ? 100 : 50);
        return new SlideSource() {
            @Override public String id() { return id; }
            @Override public String name() { return id + ".tif"; }
            @Override public PathObjectHierarchy readHierarchy() {
                PathObjectHierarchy h = new PathObjectHierarchy();
                h.addObjects(List.of(annotations));
                h.addObjects(cells.detections());
                return h;
            }
        };
    }

    /** Two enabled roots on ONE channel: root 0 on the negative peak, root 1 in the valley. */
    static GateTree tree() {
        GateTree tree = new GateTree();
        tree.setReferenceSlideId("ref");
        for (double t : new double[]{NEG_PEAK, VALLEY}) {
            GateNode g = new GateNode("CD8", t);
            g.setStatistic(Statistic.MEAN);
            tree.addRoot(g);
        }
        return tree;
    }

    /** Samples every slide under {@code sampledUnder}, then scores the cohort for {@code scored}. */
    static CohortSession session(List<SlideSource> slides, GateTree sampledUnder, GateTree scored) {
        CohortSession s = new CohortSession();
        s.setProjectSlides(slides.stream().map(x -> new CohortSession.SlideRef(x.id(), x.name())).toList());
        s.samplingStarted();
        for (SlideSource source : slides) s.landed(CohortSampler.sampleOne(source, sampledUnder, 0));
        s.samplingFinished();
        rescore(s, scored);
        return s;
    }

    static void rescore(CohortSession s, GateTree tree) {
        s.adopt(CohortSession.score(s.snapshot(tree), tree.deepCopy()));
    }

    /** 30% CD8+ (every cell whose index ends in 0, 1 or 2), spread through the slide. */
    static List<SlideSource> alternating(PathObject... annotations) {
        IntPredicate odd = i -> i % 10 < 3;
        List<SlideSource> out = new ArrayList<>();
        out.add(slide("ref", 1, 0.0, odd, annotations));
        out.add(slide("s1", 2, 0.05, odd, annotations));
        out.add(slide("s2", 3, -0.05, odd, annotations));
        out.add(slide("s3", 4, 0.1, odd, annotations));
        return out;
    }

    static boolean onPeak(CohortSession s, String slide, int root) {
        return s.review().items().stream().anyMatch(i -> i.key().slideId().equals(slide) && i.key().rootIndex() == root
                && i.flags().contains(ReviewItem.Flag.ON_PEAK));
    }

    /** A landmark back in raw units: the cofactor moves with the clean cells, so asinh units do not compare. */
    static double raw(double u, Landmarks lm) {
        return Landmarks.sinh(u, lm.cofactor());
    }

    static int count(boolean[] mask) {
        int n = 0;
        for (boolean b : mask) if (b) n++;
        return n;
    }

    @Test
    void aQualityFilterSetAfterSamplingChangesTheLandmarksAndTheFlags() {
        GateTree unfiltered = tree();
        CohortSession s = session(alternating(), unfiltered, unfiltered);
        Landmarks before = s.model().landmarks("s1", "CD8");
        assertTrue(onPeak(s, "s1", 0), "root 0 sits on the negative peak of every cell");
        assertEquals(N, count(s.sample("s1").clean()));

        // The filter the user loads or drags afterwards: area >= 60 keeps only the CD8+ cells.
        GateTree filtered = unfiltered.deepCopy();
        filtered.getQualityFilter().setMinArea(60);
        rescore(s, filtered);

        assertEquals(POSITIVES, count(s.sample("s1").clean()), "the sample is re-scoped to the scored tree's filter");
        Landmarks after = s.model().landmarks("s1", "CD8");
        assertTrue(raw(after.l1(), after) > 5 * raw(before.l1(), before),
                "L1 is now the lowest peak of the clean cells, the CD8+ one: " + before + " -> " + after);
        assertFalse(onPeak(s, "s1", 0), "no clean cell reaches the negative peak any more");
        assertFalse(onPeak(s, "s1", 1), "and the second same-channel root is judged on the same clean cells");
    }

    @Test
    void withTheRoiOnOnlyCellsInTheRegionCount() {
        // The last 30% of the cells are CD8+, the rest CD8−; the annotation encloses exactly the CD8+ ones.
        IntPredicate lastThirty = i -> i >= N - POSITIVES;
        PathObject box = PathObjects.createAnnotationObject(
                ROIs.createRectangleROI(N - POSITIVES - 0.5, -1, POSITIVES, 2, ImagePlane.getDefaultPlane()));
        List<SlideSource> slides = List.of(slide("ref", 1, 0.0, lastThirty, box), slide("s1", 2, 0.05, lastThirty, box),
                slide("s2", 3, -0.05, lastThirty, box));
        GateTree off = tree();
        CohortSession s = session(slides, off, off);
        Landmarks wholeSlide = s.model().landmarks("s1", "CD8");

        GateTree on = off.deepCopy();
        on.setRoiFilterEnabled(true);
        rescore(s, on);

        SlideSample scoped = s.sample("s1");
        assertEquals(POSITIVES, count(scoped.clean()));
        for (int i = 0; i < N; i++) {
            PathObject cell = scoped.index().getObjects()[i];
            assertEquals(cell.getROI().getCentroidX() >= N - POSITIVES, scoped.clean()[i], "cell " + i);
        }
        Landmarks inRegion = s.model().landmarks("s1", "CD8");
        assertTrue(raw(inRegion.l1(), inRegion) > 5 * raw(wholeSlide.l1(), wholeSlide),
                "the landmarks come from the region's cells only");
    }

    @Test
    void theCacheMissesAfterAFilterChangeAndHitsOtherwise() {
        GateTree tree = tree();
        CohortSession s = session(alternating(), tree, tree);
        String key = s.sample("s1").cacheKey();
        assertEquals(key, s.model().cache().slides().get("s1").fingerprint());
        Landmarks first = s.model().landmarks("s1", "CD8");

        // A gate edit changes no filter: the same key, the cached landmarks reused as they are.
        GateTree moved = tree.deepCopy();
        moved.getRoots().get(1).setThreshold(VALLEY * 1.1);
        rescore(s, moved);
        assertEquals(key, s.sample("s1").cacheKey());
        assertSame(first, s.model().landmarks("s1", "CD8"), "a hit: nothing was found again");

        // A filter change moves the key: a miss, and the entry is re-keyed.
        GateTree filtered = moved.deepCopy();
        filtered.getQualityFilter().setMinArea(60);
        rescore(s, filtered);
        String filteredKey = s.sample("s1").cacheKey();
        assertNotEquals(key, filteredKey);
        assertEquals(filteredKey, s.model().cache().slides().get("s1").fingerprint());
        assertNotSame(first, s.model().landmarks("s1", "CD8"));

        // The same ROI flag and filter from another tree object: the same key again.
        GateTree sameFilter = filtered.deepCopy();
        rescore(s, sameFilter);
        assertEquals(filteredKey, s.sample("s1").cacheKey());
    }
}
