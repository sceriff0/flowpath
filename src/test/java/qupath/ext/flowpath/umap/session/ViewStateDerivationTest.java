package qupath.ext.flowpath.umap.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.CompartmentCapability;
import qupath.ext.flowpath.model.MarkerSelection;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.testing.Cells;
import qupath.ext.flowpath.umap.PhenotypeSnapshot;
import qupath.ext.flowpath.umap.engine.EmbeddingReport;
import qupath.ext.flowpath.umap.testing.Embeddings;
import qupath.ext.flowpath.umap.engine.UmapOutcome;
import qupath.ext.flowpath.umap.model.PopulationTag;
import qupath.ext.flowpath.umap.model.UmapParameters;
import qupath.ext.flowpath.umap.model.UmapResult;
import qupath.ext.flowpath.umap.session.ViewState.Stage;
import qupath.lib.objects.PathObject;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole UI-state decision, tested without a JavaFX toolkit.
 * <p>
 * This file is the point of the change it tests. The rule it exercises used to live in
 * {@code UmapPane.computeRestingState()} — a private method on a {@code BorderPane} that
 * needs a {@code QuPathGUI} to construct — so it was never tested, was duplicated inside
 * {@code clearPolygon()} with the gate-mask branch missing, and was consulted by
 * {@code ComputeController} on every cancel and every error. Note the absence of any
 * {@code assumeTrue(FxTestSupport.toolkitAvailable())} guard below: if one ever becomes
 * necessary here, the derivation has leaked back into the widgets.
 */
class ViewStateDerivationTest {

    private static final List<String> PANEL = List.of("CD3", "CD8", "FoxP3");

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static CellIndex index(int cells) {
        return Cells.of(cells).atGrid(1, 1)
                .marker("CD3", i -> i)
                .marker("CD8", i -> i * 2.0)
                .marker("FoxP3", i -> i * 3.0)
                .build();
    }

    /** A standalone session holding {@code cells} indexed cells and nothing else. */
    private static UmapSession withCells(int cells) {
        var session = new UmapSession();
        var idx = index(cells);
        session.installIndex(idx, MarkerStats.compute(idx), PANEL,
                CompartmentCapability.empty(), new MarkerSelection());
        return session;
    }

    /** A session whose cell set arrived from the gating tree. */
    private static UmapSession withSnapshot(int cells) {
        var session = new UmapSession();
        session.adopt(snapshotOf(index(cells)));
        return session;
    }

    private static PhenotypeSnapshot snapshotOf(CellIndex idx) {
        int n = idx.size();
        String[] labels = new String[n];
        int[] colors = new int[n];
        boolean[] excluded = new boolean[n];
        for (int i = 0; i < n; i++) labels[i] = "T cell";
        return new PhenotypeSnapshot(idx, MarkerStats.compute(idx), PANEL,
                CompartmentCapability.empty(), labels, colors, excluded,
                List.of("CD3", "CD8"), new MarkerSelection(), 2, "img");
    }

    private static UmapResult embeddingOver(CellIndex idx) {
        int n = idx.size();
        PathObject[] objects = idx.getObjects();
        return new UmapResult(new double[n], new double[n], objects,
                PANEL.toArray(new String[0]), UmapParameters.defaults());
    }

    private static EmbeddingReport cleanReport(CellIndex idx) {
        return EmbeddingReport.training(Embeddings.of(idx), null)
                .completedWith(EmbeddingReport.Steering.none(), EmbeddingReport.Projection.none());
    }

    /** Drive {@code session} to a finished embedding the way a real run does. */
    private static UmapSession embedded(UmapSession session) {
        session.beginRun();
        session.record(UmapOutcome.succeeded(embeddingOver(session.index()),
                cleanReport(session.index())));
        return session;
    }

    private static void untickAllBut(UmapSession session, int keep) {
        // Through the session, the way the feature picker writes. Putting into what
        // selection() returns changes a copy — which is the point of it being a copy.
        for (int i = keep; i < PANEL.size(); i++) {
            session.editSelection(PANEL.get(i), MarkerSelection.defaultEntry().withIncluded(false));
        }
    }

    // ------------------------------------------------------------------
    // The stage table: (index, snapshot, embedding, gateMask, tags, running)
    // ------------------------------------------------------------------

