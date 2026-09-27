package qupath.ext.flowpath.cohort;

/**
 * What the cohort half of the panel may offer, derived by {@link CohortSession#state()} — never
 * set. Contradictory combinations are refused here, as {@code umap/session/ViewState} does.
 */
public record CohortState(boolean available, boolean sampling, int sampled, int total, int failed, int remaining,
                          String referenceName, String suggestedReferenceName, boolean correctionDisabled,
                          String message, boolean batchRunning, boolean canRunBatch) {

    public static final CohortState UNAVAILABLE =
            new CohortState(false, false, 0, 0, 0, 0, null, null, false, null, false, false);

    public CohortState {
        if (!available && (sampling || batchRunning || canRunBatch || total != 0 || remaining != 0)) {
            throw new IllegalArgumentException("an unavailable cohort offers nothing");
        }
        if (sampled + failed > total) throw new IllegalArgumentException("more slides sampled than exist");
        if (batchRunning && canRunBatch) throw new IllegalArgumentException("one batch run at a time");
        if (correctionDisabled && message == null) throw new IllegalArgumentException("disabled correction must say why");
    }
}
