package qupath.ext.flowpath.ui;

import java.util.Optional;

/**
 * What the panel may offer while these three background workers are busy — not whether
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
 * @param exporting the CSV writer is running, from a snapshot taken when it started
 */
record BusyState(boolean loading, boolean deriving, boolean exporting) {

    static final BusyState IDLE = new BusyState(false, false, false);

    /**
     * Whether the gate the editor shows may be edited.
     * <p>
     * Not while cells are being read (there are none to gate), and not while a derivation is
     * in flight: the editor writes into the gate and reports afterwards, and both halves of
     * that would run against statistics the session is in the middle of replacing — a
     * re-pointed legacy z-score gate would be converted through the outgoing statistics and
     * keep the wrong threshold for good.
     */
    boolean editingBlocked() {
        return loading || deriving;
    }

    /**
     * Whether a CSV export may start. The export snapshots the tree, the statistics, the ROI
     * mask and the regions together; mid-derivation those describe two different states, so
     * the file would be gated against masks the tree it names never saw.
     */
    boolean exportBlocked() {
        return loading || deriving || exporting;
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
        return Optional.empty();
    }
}
