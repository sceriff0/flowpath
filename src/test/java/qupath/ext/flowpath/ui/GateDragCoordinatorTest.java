package qupath.ext.flowpath.ui;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.Branch;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GateDragCoordinator} — what a gate drag in the tree view may do, with no JavaFX
 * toolkit: {@code FlowPathCell}'s drag handlers hold no state and decide nothing, they ask
 * this.
 * <p>
 * The two properties worth pinning are the ones a user notices: a refused drop must change
 * <b>nothing at all</b> — no undo step, no gating pass, no half-applied move — and an accepted
 * one must record its undo step <em>before</em> the tree is mutated, or undo would restore the
 * tree as the move already left it. Both are asserted from the callbacks' own point of view
 * rather than from the coordinator's return value.
 */
class GateDragCoordinatorTest {

    /**
     * <pre>
     * CD45(10.5)   [root 0]   CD45+ -> CD3(1.0)
     * CD45(15.5)   [root 1]
     * </pre>
     */
    private static GateTree tree() {
        GateTree tree = new GateTree();
        GateNode rootA = new GateNode("CD45", 10.5);
        rootA.getPositiveChildren().add(new GateNode("CD3", 1.0));
        tree.addRoot(rootA);
        tree.addRoot(new GateNode("CD45", 15.5));
        return tree;
    }

    private static GateNode cd3(GateTree tree) { return tree.getRoots().get(0).getPositiveChildren().get(0); }
    private static Branch rootBPositive(GateTree tree) { return tree.getRoots().get(1).getBranches().get(0); }

    /** Records what the coordinator did, in order, and where the gate was when it did it. */
    private static final class Host {
        final GateTree tree = tree();
        final List<String> events = new ArrayList<>();
        final List<GateNode> moved = new ArrayList<>();
        boolean blocked;
        /** Where {@code CD3} hung at the moment the undo step was recorded. */
        String gateHomeWhenRecorded;

        GateDragCoordinator coordinator() {
            return new GateDragCoordinator(() -> tree, () -> blocked,
                    () -> {
                        events.add("undo");
                        gateHomeWhenRecorded = homeOfCd3();
                    },
                    gate -> { events.add("moved"); moved.add(gate); });
        }

        /** "rootA+" / "rootB+" / "root" — which list the CD3 gate is in right now. */
        String homeOfCd3() {
            if (tree.getRoots().stream().anyMatch(n -> "CD3".equals(n.getChannel()))) return "root";
            for (int r = 0; r < tree.getRoots().size(); r++) {
                List<Branch> branches = tree.getRoots().get(r).getBranches();
                for (int b = 0; b < branches.size(); b++) {
                    for (GateNode child : branches.get(b).getChildren()) {
                        if ("CD3".equals(child.getChannel())) return "root" + r + "/branch" + b;
                    }
                }
            }
            return "nowhere";
        }
    }

    // ---- an accepted drop ---------------------------------------------------------------

    @Test
    void anAcceptedDropRecordsItsUndoStepBeforeMutatingTheTree() {
        Host host = new Host();
        GateDragCoordinator drag = host.coordinator();
        GateNode gate = cd3(host.tree);

        assertTrue(drag.begin(gate));
        assertTrue(drag.accepts(rootBPositive(host.tree)));
        assertTrue(drag.drop(rootBPositive(host.tree)));

        assertEquals(List.of("undo", "moved"), host.events, "undo step first, then the pass");
        assertEquals("root0/branch0", host.gateHomeWhenRecorded,
                "the tree recorded for undo is the one BEFORE the move");
        assertEquals("root1/branch0", host.homeOfCd3(), "and the move itself was applied");
        assertEquals(List.of(gate), host.moved, "the moved gate is handed back, so it can be reselected");
    }

    @Test
    void anAcceptedDropEndsTheDragSoTheNextHoverAcceptsNothing() {
        Host host = new Host();
        GateDragCoordinator drag = host.coordinator();

        assertTrue(drag.begin(cd3(host.tree)));
        assertTrue(drag.drop(rootBPositive(host.tree)));

        assertNull(drag.dragged());
        assertFalse(drag.accepts(host.tree.getRoots().get(0).getBranches().get(1)));
    }

