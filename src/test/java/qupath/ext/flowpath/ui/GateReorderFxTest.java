package qupath.ext.flowpath.ui;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.engine.GatingEngine;
import qupath.ext.flowpath.model.Branch;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.testing.Cells;
import qupath.ext.flowpath.testing.FxTestSupport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drag-and-drop gate reordering, driven through {@link FlowPathCell}'s own drag handlers over
 * a real {@link GatingSession}: a drop performs the move, one undo restores the tree exactly,
 * and the per-branch counts of a gating pass follow on both sides of that undo.
 * <p>
 * <b>Two enabled roots on the same channel</b>, as every per-branch test in this suite must
 * be: the two roots' branches are byte-identical in name, so a move that confused them would
 * pass a single-root test unnoticed. Counts are read per branch, by identity, never from
 * {@code cellsTotal()}-style totals that are non-zero either way.
 * <p>
 * <b>What is not exercised here.</b> {@code FlowPathPane} needs a live {@code QuPathGUI} and
 * cannot be built in the suite, so the two-line lambdas that adapt JavaFX's {@code DragEvent}s
 * to the handlers below (and the cell factory that hands each cell the coordinator) are not
 * covered. Everything those lambdas call is: {@code startDragAndDrop} needs a real drag
 * gesture from the platform toolkit, which a synthetic event cannot produce.
 */
class GateReorderFxTest {

    private static final int N = 20;

    @BeforeAll
    static void initJfx() throws InterruptedException {
        FxTestSupport.startToolkit();
    }

    /** CD45 = 1..20, CD3 = 20..1, so the two channels disagree about every cell. */
    private static CellIndex index() {
        return Cells.of(N)
                .marker("CD45", i -> i + 1.0)
                .marker("CD3", i -> N - i)
                .area(50.0)
                .build();
    }

    /**
     * <pre>
     * CD45(10.5)   [root 0]   CD45+ -> CD3(2.5)
     * CD45(15.5)   [root 1]
     * </pre>
     * Both roots enabled, both on CD45: their branches are named identically.
     */
    private static GateTree twoRootsSameChannel() {
        GateNode rootA = new GateNode("CD45", 10.5);
        rootA.setStatistic(Statistic.MEAN);
        GateNode cd3 = new GateNode("CD3", 2.5);
        cd3.setStatistic(Statistic.MEAN);
        rootA.getPositiveChildren().add(cd3);

        GateNode rootB = new GateNode("CD45", 15.5);
        rootB.setStatistic(Statistic.MEAN);

        GateTree tree = new GateTree();
        tree.addRoot(rootA);
        tree.addRoot(rootB);
        return tree;
    }

    /** A structural fingerprint of the whole forest: channel, threshold, branch names, order. */
    private static String describe(GateTree tree) {
        StringBuilder sb = new StringBuilder();
        for (GateNode root : tree.getRoots()) describe(root, "", sb);
        return sb.toString();
    }

    private static void describe(GateNode node, String indent, StringBuilder sb) {
        sb.append(indent).append(node.getChannel()).append('@').append(node.getThreshold()).append('\n');
        for (Branch branch : node.getBranches()) {
            sb.append(indent).append("  ").append(branch.getName()).append('\n');
            for (GateNode child : branch.getChildren()) describe(child, indent + "    ", sb);
        }
    }

    /**
     * Run a gating pass over the session's CURRENT tree and read back every branch's count,
     * keyed by {@code rootIndex + "/" + path} so the two same-channel roots stay apart. The
     * counts come from the tree the walk filled, exactly as the tree view reads them.
     */
    private static Map<String, Integer> countsOf(GateTree tree, CellIndex index, MarkerStats stats) {
        GatingEngine.assignAll(tree, index, stats, null);
        Map<String, Integer> counts = new LinkedHashMap<>();
        int rootIndex = 0;
        for (GateNode root : tree.getRoots()) {
            if (!root.isEnabled()) continue;
            collect(root, rootIndex + "/", counts);
            rootIndex++;
        }
        return counts;
    }

