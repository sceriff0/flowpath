package qupath.ext.flowpath.model;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ReconciliationPreClearTest {

    private CellPhenotype ambiguous(long label, double topScore, String topName) {
        Map<String, Number> m = new HashMap<>();
        m.put("label", (double) label);
        m.put("n_candidates", 2.0);
        m.put("pheno_score: " + topName, topScore);
        m.put("pheno_score: Other", topScore - 0.2);
        return CellPhenotype.fromMeasurements(m, "Ambiguous", new PhenotypeTree());
    }

    @Test
    void commitsAboveThresholdAndReturnsResidue() {
        List<CellPhenotype> worklist = List.of(
                ambiguous(1, 0.9, "CD8_T"),
                ambiguous(2, 0.5, "CD4_T"));
        List<String> committed = new ArrayList<>();
        List<CellPhenotype> residue = ReconciliationQueue.residueAfterPreClear(
                worklist, 0.8, (cell, name) -> committed.add(cell.getLabel() + "=" + name));

        assertEquals(List.of("1=CD8_T"), committed);
        assertEquals(List.of(2L), residue.stream().map(CellPhenotype::getLabel).toList());
    }
}
