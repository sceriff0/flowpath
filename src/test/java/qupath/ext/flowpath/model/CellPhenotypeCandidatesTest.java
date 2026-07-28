package qupath.ext.flowpath.model;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class CellPhenotypeCandidatesTest {

    @Test
    void candidatesAreScorePositiveSortedDescending() {
        Map<String, Number> m = new HashMap<>();
        m.put("n_candidates", 2.0);
        m.put("pheno_score: CD8_T", 0.6);
        m.put("pheno_score: CD4_T", 0.4);
        m.put("pheno_score: Tumour", 0.0);   // excluded (not > 0)

        CellPhenotype c = CellPhenotype.fromMeasurements(m, "Ambiguous", new PhenotypeTree());
        List<Candidate> cands = c.candidates();

        assertEquals(2, cands.size());
        assertEquals("CD8_T", cands.get(0).name());
        assertEquals("CD4_T", cands.get(1).name());
        assertEquals(List.of("CD8_T", "CD4_T"), c.candidateNames());
    }

    @Test
    void tiesBrokenByNameAscending() {
        Map<String, Number> m = new HashMap<>();
        m.put("pheno_score: B_cell", 0.5);
        m.put("pheno_score: A_cell", 0.5);
        CellPhenotype c = CellPhenotype.fromMeasurements(m, "Ambiguous", new PhenotypeTree());
        assertEquals(List.of("A_cell", "B_cell"), c.candidateNames());
    }
}