    private static void collect(GateNode node, String prefix, Map<String, Integer> counts) {
        for (Branch branch : node.getBranches()) {
            String path = prefix + branch.getName();
            counts.merge(path, branch.getCount(), Integer::sum);
            for (GateNode child : branch.getChildren()) collect(child, path + "/", counts);
        }
    }

    /** A session over {@link #index()} whose tree is {@link #twoRootsSameChannel()}. */
    private static GatingSession session(List<GatingSession.PassInput> passes) {
        GatingSession session = new GatingSession(System::currentTimeMillis, passes::add);
        session.adoptIndex(index());
        for (GateNode root : twoRootsSameChannel().getRoots()) session.tree().addRoot(root);
        session.settle();
        return session;
    }

    private static GateNode rootA(GatingSession s) { return s.tree().getRoots().get(0); }
    private static GateNode rootB(GatingSession s) { return s.tree().getRoots().get(1); }
    private static GateNode cd3(GatingSession s) { return rootA(s).getPositiveChildren().get(0); }

    /** A cell showing {@code item}; {@code null} with {@code empty} gives a background row. */
    private static FlowPathCell cellFor(GateDragCoordinator drag, Object item, boolean empty) {
        FlowPathCell cell = new FlowPathCell();
        cell.setDragCoordinator(drag);
        cell.updateItem(item, empty);
        return cell;
    }

    private static FlowPathCell gateCell(GateDragCoordinator drag, GateNode gate) {
        return cellFor(drag, gate, false);
    }

    private static FlowPathCell branchCell(GateDragCoordinator drag, GateNode owner, int branchIndex) {
        return cellFor(drag, new FlowPathCell.BranchItem(owner, owner.getBranches().get(branchIndex),
                branchIndex), false);
    }

    // ---- the whole round trip -----------------------------------------------------------

    @Test
    void aDropMovesTheGateAndOneUndoRestoresTheTreeAndItsCounts() {
        FxTestSupport.onFxRun(() -> {
            List<GatingSession.PassInput> passes = new ArrayList<>();
            GatingSession session = session(passes);
            List<GateNode> moved = new ArrayList<>();
            GateDragCoordinator drag = new GateDragCoordinator(session::tree, () -> false,
                    session::recordEdit, moved::add);

            MarkerStats stats = MarkerStats.compute(session.index(), Cells.allTrue(N));
            String before = describe(session.tree());
            Map<String, Integer> countsBefore = countsOf(session.tree(), session.index(), stats);
            // The fixture is only worth gating if the moved gate actually splits its parent.
            assertTrue(countsBefore.get("0/CD45+/CD3+") > 0 && countsBefore.get("0/CD45+/CD3-") > 0,
                    "the CD3 gate splits root 0's positive branch: " + countsBefore);

            GateNode gate = cd3(session);
            FlowPathCell source = gateCell(drag, gate);
            FlowPathCell target = branchCell(drag, rootB(session), 0);

            assertTrue(source.beginDrag(), "a gate row starts a drag");
            assertTrue(target.dragOver(), "root 1's positive branch takes it");
            assertTrue(target.dropHere());

            assertSame(gate, session.tree().getRoots().get(1).getBranches().get(0).getChildren().get(0),
                    "the very same gate object now hangs off root 1");
            assertTrue(rootA(session).getPositiveChildren().isEmpty());
            assertEquals(List.of(gate), moved, "the pane is told which gate to reselect");

            Map<String, Integer> countsAfter = countsOf(session.tree(), session.index(), stats);
            assertEquals(0, countsAfter.getOrDefault("0/CD45+/CD3+", 0),
                    "root 0 no longer has a CD3 population");
            assertTrue(countsAfter.get("1/CD45+/CD3+") > 0, "root 1 does: " + countsAfter);
            assertEquals(countsAfter.get("1/CD45+"),
                    countsAfter.get("1/CD45+/CD3+") + countsAfter.get("1/CD45+/CD3-"),
                    "and the moved gate partitions its new parent");
            assertFalse(countsAfter.equals(countsBefore), "the move really did change the counts");

            assertTrue(session.undo(), "one undo step");
            assertEquals(before, describe(session.tree()), "restores the exact previous tree");
            assertEquals(countsBefore, countsOf(session.tree(), session.index(), stats),
                    "and the exact previous per-branch counts");
            assertFalse(session.undo(), "the move was ONE undo step, not two");
        });
    }

