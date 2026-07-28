package qupath.ext.flowpath.model;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class CellPhenotypeConstraintTest {

    private PhenotypeTree treeWithConstraint() {
        PhenotypeTree t = new PhenotypeTree();
        t.addConstraint(new ConstraintEntry(0, List.of("CD3", "CD20"), "never", 0.0));
        return t;
    }

    @Test
    void conflictNamesItsConstraintViaSidecarTable() {
        Map<String, Number> m = new HashMap<>();
        m.put("n_candidates", 0.0);
        m.put("empty_type", 1.0);
        m.put("violated_constraint_id", 0.0);
        CellPhenotype c = CellPhenotype.fromMeasurements(m, "Conflict", treeWithConstraint());
        assertEquals("CD3 ⊥ CD20 (never)", c.violatedConstraintLabel(treeWithConstraint()));
    }

    @Test
    void nonConflictHasDashLabel() {
        Map<String, Number> m = new HashMap<>();
        m.put("n_candidates", 1.0);
        m.put("empty_type", 0.0);
        m.put("violated_constraint_id", -1.0);
        m.put("pheno_score: Tumour", 1.0);
        PhenotypeTree t = treeWithConstraint();
        PhenotypeNode tumour = new PhenotypeNode("Tumour", null, Map.of(), 0, true);
        t.addRoot(tumour); t.register(tumour);
        CellPhenotype c = CellPhenotype.fromMeasurements(m, "Tumour", t);
        assertEquals("—", c.violatedConstraintLabel(t));
    }
}
