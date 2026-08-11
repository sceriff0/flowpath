package qupath.ext.flowpath.umap.session;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.Compartment;
import qupath.ext.flowpath.model.CompartmentCapability;
import qupath.ext.flowpath.model.MarkerSelection;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.umap.PhenotypeSnapshot;
import qupath.ext.flowpath.testing.Cells;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The identity contract between {@link UmapSession} and {@link PhenotypeSnapshot}.
 * <p>
 * {@code PhenotypeSnapshot}'s constructor turns a positional misalignment into a loud
 * failure, but it can only see the arrays it is handed. The consumer can still falsify
 * the guarantee by <em>replacing the index underneath a snapshot</em>: the UMAP view
 * rebuilds its {@link CellIndex} whenever the feature picker changes, and a session that
 * keeps the old snapshot alongside the new index answers "same cells?" against a
 * remembered index rather than the one it is working with. Every assertion here pins the
 * corrected behaviour: the session never holds an index its snapshot does not name.
 * <p>
 * No JavaFX toolkit is involved, deliberately — these are rules, not widgets.
 */
class UmapSessionIdentityTest {

    // --- fixtures ---

    private static List<PathObject> cells(int n, double offset) {
        List<PathObject> out = new ArrayList<>(Cells.of(n)
                .at(i -> i + offset, i -> i + offset)
                .marker("CD3", i -> i + offset)
                .marker("CD3", Compartment.NUCLEAR, Statistic.MEAN, i -> (i + offset) * 2)
                .detections());
        out.forEach(o -> o.setPathClass(PathClass.fromString("Other", 0xFF112233)));
        return out;
    }

    private static final List<String> PANEL = List.of("CD3");

    private static CellIndex indexOf(List<PathObject> objects) {
        return CellIndex.build(objects, PANEL);
    }

    private static CellIndex indexOf(List<PathObject> objects, Compartment c, Statistic s) {
        var sel = new MarkerSelection();
        sel.put("CD3", new MarkerSelection.Entry(c, s, true));
        return CellIndex.build(objects, PANEL, sel);
    }

    /** A snapshot painting every cell the same phenotype, so drift is visible in the colours. */
    private static PhenotypeSnapshot snapshotOf(CellIndex index, String label, int rgb) {
        int n = index.size();
        String[] labels = new String[n];
        int[] colors = new int[n];
        for (int i = 0; i < n; i++) {
            labels[i] = label;
            colors[i] = rgb;
        }
        return new PhenotypeSnapshot(index, MarkerStats.compute(index), PANEL,
                CompartmentCapability.empty(), labels, colors, new boolean[n],
                List.of("CD3"), new MarkerSelection(), 1, "img-1");
    }

    // ------------------------------------------------------------------
    // The falsification
    // ------------------------------------------------------------------

    /**
     * The headline regression. A feature-picker change rebuilds the index from the
     * snapshot's own cells; if the session installs that index without reconciling the
     * snapshot, it is left holding two different indices at once.
     */
    @Test
    void featureRebuildLeavesTheSessionAndItsSnapshotOnTheSameIndex() {
        var objects = cells(4, 0);
        var indexA = indexOf(objects);
        var session = new UmapSession();
        assertEquals(UmapSession.Adoption.REBUILD, session.adopt(snapshotOf(indexA, "T cell", 0x00FF00)));
        assertSame(indexA, session.index());

        // The user edits the feature picker: same cells, different resolved column.
        var detections = session.detectionsForRebuild();
        assertNotNull(detections, "In snapshot mode the rebuild must reuse the snapshot's own cells");
        var indexB = CellIndex.build(detections, PANEL, new MarkerSelection());
        session.installRebuiltIndex(indexB, MarkerStats.compute(indexB));

        assertSame(indexB, session.index(), "The rebuilt index is the one in use");
        assertSame(indexB, session.snapshot().index(),
                "The snapshot must be rebound onto the index the session now holds — "
                        + "leaving it pointing at the previous build is exactly the drift "
                        + "PhenotypeSnapshot's length check cannot see");
        assertDoesNotThrow(session::assertIndexInvariant);
    }

