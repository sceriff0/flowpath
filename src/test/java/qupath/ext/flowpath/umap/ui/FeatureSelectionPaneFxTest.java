package qupath.ext.flowpath.umap.ui;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.Compartment;
import qupath.ext.flowpath.model.CompartmentCapability;
import qupath.ext.flowpath.model.MarkerSelection;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.testing.Cells;
import qupath.ext.flowpath.testing.FxTestSupport;
import qupath.ext.flowpath.umap.session.UmapSession;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The feature picker enables compartment/statistic combos for rich data and disables them
 * (pinned whole-cell/mean) for legacy data — and every edit it makes lands on
 * {@link UmapSession}, which is the whole reason {@link FeatureSelectionPane#populate}
 * takes the session and nothing else.
 * <p>
 * The earlier shape passed the picker a {@code MarkerSelection} and, later, a
 * {@code BiConsumer} writer. Both left the call site free to name
 * {@code selection()::put} — reinstating the leak the observer design rests on, with the
 * whole suite green. Skips when no display is available.
 */
class FeatureSelectionPaneFxTest {

    private static final List<String> PANEL = List.of("CD3");

    /** A standalone session holding one marker, its capability and its selection. */
    private static UmapSession sessionWith(CompartmentCapability cap, MarkerSelection selection) {
        CellIndex index = Cells.of(2).atGrid(1, 1).marker("CD3", i -> i).build();
        var session = new UmapSession();
        session.installIndex(index, MarkerStats.compute(index), PANEL, cap, selection);
        return session;
    }

    @Test
    void richDataPopulatesEditableCombosAndEditsLandOnTheSession() {
        assumeTrue(FxTestSupport.toolkitAvailable());

        var cap = CompartmentCapability.fromKeys(List.of(
                "CD3: Nucleus: Mean", "CD3: Cytoplasm: Mean", "CD3: Cell: Mean", "CD3: Cell: Median"));
        var session = sessionWith(cap, MarkerSelection.defaultFor(PANEL));
        var published = new AtomicInteger();
        session.observe(state -> published.incrementAndGet());
        // The delta is taken across the edit alone. Against a fixed floor it was not:
        // observe() publishes once on subscribe and populate() publishes again on its own,
        // so "> 1" held whether or not the edit was ever heard.
        var beforeTheEdit = new AtomicInteger(-1);

        FxTestSupport.onFxRun(() -> {
            var pane = new FeatureSelectionPane();
            pane.populate(session);
            beforeTheEdit.set(published.get());
            // Simulate the user choosing the nuclear compartment. This is the write the
            // combo's own handler makes — through the session, because the pane has no
            // other way to write.
            session.editSelection("CD3",
                    session.selectionEntry("CD3").withCompartment(Compartment.NUCLEAR));
        });

        assertTrue(cap.isRich());
        assertEquals(Compartment.NUCLEAR, session.selectionEntry("CD3").compartment());
        assertTrue(published.get() > beforeTheEdit.get(),
                "the session heard the edit — an edit it does not hear is one the panel "
                        + "never re-derives Run UMAP from. Published " + beforeTheEdit.get()
                        + " times before it and " + published.get() + " after");
    }

    @Test
    void legacyDataPinsTheSessionsSelectionToWholeCellMean() {
        assumeTrue(FxTestSupport.toolkitAvailable());

        var cap = CompartmentCapability.empty(); // legacy: not rich
        var selection = MarkerSelection.defaultFor(PANEL);
        // Even if a stale non-default entry exists, populate() must pin it back.
        selection.put("CD3", new MarkerSelection.Entry(Compartment.NUCLEAR, Statistic.MEDIAN, true));
        var session = sessionWith(cap, selection);

        var published = new AtomicInteger();
        session.observe(state -> published.incrementAndGet());
        // Read the floor rather than assume it: observe() publishes on subscribe, so a
        // literal "> 1" was one publish away from being satisfied by nothing populate did.
        int beforePopulate = published.get();

        FxTestSupport.onFxRun(() -> new FeatureSelectionPane().populate(session));

        // The pin is a write, and it is the one write populate() makes on its own. If it
        // went anywhere but the session, neither of these would hold.
        assertEquals(Compartment.WHOLE_CELL, session.selectionEntry("CD3").compartment());
        assertEquals(Statistic.MEAN, session.selectionEntry("CD3").statistic());
        assertTrue(published.get() > beforePopulate,
                "and the session heard it: published " + beforePopulate
                        + " times before populate() and " + published.get() + " after");
    }

    @Test
    void theSessionHandsOutACopyOfItsSelectionRatherThanTheObject() {
        var session = sessionWith(CompartmentCapability.empty(), MarkerSelection.defaultFor(PANEL));

        var handedOut = session.selection();
        assertNotSame(handedOut, session.selection());
        handedOut.put("CD3", MarkerSelection.defaultEntry().withIncluded(false));

        assertTrue(session.selectionEntry("CD3").included(),
                "writing into what selection() returned must not change whether Run UMAP "
                        + "is clickable behind the session's back");
    }
}