    /**
     * Every combination that can arise, named by the situation it describes. The old rule
     * covered four of these rows; the two it dropped are the ones that hurt — a gate open
     * when a cancel lands ({@code clearPolygon}'s copy forgot the mask) and a run that
     * failed with nothing on the plot (no state existed for it at all).
     */
    static Stream<Object[]> stageTable() {
        return Stream.of(
                new Object[]{"no image at all", (Runner) s -> {}, new UmapSession(), Stage.NO_IMAGE},
                new Object[]{"cells indexed, nothing embedded", (Runner) s -> {},
                        withCells(8), Stage.READY},
                new Object[]{"cells from a gating snapshot, nothing embedded", (Runner) s -> {},
                        withSnapshot(8), Stage.READY},
                new Object[]{"a run in flight over indexed cells",
                        (Runner) UmapSession::beginRun, withCells(8), Stage.COMPUTING},
                new Object[]{"a run in flight over an existing embedding",
                        (Runner) UmapSession::beginRun, embedded(withCells(8)), Stage.COMPUTING},
                new Object[]{"an embedding on screen, no gate, no tags", (Runner) s -> {},
                        embedded(withCells(8)), Stage.COMPUTED},
                new Object[]{"a polygon closed over the embedding",
                        (Runner) s -> s.setGateMask(new boolean[8]),
                        embedded(withCells(8)), Stage.GATING},
                new Object[]{"a tag applied and the gate retired",
                        (Runner) s -> s.addTag(new PopulationTag("CD4", 0xFF0000, new boolean[8])),
                        embedded(withCells(8)), Stage.TAGGED},
                new Object[]{"a gate open on a tagged embedding — the row the duplicate rule lost",
                        (Runner) s -> {
                            s.addTag(new PopulationTag("CD4", 0xFF0000, new boolean[8]));
                            s.setGateMask(new boolean[8]);
                        },
                        embedded(withCells(8)), Stage.GATING},
                new Object[]{"the first run failed and left nothing to show",
                        (Runner) s -> {
                            s.beginRun();
                            s.record(UmapOutcome.failed("out of memory"));
                        }, withCells(8), Stage.FAILED},
                new Object[]{"a re-run failed over a surviving embedding",
                        (Runner) s -> {
                            s.beginRun();
                            s.record(UmapOutcome.failed("out of memory"));
                        }, embedded(withCells(8)), Stage.COMPUTED},
                new Object[]{"a gate is meaningless without an embedding",
                        (Runner) s -> s.setGateMask(new boolean[8]), withCells(8), Stage.READY},
                new Object[]{"the image changed and the gating tree has not re-pushed yet",
                        (Runner) UmapSession::detachSnapshot, withSnapshot(8), Stage.NO_IMAGE},
                new Object[]{"a feature rebuild is in flight",
                        (Runner) UmapSession::beginRebuild, withCells(8), Stage.READY});
    }

    /** A session mutation applied on top of a fixture, for the table above. */
    interface Runner {
        void accept(UmapSession session);
    }

    @ParameterizedTest(name = "{0} -> {3}")
    @MethodSource("stageTable")
    @DisplayName("Stage is a total function of the session's facts")
    void stageTable(String situation, Runner setup, UmapSession session, Stage expected) {
        setup.accept(session);
        assertEquals(expected, session.viewState().stage(), situation);
    }

    // ------------------------------------------------------------------
    // The affordances
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Compute is refused below the embedding's minimum feature count")
    void computeNeedsEnoughTickedMarkers() {
        var session = withCells(8);
        assertTrue(session.viewState().canCompute(), "three ticked markers is enough");

        untickAllBut(session, 2);
        assertTrue(session.viewState().canCompute(), "two is the minimum, and it is enough");

        untickAllBut(session, 1);
        var state = session.viewState();
        assertEquals(Stage.READY, state.stage(),
                "one marker is a READY panel with a Run button that must not be clickable");
        assertFalse(state.canCompute(),
                "the toolbar button used to invite a click whose only ending was a failure");
        assertTrue(state.offerFirstRun(),
                "the overlay still offers its Run button — a panel with cells has one, and "
                        + "withdrawing it would say 'nothing to run' when the truth is "
                        + "'not enough markers'");
        // The conjunction this replaces was constant-false the moment the line above it
        // passed, so it pinned nothing. The agreement worth pinning is that BOTH Run
        // affordances read their enabled-ness off the same canCompute: the overlay's is
        // shown and disabled, never shown and clickable.
    }

    @Test
    @DisplayName("Compute needs cells, not merely a session")
    void computeNeedsCells() {
        assertFalse(new UmapSession().viewState().canCompute());
    }

    @Test
    @DisplayName("An export in flight withdraws Export and nothing else")
    void exportInFlight() {
        var session = embedded(withCells(8));
        assertTrue(session.viewState().canExport());

        session.beginExport();
        var during = session.viewState();
        assertFalse(during.canExport(), "a second click would write the same file twice");
        assertTrue(during.canGate(), "the plot is still gateable while the CSV writes");
        assertTrue(during.canCompute());

        session.endExport();
        assertTrue(session.viewState().canExport());
    }

