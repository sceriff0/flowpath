package qupath.ext.flowpath.engine;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.testing.Cells;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.RoiTools;
import qupath.lib.roi.interfaces.ROI;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the annotation filter actually does with the annotation shapes QuPath can produce.
 * <p>
 * The mask is a centroid-in-geometry test in level-0 pixels, unioned over the annotations.
 * The cases below are the ones whose answer is not obvious from that sentence: an
 * annotation drawn as several disjoint islands, one with a hole punched in it, and the
 * non-area annotation types that answer {@code contains} with a flat "no".
 */
class RoiMaskTest {

    /** Cells at x = 0, 10, 20, ... 90, all at y = 0. */
    private static CellIndex row() {
        return Cells.of(10).marker("A", i -> 1.0).at(i -> i * 10.0, i -> 0.0).area(100.0).build();
    }

    private static String bits(boolean[] mask) {
        StringBuilder sb = new StringBuilder();
        for (boolean b : mask) sb.append(b ? '1' : '0');
        return sb.toString();
    }

    /**
     * One annotation drawn as two separate islands behaves exactly like two annotations.
     * QuPath backs it with a multi-part geometry and {@code contains} walks every part, so
     * nothing in FlowPath has to know the difference.
     */
    @Test
    void oneDisjointAnnotationMatchesEveryIsland() {
        CellIndex index = row();
        ImagePlane plane = ImagePlane.getDefaultPlane();
        ROI left = ROIs.createRectangleROI(-5, -5, 20, 10, plane);    // x = 0, 10
        ROI right = ROIs.createRectangleROI(65, -5, 20, 10, plane);   // x = 70, 80
        ROI disjoint = RoiTools.combineROIs(left, right, RoiTools.CombineOp.ADD);

        assertEquals(2, disjoint.getGeometry().getNumGeometries(),
                "the combined annotation really is two separate islands");

        assertEquals("1100000110", bits(GatingEngine.computeRoiMask(index, disjoint)));
        assertArrayEquals(GatingEngine.computeRoiMask(index, List.of(left, right)),
                GatingEngine.computeRoiMask(index, disjoint),
                "one two-island annotation == two one-island annotations");
    }

    /** A hole in an annotation excludes the cells inside the hole. */
    @Test
    void holeInAnnotationExcludesCellsInsideIt() {
        CellIndex index = row();
        ImagePlane plane = ImagePlane.getDefaultPlane();
        ROI outer = ROIs.createRectangleROI(-5, -5, 100, 10, plane);  // every cell
        ROI hole = ROIs.createRectangleROI(35, -5, 20, 10, plane);    // x = 40, 50
        ROI donut = RoiTools.combineROIs(outer, hole, RoiTools.CombineOp.SUBTRACT);

        assertEquals("1111001111", bits(GatingEngine.computeRoiMask(index, donut)));
    }

    /** Annotations are unioned, not intersected. */
    @Test
    void multipleAnnotationsAreUnioned() {
        CellIndex index = row();
        ImagePlane plane = ImagePlane.getDefaultPlane();
        ROI a = ROIs.createRectangleROI(-5, -5, 20, 10, plane);
        ROI b = ROIs.createRectangleROI(25, -5, 20, 10, plane);
        // a spans x=-5..15 (cells 0, 10); b spans x=25..45 (cells 30, 40).
        assertEquals("1101100000", bits(GatingEngine.computeRoiMask(index, List.of(a, b))));
    }

    /**
     * Line and point annotations enclose no area, so they match nothing. Worth pinning
     * because the consequence is silent and severe: with only such annotations on the
     * image, turning the filter on excludes every cell, and the histograms simply empty.
     */
    @Test
    void nonAreaAnnotationsMatchNoCells() {
        CellIndex index = row();
        ImagePlane plane = ImagePlane.getDefaultPlane();
        assertEquals("0000000000",
                bits(GatingEngine.computeRoiMask(index, ROIs.createLineROI(0, 0, 90, 0, plane))),
                "a line annotation encloses no area");
        assertEquals("0000000000",
                bits(GatingEngine.computeRoiMask(index, ROIs.createPointsROI(10, 0, plane))),
                "nor does a points annotation");
    }

    /**
     * The bounding-box prefilter must be invisible: it may only reject cells the full
     * geometry test would have rejected anyway. A concave shape is the case that would
     * expose a prefilter mistakenly used as the answer rather than as a screen -- the
     * notch cells sit inside the envelope but outside the polygon.
     */
    @Test
    void boundingBoxPrefilterDoesNotChangeTheAnswer() {
        CellIndex index = row();
        // A "U": covers x <= 20 and x >= 70, with the middle notched out, but whose
        // envelope still spans the whole row.
        double[] xs = {-5, 25, 25, 65, 65, 95, 95, -5};
        double[] ys = {-5, -5,  5,  5, -5, -5,  5,  5};
        ROI u = ROIs.createPolygonROI(xs, ys, ImagePlane.getDefaultPlane());

        boolean[] mask = GatingEngine.computeRoiMask(index, u);
        assertTrue(u.getBoundsWidth() >= 95, "the envelope spans cells the polygon excludes");
        assertEquals("1110000111", bits(mask),
                "cells in the notch are inside the envelope but outside the polygon");
    }
}
