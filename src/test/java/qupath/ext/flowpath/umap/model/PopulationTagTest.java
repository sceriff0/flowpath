package qupath.ext.flowpath.umap.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PopulationTagTest {

    @Test
    void countReturnsTrueEntries() {
        boolean[] mask = {true, false, true, false, true};
        var tag = new PopulationTag("Cluster A", 0xFF0000, mask);

        assertEquals(3, tag.count());
    }

    @Test
    void allFalseMaskReturnsZero() {
        boolean[] mask = new boolean[10]; // all false
        var tag = new PopulationTag("Empty", 0x000000, mask);

        assertEquals(0, tag.count());
    }

    @Test
    void allTrueMaskReturnsLength() {
        boolean[] mask = {true, true, true, true};
        var tag = new PopulationTag("All", 0x00FF00, mask);

        assertEquals(4, tag.count());
    }

    @Test
    void recordFieldsAccessible() {
        var tag = new PopulationTag("Test", 0xABCDEF, new boolean[]{true});

        assertEquals("Test", tag.name());
        assertEquals(0xABCDEF, tag.color());
        assertEquals(1, tag.mask().length);
    }

    @Test
    void maskDefensivelyCopiedOnConstruction() {
        boolean[] original = {true, false, true};
        var tag = new PopulationTag("Test", 0xFF0000, original);
        original[0] = false; // mutate original
        assertEquals(2, tag.count(), "Mutation of original array should not affect tag");
    }

    @Test
    void maskGetterReturnsDefensiveCopy() {
        var tag = new PopulationTag("Test", 0xFF0000, new boolean[]{true, true, true});
        boolean[] retrieved = tag.mask();
        retrieved[0] = false; // mutate retrieved copy
        assertEquals(3, tag.count(), "Mutation of returned array should not affect tag");
    }
}
