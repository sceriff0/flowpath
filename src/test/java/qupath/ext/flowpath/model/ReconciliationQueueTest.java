package qupath.ext.flowpath.model;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ReconciliationQueueTest {

    private CellPhenotype cell(long label, int nCand, int emptyType, String cls) {
        Map<String, Number> m = new HashMap<>();
        m.put("label", (double) label);
        m.put("n_candidates", (double) nCand);
        m.put("empty_type", (double) emptyType);
        return CellPhenotype.fromMeasurements(m, cls, new PhenotypeTree());
    }

    @Test
    void ordersConflictThenAmbiguousThenUnclassifiedAndDropsArtefact() {
        List<CellPhenotype> in = List.of(
                cell(1, 3, 0, "Ambiguous"),
                cell(2, 0, 2, "Artefact"),
                cell(3, 0, 1, "Conflict"),
                cell(4, 1, 0, "Unclassified"));

        List<CellPhenotype> out = ReconciliationQueue.buildWorklist(in, true);

        assertEquals(List.of(3L, 1L, 4L), out.stream().map(CellPhenotype::getLabel).toList());
    }

    @Test
    void unclassifiedExcludedWhenFlagFalse() {
        List<CellPhenotype> in = List.of(cell(1, 3, 0, "Ambiguous"), cell(2, 1, 0, "Unclassified"));
        List<CellPhenotype> out = ReconciliationQueue.buildWorklist(in, false);
        assertEquals(List.of(1L), out.stream().map(CellPhenotype::getLabel).toList());
    }
}
