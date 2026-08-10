package qupath.ext.flowpath.umap.model;

/**
 * Feature-scaling strategy applied to the cell-by-marker matrix <em>before</em>
 * UMAP runs.
 *
 * <p>UMAP builds its neighbor graph in Euclidean space, so every marker
 * contributes to "cell similarity" in proportion to its raw numeric magnitude.
 * On multiplexed imaging data (CODEX/MIBI/mIF) marker intensities routinely
 * span 10–100× across channels, so without scaling a few bright, ubiquitous
 * markers dominate the embedding and rarer biology is washed out. Standardising
 * each marker first (z-score) is the conventional remedy in single-cell and
 * cytometry pipelines.</p>
 *
 * <p>The same transform must be applied to the held-out cells projected after a
 * subsampled run, using the parameters fit on the training set — see
 * {@code qupath.ext.flowpath.umap.engine.FeatureScaler}.</p>
 */
public enum ScalingMode {

    /** Use raw marker values unchanged. Magnitude-dominant markers will steer the layout. */
    NONE("None"),

    /** Standardise each marker to mean 0, unit variance. Recommended for multiplexed imaging. */
    ZSCORE("Z-score"),

    /** Inverse hyperbolic sine — a variance-stabilising transform that tames bright outliers. */
    ARCSINH("Arcsinh");

    private final String label;

    ScalingMode(String label) {
        this.label = label;
    }

    /** Human-readable label shown in the UI combo. */
    public String label() {
        return label;
    }

    /**
     * Resolve a {@link ScalingMode} from its UI label, falling back to
     * {@link #ZSCORE} (the recommended default) for null/unknown input rather
     * than throwing — the UI should never be able to wedge a compute on a typo.
     */
    public static ScalingMode fromLabel(String label) {
        if (label != null) {
            for (ScalingMode m : values()) {
                if (m.label.equals(label)) return m;
            }
        }
        return ZSCORE;
    }
}
