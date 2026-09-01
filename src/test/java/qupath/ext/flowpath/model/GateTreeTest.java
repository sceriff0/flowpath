package qupath.ext.flowpath.model;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class GateTreeTest {

    @Test
    void addAndRemoveRoot() {
        var tree = new GateTree();
        var node = new GateNode("CD45");
        tree.addRoot(node);
        assertEquals(1, tree.getRoots().size());
        assertSame(node, tree.getRoots().get(0));

        tree.removeRoot(node);
        assertTrue(tree.getRoots().isEmpty());
    }

    @Test
    void deepCopyCreatesIndependentTree() {
        var tree = new GateTree();
        var root = new GateNode("CD45", 1.0);
        root.getPositiveChildren().add(new GateNode("CD3", 0.5));
        tree.addRoot(root);

        var copy = tree.deepCopy();

        // Modify original
        root.setThreshold(99.0);
        tree.addRoot(new GateNode("PANCK"));

        // Copy should be unaffected
        assertEquals(1, copy.getRoots().size());
        assertEquals(1.0, copy.getRoots().get(0).getThreshold());
    }

    @Test
    void transferCountsWalksInParallel() {
        // Build two identical trees
        var orig = new GateNode("CD45");
        orig.getPositiveChildren().add(new GateNode("CD3"));

        var copyNode = new GateNode("CD45");
        var copyChild = new GateNode("CD3");
        copyNode.getPositiveChildren().add(copyChild);

        // Set counts on copy
        copyNode.setPosCount(100);
        copyNode.setNegCount(200);
        copyChild.setPosCount(30);
        copyChild.setNegCount(70);

        GateTree.transferCounts(List.of(orig), List.of(copyNode));

        assertEquals(100, orig.getPosCount());
        assertEquals(200, orig.getNegCount());
        assertEquals(30, orig.getPositiveChildren().get(0).getPosCount());
        assertEquals(70, orig.getPositiveChildren().get(0).getNegCount());
    }

    @Test
    void transferCountsHandlesMismatchedSizes() {
        var orig = List.of(new GateNode("CD45"));
        var copies = List.of(new GateNode("CD45"), new GateNode("CD3"));

        // Should not throw — just processes the shorter list
        assertDoesNotThrow(() -> GateTree.transferCounts(orig, copies));
    }

    @Test
    void collectLeafNamesAcrossMultipleRoots() {
        var tree = new GateTree();
        tree.addRoot(new GateNode("CD45"));
        tree.addRoot(new GateNode("PANCK"));

        var names = tree.collectLeafNames();
        assertEquals(List.of("CD45+", "CD45-", "PANCK+", "PANCK-"), names);
    }

    @Test
    void defaultQualityFilterIsPresent() {
        var tree = new GateTree();
        assertNotNull(tree.getQualityFilter());
    }

    @Test
    void findDuplicateLeafNamesDetectsCollision() {
        var tree = new GateTree();
        // Both roots have a branch named "CD45+"
        tree.addRoot(new GateNode("CD45"));
        tree.addRoot(new GateNode("CD45"));

        Map<String, List<Integer>> dupes = tree.findDuplicateLeafNames();
        assertTrue(dupes.containsKey("CD45+"));
        assertTrue(dupes.containsKey("CD45-"));
        assertEquals(List.of(0, 1), dupes.get("CD45+"));
    }

    @Test
    void findDuplicateLeafNamesNoDuplicates() {
        var tree = new GateTree();
        tree.addRoot(new GateNode("CD45"));
        tree.addRoot(new GateNode("PANCK"));

        Map<String, List<Integer>> dupes = tree.findDuplicateLeafNames();
        assertTrue(dupes.isEmpty());
    }

    @Test
    void findDuplicateLeafNamesIgnoresRepeatsWithinASingleRoot() {
        var tree = new GateTree();
        var root = new GateNode("CD45");
        // One root, both of whose leaves carry the same name. That is a naming
        // choice inside one root, not the cross-root collision this method reports
        // (and which GatingEngine logs as a warning).
        root.setPositiveName("SAME");
        root.setNegativeName("SAME");
        tree.addRoot(root);

        assertTrue(tree.findDuplicateLeafNames().isEmpty(),
                "a name repeated inside one root is not a cross-root duplicate");
    }

    @Test
    void findDuplicateLeafNamesReportsEachRootOnceForACrossRootCollision() {
        var tree = new GateTree();
        var first = new GateNode("CD45");
        first.setPositiveName("SAME");
        first.setNegativeName("SAME");
        var second = new GateNode("CD3");
        second.setPositiveName("SAME");
        tree.addRoot(first);
        tree.addRoot(second);

        Map<String, List<Integer>> dupes = tree.findDuplicateLeafNames();
        assertEquals(List.of(0, 1), dupes.get("SAME"),
                "root 0 contributes once even though it holds the name twice");
    }

    @Test
    void findBranchResolvesAcrossADeepCopy() {
        var tree = new GateTree();
        var first = new GateNode("CD45", 1.0);
        var second = new GateNode("CD45", 2.0);          // the same channel, deliberately
        second.getPositiveChildren().add(new GateNode("CD3", 0.5));
        tree.addRoot(first);
        tree.addRoot(second);

        Branch original = tree.findBranch(1, "CD45+/CD3+");
        assertNotNull(original, "the second root's child branch is reachable by path");

        var copy = tree.deepCopy();
        Branch inCopy = copy.findBranch(1, "CD45+/CD3+");
        assertNotNull(inCopy, "the path is the identity, not the object");
        assertNotSame(original, inCopy, "precondition: deepCopy really did mint new branches");
        assertEquals(original.getName(), inCopy.getName());
    }

    @Test
    void findBranchIndexesEnabledRootsOnlySoIndicesMatchTheReport() {
        // rootIndex is an index among ENABLED roots — PopulationStats.collectFromRoots assigns
        // it that way — so a lookup that counted disabled roots too would resolve the wrong gate.
        var tree = new GateTree();
        var skipped = new GateNode("PANCK", 1.0);
        skipped.setEnabled(false);
        tree.addRoot(skipped);
        tree.addRoot(new GateNode("CD45", 1.0));

        assertNotNull(tree.findBranch(0, "CD45+"),
                "the first ENABLED root is index 0, not the first root in the list");
        assertNull(tree.findBranch(0, "PANCK+"), "a disabled root contributes no branches");
        assertNull(tree.findBranch(9, "CD45+"), "an out-of-range root is absent, not an exception");
        assertNull(tree.findBranch(0, "no/such/path"));
    }
}
