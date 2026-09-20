package qupath.ext.flowpath.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GateTree#move(GateNode, Branch)} and its verdict — the whole rule for "which drops
 * does the tree view take?", table-tested with no JavaFX toolkit anywhere near it.
 * <p>
 * The drag-and-drop handlers in {@code ui/FlowPathCell} decide nothing: they ask
 * {@link GateTree#checkMove(GateNode, Branch)} whether the row under the cursor is droppable
 * and then call {@link GateTree#move(GateNode, Branch)}, which re-checks. One rule, asserted
 * here, rather than a "can I?" in the hover path and a separate "do it" in the drop path that
 * could disagree — the drop would then be visibly offered and silently refused, or worse,
 * taken when the hover said no.
 * <p>
 * Every refusal case asserts the tree is <b>byte-identical afterwards</b>, not merely that the
 * call returned {@code false}: a move that detached the gate before discovering the target was
 * illegal would also return {@code false}, and would lose the gate.
 */
class GateTreeMoveTest {

    // ---- fixtures ---------------------------------------------------------------------

    /**
     * Two enabled roots on the SAME channel — this codebase's recurring blind spot — with a
     * subtree hanging off root A's positive branch:
     * <pre>
     * CD45(10.5)            [root 0]
     *   CD45+ -> CD3(1.0)
     *              CD3+ -> CD8(2.0)
     *   CD45-
     * CD45(15.5)            [root 1]
     *   CD45+
     *   CD45-
     * </pre>
     */
    private static GateTree twoRootsSameChannel() {
        GateTree tree = new GateTree();
        GateNode rootA = new GateNode("CD45", 10.5);
        GateNode cd3 = new GateNode("CD3", 1.0);
        GateNode cd8 = new GateNode("CD8", 2.0);
        cd3.getPositiveChildren().add(cd8);
        rootA.getPositiveChildren().add(cd3);
        GateNode rootB = new GateNode("CD45", 15.5);
        tree.addRoot(rootA);
        tree.addRoot(rootB);
        return tree;
    }

    private static GateNode rootA(GateTree tree) { return tree.getRoots().get(0); }
    private static GateNode rootB(GateTree tree) { return tree.getRoots().get(1); }
    private static GateNode cd3(GateTree tree) { return rootA(tree).getPositiveChildren().get(0); }
    private static GateNode cd8(GateTree tree) { return cd3(tree).getPositiveChildren().get(0); }

    /** A whole-forest structural fingerprint: channel, threshold and branch names, in order. */
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

    // ---- the plain move ----------------------------------------------------------------

    @Test
    void movesAGateUnderTheTargetBranchCarryingItsWholeSubtree() {
        GateTree tree = twoRootsSameChannel();
        GateNode moved = cd3(tree);
        GateNode carried = cd8(tree);
        Branch target = rootB(tree).getBranches().get(0);

        assertEquals(GateTree.MoveVerdict.ALLOWED, tree.checkMove(moved, target));
        assertTrue(tree.move(moved, target));

        assertTrue(rootA(tree).getPositiveChildren().isEmpty(), "detached from its old branch");
        assertEquals(1, target.getChildren().size());
        assertSame(moved, target.getChildren().get(0), "the same gate object, not a copy");
        assertSame(carried, moved.getPositiveChildren().get(0), "its subtree travelled with it");
    }

    @Test
    void appendsLastAmongTheTargetBranchExistingChildren() {
        GateTree tree = twoRootsSameChannel();
        Branch target = rootB(tree).getBranches().get(0);
        GateNode sitting = new GateNode("CD20", 3.0);
        target.getChildren().add(sitting);

        assertTrue(tree.move(cd3(tree), target));

        assertEquals(2, target.getChildren().size());
        assertSame(sitting, target.getChildren().get(0), "the gate already there keeps its place");
        assertEquals("CD3", target.getChildren().get(1).getChannel(), "the moved gate is appended last");
    }

    @Test
    void movingARootUnderABranchRemovesItFromTheRootList() {
        GateTree tree = twoRootsSameChannel();
        GateNode movedRoot = rootB(tree);
        Branch target = rootA(tree).getBranches().get(1); // CD45-

        assertTrue(tree.move(movedRoot, target));

        assertEquals(1, tree.getRoots().size());
        assertSame(rootA(tree), tree.getRoots().get(0));
        assertSame(movedRoot, target.getChildren().get(0));
    }

    // ---- promotion back to a root (a null target) ---------------------------------------

    @Test
    void aNullTargetPromotesTheGateToARootAppendedLast() {
        GateTree tree = twoRootsSameChannel();
        GateNode promoted = cd3(tree);
        GateNode carried = cd8(tree);

        assertEquals(GateTree.MoveVerdict.ALLOWED, tree.checkMove(promoted, null));
        assertTrue(tree.move(promoted, null));

        assertEquals(3, tree.getRoots().size());
        assertSame(promoted, tree.getRoots().get(2), "appended last, like every other move");
        assertSame(carried, promoted.getPositiveChildren().get(0), "subtree travelled with it");
        assertTrue(rootA(tree).getPositiveChildren().isEmpty());
    }

    @Test
    void promotingAGateThatIsAlreadyARootIsRefusedAsANoOp() {
        GateTree tree = twoRootsSameChannel();
        String before = describe(tree);

        assertEquals(GateTree.MoveVerdict.ALREADY_THERE, tree.checkMove(rootB(tree), null));
        assertFalse(tree.move(rootB(tree), null));
        assertEquals(before, describe(tree));
        assertEquals(2, tree.getRoots().size());
    }

    // ---- refusals ----------------------------------------------------------------------

    @Test
    void refusesADropOnTheGateOwnBranch() {
        GateTree tree = twoRootsSameChannel();
        GateNode gate = cd3(tree);
        String before = describe(tree);

        for (Branch own : gate.getBranches()) {
            assertEquals(GateTree.MoveVerdict.WOULD_CYCLE, tree.checkMove(gate, own));
            assertFalse(tree.move(gate, own));
        }
        assertEquals(before, describe(tree), "a refused move leaves the tree exactly as it was");
    }

    @Test
    void refusesADropOnABranchDeepInsideTheGateOwnSubtree() {
        GateTree tree = twoRootsSameChannel();
        GateNode gate = cd3(tree);
        Branch deep = cd8(tree).getBranches().get(0);
        String before = describe(tree);

        assertEquals(GateTree.MoveVerdict.WOULD_CYCLE, tree.checkMove(gate, deep));
        assertFalse(tree.move(gate, deep));
        assertEquals(before, describe(tree));
    }

    @Test
    void refusesADropOnTheBranchItAlreadyBelongsTo() {
        GateTree tree = twoRootsSameChannel();
        GateNode gate = cd3(tree);
        Branch home = rootA(tree).getBranches().get(0);
        String before = describe(tree);

        assertEquals(GateTree.MoveVerdict.ALREADY_THERE, tree.checkMove(gate, home));
        assertFalse(tree.move(gate, home));
        assertEquals(before, describe(tree));
        assertEquals(1, home.getChildren().size(), "not appended a second time");
    }

    @Test
    void refusesAGateThatIsNotInThisTree() {
        GateTree tree = twoRootsSameChannel();
        Branch target = rootB(tree).getBranches().get(0);
        String before = describe(tree);

        assertEquals(GateTree.MoveVerdict.NO_SUCH_GATE, tree.checkMove(null, target));
        assertEquals(GateTree.MoveVerdict.NO_SUCH_GATE, tree.checkMove(new GateNode("CD4", 1.0), target));
        assertFalse(tree.move(new GateNode("CD4", 1.0), target));
        assertEquals(before, describe(tree));
    }

    @Test
    void refusesABranchThatIsNotInThisTree() {
        GateTree tree = twoRootsSameChannel();
        Branch stranger = new GateNode("CD4", 1.0).getBranches().get(0);
        String before = describe(tree);

        assertEquals(GateTree.MoveVerdict.NO_SUCH_BRANCH, tree.checkMove(cd3(tree), stranger));
        assertFalse(tree.move(cd3(tree), stranger));
        assertEquals(before, describe(tree));
        assertTrue(stranger.getChildren().isEmpty(), "and nothing was appended to it either");
    }

    /**
     * A disabled root is still a legal move target: disabling a gate stops the gating walk
     * (see {@code GatingEngine.walkNode}), it does not take the gate out of the tree the user
     * is editing. Only {@code rootIndex} ignores it.
     */
    @Test
    void aDisabledRootIsStillAMoveTarget() {
        GateTree tree = twoRootsSameChannel();
        rootB(tree).setEnabled(false);
        Branch target = rootB(tree).getBranches().get(0);

        assertEquals(GateTree.MoveVerdict.ALLOWED, tree.checkMove(cd3(tree), target));
        assertTrue(tree.move(cd3(tree), target));
        assertEquals(1, target.getChildren().size());
    }

    // ---- two roots on one channel -------------------------------------------------------

    /**
     * Two roots on one channel emit byte-identical {@code path}s, so {@code rootIndex} is the
     * only thing that tells their populations apart ({@code PopulationRef}, {@code
     * DenominatorRef}, the colour-by-root picker). A move must leave that indexing intact:
     * the roots keep their order and their identity, and {@code locate}/{@code findBranch}
     * still round-trip through the moved gate's new home.
     */
    @Test
    void twoSameChannelRootsKeepTheirIdentityAndRootIndexOrderingAcrossAMove() {
        GateTree tree = twoRootsSameChannel();
        GateNode a = rootA(tree);
        GateNode b = rootB(tree);
        GateNode moved = cd3(tree);

        assertEquals(new GateTree.BranchLocation(0, "CD45+/CD3+"),
                tree.locate(moved.getBranches().get(0)), "before the move");

        assertTrue(tree.move(moved, b.getBranches().get(0)));

        assertSame(a, tree.getRoots().get(0), "root 0 is still root 0");
        assertSame(b, tree.getRoots().get(1), "root 1 is still root 1");

        GateTree.BranchLocation after = tree.locate(moved.getBranches().get(0));
        assertEquals(new GateTree.BranchLocation(1, "CD45+/CD3+"), after,
                "the moved gate's population is now root 1's, at the same path");
        assertSame(moved.getBranches().get(0), tree.findBranch(after.rootIndex(), after.path()),
                "locate and findBranch still round-trip after the move");
    }

    /**
     * {@code rootIndex} counts ENABLED roots only, and a move that promotes a gate appends a
     * root at the end — so an existing root's index never shifts.
     */
    @Test
    void promotingAGateDoesNotShiftAnExistingRootIndex() {
        GateTree tree = twoRootsSameChannel();
        Branch rootBPositive = rootB(tree).getBranches().get(0);
        assertEquals(new GateTree.BranchLocation(1, "CD45+"), tree.locate(rootBPositive));

        assertTrue(tree.move(cd3(tree), null));

        assertEquals(new GateTree.BranchLocation(1, "CD45+"), tree.locate(rootBPositive),
                "root 1 keeps index 1 when a third root is appended");
        assertEquals(new GateTree.BranchLocation(2, "CD3+"),
                tree.locate(tree.getRoots().get(2).getBranches().get(0)));
    }
}