    @Test
    @DisplayName("A run in flight locks every input — the mid-run-edit hole")
    void aRunLocksTheInputs() {
        var session = embedded(withCells(8));
        session.setGateMask(new boolean[8]);
        assertTrue(session.viewState().canEditInputs());

        session.beginRun();
        var during = session.viewState();
        // onFeatureSelectionChanged calls session.installRebuiltIndex(...) — swapping the
        // CellIndex out from under a compute thread that is still reading it. Unlike
        // reloadCells, that path has no computeService.cancel(), so nothing else stops it.
        assertFalse(during.canEditInputs(),
                "the feature picker and the embedding parameters must be unreachable mid-run");
        assertFalse(during.canCompute());
        assertFalse(during.canGate());
        assertFalse(during.canTag());
        assertFalse(during.canExport());
        assertTrue(during.canCancel(), "cancel is the only way out");
    }

    @Test
    @DisplayName("The annotation filter belongs to the panel only when no snapshot drives it")
    void standaloneTracksSnapshotMode() {
        assertTrue(withCells(8).viewState().standalone());
        assertFalse(withSnapshot(8).viewState().standalone());
    }

    // ------------------------------------------------------------------
    // The stale cell set
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Detaching a snapshot leaves nothing runnable over the previous image's cells")
    void detachingASnapshotWithdrawsTheStaleCellSet() {
        var session = embedded(withSnapshot(8));
        assertEquals(Stage.COMPUTED, session.viewState().stage());

        // What UmapPane does when the active image changes while the gating tree owns the
        // cells: drop the snapshot and wait for the next push. The CellIndex deliberately
        // survives — the pane still needs those PathObjects until a replacement arrives.
        session.detachSnapshot();

        assertNotNull(session.index(), "the stale index is still held");
        assertTrue(session.isAwaitingSnapshot());

        var state = session.viewState();
        assertEquals(Stage.NO_IMAGE, state.stage(),
                "READY here would offer a Run over the PREVIOUS image's cells");
        assertFalse(state.canCompute());
        assertFalse(state.offerFirstRun());
        assertFalse(state.standalone(),
                "the gating pane still owns the cell set, so this panel offers no "
                        + "annotation filter of its own — it would re-index the new image "
                        + "behind the gating pane's back");
        assertFalse(session.hasCells());
        assertFalse(state.canEditInputs(),
                "and the feature picker is still populated with the PREVIOUS image's "
                        + "markers — ticking one there re-indexed the new image's whole "
                        + "hierarchy behind the gating pane's back");
    }

    /**
     * "Who owns the cells" is one fact, and the awaiting window is on the gating side of it.
     * <p>
     * {@code isSnapshotMode()} used to be a second, unrepaired reading of the same question
     * ({@code snapshot != null}), which the awaiting window falsified: it said standalone
     * while {@link ViewState#standalone()} said otherwise about the same instant.
     */
    @Test
    @DisplayName("Awaiting a snapshot is still snapshot mode, and says so once")
    void awaitingIsStillSnapshotMode() {
        var session = withSnapshot(8);
        assertTrue(session.isSnapshotMode());

        session.detachSnapshot();

        assertNull(session.snapshot());
        assertTrue(session.isSnapshotMode(), "the gating pane has not handed the cells back");
        // Deliberately no `standalone() == !isSnapshotMode()` assertion: standalone is now
        // *defined* as that, so it could not fail.
        assertNotNull(session.detectionsForRebuild());
        assertTrue(session.detectionsForRebuild().isEmpty(),
                "a rebuild in this window has nothing to do, rather than the new hierarchy");
    }

    @Test
    @DisplayName("The next snapshot ends the wait")
    void adoptingEndsTheWait() {
        var session = withSnapshot(8);
        session.detachSnapshot();

        session.adopt(snapshotOf(index(6)));

        assertFalse(session.isAwaitingSnapshot());
        assertEquals(Stage.READY, session.viewState().stage());
        assertTrue(session.viewState().canCompute());
    }

    @Test
    @DisplayName("adopt(null) is the same detach, not a second one")
    void adoptingNullTakesTheSameRouteAsDetach() {
        var session = embedded(withSnapshot(8));

        assertEquals(UmapSession.Adoption.DETACHED, session.adopt(null));

        // This branch used to null the snapshot and stop, which was identical to
        // detachSnapshot() until detachSnapshot() learnt about the stale cell set. Left
        // behind, it was a public route back into READY over the previous image's cells.
        assertTrue(session.isAwaitingSnapshot());
        var state = session.viewState();
        assertEquals(Stage.NO_IMAGE, state.stage());
        assertFalse(state.canCompute());
        assertFalse(state.standalone());
        assertNull(session.embedding(), "and the embedding it described goes with it");
    }

