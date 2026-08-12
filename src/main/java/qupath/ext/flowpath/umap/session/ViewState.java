package qupath.ext.flowpath.umap.session;

import java.util.Objects;

/**
 * What the UMAP panel may offer the user right now — <em>derived</em> from
 * {@link UmapSession}, never chosen.
 *
 * <h2>Why this is not an enum any more</h2>
 * {@code UiStateController} used to take a {@code UiState} enum constant and switch on it.
 * Every caller therefore had to decide which constant was true of the data, and four of
 * them decided differently: {@code UmapPane.computeRestingState()} consulted the gate mask,
 * its copy inside {@code clearPolygon()} did not, the export path re-derived a third
 * variant, and the empty state's Run button asked the toolbar button whether it was
 * disabled. The states were mutually exclusive by construction, so "is a gate open" and
 * "are enough markers ticked" — which are orthogonal to each other and to the run
 * lifecycle — had to be smuggled into the choice of constant or dropped.
 * <p>
 * Here the stage says what phase the panel is in, and the affordances are separate
 * booleans, each a function of the facts that actually govern it. Nothing outside
 * {@link UmapSession#viewState()} constructs one that describes a live session: the
 * session hands this to its subscribers itself, so there is no seam through which a caller
 * can name a state at all — nor one through which a caller can fail to apply one.
 *
 * <h2>The invariants are checked here</h2>
 * The compact constructor rejects combinations that cannot be true of any session — a
 * Cancel offered outside a run, a Compute offered alongside it, a tag field unlocked with
 * no polygon closed, a FAILED stage with no reason. A future edit to the
 * derivation that produces one of these fails loudly at the point of construction rather
 * than reaching the widgets and being noticed as a stuck button.
 *
 * @param canCompute      Run UMAP is clickable: cells are indexed, at least
 *                        {@code EmbeddingFeatures.MINIMUM_FEATURES} markers are ticked,
 *                        and no run is in flight
 * @param canCancel       Cancel is visible and clickable — exactly while a run is in flight
 * @param canGate         Draw / Clear Shape are usable: an embedding is on screen and idle
 * @param canTag          the tag name/colour/apply controls are unlocked — only with a
 *                        closed polygon
 * @param canExport       Export Data is clickable: an embedding exists, nothing is running
 *                        and no export is already writing
 * @param canEditInputs   the feature picker and every embedding parameter may be touched.
 *                        False during a run: {@code onFeatureSelectionChanged} reinstalls
 *                        the session's {@code CellIndex} while the compute thread is still
 *                        reading the old one
 * @param indexRebuilding a feature rebuild is in flight, so the columns the next run would
 *                        read are about to change under it. Withholds {@code canCompute},
 *                        symmetrically with a run withholding {@code canEditInputs}
 * @param showEmptyState  the "no embedding yet" overlay covers the plot
 * @param offerFirstRun   that overlay carries its own Run button — only when there are
 *                        cells to embed
 * @param standalone      no gating snapshot drives this session, so the panel owns its own
 *                        annotation filter
 * @param failure         why the last run failed, or {@code null}. Always set on
 *                        {@link Stage#FAILED}; also set when a re-run failed over a
 *                        surviving embedding, where the stage stays COMPUTED/GATING/TAGGED
 */
public record ViewState(Stage stage,
                        boolean canCompute,
                        boolean canCancel,
                        boolean canGate,
                        boolean canTag,
                        boolean canExport,
                        boolean canEditInputs,
                        boolean indexRebuilding,
                        boolean showEmptyState,
                        boolean offerFirstRun,
                        boolean standalone,
                        String failure) {

    /**
     * The phase the panel is in — what the overlay and the status line talk about, as
     * opposed to what the buttons do.
     */
    public enum Stage {
        /** No cells indexed: nothing can be embedded yet. */
        NO_IMAGE,
        /** Cells indexed, no embedding yet. */
        READY,
        /** A run is in flight. The only stage in which Cancel exists. */
        COMPUTING,
        /** An embedding is on screen and no polygon is open. */
        COMPUTED,
        /** A polygon is closed over the embedding, so a population can be named. */
        GATING,
        /** As {@link #COMPUTED}, with at least one population tag applied. */
        TAGGED,
        /**
         * The last run ended in {@code UmapOutcome.Failed} and left nothing to show.
         * <p>
         * There was no such stage before, which is most of why a crashed embedding was
         * invisible: the error alert was modal and momentary, the status line wiped itself
         * after five seconds, and the panel then sat in READY over an empty plot reading
         * "Ready to embed" — the same thing it says when nothing has been tried. A stage
         * that survives the alert is what lets the overlay keep saying so.
         * <p>
         * A failure over an <em>existing</em> embedding is not this stage: the plot still
         * has something true on it, so the panel stays COMPUTED/GATING/TAGGED and carries
         * {@link ViewState#failure()} alongside. {@code failure() != null} — not the stage —
         * is the question "did the last run fail".
         */
        FAILED
    }

    public ViewState {
        Objects.requireNonNull(stage, "stage");
        require(canCancel == (stage == Stage.COMPUTING),
                "Cancel is offered exactly while a run is in flight");
        require(!(canCompute && canCancel),
                "Compute and Cancel must never both be offered — one click would race the other");
        require(!canTag || stage == Stage.GATING,
                "tag controls unlock only with a closed polygon");
        require(!canTag || canGate,
                "a closed polygon implies a gateable embedding");
        require(stage != Stage.COMPUTING || !(canCompute || canGate || canExport || canEditInputs),
                "a run in flight locks compute, gating, export and every input");
        require(!(indexRebuilding && canCompute),
                "a run must not start over columns a rebuild is about to replace");
        require(stage != Stage.FAILED || failure != null,
                "the FAILED stage must carry the reason it failed");
        require(stage != Stage.COMPUTING || failure == null,
                "a run in flight has not failed");
        require(!canGate || !showEmptyState,
                "an embedding cannot be gateable and absent at once");
        require(!offerFirstRun || showEmptyState,
                "the empty state's Run button cannot be offered without the empty state");
    }

    /** {@code true} when the panel has an embedding to show. */
    public boolean hasEmbedding() {
        return !showEmptyState;
    }

    private static void require(boolean condition, String what) {
        if (!condition) {
            throw new IllegalArgumentException("Contradictory ViewState: " + what);
        }
    }
}
