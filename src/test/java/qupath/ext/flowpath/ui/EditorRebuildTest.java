package qupath.ext.flowpath.ui;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.testing.Cells;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * When a resync rebuilds the gate editor. A rebuild discards a polygon the user is halfway
 * through drawing, so an annotation edit or an ROI toggle must not trigger one; an undo that
 * swaps the tree's nodes, a new index or a migration must.
 * <p>
 * Driven through a real {@link GatingSession} and replayed the way {@code FlowPathPane.render}
 * uses the rule — resolve the surviving selection, decide, and if rebuilt the editor then shows
 * the selection — across several passes, because staleness only shows on the pass after the
 * one that changed the tree.
 */
class EditorRebuildTest {

    /** What the editor shows, updated the way {@code FlowPathPane.render} updates it. */
    private static final class Pane {
        final GatingSession session;
        GateNode selected;
        GateNode shown;
        int rebuilds;

        Pane(GatingSession session) { this.session = session; }

        /** One render: returns whether it rebuilt the editor. */
        boolean render(Optional<GatingSession.MigrationNotice> notice, boolean newIndex) {
            selected = EditorRebuild.surviving(selected, session.tree());
            boolean rebuild = EditorRebuild.needed(newIndex, notice.isPresent(), shown, selected);
            if (rebuild) {
                shown = selected;
                rebuilds++;
            }
            return rebuild;
        }
    }

    private static GateTree twoRootsOnCd3() {
        GateTree tree = new GateTree();
        GateNode high = new GateNode("CD3", 5.5);
        high.setStatistic(Statistic.MEAN);
        GateNode low = new GateNode("CD3", 2.5);
        low.setStatistic(Statistic.MEAN);
        tree.addRoot(high);
        tree.addRoot(low);
        return tree;
    }

    @Test
    void annotationEditsAndToggleKeepTheEditorAndAnUndoRebuildsItOnce() {
        GatingSession session = new GatingSession(() -> 0L, input -> { });
        session.replaceTree(twoRootsOnCd3());
        session.adoptIndex(Cells.of(10).marker("CD3", i -> i + 1.0).area(i -> 50.0)
                .at(i -> i * 10.0, i -> 5.0).build());
        Pane pane = new Pane(session);

        // The image lands and the user selects root 1 (a tree click shows it).
        assertTrue(pane.render(session.resync(List::of), true), "new cells: rebuild");
        pane.selected = session.tree().getRoots().get(1);
        pane.shown = pane.selected;

        // Pass: annotation added under the ROI filter. Pass: it moves. Pass: filter toggled off.
        PathObject annotation = PathObjects.createAnnotationObject(
                ROIs.createRectangleROI(-5, 0, 50, 10, ImagePlane.getDefaultPlane()));
        Supplier<List<PathObject>> annotations = () -> List.of(annotation);
        session.setRoiFilterEnabled(true);
        assertFalse(pane.render(session.resync(annotations), false), "ROI toggle keeps the editor");
        assertFalse(pane.render(session.resync(annotations), false), "annotation edit keeps the editor");
        session.settle();
        assertSame(session.tree().getRoots().get(1), pane.shown);

        // An edit, then undo: the tree is a fresh copy, root 1's old node is gone.
        session.recordEdit();
        session.tree().getRoots().get(1).setThreshold(3.5);
        session.settle();
        GateNode before = pane.shown;
        assertTrue(session.undo());
        assertTrue(pane.render(session.resync(annotations), false), "undo swapped the nodes: rebuild");
        assertNull(pane.selected, "the old node is not in the restored tree");
        assertNull(pane.shown, "the editor no longer writes to the discarded node");
        assertNotSame(before, session.tree().getRoots().get(1));

        // Second pass after the undo: nothing changed again, so no second rebuild.
        int rebuilds = pane.rebuilds;
        assertFalse(pane.render(session.resync(annotations), false));
        assertEquals(rebuilds, pane.rebuilds);

        // Selecting a gate of the restored tree shows it; the next annotation pass keeps it.
        pane.selected = session.tree().getRoots().get(0);
        pane.shown = pane.selected;
        assertFalse(pane.render(session.resync(annotations), false));
        assertSame(session.tree().getRoots().get(0), pane.shown);
    }