    // ---- refusals -----------------------------------------------------------------------

    @Test
    void aRefusedDropChangesNothingAndCostsNoUndoStep() {
        FxTestSupport.onFxRun(() -> {
            List<GatingSession.PassInput> passes = new ArrayList<>();
            GatingSession session = session(passes);
            List<GateNode> moved = new ArrayList<>();
            GateDragCoordinator drag = new GateDragCoordinator(session::tree, () -> false,
                    session::recordEdit, moved::add);
            String before = describe(session.tree());

            GateNode gate = cd3(session);
            assertTrue(gateCell(drag, gate).beginDrag());

            // Its own branch (a cycle), the branch it already hangs off (a no-op), and a gate
            // row (only branches hold children).
            FlowPathCell ownBranch = branchCell(drag, gate, 0);
            FlowPathCell home = branchCell(drag, rootA(session), 0);
            FlowPathCell gateRow = gateCell(drag, rootB(session));
            for (FlowPathCell cell : List.of(ownBranch, home, gateRow)) {
                assertFalse(cell.dragOver(), "not a drop target");
                assertFalse(cell.dropHere(), "and dropping there does nothing");
            }

            assertEquals(before, describe(session.tree()));
            assertEquals(List.of(), moved, "no gating pass was asked for");
            assertFalse(session.undo(), "a refused drop recorded no undo step");
        });
    }

    @Test
    void noDropIsTakenWhileEditingIsBlocked() {
        FxTestSupport.onFxRun(() -> {
            List<GatingSession.PassInput> passes = new ArrayList<>();
            GatingSession session = session(passes);
            boolean[] blocked = {false};
            List<GateNode> moved = new ArrayList<>();
            GateDragCoordinator drag = new GateDragCoordinator(session::tree, () -> blocked[0],
                    session::recordEdit, moved::add);
            String before = describe(session.tree());

            assertTrue(gateCell(drag, cd3(session)).beginDrag());
            blocked[0] = true; // a derivation started mid-drag

            FlowPathCell target = branchCell(drag, rootB(session), 0);
            assertFalse(target.dragOver());
            assertFalse(target.dropHere());
            assertEquals(before, describe(session.tree()));
            assertFalse(session.undo());

            // And a drag cannot even start while blocked.
            drag.end();
            assertFalse(gateCell(drag, cd3(session)).beginDrag());
            assertNull(drag.dragged());
        });
    }

    // ---- promotion back to a root --------------------------------------------------------

    @Test
    void droppingOnTheTreeBackgroundPromotesTheGateToARoot() {
        FxTestSupport.onFxRun(() -> {
            List<GatingSession.PassInput> passes = new ArrayList<>();
            GatingSession session = session(passes);
            List<GateNode> moved = new ArrayList<>();
            GateDragCoordinator drag = new GateDragCoordinator(session::tree, () -> false,
                    session::recordEdit, moved::add);
            MarkerStats stats = MarkerStats.compute(session.index(), Cells.allTrue(N));
            String before = describe(session.tree());
            Map<String, Integer> countsBefore = countsOf(session.tree(), session.index(), stats);

            GateNode gate = cd3(session);
            assertTrue(gateCell(drag, gate).beginDrag());

            FlowPathCell background = cellFor(drag, null, true);
            assertTrue(background.dragOver(), "the empty space below the rows is the root list");
            assertTrue(background.dropHere());

            assertEquals(3, session.tree().getRoots().size());
            assertSame(gate, session.tree().getRoots().get(2), "appended last");

            Map<String, Integer> countsAfter = countsOf(session.tree(), session.index(), stats);
            assertTrue(countsAfter.get("2/CD3+") > 0, "the promoted root gates every cell: " + countsAfter);
            assertEquals(N, countsAfter.get("2/CD3+") + countsAfter.get("2/CD3-"));

            assertTrue(session.undo());
            assertEquals(before, describe(session.tree()));
            assertEquals(countsBefore, countsOf(session.tree(), session.index(), stats));
        });
    }