    @Test
    void aNullTargetPromotesTheDraggedGateToARoot() {
        Host host = new Host();
        GateDragCoordinator drag = host.coordinator();

        assertTrue(drag.begin(cd3(host.tree)));
        assertTrue(drag.accepts(null));
        assertTrue(drag.drop(null));

        assertEquals("root", host.homeOfCd3());
        assertEquals(3, host.tree.getRoots().size());
    }

    // ---- a refused drop -----------------------------------------------------------------

    @Test
    void aRefusedDropChangesNothingAtAll() {
        Host host = new Host();
        GateDragCoordinator drag = host.coordinator();
        GateNode gate = cd3(host.tree);

        assertTrue(drag.begin(gate));
        // Its own branch: a cycle.
        assertFalse(drag.accepts(gate.getBranches().get(0)));
        assertFalse(drag.drop(gate.getBranches().get(0)));
        // The branch it already hangs off: a no-op.
        assertFalse(drag.accepts(host.tree.getRoots().get(0).getBranches().get(0)));
        assertFalse(drag.drop(host.tree.getRoots().get(0).getBranches().get(0)));

        assertEquals(List.of(), host.events, "no undo step and no gating pass for a refused drop");
        assertEquals("root0/branch0", host.homeOfCd3(), "and the gate did not move");
        assertSame(gate, drag.dragged(), "a refused drop does not end the drag either");
    }

    @Test
    void nothingIsDraggableOrDroppableWhileEditingIsBlocked() {
        Host host = new Host();
        GateDragCoordinator drag = host.coordinator();
        host.blocked = true;

        assertFalse(drag.begin(cd3(host.tree)), "a drag cannot start while a derivation runs");
        assertNull(drag.dragged());
        assertFalse(drag.accepts(rootBPositive(host.tree)));
        assertFalse(drag.drop(rootBPositive(host.tree)));
        assertEquals(List.of(), host.events);
    }

    /**
     * A derivation that starts <em>during</em> the drag blocks the drop too: the editor is
     * greyed out for the same reason — the statistics beside the tree are being replaced — and
     * a drop is a tree edit like any other.
     */
    @Test
    void aDerivationStartingMidDragBlocksTheDrop() {
        Host host = new Host();
        GateDragCoordinator drag = host.coordinator();

        assertTrue(drag.begin(cd3(host.tree)));
        host.blocked = true;

        assertFalse(drag.accepts(rootBPositive(host.tree)));
        assertFalse(drag.drop(rootBPositive(host.tree)));
        assertEquals(List.of(), host.events);
        assertEquals("root0/branch0", host.homeOfCd3());
    }

    @Test
    void nothingIsAcceptedWhenNoDragIsInProgress() {
        Host host = new Host();
        GateDragCoordinator drag = host.coordinator();

        assertNull(drag.dragged());
        assertFalse(drag.accepts(rootBPositive(host.tree)));
        assertFalse(drag.accepts(null));
        assertFalse(drag.drop(rootBPositive(host.tree)));
        assertEquals(List.of(), host.events);
    }

    @Test
    void endClearsTheDragWithoutTouchingTheTree() {
        Host host = new Host();
        GateDragCoordinator drag = host.coordinator();

        assertTrue(drag.begin(cd3(host.tree)));
        drag.end();

        assertNull(drag.dragged());
        assertEquals(List.of(), host.events);
        assertEquals("root0/branch0", host.homeOfCd3());
    }

    @Test
    void aGateThatIsNotInTheTreeCannotBeDropped() {
        Host host = new Host();
        GateDragCoordinator drag = host.coordinator();

        assertTrue(drag.begin(new GateNode("CD4", 1.0)));
        assertFalse(drag.accepts(rootBPositive(host.tree)));
        assertFalse(drag.drop(rootBPositive(host.tree)));
        assertEquals(List.of(), host.events);
    }
}
