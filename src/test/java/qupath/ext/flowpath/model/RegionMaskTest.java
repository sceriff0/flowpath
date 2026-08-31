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
}
