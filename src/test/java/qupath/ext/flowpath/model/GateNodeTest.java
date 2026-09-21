package qupath.ext.flowpath.model;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GateNodeTest {

    @Test
    void constructorSetsDefaults() {
        var node = new GateNode("CD45", 1.5);
        assertEquals("CD45", node.getChannel());
        assertEquals(1.5, node.getThreshold());
        assertFalse(node.isThresholdIsZScore(), "new gates compare the column as measured");
        assertEquals("CD45+", node.getPositiveName());
        assertEquals("CD45-", node.getNegativeName());
    }

    @Test
    void singleArgConstructorDefaultsThresholdToZero() {
        var node = new GateNode("CD3");
        assertEquals(0.0, node.getThreshold());
        assertFalse(node.isThresholdIsZScore());
    }

    @Test
    void isLeafTrueWhenNoChildren() {
        var node = new GateNode("CD8");
        assertTrue(node.isLeaf());
    }

    @Test
    void isLeafFalseWithPositiveChildren() {
        var node = new GateNode("CD45");
        node.getPositiveChildren().add(new GateNode("CD3"));
        assertFalse(node.isLeaf());
    }

    @Test
    void isLeafFalseWithNegativeChildren() {
        var node = new GateNode("CD45");
        node.getNegativeChildren().add(new GateNode("PANCK"));
        assertFalse(node.isLeaf());
    }

    @Test
    void deepCopyCreatesIndependentCopy() {
        var original = new GateNode("CD45", 2.0);
        original.setPositiveColor(0xFF0000);
        var copy = original.deepCopy();

        // Modify original
        original.setThreshold(5.0);
        original.setPositiveColor(0x00FF00);

        // Copy should be unchanged
        assertEquals(2.0, copy.getThreshold());
        assertEquals(0xFF0000, copy.getPositiveColor());
    }

    @Test
    void deepCopyPreservesAllFields() {
        var node = new GateNode("CD45", 1.5);
        node.setPositiveName("Immune+");
        node.setNegativeName("Immune-");
        node.setPositiveColor(0xFF0000);
        node.setNegativeColor(0x808080);
        node.setClipPercentileLow(2.0);
        node.setClipPercentileHigh(98.0);
        node.setExcludeOutliers(true);
        node.setThresholdIsZScore(true);   // a legacy gate not yet migrated

        var copy = node.deepCopy();
        assertEquals("CD45", copy.getChannel());
        assertEquals(1.5, copy.getThreshold());
        assertTrue(copy.isThresholdIsZScore());
        assertEquals("Immune+", copy.getPositiveName());
        assertEquals("Immune-", copy.getNegativeName());
        assertEquals(0xFF0000, copy.getPositiveColor());
        assertEquals(0x808080, copy.getNegativeColor());
        assertEquals(2.0, copy.getClipPercentileLow());
        assertEquals(98.0, copy.getClipPercentileHigh());
        assertTrue(copy.isExcludeOutliers());
    }

    @Test
    void deepCopyRecursivelycopiesChildren() {
        var root = new GateNode("CD45");
        var child = new GateNode("CD3");
        var grandchild = new GateNode("CD8");
        child.getPositiveChildren().add(grandchild);
        root.getPositiveChildren().add(child);

        var copy = root.deepCopy();

        // Modify original grandchild
        grandchild.setThreshold(99.0);

        // Copy's grandchild should be unaffected
        var copiedGrandchild = copy.getPositiveChildren().get(0).getPositiveChildren().get(0);
        assertEquals(0.0, copiedGrandchild.getThreshold());
        assertEquals("CD8", copiedGrandchild.getChannel());
    }

    @Test
    void collectLeafNamesOnSingleNode() {
        var node = new GateNode("CD45");
        List<String> names = new ArrayList<>();
        node.collectLeafNames(names);
        assertEquals(List.of("CD45+", "CD45-"), names);
    }

    @Test
    void collectLeafNamesOnTreeReturnsOnlyLeaves() {
        // CD45 -> pos: CD3 (leaf), neg: PANCK (leaf)
        var root = new GateNode("CD45");
        root.getPositiveChildren().add(new GateNode("CD3"));
        root.getNegativeChildren().add(new GateNode("PANCK"));

        List<String> names = new ArrayList<>();
        root.collectLeafNames(names);

        // Should get leaf names from CD3 and PANCK, not CD45+/CD45-
        assertEquals(List.of("CD3+", "CD3-", "PANCK+", "PANCK-"), names);
    }

    @Test
    void transferCountsFromCopiesCounts() {
        var source = new GateNode("CD45");
        source.setPosCount(42);
        source.setNegCount(58);

        var target = new GateNode("CD45");
        target.transferCountsFrom(source);

        assertEquals(42, target.getPosCount());
        assertEquals(58, target.getNegCount());
    }
}
