package qupath.ext.flowpath.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.DoubleUnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Per-shape {@code clearShape()} and {@code remapCoordinates()} on {@link Region2DGate}.
 * Toolkit-free: these are pure model operations, previously duplicated as
 * {@code instanceof} chains in {@code GateEditorPane} ("Clear Shape" and
 * {@code remapRegionShape}).
 */
class Region2DGateShapeOperationsTest {

    private static final DoubleUnaryOperator IDENTITY = v -> v;

    // ---- PolygonGate -----------------------------------------------------

    @Test
    void polygonClearShapeEnclosesNothing() {
        PolygonGate gate = new PolygonGate("CD3", "CD4");
        gate.setVertices(List.of(
                new double[]{0, 0}, new double[]{10, 0}, new double[]{10, 10}, new double[]{0, 10}));
        assertTrue(gate.contains(5, 5), "sanity: the drawn square encloses its centre");

        gate.clearShape();

        assertTrue(gate.getVertices().isEmpty());
        assertFalse(gate.contains(5, 5));
        assertFalse(gate.contains(0, 0), "a vertex of the old shape must not still be enclosed");
    }

    @Test
    void polygonRemapCoordinatesIdentityLeavesVerticesUnchanged() {
        PolygonGate gate = new PolygonGate("CD3", "CD4");
        gate.setVertices(List.of(new double[]{1, 2}, new double[]{3, 4}, new double[]{5, -1}));

        gate.remapCoordinates(IDENTITY, IDENTITY);

        List<double[]> v = gate.getVertices();
        assertArrayEquals(new double[]{1, 2}, v.get(0));
        assertArrayEquals(new double[]{3, 4}, v.get(1));
        assertArrayEquals(new double[]{5, -1}, v.get(2));
    }

    @Test
    void polygonRemapCoordinatesAppliesALinearMapPerAxis() {
        PolygonGate gate = new PolygonGate("CD3", "CD4");
        gate.setVertices(List.of(new double[]{0, 0}, new double[]{1, 2}, new double[]{-1, 3}));

        gate.remapCoordinates(x -> x * 2 + 1, y -> y - 3);

        List<double[]> v = gate.getVertices();
        assertArrayEquals(new double[]{1, -3}, v.get(0));
        assertArrayEquals(new double[]{3, -1}, v.get(1));
        assertArrayEquals(new double[]{-1, 0}, v.get(2));
    }

    @Test
    void polygonClearedShapeRemapIsANoOp() {
        PolygonGate gate = new PolygonGate("CD3", "CD4");
        gate.clearShape();

        gate.remapCoordinates(x -> x * 100, y -> y * 100);

        assertTrue(gate.getVertices().isEmpty());
    }

    // ---- RectangleGate -----------------------------------------------------

    @Test
    void rectangleClearShapeEnclosesNothing() {
        RectangleGate gate = new RectangleGate("CD3", "CD4", -1, 1, -1, 1);
        assertTrue(gate.contains(0, 0), "sanity: the drawn rectangle encloses the origin");

        gate.clearShape();

        assertFalse(gate.contains(0, 0), "a cleared rectangle must not still enclose the origin");
        assertEquals(0, gate.getMinX());
        assertEquals(0, gate.getMaxX());
        assertEquals(0, gate.getMinY());
        assertEquals(0, gate.getMaxY());
    }

    @Test
    void rectangleRemapCoordinatesIdentityLeavesBoundsUnchanged() {
        RectangleGate gate = new RectangleGate("CD3", "CD4", -2, 3, -4, 5);

        gate.remapCoordinates(IDENTITY, IDENTITY);

        assertEquals(-2, gate.getMinX());
        assertEquals(3, gate.getMaxX());
        assertEquals(-4, gate.getMinY());
        assertEquals(5, gate.getMaxY());
    }

    @Test
    void rectangleRemapCoordinatesAppliesALinearMapPerAxis() {
        RectangleGate gate = new RectangleGate("CD3", "CD4", 0, 2, 0, 4);

        gate.remapCoordinates(x -> x * 3 + 1, y -> y * 2);

        assertEquals(1, gate.getMinX());
        assertEquals(7, gate.getMaxX());
        assertEquals(0, gate.getMinY());
        assertEquals(8, gate.getMaxY());
    }

