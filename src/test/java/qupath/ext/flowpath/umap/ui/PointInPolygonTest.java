package qupath.ext.flowpath.umap.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PointInPolygonTest {

    // Triangle: (0,0), (10,0), (5,10)
    private static final List<double[]> TRIANGLE = List.of(
            new double[]{0, 0}, new double[]{10, 0}, new double[]{5, 10}
    );

    // Square: (0,0), (10,0), (10,10), (0,10)
    private static final List<double[]> SQUARE = List.of(
            new double[]{0, 0}, new double[]{10, 0}, new double[]{10, 10}, new double[]{0, 10}
    );

    @Test
    void pointInsideTriangle() {
        assertTrue(UmapCanvas.pointInPolygon(5, 3, TRIANGLE));
    }

    @Test
    void pointOutsideTriangle() {
        assertFalse(UmapCanvas.pointInPolygon(0, 10, TRIANGLE));
        assertFalse(UmapCanvas.pointInPolygon(-1, 0, TRIANGLE));
    }

    @Test
    void pointInsideSquare() {
        assertTrue(UmapCanvas.pointInPolygon(5, 5, SQUARE));
        assertTrue(UmapCanvas.pointInPolygon(1, 1, SQUARE));
        assertTrue(UmapCanvas.pointInPolygon(9, 9, SQUARE));
    }

    @Test
    void pointOutsideSquare() {
        assertFalse(UmapCanvas.pointInPolygon(11, 5, SQUARE));
        assertFalse(UmapCanvas.pointInPolygon(-1, 5, SQUARE));
        assertFalse(UmapCanvas.pointInPolygon(5, -1, SQUARE));
    }

    @Test
    void fewerThanThreeVerticesReturnsFalse() {
        List<double[]> line = List.of(new double[]{0, 0}, new double[]{10, 10});
        assertFalse(UmapCanvas.pointInPolygon(5, 5, line));

        List<double[]> point = List.of(new double[]{0, 0});
        assertFalse(UmapCanvas.pointInPolygon(0, 0, point));
    }

    @Test
    void concavePolygon() {
        // L-shape: concave polygon
        List<double[]> lShape = List.of(
                new double[]{0, 0}, new double[]{10, 0}, new double[]{10, 5},
                new double[]{5, 5}, new double[]{5, 10}, new double[]{0, 10}
        );
        assertTrue(UmapCanvas.pointInPolygon(2, 2, lShape));   // inside lower part
        assertTrue(UmapCanvas.pointInPolygon(2, 8, lShape));   // inside upper left
        assertFalse(UmapCanvas.pointInPolygon(8, 8, lShape));  // outside upper right (the concavity)
    }

    @Test
    void emptyVertexList() {
        assertFalse(UmapCanvas.pointInPolygon(5, 5, List.of()));
    }

    @Test
    void nanCoordinatesReturnFalse() {
        assertFalse(UmapCanvas.pointInPolygon(Double.NaN, 5, SQUARE));
        assertFalse(UmapCanvas.pointInPolygon(5, Double.NaN, SQUARE));
        assertFalse(UmapCanvas.pointInPolygon(Double.NaN, Double.NaN, SQUARE));
    }

    @Test
    void largeCoordinates() {
        List<double[]> bigSquare = List.of(
                new double[]{0, 0}, new double[]{1e15, 0},
                new double[]{1e15, 1e15}, new double[]{0, 1e15}
        );
        assertTrue(UmapCanvas.pointInPolygon(5e14, 5e14, bigSquare));
        assertFalse(UmapCanvas.pointInPolygon(2e15, 5e14, bigSquare));
    }
}
