package qupath.ext.flowpath.cohort;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.testing.Cells;
import qupath.ext.flowpath.testing.GateTreeFixtures;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class CohortSamplerTest {

    static SlideSource slide(String id, Cells cells, PathObject... annotations) {
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

    static Cells cells(int n) {
        return Cells.of(n).atGrid(10, 10).marker("CD3", i -> i).marker("CD8", i -> 2.0 * i).area(i -> i % 2 == 0 ? 50 : 100);
    }

    static GateTree tree() {
        return GateTreeFixtures.twoRootsOnCd3AndCd8(10, 20);
    }

    private static SlideSample sampled(CohortSampler.Outcome o) {
        return assertInstanceOf(CohortSampler.Sampled.class, o).sample();
    }

    @Test
    void theSameSettingAlwaysDrawsTheSameSampleInHierarchyOrder() {
        Cells population = cells(500);
        SlideSample a = sampled(CohortSampler.sampleOne(slide("s1", population), tree(), 100));
        SlideSample b = sampled(CohortSampler.sampleOne(slide("s1", population), tree(), 100));
        assertEquals(100, a.index().size());
        assertArrayEquals(a.index().getObjects(), b.index().getObjects());
        assertEquals(a.fingerprint(), b.fingerprint());
        List<PathObject> all = population.detections();
        int last = -1;
        for (PathObject o : a.index().getObjects()) {
            int at = all.indexOf(o);
            assertTrue(at > last, "kept in hierarchy order");
            last = at;
        }
        assertEquals(500, a.detectionCount());
    }

    @Test
    void zeroMeansEveryCellAndTheSettingIsInTheFingerprint() {
        Cells population = cells(300);
        SlideSample all = sampled(CohortSampler.sampleOne(slide("s1", population), tree(), 0));
        assertEquals(300, all.index().size());
        SlideSample some = sampled(CohortSampler.sampleOne(slide("s1", population), tree(), 100));
        assertNotEquals(all.fingerprint(), some.fingerprint());
        SlideSample big = sampled(CohortSampler.sampleOne(slide("s1", population), tree(), 5000));
        assertEquals(300, big.index().size(), "a setting above the detection count takes every cell");
    }

    @Test
    void theCleanMaskIsTheQualityFilterAndTheRoi() {
        GateTree t = tree();
        t.getQualityFilter().setMinArea(60);
        SlideSample q = sampled(CohortSampler.sampleOne(slide("s1", cells(40)), t, 0));
        assertEquals(20, count(q.clean()), "the area filter drops the 50-area half");

        t.setRoiFilterEnabled(true);
        PathObject box = PathObjects.createAnnotationObject(
                ROIs.createRectangleROI(-1, -1, 200, 200, ImagePlane.getDefaultPlane()));
        SlideSample r = sampled(CohortSampler.sampleOne(slide("s1", cells(40), box), t, 0));
        assertEquals(10, count(r.clean()), "cells 0..19 lie in the box; half pass the area filter");
    }

    @Test
    void aFailingSlideIsAValueAndTheRunContinues() {
        SlideSource broken = new SlideSource() {
            @Override public String id() { return "bad"; }
            @Override public String name() { return "bad.tif"; }
            @Override public PathObjectHierarchy readHierarchy() { throw new OutOfMemoryError("Java heap space"); }
        };
        SlideSource empty = slide("empty", Cells.of(0));
        List<CohortSampler.Outcome> seen = new ArrayList<>();
        List<CohortSampler.Outcome> out = CohortSampler.sampleAll(
                List.of(slide("s1", cells(50)), broken, empty, slide("s2", cells(50))),
                tree(), 0, seen::add, () -> false);
        assertEquals(4, out.size());
        assertEquals(out, seen, "each outcome is reported as it lands");
        assertInstanceOf(CohortSampler.Sampled.class, out.get(0));
        CohortSampler.Failed oom = assertInstanceOf(CohortSampler.Failed.class, out.get(1));
        assertTrue(oom.reason().contains("Java heap space"), oom.reason());
        assertEquals("no detections on this slide",
                assertInstanceOf(CohortSampler.Failed.class, out.get(2)).reason());
        assertInstanceOf(CohortSampler.Sampled.class, out.get(3));
    }

    @Test
    void cancellationStopsBeforeTheNextSlide() {
        AtomicBoolean cancel = new AtomicBoolean();
        List<CohortSampler.Outcome> out = CohortSampler.sampleAll(
                List.of(slide("s1", cells(20)), slide("s2", cells(20))), tree(), 0,
                o -> cancel.set(true), cancel::get);
        assertEquals(1, out.size());
    }

    private static int count(boolean[] mask) {
        int n = 0;
        for (boolean b : mask) if (b) n++;
        return n;
    }
}
