package qupath.ext.flowpath.analysis.session;

import qupath.ext.flowpath.model.PopulationStats;

import java.util.List;

/**
 * What the Analysis window may offer right now — <em>derived</em> from
 * {@link AnalysisSession}, never chosen by a caller.
 * <p>
 * The same split {@code ViewState} makes for the UMAP panel, and for the same reason: when
 * the UI decides its own state, every code path that touches the UI becomes a decision-maker,
 * and they disagree. Here the session answers once and the window applies the answer.
 * <p>
 * The compact constructor rejects combinations no session can be in, so a future edit to the
 * derivation fails at construction rather than reaching the widgets and being noticed as a
 * stuck button.
 *
 * @param hasData        a gating pass has been accepted
 * @param hasRegions     at least one annotated region is in play
 * @param canExport      there is something worth writing to a file
 * @param cellCount      cells the pass covered
 * @param regionCount    annotated regions, 0 when none
 * @param availableScopes which scopes have rows; empty when there is no data
 * @param emptyMessage   what to show instead of a table, or {@code null} when there is data
 */
public record AnalysisState(boolean hasData, boolean hasRegions, boolean canExport,
                            int cellCount, int regionCount,
                            List<PopulationStats.Scope> availableScopes,
                            String emptyMessage) {

    public AnalysisState {
        availableScopes = List.copyOf(availableScopes);
        if (!hasData && canExport) {
            throw new IllegalArgumentException("cannot export with no data");
        }
        if (!hasData && !availableScopes.isEmpty()) {
            throw new IllegalArgumentException("no data means no scopes to report");
        }
        if (hasRegions && regionCount <= 0) {
            throw new IllegalArgumentException("hasRegions with regionCount " + regionCount);
        }
        if (hasData == (emptyMessage != null)) {
            throw new IllegalArgumentException(
                    "an empty panel must explain itself, and a full one must not");
        }
    }
}