    /**
     * The consequence: with a stale snapshot in hand, the recolour-vs-invalidate decision
     * is taken against an index the session is no longer using. It must be taken against
     * the live one.
     */
    @Test
    void recolourDecisionIsTakenAgainstTheLiveIndexNotARememberedOne() {
        var objects = cells(4, 0);
        var indexA = indexOf(objects);
        var session = new UmapSession();
        session.adopt(snapshotOf(indexA, "T cell", 0x00FF00));

        var indexB = CellIndex.build(session.detectionsForRebuild(), PANEL, new MarkerSelection());
        session.installRebuiltIndex(indexB, MarkerStats.compute(indexB));

        // Gate edit: the gating pane re-walks ITS index, which is still indexA.
        var edited = snapshotOf(indexA, "B cell", 0x0000FF);
        assertEquals(UmapSession.Adoption.RECOLOUR, session.adopt(edited),
                "Same cells, new labels — the embedding survives");
        assertSame(indexB, session.snapshot().index(),
                "Recolouring must not re-seat the session on the gating pane's older index");
        assertSame(indexB, session.index());
        assertDoesNotThrow(session::assertIndexInvariant);
    }

    /**
     * The corruption the falsified check permits. A rebuild that quietly changes which
     * cells are indexed — same count, different objects — slips past
     * {@code PhenotypeSnapshot}'s length validation, and the session then paints the old
     * snapshot's phenotypes onto the new cells. That must be a loud failure.
     */
    @Test
    void rebuildingOntoADifferentCellSetIsRejectedRatherThanSilentlyMislabelled() {
        var original = cells(4, 0);
        var indexA = indexOf(original);
        var session = new UmapSession();
        session.adopt(snapshotOf(indexA, "T cell", 0x00FF00));

        // Same size, different cells: the length check cannot see this.
        var impostor = indexOf(cells(4, 100));
        assertEquals(indexA.size(), impostor.size());

        var ex = assertThrows(IllegalArgumentException.class,
                () -> session.installRebuiltIndex(impostor, MarkerStats.compute(impostor)),
                "Installing an index covering different cells under a live snapshot would "
                        + "mislabel every one of them");
        assertTrue(ex.getMessage().toLowerCase().contains("cell"),
                "The failure should say what drifted, got: " + ex.getMessage());

        // And the session is left untouched rather than half-migrated.
        assertSame(indexA, session.index());
        assertDoesNotThrow(session::assertIndexInvariant);
    }

    /**
     * The identity question itself, on {@link PhenotypeSnapshot}, where it belongs: cell
     * identity is a property of the objects, not of which build produced the columns.
     */
    @Test
    void snapshotKnowsWhetherAnIndexCoversItsOwnCells() {
        var objects = cells(3, 0);
        var indexA = indexOf(objects, Compartment.WHOLE_CELL, Statistic.MEAN);
        var snapshot = snapshotOf(indexA, "T cell", 0x00FF00);

        assertTrue(snapshot.describesSameCells(indexA), "An index is trivially its own cell set");
        assertTrue(snapshot.describesSameCells(indexOf(objects, Compartment.NUCLEAR, Statistic.MEAN)),
                "A different feature resolution over the same cells is still the same cells");
        assertFalse(snapshot.describesSameCells(indexOf(cells(3, 50))),
                "Different objects of the same count are NOT the same cells");
        assertFalse(snapshot.describesSameCells(indexOf(cells(2, 0))));
        assertFalse(snapshot.describesSameCells(null));

        var reordered = new ArrayList<>(objects);
        java.util.Collections.reverse(reordered);
        assertFalse(snapshot.describesSameCells(indexOf(reordered)),
                "Order is part of the contract — the arrays are positional");
    }