    @Test
    @DisplayName("Forgetting the image entirely also ends the wait")
    void clearingTheIndexEndsTheWait() {
        var session = withSnapshot(8);
        session.detachSnapshot();
        assertFalse(session.viewState().standalone());

        // Reachable in production: a second image change lands in the standalone branch of
        // initializeFromImage, which reloads rather than waiting. Leaving the flag set would
        // hide the annotation filter on a panel that now owns its own cells.
        session.clearIndex();

        assertFalse(session.isAwaitingSnapshot());
        assertTrue(session.viewState().standalone());
    }

    @Test
    @DisplayName("A standalone re-index ends the wait too")
    void installingAnIndexEndsTheWait() {
        var session = withSnapshot(8);
        session.detachSnapshot();

        var idx = index(6);
        session.installIndex(idx, MarkerStats.compute(idx), PANEL,
                CompartmentCapability.empty(), new MarkerSelection());

        assertFalse(session.isAwaitingSnapshot());
        assertTrue(session.viewState().standalone());
        assertTrue(session.viewState().canCompute());
    }

    // ------------------------------------------------------------------
    // The rebuild lock, the other half of the mid-run-edit hole
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A pending feature rebuild withholds Run, symmetrically with a run withholding edits")
    void aPendingRebuildWithholdsRun() {
        var session = withCells(8);
        assertTrue(session.viewState().canCompute());

        // runUmap does not bump the build generation, so without this a user could tick a
        // marker and click Run before the rebuild lands — installRebuiltIndex would then
        // seat a new CellIndex under a live compute, which is exactly what locking the
        // inputs during a run was meant to prevent.
        session.beginRebuild();
        var during = session.viewState();
        assertTrue(during.indexRebuilding());
        assertFalse(during.canCompute());
        assertTrue(during.offerFirstRun(),
                "still offered — withheld, not hidden, so the disabled state is legible");

        session.endRebuild();
        assertTrue(session.viewState().canCompute());
    }

    @Test
    @DisplayName("Overlapping rebuilds only unlock Run when the last one lands")
    void overlappingRebuildsCount() {
        var session = withCells(8);
        session.beginRebuild();
        session.beginRebuild();

        session.endRebuild();
        assertFalse(session.viewState().canCompute(), "one is still in flight");

        session.endRebuild();
        assertTrue(session.viewState().canCompute());

        session.endRebuild();
        assertTrue(session.viewState().canCompute(), "an unbalanced end must not go negative");
    }

    @Test
    @DisplayName("The empty state and its Run button track the embedding and the cells")
    void emptyStateFollowsTheData() {
        assertTrue(new UmapSession().viewState().showEmptyState());
        assertFalse(new UmapSession().viewState().offerFirstRun(),
                "nothing to run over");

        var ready = withCells(8).viewState();
        assertTrue(ready.showEmptyState());
        assertTrue(ready.offerFirstRun());

        var computed = embedded(withCells(8)).viewState();
        assertFalse(computed.showEmptyState());
        assertFalse(computed.offerFirstRun());
        assertTrue(computed.hasEmbedding());
    }

    // ------------------------------------------------------------------
    // Outcome -> state
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Succeeded ends the run, installs the embedding and clears any failure")
    void succeededOutcome() {
        var session = withCells(8);
        session.beginRun();
        session.record(UmapOutcome.failed("first attempt died"));
        session.beginRun();

        var result = embeddingOver(session.index());
        var state = session.record(UmapOutcome.succeeded(result, cleanReport(session.index())));

        assertEquals(Stage.COMPUTED, state.stage());
        assertSame(result, session.embedding());
        assertNull(state.failure(), "a successful run erases the previous one's failure");
        assertFalse(session.isRunning());
    }

    @Test
    @DisplayName("Failed leaves a reason that outlives the alert")
    void failedOutcome() {
        var session = withCells(8);
        session.beginRun();

        var state = session.record(UmapOutcome.failed("no memory", new OutOfMemoryError()));

        assertEquals(Stage.FAILED, state.stage());
        assertNotNull(state.failure());
        assertTrue(state.failure().contains("no memory"));
        assertTrue(state.failure().contains("OutOfMemoryError"),
                "Failed carries the throwable class separately; the panel shows both");
        assertTrue(state.canCompute(), "and the user may try again immediately");
        assertFalse(session.isRunning());
    }

