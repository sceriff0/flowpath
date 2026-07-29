package qupath.ext.flowpath.model;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PhenotypeStatsTest {

    private CellPhenotype cell(int nCand, int emptyType, String cls, boolean manual) {
        Map<String, Number> m = new HashMap<>();
        m.put("n_candidates", (double) nCand);
        m.put("empty_type", (double) emptyType);
        m.put("provenance", manual ? 1.0 : 0.0);
        return CellPhenotype.fromMeasurements(m, cls, new PhenotypeTree());
    }

    @Test
    void countsAndRatesPerOutcome() {
        PhenotypeStats s = PhenotypeStats.compute(List.of(
                cell(1, 0, "Tumour", false),      // note: unnamed here -> UNCLASSIFIED
                cell(3, 0, "Ambiguous", false),
                cell(0, 2, "Artefact", false),
                cell(0, 1, "Conflict", false)));
        assertEquals(4, s.total());
        assertEquals(1, s.count(PhenotypeOutcome.AMBIGUOUS));
        assertEquals(0.25, s.rate(PhenotypeOutcome.CONFLICT), 1e-9);
    }

    @Test
    void reconciliationProgressCountsManualResolvedUncertain() {
        PhenotypeStats s = PhenotypeStats.compute(List.of(
                cell(3, 0, "Ambiguous", true),    // uncertain, resolved
                cell(0, 1, "Conflict", false)));  // uncertain, unresolved
        assertEquals(2, s.uncertainTotal());
        assertEquals(1, s.uncertainResolved());
        assertEquals(0.5, s.reconciliationProgress(), 1e-9);
    }
}
