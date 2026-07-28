package qupath.ext.flowpath.model;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ReconciliationPropagateUpTest {

    private PhenotypeTree tree() {
        PhenotypeTree t = new PhenotypeTree();
        PhenotypeNode immune = new PhenotypeNode("Immune", null, Map.of(), 0, false);
        PhenotypeNode tcell = new PhenotypeNode("T_cell", "Immune", Map.of(), 0, false);
        PhenotypeNode cd8 = new PhenotypeNode("CD8_T", "T_cell", Map.of(), 0, true);
        PhenotypeNode cd4 = new PhenotypeNode("CD4_T", "T_cell", Map.of(), 0, true);
        PhenotypeNode b = new PhenotypeNode("B_cell", "Immune", Map.of(), 0, true);
        immune.addChild(tcell); tcell.addChild(cd8); tcell.addChild(cd4); immune.addChild(b);
        t.addRoot(immune);
        for (PhenotypeNode n : new PhenotypeNode[]{immune, tcell, cd8, cd4, b}) t.register(n);
        return t;
    }

    private CellPhenotype ambiguous(String... names) {
        Map<String, Number> m = new HashMap<>();
        m.put("n_candidates", (double) names.length);
        for (int i = 0; i < names.length; i++) m.put("pheno_score: " + names[i], 0.5 - i * 0.01);
        return CellPhenotype.fromMeasurements(m, "Ambiguous", new PhenotypeTree());
    }

    @Test
    void siblingLeavesPropagateToNearestCommonAncestor() {
        assertEquals("T_cell", ReconciliationQueue.propagateUp(ambiguous("CD8_T", "CD4_T"), tree()));
    }

    @Test
    void divergentBranchesPropagateToRootOrNull() {
        assertEquals("Immune", ReconciliationQueue.propagateUp(ambiguous("CD8_T", "B_cell"), tree()));
    }

    @Test
    void singleOrNoCommonAncestorReturnsNull() {
        assertNull(ReconciliationQueue.propagateUp(ambiguous("CD8_T"), tree()));
    }
}