    @Test
    void rectangleRemapCoordinatesResortsBoundsUnderANonMonotoneMap() {
        // A decreasing map (fx = -x) sends minX below maxX in raw order to the OTHER
        // side: the gate must keep minX <= maxX afterward rather than storing an
        // inverted rectangle that contains() would then reject everywhere.
        RectangleGate gate = new RectangleGate("CD3", "CD4", 1, 3, 2, 6);

        gate.remapCoordinates(x -> -x, y -> -y);

        assertEquals(-3, gate.getMinX());
        assertEquals(-1, gate.getMaxX());
        assertEquals(-6, gate.getMinY());
        assertEquals(-2, gate.getMaxY());
        assertTrue(gate.contains(-2, -4), "the resorted rectangle must still enclose its own centre");
    }

    /** Degenerate on Y alone (real width, no height): the guard must catch this axis too. */
    @Test
    void rectangleWithZeroYExtentRemapIsANoOp() {
        RectangleGate gate = new RectangleGate("CD3", "CD4", 0, 2, 5, 5);

        gate.remapCoordinates(x -> x * 100 + 7, y -> y * 100 + 7);

        assertEquals(0, gate.getMinX());
        assertEquals(2, gate.getMaxX());
        assertEquals(5, gate.getMinY());
        assertEquals(5, gate.getMaxY());
    }

    @Test
    void rectangleClearedShapeRemapIsANoOp() {
        RectangleGate gate = new RectangleGate();
        gate.clearShape();

        gate.remapCoordinates(x -> x * 100 + 5, y -> y * 100 + 5);

        assertEquals(0, gate.getMinX());
        assertEquals(0, gate.getMaxX());
        assertEquals(0, gate.getMinY());
        assertEquals(0, gate.getMaxY());
    }

    // ---- EllipseGate -----------------------------------------------------

    @Test
    void ellipseClearShapeEnclosesNothing() {
        EllipseGate gate = new EllipseGate("CD3", "CD4", 0, 0, 5, 5);
        assertTrue(gate.contains(0, 0), "sanity: the drawn ellipse encloses its centre");

        gate.clearShape();

        assertFalse(gate.contains(0, 0), "a cleared ellipse must not still enclose its old centre");
        assertEquals(0, gate.getCenterX());
        assertEquals(0, gate.getCenterY());
        assertEquals(0, gate.getRadiusX());
        assertEquals(0, gate.getRadiusY());
    }

    @Test
    void ellipseRemapCoordinatesIdentityLeavesShapeUnchanged() {
        EllipseGate gate = new EllipseGate("CD3", "CD4", 2, -3, 4, 6);

        gate.remapCoordinates(IDENTITY, IDENTITY);

        assertEquals(2, gate.getCenterX(), 1e-9);
        assertEquals(-3, gate.getCenterY(), 1e-9);
        assertEquals(4, gate.getRadiusX(), 1e-9);
        assertEquals(6, gate.getRadiusY(), 1e-9);
    }

    @Test
    void ellipseRemapCoordinatesAppliesALinearMapToCentreAndScalesRadiusBySlope() {
        // fx(v) = 2v + 1: a linear map. The centre moves through fx/fy directly; the
        // radius, which has no position, scales by the magnitude of the slope only.
        EllipseGate gate = new EllipseGate("CD3", "CD4", 1, 2, 3, 4);

        gate.remapCoordinates(x -> 2 * x + 1, y -> -3 * y);

        assertEquals(3, gate.getCenterX(), 1e-9, "fx(1) = 2*1+1 = 3");
        assertEquals(6, gate.getRadiusX(), 1e-9, "radius scales by |slope| = 2");
        assertEquals(-6, gate.getCenterY(), 1e-9, "fy(2) = -3*2 = -6");
        assertEquals(12, gate.getRadiusY(), 1e-9, "radius scales by |slope| = 3");
    }

    /** Degenerate on Y alone (real X radius, none on Y): the guard must catch this axis too. */
    @Test
    void ellipseWithZeroYRadiusRemapIsANoOp() {
        EllipseGate gate = new EllipseGate("CD3", "CD4", 1, 2, 4, 0);

        gate.remapCoordinates(x -> x * 100 + 7, y -> y * 100 + 7);

        assertEquals(1, gate.getCenterX());
        assertEquals(2, gate.getCenterY());
        assertEquals(4, gate.getRadiusX());
        assertEquals(0, gate.getRadiusY());
    }

    @Test
    void ellipseClearedShapeRemapIsANoOp() {
        EllipseGate gate = new EllipseGate();
        gate.clearShape();

        gate.remapCoordinates(x -> x * 100 + 5, y -> y * 100 + 5);

        assertEquals(0, gate.getCenterX());
        assertEquals(0, gate.getCenterY());
        assertEquals(0, gate.getRadiusX());
        assertEquals(0, gate.getRadiusY());
    }
}
