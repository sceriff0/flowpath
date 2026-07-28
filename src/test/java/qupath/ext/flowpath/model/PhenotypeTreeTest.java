package qupath.ext.flowpath.model;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PhenotypeTreeTest {

    private PhenotypeTree sampleTree() {
        PhenotypeTree t = new PhenotypeTree();
        PhenotypeNode immune = new PhenotypeNode("Immune", null, Map.of("CD45", 1, "PanCK", 0), 0x00A0FF, false);
        PhenotypeNode tcell = new PhenotypeNode("T_cell", "Immune", Map.of("CD45", 1, "CD3", 1), 0x00C080, false);
        PhenotypeNode cd8 = new PhenotypeNode("CD8_T", "T_cell", Map.of("CD3", 1, "CD8", 1, "CD4", 0), 0x10FF10, true);
        immune.addChild(tcell);
        tcell.addChild(cd8);
        t.addRoot(immune);
        t.register(immune);
        t.register(tcell);
        t.register(cd8);
        return t;
    }

    @Test
    void findByNameResolvesRegisteredNodes() {
        PhenotypeTree t = sampleTree();
        assertEquals("CD8_T", t.findByName("CD8_T").getName());
        assertNull(t.findByName("NoSuchType"));
    }

    @Test
    void ancestorsOfWalksParentChainNearestFirstExcludingSelf() {
        PhenotypeTree t = sampleTree();
        assertEquals(List.of("T_cell", "Immune"), t.ancestorsOf("CD8_T"));
        assertEquals(List.of(), t.ancestorsOf("Immune"));
    }

    @Test
    void nodeExposesSignatureColorAndLeafFlag() {
        PhenotypeNode cd8 = sampleTree().findByName("CD8_T");
        assertTrue(cd8.isLeaf());
        assertEquals(1, cd8.getSignature().get("CD8"));
        assertEquals(0x10FF10, cd8.getColor());
    }
}
