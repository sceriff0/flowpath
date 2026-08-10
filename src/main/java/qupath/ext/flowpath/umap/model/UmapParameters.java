package qupath.ext.flowpath.umap.model;

public record UmapParameters(int k, double minDist, double spread, int epochs, int negativeSamples) {

    /**
     * Sentinel epochs value meaning "let the compute service pick the epoch count
     * adaptively from the training-set size". Concretely the service substitutes
     * {@link #defaultsFor(int)}'s epochs at the time UMAP runs.
     */
    public static final int ADAPTIVE_EPOCHS = -1;

    public UmapParameters {
        if (k <= 0) throw new IllegalArgumentException("k must be > 0, got: " + k);
        if (minDist < 0) throw new IllegalArgumentException("minDist must be >= 0, got: " + minDist);
        if (spread <= 0) throw new IllegalArgumentException("spread must be > 0, got: " + spread);
        if (minDist > spread) throw new IllegalArgumentException("minDist must be <= spread, got minDist=" + minDist + " spread=" + spread);
        if (epochs <= 0 && epochs != ADAPTIVE_EPOCHS)
            throw new IllegalArgumentException("epochs must be > 0 or ADAPTIVE_EPOCHS, got: " + epochs);
        if (negativeSamples <= 0) throw new IllegalArgumentException("negativeSamples must be > 0, got: " + negativeSamples);
    }

    /** Backward-compatible constructor defaulting negativeSamples to 5. */
    public UmapParameters(int k, double minDist, double spread, int epochs) {
        this(k, minDist, spread, epochs, 5);
    }

    /**
     * Default UMAP parameters with {@link #ADAPTIVE_EPOCHS} as the sentinel for
     * epochs — the compute service substitutes a concrete count based on the
     * training-N at the time of computation.
     */
    public static UmapParameters defaults() {
        return new UmapParameters(15, 0.1, 1.0, ADAPTIVE_EPOCHS, 5);
    }

    /**
     * Build a defaults instance for a given training-set size, picking epochs
     * adaptively: more passes for small sets (where each epoch is cheap and
     * convergence matters), fewer for large sets (where each epoch is expensive
     * and the layout converges in fewer passes).
     */
    public static UmapParameters defaultsFor(int trainingN) {
        int epochs = trainingN < 10_000 ? 200 : trainingN < 50_000 ? 100 : 60;
        return new UmapParameters(15, 0.1, 1.0, epochs, 5);
    }
}
