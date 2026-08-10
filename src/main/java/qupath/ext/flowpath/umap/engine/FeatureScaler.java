package qupath.ext.flowpath.umap.engine;

import qupath.ext.flowpath.umap.model.ScalingMode;

/**
 * Per-marker feature scaler applied to the cell-by-marker matrix before UMAP.
 *
 * <p><b>Fit-once, transform-twice.</b> A scaler is {@link #fit fit} on the
 * training matrix (the full matrix, or the subsample that UMAP is actually
 * trained on). The captured parameters are then reused to {@link #transform}
 * the held-out cells that are projected into the embedding after a subsampled
 * run, so training and projection share one coordinate frame. Fitting the two
 * independently would put them on different scales and corrupt the kNN
 * projection — the exact kind of silent error that yields a plausible but wrong
 * layout.</p>
 *
 * <p>Transforms operate on a copy of the input by default; {@link #transformInPlace}
 * and {@link #transformRowInPlace} mutate the caller's arrays for the hot paths
 * where an extra allocation per cell would matter (millions of cells).</p>
 *
 * <p>Input is assumed already NaN-imputed (the compute service imputes column
 * means before scaling); the scaler does not re-impute.</p>
 */
public final class FeatureScaler {

    private final ScalingMode mode;
    /** Per-column subtractive center (z-score mean); null for non-fitting modes. */
    private final double[] center;
    /** Per-column divisor (z-score std, guarded to 1.0 for constant columns); null otherwise. */
    private final double[] scale;

    private FeatureScaler(ScalingMode mode, double[] center, double[] scale) {
        this.mode = mode;
        this.center = center;
        this.scale = scale;
    }

    /** The scaling mode this scaler implements. */
    public ScalingMode mode() {
        return mode;
    }

    /**
     * Fit a scaler to a cell-by-marker {@code matrix} ({@code [cell][marker]}).
     * For {@link ScalingMode#NONE} and {@link ScalingMode#ARCSINH} no parameters
     * are needed (the transforms are stateless), so fitting is essentially free.
     * For {@link ScalingMode#ZSCORE} this captures each column's mean and
     * population standard deviation; a column with (near-)zero variance gets a
     * divisor of 1.0 so constant markers map to 0 rather than NaN/∞.
     */
    public static FeatureScaler fit(double[][] matrix, ScalingMode mode) {
        if (mode != ScalingMode.ZSCORE || matrix.length == 0) {
            return new FeatureScaler(mode, null, null);
        }
        int n = matrix.length;
        int m = matrix[0].length;
        double[] center = new double[m];
        double[] scale = new double[m];
        for (int j = 0; j < m; j++) {
            double sum = 0.0;
            for (double[] row : matrix) sum += row[j];
            double mean = sum / n;
            double ss = 0.0;
            for (double[] row : matrix) {
                double d = row[j] - mean;
                ss += d * d;
            }
            double std = Math.sqrt(ss / n); // population std (ddof=0), matches StandardScaler
            center[j] = mean;
            scale[j] = std < 1e-10 ? 1.0 : std;
        }
        return new FeatureScaler(mode, center, scale);
    }

    /** Scale every row of {@code matrix} in place. */
    public void transformInPlace(double[][] matrix) {
        if (mode == ScalingMode.NONE) return;
        for (double[] row : matrix) {
            transformRowInPlace(row);
        }
    }

    /** Scale a single row in place. */
    public void transformRowInPlace(double[] row) {
        switch (mode) {
            case NONE -> { /* no-op */ }
            case ZSCORE -> {
                for (int j = 0; j < row.length; j++) {
                    row[j] = (row[j] - center[j]) / scale[j];
                }
            }
            case ARCSINH -> {
                for (int j = 0; j < row.length; j++) {
                    row[j] = asinh(row[j]);
                }
            }
        }
    }

    /** Return a scaled copy of {@code row}, leaving the input untouched. */
    public double[] transform(double[] row) {
        double[] out = row.clone();
        transformRowInPlace(out);
        return out;
    }

    /**
     * Inverse hyperbolic sine. {@code Math} has no {@code asinh}; the closed form
     * {@code ln(x + sqrt(x²+1))} is odd and well-defined for all finite inputs
     * (the radicand always exceeds |x|, so no domain issues for negatives).
     */
    private static double asinh(double x) {
        return Math.log(x + Math.sqrt(x * x + 1.0));
    }
}