    @Test
    void aNewIndexOrARewrittenTreeRebuildsEvenWithTheSameGateShown() {
        GateNode gate = new GateNode("CD3", 1.0);
        assertTrue(EditorRebuild.needed(true, false, gate, gate), "new cells");
        assertTrue(EditorRebuild.needed(false, true, gate, gate), "migration rewrote the gate");
        assertFalse(EditorRebuild.needed(false, false, gate, gate), "same gate, same cells");
        assertFalse(EditorRebuild.needed(false, false, null, null), "nothing shown, nothing selected");
        assertTrue(EditorRebuild.needed(false, false, gate, null), "selection dropped");
        assertTrue(EditorRebuild.needed(false, false, null, gate), "new selection");
    }

    /**
     * A gate dragged to another branch is still the same object in the same tree, so it
     * survives and the editor goes on showing it — no rebuild, because the gate's own
     * controls (channel, threshold, branch names) are untouched by a re-parenting. What
     * does change is the ancestor mask, which {@code FlowPathPane.render} re-applies
     * separately from the rebuild decision.
     * <p>
     * This is the rule {@code FlowPathPane.onGateMoved} routes through: it seeds the
     * selection with the moved gate and renders. Deciding the editor's fate anywhere else
     * is how a completed move left the tree showing the gate selected and the editor blank.
     * Replayed over two passes, because staleness only shows on the pass after the edit.
     */
    @Test
    void aMovedGateSurvivesSoTheEditorFollowsItRatherThanBlanking() {
        GatingSession session = new GatingSession(() -> 0L, input -> { });
        session.replaceTree(twoRootsOnCd3());
        GateNode child = new GateNode("CD3", 7.0);
        child.setStatistic(Statistic.MEAN);
        session.tree().getRoots().get(0).getBranches().get(0).getChildren().add(child);
        session.settle();

        Pane pane = new Pane(session);
        pane.selected = child;
        pane.shown = child;
        int rebuilds = pane.rebuilds;

        // The move itself, as GateDragCoordinator applies it.
        session.recordEdit();
        assertTrue(session.tree().move(child, session.tree().getRoots().get(1).getBranches().get(0)));
        session.settle();

        // onGateMoved: the moved gate becomes the selection, then render decides.
        pane.selected = child;
        assertFalse(pane.render(Optional.empty(), false), "a re-parented gate needs no rebuild");
        assertSame(child, pane.selected, "the moved gate is still in the tree");
        assertSame(child, pane.shown, "so the editor keeps it instead of blanking");
        assertEquals(rebuilds, pane.rebuilds);

        // The pass after the move: still the same gate, still no rebuild.
        assertFalse(pane.render(Optional.empty(), false));
        assertSame(child, pane.shown);

        // And the contrast: one undo swaps in a fresh tree, so the moved node is gone and
        // the editor MUST let go of it — the case `surviving` exists for.
        assertTrue(session.undo());
        assertTrue(pane.render(Optional.empty(), false), "undo swapped the nodes: rebuild");
        assertNull(pane.selected);
        assertNull(pane.shown);
    }

    @Test
    void aChildGateSurvivesByIdentity() {
        GateTree tree = twoRootsOnCd3();
        GateNode child = new GateNode("CD3", 7.0);
        tree.getRoots().get(0).getBranches().get(1).getChildren().add(child);
        assertSame(child, EditorRebuild.surviving(child, tree));
        assertNull(EditorRebuild.surviving(child.deepCopy(), tree), "an equal copy is not in the tree");
        assertNull(EditorRebuild.surviving(child, tree.deepCopy()));
        assertNull(EditorRebuild.surviving(null, tree));
    }
}
