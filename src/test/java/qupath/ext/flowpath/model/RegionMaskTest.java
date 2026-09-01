package qupath.ext.flowpath.model;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.testing.Cells;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The annotation filter, once it can say <em>which</em> region rather than just in-or-out.
 */
class RegionMaskTest {

    private static final ImagePlane PLANE = ImagePlane.getDefaultPlane();

    /** Cells at x = 0, 10, ... 90, all at y = 0. */
    private static CellIndex row() {
        return Cells.of(10).marker("A", i -> 1.0).at(i -> i * 10.0, i -> 0.0).area(100.0).build();
    }

    private static PathObject annotation(String name, PathClass pathClass, ROI roi) {
        PathObject ann = PathObjects.createAnnotationObject(roi, pathClass);
        if (name != null) ann.setName(name);
        return ann;
    }

    private static ROI band(double fromX, double toX) {
        return ROIs.createRectangleROI(fromX, -5, toX - fromX, 10, PLANE);
    }

    @Test
    void everyCellIsAttributedToItsRegion() {
        CellIndex index = row();
        RegionMask mask = RegionMask.compute(index, List.of(
                annotation("Core", null, band(-5, 25)),      // x = 0, 10, 20
                annotation("Margin", null, band(55, 95))));  // x = 60, 70, 80, 90

        assertEquals(List.of("Core", "Margin"), mask.regionNames());
        assertEquals("Core", mask.regionNameOf(0));
        assertEquals("Core", mask.regionNameOf(2));
        assertNull(mask.regionNameOf(3), "x=30 is in neither region");
        assertEquals("Margin", mask.regionNameOf(6));
        assertEquals(List.of(3, 4), mask.regionCounts());
        assertEquals(7, mask.includedCount());
    }

    @Test
    void regionRoisAreParallelToRegionNamesAndAreaBearing() {
        CellIndex index = row();
        ROI core = band(-5, 25);
        ROI margin = band(55, 95);
        RegionMask mask = RegionMask.compute(index, List.of(
                annotation("Core", null, core),
                annotation("Margin", null, margin)));

        assertEquals(List.of(core, margin), mask.regionRois());
        assertEquals(mask.regionNames().size(), mask.regionRois().size());
    }

    /**
     * The implicit "whole image, minus exclusions" region has no single ROI describing its
     * shape, so a caller after an area (density = count / area) must see {@code null} here
     * rather than be handed some other region's ROI or a fabricated one.
     */
    @Test
    void regionRoisIsNullForTheImplicitWholeImageRegion() {
        CellIndex index = row();
        RegionMask mask = RegionMask.compute(index, List.of(
                annotation("Fold", PathClass.fromString("Ignore*"), band(35, 55))));

        assertEquals(1, mask.regionRois().size());
        assertNull(mask.regionRois().get(0));
    }

    @Test
    void includedMatchesTheBooleanMaskConsumersAlreadyUse() {
        CellIndex index = row();
        RegionMask mask = RegionMask.compute(index, List.of(
                annotation("Core", null, band(-5, 25))));
        boolean[] expected = {true, true, true, false, false, false, false, false, false, false};
        assertArrayEquals(expected, mask.included());
    }

    /**
     * QuPath's own "ignored" convention -- the {@code Ignore*} class, or any class whose
     * name ends in {@code *} -- marks a region as a subtraction. Reusing it means necrosis
     * and tissue folds are excluded by classifying them the way QuPath already expects,
     * with no FlowPath-specific marker to learn.
     */
    @Test
    void ignoredClassAnnotationSubtracts() {
        CellIndex index = row();
        RegionMask mask = RegionMask.compute(index, List.of(
                annotation("Tumour", null, band(-5, 95)),                    // every cell
                annotation("Necrosis", PathClass.fromString("Ignore*"), band(35, 55))));  // x = 40, 50

        assertEquals(1, mask.excludeRegionCount());
        assertEquals(2, mask.excludedByRegion());
        assertEquals(8, mask.includedCount());
        assertFalse(mask.included()[4], "x=40 sits in the necrotic region");
        assertFalse(mask.included()[5], "x=50 too");
        assertEquals("Tumour", mask.regionNameOf(0));
        assertNull(mask.regionNameOf(4), "a subtracted cell belongs to no region");
    }

