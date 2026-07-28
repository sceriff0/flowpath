package qupath.ext.flowpath.engine;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.*;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ReconciliationControllerTest {

    private PhenotypeTree tree() {
        PhenotypeTree t = new PhenotypeTree();
        PhenotypeNode immune = new PhenotypeNode("Immune", null, Map.of(), 0, false);
        PhenotypeNode cd8 = new PhenotypeNode("CD8_T", "Immune", Map.of(), 0, true);
        immune.addChild(cd8); t.addRoot(immune); t.register(immune); t.register(cd8);
        return t;
    }

    private CellPhenotype ambiguous() {
        Map<String, Number> m = new HashMap<>();
        m.put("n_candidates", 2.0);
        m.put("pheno_score: CD8_T", 0.6);
        m.put("pheno_score: Immune", 0.4);
        return CellPhenotype.fromMeasurements(m, "Ambiguous", tree());
    }

    @Test
    void commitSetsManualProvenanceAndCommittedName() {
        ReconciliationController ctrl = new ReconciliationController(tree());
        CellPhenotype cell = ambiguous();
        ctrl.commit(cell, "CD8_T");

        assertEquals("CD8_T", cell.getCommitted());
        assertEquals(Provenance.MANUAL, cell.getProvenance());
        assertEquals(PhenotypeOutcome.PHENOTYPE, cell.getOutcome());
        assertTrue(ctrl.canUndo());
    }

    @Test
    void undoRestoresPriorState() {
        ReconciliationController ctrl = new ReconciliationController(tree());
        CellPhenotype cell = ambiguous();
        ctrl.commit(cell, "CD8_T");
        ctrl.undo();

        assertEquals("Ambiguous", cell.getCommitted());
        assertEquals(Provenance.MODEL, cell.getProvenance());
        assertEquals(PhenotypeOutcome.AMBIGUOUS, cell.getOutcome());
        assertFalse(ctrl.canUndo());
    }
}
