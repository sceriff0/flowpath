package qupath.ext.flowpath.model;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.*;

class PhenotypeFilterTest {

    private PhenotypeTree tree() {
        PhenotypeTree t = new PhenotypeTree();
        PhenotypeNode immune = new PhenotypeNode("Immune", null, Map.of(), 0, false);
        PhenotypeNode cd8 = new PhenotypeNode("CD8_T", "Immune", Map.of(), 0, true);
        immune.addChild(cd8); t.addRoot(immune); t.register(immune); t.register(cd8);
        return t;
    }

    private CellPhenotype cell(String cls, int nCand, int emptyType, PhenotypeTree t) {
        Map<String, Number> m = new HashMap<>();
        m.put("n_candidates", (double) nCand);
        m.put("empty_type", (double) emptyType);
        if (t.findByName(cls) != null) m.put("pheno_score: " + cls, 1.0);
        return CellPhenotype.fromMeasurements(m, cls, t);
    }

    @Test
    void nodeFilterMatchesSelfAndDescendants() {
        PhenotypeTree t = tree();
        Predicate<CellPhenotype> p = PhenotypeFilter.forNode("Immune", t);
        assertTrue(p.test(cell("CD8_T", 1, 0, t)));   // descendant
        assertTrue(p.test(cell("Immune", 1, 0, t)));  // self
    }

    @Test
    void uncertainIsAmbiguousUnionConflict() {
        PhenotypeTree t = tree();
        Predicate<CellPhenotype> p = PhenotypeFilter.forReserved("Uncertain");
        assertTrue(p.test(cell("Ambiguous", 3, 0, t)));
        assertTrue(p.test(cell("Conflict", 0, 1, t)));
        assertFalse(p.test(cell("Artefact", 0, 2, t)));
    }
}