    /** An exclusion with nothing to subtract from means "the whole image, minus this". */
    @Test
    void exclusionAloneMeansEverythingElse() {
        CellIndex index = row();
        RegionMask mask = RegionMask.compute(index, List.of(
                annotation("Fold", PathClass.fromString("Ignore*"), band(35, 55))));

        assertEquals(List.of(RegionMask.WHOLE_IMAGE), mask.regionNames());
        assertEquals(8, mask.includedCount());
        assertFalse(mask.included()[4]);
        assertTrue(mask.included()[0]);
    }

    /**
     * Lines and points enclose no area, so {@code contains} is false everywhere for them.
     * They are skipped and counted, rather than being allowed to produce an all-false mask
     * that silently emptied the whole view.
     */
    @Test
    void nonAreaAnnotationsAreSkippedAndCounted() {
        CellIndex index = row();
        RegionMask mask = RegionMask.compute(index, List.of(
                annotation("A line", null, ROIs.createLineROI(0, 0, 90, 0, PLANE)),
                annotation("A point", null, ROIs.createPointsROI(10, 0, PLANE))));

        assertEquals(2, mask.droppedNonArea());
        assertTrue(mask.isEmpty(), "nothing usable to filter by");
        assertEquals(0, mask.includedCount());
    }

    @Test
    void overlappingRegionsResolveToTheFirstMatch() {
        CellIndex index = row();
        RegionMask mask = RegionMask.compute(index, List.of(
                annotation("First", null, band(-5, 45)),
                annotation("Second", null, band(15, 95))));

        assertEquals("First", mask.regionNameOf(2), "x=20 is in both; the first wins");
        // Counts partition the population -- no cell is counted twice.
        assertEquals(mask.includedCount(),
                mask.regionCounts().stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    void annotationsFallBackToClassNameThenOrdinal() {
        CellIndex index = row();
        RegionMask mask = RegionMask.compute(index, List.of(
                annotation(null, PathClass.fromString("Tumor"), band(-5, 5)),
                annotation(null, null, band(15, 25))));
        assertEquals(List.of("Tumor", "Region 2"), mask.regionNames());
    }

    /** A disjoint annotation is still one region, exactly as it is one annotation. */
    @Test
    void disjointAnnotationIsOneRegion() {
        CellIndex index = row();
        ROI left = band(-5, 15);
        ROI right = band(65, 85);
        ROI both = qupath.lib.roi.RoiTools.combineROIs(left, right, qupath.lib.roi.RoiTools.CombineOp.ADD);

        RegionMask mask = RegionMask.compute(index, List.of(annotation("Islands", null, both)));

        assertEquals(List.of("Islands"), mask.regionNames());
        assertEquals(List.of(4), mask.regionCounts());
        assertEquals("Islands", mask.regionNameOf(0));
        assertEquals("Islands", mask.regionNameOf(7));
        assertNull(mask.regionNameOf(4));
    }

    // ---- effective areas ----

    /** A plain axis-aligned rectangle, so an expected area is width x height and nothing else. */
    private static ROI rect(double x, double y, double w, double h) {
        return ROIs.createRectangleROI(x, y, w, h, PLANE);
    }

    /**
     * The trivial case, pinned so the two adjustments below are visibly adjustments to
     * something: with one plain annotation and nothing to subtract, a region's effective
     * area is just its ROI's area, in level-0 pixels².
     */
    @Test
    void effectiveAreaOfASinglePlainRegionIsItsRoiArea() {
        CellIndex index = row();
        RegionMask mask = RegionMask.compute(index, List.of(
                annotation("Tumour", null, rect(0, 0, 40, 20))));

        double[] areas = mask.effectiveAreasPixels();
        assertEquals(1, areas.length, "one area per region name");
        assertEquals(mask.regionNames().size(), areas.length);
        assertEquals(800.0, areas[0], 1e-6, "40 x 20 pixels");
    }

    /**
     * The defect this method exists for. An {@code Ignore*} annotation drops every cell
     * inside it — {@link #ignoredClassAnnotationSubtracts} pins that — but the enclosing
     * annotation's own ROI still reports the hole as part of its area. A density built from
     * the raw ROI area therefore divides a hole-excluded numerator by an un-holed
     * denominator and reads systematically low, worst exactly where a pathologist has been
     * most careful about excluding necrosis or a tissue fold.
     */
    @Test
    void effectiveAreaSubtractsAnIgnoredClassHole() {
        CellIndex index = row();
        ROI outer = rect(0, 0, 40, 20);      // 800
        ROI hole = rect(10, 5, 10, 5);       // 50, wholly inside outer
        RegionMask mask = RegionMask.compute(index, List.of(
                annotation("Tumour", null, outer),
                annotation("Necrosis", PathClass.fromString("Ignore*"), hole)));

        assertEquals(1, mask.excludeRegionCount(), "the hole is a subtraction, not a region");
        assertEquals(List.of("Tumour"), mask.regionNames());

        double[] areas = mask.effectiveAreasPixels();
        assertEquals(1, areas.length);
        assertEquals(750.0, areas[0], 1e-6,
                "800 minus the 50-pixel hole -- the cells inside it are not counted either");
        assertNotEquals(outer.getArea(), areas[0],
                "the raw ROI area is the wrong denominator, which is the whole point");
    }

    /**
     * Include regions are first-match-wins for cells
     * ({@link #overlappingRegionsResolveToTheFirstMatch}), so the geometry has to be resolved
     * the same way: the earlier annotation claims the whole of its own ROI and the later one
     * keeps only what is left. Charging the shared area to both would let two overlapping
     * annotations report a combined area larger than the tissue they cover, and every cell in
     * the overlap would already have been counted against the first region alone.
     */
    @Test
    void overlappingIncludeRegionsPartitionTheirUnion() {
        CellIndex index = row();
        ROI first = rect(0, 0, 40, 20);      // 800
        ROI second = rect(20, 0, 40, 20);    // 800, overlapping the first over x = 20..40
        RegionMask mask = RegionMask.compute(index, List.of(
                annotation("First", null, first),
                annotation("Second", null, second)));

        double[] areas = mask.effectiveAreasPixels();
        assertEquals(2, areas.length);
        assertEquals(800.0, areas[0], 1e-6, "the first region claims the whole of its own ROI");
        assertEquals(400.0, areas[1], 1e-6,
                "the second keeps only x = 40..60; x = 20..40 was already claimed");

        // The two partition their union, so they can be summed without double-counting --
        // the geometric statement of the same rule the cell counts obey.
        double union = 60 * 20;              // x = 0..60, y = 0..20
        assertEquals(union, areas[0] + areas[1], 1e-6);
    }

    /**
     * The implicit "whole image, minus exclusions" region has no ROI describing it
     * ({@link #regionRoisIsNullForTheImplicitWholeImageRegion}), and an unknown area must
     * stay unknown. Zero would be the worst possible stand-in: {@code count / 0} is
     * {@code Infinity}, which renders as a number and reads as an answer, whereas
     * {@code NaN} is the value every density display already treats as "not available".
     */
    @Test
    void effectiveAreaOfTheImplicitWholeImageRegionIsNaNNotZero() {
        CellIndex index = row();
        RegionMask mask = RegionMask.compute(index, List.of(
                annotation("Fold", PathClass.fromString("Ignore*"), band(35, 55))));

        assertEquals(List.of(RegionMask.WHOLE_IMAGE), mask.regionNames());

        double[] areas = mask.effectiveAreasPixels();
        assertEquals(1, areas.length);
        assertTrue(Double.isNaN(areas[0]),
                "no ROI describes the whole image minus its exclusions, so its area is unknown");
        assertNotEquals(0.0, areas[0], "and an unknown area is never reported as a number");
    }
}
