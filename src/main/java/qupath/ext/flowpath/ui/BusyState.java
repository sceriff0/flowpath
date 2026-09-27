package qupath.ext.flowpath.ui;

import java.util.Optional;

/**
 * What the panel may offer while its background workers are busy — not whether
 * anything is running at all; see the note below on {@code FlowPathPane.updateSpinner}.
 * <p>
 * The pane's three background workers each used to decide for themselves what to disable:
 * {@code IngestCoordinator} greyed the editor and the Add Gate button, {@code CsvExportCoordinator}
 * the export button, and {@code DerivationCoordinator} — the newest — nothing at all, which is
 * how a Ctrl+E fired mid-derivation could snapshot a tree the statistics beside it no longer
 * describe, and how the editor stayed live over a gate the session had already replaced. The
 * question "what is available while busy?" is answered here, once, as a pure function of the
 * three states, and {@code FlowPathPane.updateBusyControls} applies the answer.
 * <p>
 * This is deliberately not "is anything running?" — a fourth state, an ordinary gating pass
 * ({@code LivePreviewService}'s own busy flag), is not tracked here at all, because editing
 * and exporting both stay allowed while one runs; only ingest-loading and stats-deriving
 * block them. {@code FlowPathPane.updateSpinner} answers the "is anything running?" question
 * instead, over all four states including the gating pass, for the spinner's own purpose —
 * it deliberately keeps its own predicate rather than reading one off this record.
 * <p>
 * Toolkit-free and table-tested, like {@code umap/session/ViewState} and
 * {@code analysis/session/AnalysisState}: a disabling rule that can be read off a table is one
 * a later worker can join without re-deriving what the others meant.
 *
 * @param loading   a new image's cells are being read: the session has none yet
 * @param deriving  the masks and statistics are being recomputed for a tree edit already made
 * <p>
 * Two cohort workers joined later, and neither blocks editing: sampling the project's other
 * slides and a batch run over all of them each work from a copy of the tree taken when they
 * started. Sampling does block starting a batch run (see {@link #batchBlocked()}). Both can last minutes, so neither replaces the status bar's counts either — their
 * progress is appended to it from {@code CohortState.message()}.
 *
 * @param exporting    the CSV writer is running, from a snapshot taken when it started
 * @param sampling     the project's slides are being sampled for staining alignment
 * @param batchRunning the gate tree is being run on every slide of the project
 */
record BusyState(boolean loading, boolean deriving, boolean exporting, boolean sampling, boolean batchRunning) {

    static final BusyState IDLE = new BusyState(false, false, false, false, false);

    /**
     * Whether the gate the editor shows may be edited.
     * <p>
     * Not while cells are being read (there are none to gate), and not while a derivation is
     * in flight: the editor writes into the gate and reports afterwards, and both halves of
     * that would run against statistics the session is in the middle of replacing — a
     * re-pointed legacy z-score gate would be converted through the outgoing statistics and
     * keep the wrong threshold for good.
     * <p>
     * Sampling other slides and a batch run never block editing: neither reads the live tree
     * after it starts.
     */
    boolean editingBlocked() {
        return loading || deriving;
    }

    /**
     * Whether a CSV export may start. The export snapshots the tree, the statistics, the ROI
     * mask and the regions together; mid-derivation those describe two different states, so
     * the file would be gated against masks the tree it names never saw. Not during a batch
     * run either: one background writer at a time.
     */
    boolean exportBlocked() {
        return loading || deriving || exporting || batchRunning;
    }

    /**
     * Whether a batch run may start. One background writer at a time: a batch run waits for an
     * export, a read or a derivation, and for the batch run already going. And not while the
     * cohort is being sampled (final ruling I4): the run gates with the alignments the review was
     * built from, and mid-sampling those lack every slide not sampled yet, which would run
     * uncorrected without anyone having been told.
     */
    boolean batchBlocked() {
        return loading || deriving || exporting || sampling || batchRunning;
    }

    /**
     * Whether "Run on all slides" may start — not {@link #batchBlocked()}, and a tree with an
     * enabled gate ({@code BatchRunner.hasEnabledGate}) — the one predicate the button and the
     * status line's "Ready to run" both read.
     */
    boolean batchAllowed(boolean hasEnabledGate) {
        return !batchBlocked() && hasEnabledGate;
    }

    /**
     * What the status bar says instead of its counts, or empty when the counts stand.
     * <p>
     * An export says nothing: it runs from its own snapshot and the panel stays usable. A
     * derivation must say something — with the ROI filter just switched on, the tree already
     * reads "filtered" while the regions are still null, and the bar would otherwise print
     * "ROI: no usable annotation", a specific diagnosis the user is meant to act on.
     */
    Optional<String> message() {
        if (loading) return Optional.of("Reading detections…");
        if (deriving) return Optional.of("Recomputing statistics…");
        // Sampling and a batch run can last minutes: their progress is appended to the normal
        // status line from CohortState.message() rather than replacing the cell counts.
        return Optional.empty();
    }
}