    @Test
    @DisplayName("Cancelled returns to whatever the data supports, gate included")
    void cancelledOutcome() {
        var session = embedded(withCells(8));
        session.setGateMask(new boolean[8]);
        session.beginRun();

        var state = session.record(UmapOutcome.cancelled());

        // computeRestingState() got this right and its copy inside clearPolygon() did not.
        assertEquals(Stage.GATING, state.stage(),
                "cancelling a re-run must not disable Tag Selection under an open polygon");
        assertTrue(state.canTag());
        assertNull(state.failure(), "a cancel is not a failure");
    }

    @Test
    @DisplayName("Superseded changes nothing — the newer run still owns COMPUTING")
    void supersededOutcome() {
        var session = embedded(withCells(8));
        session.beginRun();
        var before = session.viewState();

        var after = session.record(UmapOutcome.superseded());

        assertEquals(Stage.COMPUTING, after.stage(),
                "reacting would drive the panel out of a state that is still true");
        assertEquals(before, after, "a superseded ending is not an ending for the view");
        assertTrue(session.isRunning());
        assertTrue(after.canCancel(), "the newer run is still cancellable");
    }

    @Test
    @DisplayName("Superseded over an idle session is still inert")
    void supersededWhenNothingIsRunning() {
        var session = embedded(withCells(8));
        var before = session.viewState();
        assertEquals(before, session.record(UmapOutcome.superseded()));
    }

    @Test
    @DisplayName("Cancel-then-outcome is idempotent")
    void cancelIsIdempotentWithItsOutcome() {
        var session = withCells(8);
        session.beginRun();
        session.cancelRun();
        assertEquals(Stage.READY, session.viewState().stage());
        assertEquals(Stage.READY, session.record(UmapOutcome.cancelled()).stage());
    }

    @Test
    void recordRejectsNull() {
        assertThrows(NullPointerException.class, () -> new UmapSession().record(null));
    }

    // ------------------------------------------------------------------
    // Cell-set turnover
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Retiring the cell set retires the embedding and the failure with it")
    void retireCellSetDropsEverythingPositional() {
        var session = embedded(withCells(8));
        session.setGateMask(new boolean[8]);
        session.addTag(new PopulationTag("CD4", 0xFF0000, new boolean[8]));

        session.retireCellSet();

        assertNull(session.embedding(),
                "the embedding is positional against the cells being replaced");
        assertEquals(Stage.READY, session.viewState().stage());
    }

    @Test
    @DisplayName("A rebuild at a new feature resolution keeps the embedding")
    void featureRebuildKeepsTheEmbedding() {
        var session = embedded(withCells(8));
        var kept = session.embedding();

        var rebuilt = CellIndex.build(new ArrayList<>(List.of(session.index().getObjects())), PANEL);
        session.installRebuiltIndex(rebuilt, MarkerStats.compute(rebuilt));

        assertSame(kept, session.embedding(),
                "the cells did not change, so the layout is still theirs — recompute is the user's call");
        assertEquals(Stage.COMPUTED, session.viewState().stage());
    }

    // ------------------------------------------------------------------
    // The record's own invariants
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Every derived state satisfies ViewState's invariants")
    void everyDerivedStateIsSelfConsistent() {
        // Construction is where the invariants are enforced, so simply deriving each row of
        // the table above is the assertion — a contradictory derivation throws.
        stageTable().forEach(row -> {
            var session = (UmapSession) row[2];
            ((Runner) row[1]).accept(session);
            assertNotNull(session.viewState(), (String) row[0]);
        });
    }

    @Test
    @DisplayName("A hand-built contradiction is rejected rather than applied")
    void contradictionsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ViewState(
                Stage.COMPUTING, true, true, false, false, false, false, false,
                true, false, true, null), "compute and cancel offered together");
        assertThrows(IllegalArgumentException.class, () -> new ViewState(
                Stage.COMPUTED, true, false, true, true, true, true, false,
                false, false, true, null), "tag controls unlocked with no polygon closed");
        assertThrows(IllegalArgumentException.class, () -> new ViewState(
                Stage.FAILED, true, false, false, false, false, true, false,
                true, true, true, null), "FAILED with no reason");
        assertThrows(IllegalArgumentException.class, () -> new ViewState(
                Stage.COMPUTING, false, true, false, false, false, true, false,
                true, false, true, null), "inputs editable mid-run");
        assertThrows(IllegalArgumentException.class, () -> new ViewState(
                Stage.READY, true, false, false, false, false, true, true,
                true, true, true, null), "Run offered over columns a rebuild is replacing");
    }
}
