package qupath.ext.flowpath.umap.engine;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.umap.model.ScalingMode;

import static org.junit.jupiter.api.Assertions.*;

class FeatureScalerTest {

    private static double columnMean(double[][] m, int j) {
        double s = 0;
        for (double[] row : m) s += row[j];
        return s / m.length;
    }

    private static double columnStd(double[][] m, int j) {
        double mean = columnMean(m, j);
        double ss = 0;
        for (double[] row : m) {
            double d = row[j] - mean;
            ss += d * d;
        }
        return Math.sqrt(ss / m.length); // population std (ddof=0), matches the scaler
    }

    @Test
    void zscoreStandardizesEachColumnToMeanZeroUnitVariance() {
        // Two markers on wildly different scales: col 0 ~1, col 1 ~1000.
        double[][] matrix = {
                {1.0, 1000.0},
                {2.0, 2000.0},
                {3.0, 3000.0},
                {4.0, 4000.0},
        };
        FeatureScaler scaler = FeatureScaler.fit(matrix, ScalingMode.ZSCORE);
        scaler.transformInPlace(matrix);

        for (int j = 0; j < 2; j++) {
            assertEquals(0.0, columnMean(matrix, j), 1e-9, "column " + j + " should have mean 0");
            assertEquals(1.0, columnStd(matrix, j), 1e-9, "column " + j + " should have unit variance");
        }
    }

    @Test
    void noneModeLeavesValuesUnchanged() {
        double[][] matrix = {{1.0, 1000.0}, {2.0, 2000.0}};
        double[][] expected = {{1.0, 1000.0}, {2.0, 2000.0}};
        FeatureScaler scaler = FeatureScaler.fit(matrix, ScalingMode.NONE);
        scaler.transformInPlace(matrix);
        assertArrayEquals(expected[0], matrix[0], 0.0);
        assertArrayEquals(expected[1], matrix[1], 0.0);
    }

    @Test
    void constantColumnProducesFiniteZerosNotNaN() {
        // A marker with zero variance must not divide by zero.
        double[][] matrix = {{5.0}, {5.0}, {5.0}};
        FeatureScaler scaler = FeatureScaler.fit(matrix, ScalingMode.ZSCORE);
        scaler.transformInPlace(matrix);
        for (double[] row : matrix) {
            assertTrue(Double.isFinite(row[0]), "constant column must not yield NaN/Inf");
            assertEquals(0.0, row[0], 1e-12);
        }
    }

    @Test
    void zscoreReusesTrainingParamsForHeldOutRow() {
        // Fit on training data, then transform a row that was NOT in the training
        // set: it must be scaled with the TRAINING mean/std, not its own. This is
        // the invariant that keeps projection in the same frame as training.
        double[][] training = {{0.0}, {10.0}}; // mean=5, population std=5
        FeatureScaler scaler = FeatureScaler.fit(training, ScalingMode.ZSCORE);

        double[] heldOut = {20.0};
        double[] scaled = scaler.transform(heldOut);
        assertEquals((20.0 - 5.0) / 5.0, scaled[0], 1e-9);
        assertEquals(20.0, heldOut[0], 0.0, "transform() must not mutate its input");
    }

    @Test
    void arcsinhIsOddAndMonotonicWithZeroFixedPoint() {
        FeatureScaler scaler = FeatureScaler.fit(new double[][]{{0.0}}, ScalingMode.ARCSINH);
        assertEquals(0.0, scaler.transform(new double[]{0.0})[0], 1e-12);
        double a = scaler.transform(new double[]{3.0})[0];
        double b = scaler.transform(new double[]{30.0})[0];
        assertTrue(b > a, "arcsinh must be increasing");
        assertEquals(-a, scaler.transform(new double[]{-3.0})[0], 1e-12, "arcsinh must be odd");
        // Compresses large magnitudes: arcsinh(30) << 30.
        assertTrue(b < 30.0);
    }

    @Test
    void fitOnEmptyMatrixIsSafe() {
        FeatureScaler scaler = FeatureScaler.fit(new double[0][], ScalingMode.ZSCORE);
        assertDoesNotThrow(() -> scaler.transformInPlace(new double[0][]));
        assertEquals(ScalingMode.ZSCORE, scaler.mode());
    }
}