    /** Rebinding preserves the labels and rejects an index it cannot vouch for. */
    @Test
    void rebindCarriesTheLabelsAcrossAndRefusesAStrangerIndex() {
        var objects = cells(3, 0);
        var indexA = indexOf(objects);
        var snapshot = snapshotOf(indexA, "T cell", 0x00FF00);
        var indexB = indexOf(objects, Compartment.NUCLEAR, Statistic.MEAN);

        var rebound = snapshot.rebindTo(indexB, MarkerStats.compute(indexB));
        assertSame(indexB, rebound.index());
        assertArrayEquals(snapshot.phenotypes(), rebound.phenotypes());
        assertArrayEquals(snapshot.colors(), rebound.colors());
        assertArrayEquals(snapshot.excluded(), rebound.excluded());
        assertEquals(snapshot.imageKey(), rebound.imageKey());
        assertSame(snapshot, snapshot.rebindTo(indexA, snapshot.stats()),
                "Rebinding onto the index it already names is a no-op");

        assertThrows(IllegalArgumentException.class,
                () -> snapshot.rebindTo(indexOf(cells(3, 77)), null));
    }

    // ------------------------------------------------------------------
    // Guard-rails that must survive the fix
    // ------------------------------------------------------------------

    /** A genuinely new cell set still invalidates everything derived from the old one. */
    @Test
    void aDifferentCellSetStillInvalidates() {
        var session = new UmapSession();
        var indexA = indexOf(cells(4, 0));
        session.adopt(snapshotOf(indexA, "T cell", 0x00FF00));
        session.setGateMask(new boolean[]{true, false, true, false});
        session.addTag(new qupath.ext.flowpath.umap.model.PopulationTag("p", 1, new boolean[4]));

        var indexC = indexOf(cells(5, 200));
        assertEquals(UmapSession.Adoption.REBUILD, session.adopt(snapshotOf(indexC, "B cell", 0xFF0000)));
        assertSame(indexC, session.index());
        assertNull(session.gateMask(), "A new cell set retires the gate");
        assertTrue(session.tags().isEmpty(), "A new cell set retires the population tags");
        assertDoesNotThrow(session::assertIndexInvariant);
    }

    /** A null push leaves snapshot mode without pretending anything was adopted. */
    @Test
    void nullSnapshotDetaches() {
        var session = new UmapSession();
        var indexA = indexOf(cells(2, 0));
        session.adopt(snapshotOf(indexA, "T cell", 0x00FF00));
        assertTrue(session.isSnapshotMode());
        assertEquals(UmapSession.Adoption.DETACHED, session.adopt(null));
        assertFalse(session.isSnapshotMode());
        assertDoesNotThrow(session::assertIndexInvariant);
    }

    /**
     * Discarding the cell set discards the snapshot with it — otherwise the session would
     * be left holding labels positional against an index that no longer exists, and the
     * standalone reload that follows could not install its replacement.
     */
    @Test
    void clearingTheIndexAlsoClearsTheSnapshotItNamed() {
        var session = new UmapSession();
        var indexA = indexOf(cells(3, 0));
        session.adopt(snapshotOf(indexA, "T cell", 0x00FF00));

        session.clearIndex();
        assertNull(session.index());
        assertNull(session.snapshot());
        assertFalse(session.isSnapshotMode());
        assertTrue(session.markers().isEmpty());
        assertDoesNotThrow(session::assertIndexInvariant);

        // ...and the standalone path is free again.
        var fresh = indexOf(cells(2, 0));
        assertDoesNotThrow(() -> session.installIndex(fresh, MarkerStats.compute(fresh),
                PANEL, CompartmentCapability.empty(), MarkerSelection.defaultFor(PANEL)));
        assertSame(fresh, session.index());
    }

    /** The standalone path never enters snapshot mode, so nothing can drift. */
    @Test
    void standaloneSessionHasNoSnapshotToDriftFrom() {
        var session = new UmapSession();
        var index = indexOf(cells(3, 0));
        session.installIndex(index, MarkerStats.compute(index), PANEL,
                CompartmentCapability.empty(), MarkerSelection.defaultFor(PANEL));
        assertFalse(session.isSnapshotMode());
        assertSame(index, session.index());
        assertNull(session.detectionsForRebuild(),
                "Standalone, the caller re-queries the hierarchy for its own cell set");
        assertDoesNotThrow(session::assertIndexInvariant);
    }
}