    @Test
    void aRootDroppedOnTheBackgroundIsRefusedAsANoOp() {
        FxTestSupport.onFxRun(() -> {
            List<GatingSession.PassInput> passes = new ArrayList<>();
            GatingSession session = session(passes);
            GateDragCoordinator drag = new GateDragCoordinator(session::tree, () -> false,
                    session::recordEdit, g -> { });
            String before = describe(session.tree());

            assertTrue(gateCell(drag, rootB(session)).beginDrag());
            FlowPathCell background = cellFor(drag, null, true);
            assertFalse(background.dragOver(), "it is already a root");
            assertFalse(background.dropHere());
            assertEquals(before, describe(session.tree()));
            assertFalse(session.undo());
        });
    }

    // ---- the visible cue ------------------------------------------------------------------

    @Test
    void theHoverCueMarksValidAndInvalidTargetsApartAndIsClearedAfterwards() {
        FxTestSupport.onFxRun(() -> {
            List<GatingSession.PassInput> passes = new ArrayList<>();
            GatingSession session = session(passes);
            GateDragCoordinator drag = new GateDragCoordinator(session::tree, () -> false,
                    session::recordEdit, g -> { });

            GateNode gate = cd3(session);
            assertTrue(gateCell(drag, gate).beginDrag());

            FlowPathCell valid = branchCell(drag, rootB(session), 0);
            valid.dragOver();
            assertTrue(valid.getStyleClass().contains(FlowPathCell.DROP_TARGET_CLASS));
            assertFalse(valid.getStyleClass().contains(FlowPathCell.DROP_INVALID_CLASS));

            FlowPathCell invalid = branchCell(drag, gate, 0);
            invalid.dragOver();
            assertFalse(invalid.getStyleClass().contains(FlowPathCell.DROP_TARGET_CLASS));
            assertTrue(invalid.getStyleClass().contains(FlowPathCell.DROP_INVALID_CLASS),
                    "an invalid target is visibly non-droppable, not silently inert");

            valid.clearDropCue();
            invalid.clearDropCue();
            assertFalse(valid.getStyleClass().contains(FlowPathCell.DROP_TARGET_CLASS));
            assertFalse(invalid.getStyleClass().contains(FlowPathCell.DROP_INVALID_CLASS));
        });
    }

    /** A recycled cell must not carry a previous row's hover cue into the row it becomes. */
    @Test
    void aRecycledCellDropsTheHoverCue() {
        FxTestSupport.onFxRun(() -> {
            List<GatingSession.PassInput> passes = new ArrayList<>();
            GatingSession session = session(passes);
            GateDragCoordinator drag = new GateDragCoordinator(session::tree, () -> false,
                    session::recordEdit, g -> { });

            assertTrue(gateCell(drag, cd3(session)).beginDrag());
            FlowPathCell cell = branchCell(drag, rootB(session), 0);
            cell.dragOver();
            assertTrue(cell.getStyleClass().contains(FlowPathCell.DROP_TARGET_CLASS));

            cell.updateItem(rootA(session), false); // scrolled away and reused
            assertFalse(cell.getStyleClass().contains(FlowPathCell.DROP_TARGET_CLASS));
            assertFalse(cell.getStyleClass().contains(FlowPathCell.DROP_INVALID_CLASS));
        });
    }

    /** Only gate rows start a drag: a branch row is a target, never a source. */
    @Test
    void onlyAGateRowStartsADrag() {
        FxTestSupport.onFxRun(() -> {
            List<GatingSession.PassInput> passes = new ArrayList<>();
            GatingSession session = session(passes);
            GateDragCoordinator drag = new GateDragCoordinator(session::tree, () -> false,
                    session::recordEdit, g -> { });

            assertFalse(branchCell(drag, rootA(session), 0).beginDrag());
            assertFalse(cellFor(drag, null, true).beginDrag());
            assertNull(drag.dragged());
        });
    }
}
