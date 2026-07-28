package qupath.ext.flowpath.model;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class CellPhenotypeTest {

    private PhenotypeTree tree() {
        PhenotypeTree t = new PhenotypeTree();
        PhenotypeNode tumour = new PhenotypeNode("Tumour", null, Map.of("PanCK", 1, "CD45", 0), 0x804000, true);
        t.addRoot(tumour);
        t.register(tumour);
        return t;
    }

    @Test
    void committedNamedPhenotypeParsesAllFields() {
        Map<String, Number> m = new HashMap<>();
        m.put("label", 42.0);
        m.put("n_candidates", 1.0);
        m.put("empty_type", 0.0);
        m.put("violated_constraint_id", -1.0);
        m.put("provenance", 0.0);
        m.put("pheno_score: Tumour", 0.95);
        m.put("CD3: p_neg", 0.02);
        m.put("CD3: p_pos", 0.8);
        m.put("state: Ki67", 1.0);

        CellPhenotype c = CellPhenotype.fromMeasurements(m, "Tumour", tree());

        assertEquals(42L, c.getLabel());
        assertEquals(PhenotypeOutcome.PHENOTYPE, c.getOutcome());
        assertEquals("Tumour", c.getCommitted());
        assertEquals(Provenance.MODEL, c.getProvenance());
        assertEquals(0.02, c.pNeg("CD3"), 1e-9);
        assertEquals(0.8, c.pPos("CD3"), 1e-9);
        assertEquals(Integer.valueOf(1), c.state("Ki67"));
    }

    @Test
    void conflictOutcomeKeyedByEmptyType() {
        Map<String, Number> m = new HashMap<>();
        m.put("label", 7.0);
        m.put("n_candidates", 0.0);
        m.put("empty_type", 1.0);
        m.put("violated_constraint_id", 0.0);
        CellPhenotype c = CellPhenotype.fromMeasurements(m, "Conflict", tree());
        assertEquals(PhenotypeOutcome.CONFLICT, c.getOutcome());
        assertEquals(0, c.getViolatedConstraintId());
    }

    @Test
    void feasibleButUnnamedIsUnclassified() {
        Map<String, Number> m = new HashMap<>();
        m.put("n_candidates", 1.0);
        m.put("empty_type", 0.0);
        CellPhenotype c = CellPhenotype.fromMeasurements(m, "Unclassified", tree());
        assertEquals(PhenotypeOutcome.UNCLASSIFIED, c.getOutcome());
    }
}
