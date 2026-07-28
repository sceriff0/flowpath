package qupath.ext.flowpath.model;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PhenotypeTreeCountsTest {

    private PhenotypeTree tree() {
        PhenotypeTree t = new PhenotypeTree();
        PhenotypeNode immune = new PhenotypeNode("Immune", null, Map.of(), 0, false);
        PhenotypeNode tcell = new PhenotypeNode("T_cell", "Immune", Map.of(), 0, false);
        PhenotypeNode cd8 = new PhenotypeNode("CD8_T", "T_cell", Map.of(), 0, true);
        immune.addChild(tcell); tcell.addChild(cd8);
        t.addRoot(immune); t.register(immune); t.register(tcell); t.register(cd8);
        return t;
    }

    private CellPhenotype committed(String name) {
        Map<String, Number> m = new HashMap<>();
        m.put("n_candidates", 1.0);
        m.put("empty_type", 0.0);
        m.put("pheno_score: " + name, 1.0);
        return CellPhenotype.fromMeasurements(m, name, tree());
    }

    private CellPhenotype bucket(int nCand, int emptyType, String cls) {
        Map<String, Number> m = new HashMap<>();
        m.put("n_candidates", (double) nCand);
        m.put("empty_type", (double) emptyType);
        return CellPhenotype.fromMeasurements(m, cls, new PhenotypeTree());
    }

    @Test
    void committedLeafRollsUpToAncestors() {
        PhenotypeTree t = tree();
        t.recomputeCounts(List.of(committed("CD8_T"), committed("CD8_T")));
        assertEquals(2, t.findByName("CD8_T").getCount());
        assertEquals(2, t.findByName("T_cell").getCount());
        assertEquals(2, t.findByName("Immune").getCount());
    }

    @Test
    void reservedBucketsCounted() {
        PhenotypeTree t = tree();
        t.recomputeCounts(List.of(bucket(3, 0, "Ambiguous"), bucket(0, 1, "Conflict"),
                bucket(0, 2, "Artefact"), bucket(1, 0, "Unclassified")));
        assertEquals(1, t.reservedCount("Ambiguous"));
        assertEquals(1, t.reservedCount("Conflict"));
        assertEquals(1, t.reservedCount("Artefact"));
        assertEquals(1, t.reservedCount("Unclassified"));
    }
}
