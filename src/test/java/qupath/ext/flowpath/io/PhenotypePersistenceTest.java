package qupath.ext.flowpath.io;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.CellPhenotype;
import qupath.ext.flowpath.model.PhenotypeTree;
import qupath.ext.flowpath.model.Provenance;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PhenotypePersistenceTest {

    private CellPhenotype cell(long label, double topScore, String topName, boolean manual, String committed) {
        Map<String, Number> m = new HashMap<>();
        m.put("label", (double) label);
        m.put("n_candidates", 2.0);
        m.put("provenance", manual ? 1.0 : 0.0);
        m.put("pheno_score: " + topName, topScore);
        m.put("pheno_score: Other", 0.1);
        return CellPhenotype.fromMeasurements(m, committed, new PhenotypeTree());
    }

    @Test
    void saveThenLoadRoundTripsManualEdits() throws Exception {
        File f = File.createTempFile("manual", ".json");
        CellPhenotype edited = cell(3, 0.7, "CD8_T", true, "CD8_T");
        PhenotypePersistence.saveManual(f, "P001", List.of(edited));
        Map<String, String> loaded = PhenotypePersistence.loadManual(f);
        assertEquals("CD8_T", loaded.get("P001:3"));
    }

    @Test
    void reattachKeepsSupportedAndResurfacesContradicted() {
        Map<String, String> prior = new HashMap<>();
        prior.put("P001:1", "CD8_T");   // still a candidate below -> kept
        prior.put("P001:2", "CD8_T");   // no longer a candidate -> resurfaced

        CellPhenotype stillSupported = cell(1, 0.6, "CD8_T", false, "Ambiguous");
        CellPhenotype contradicted   = cell(2, 0.6, "B_cell", false, "Ambiguous");

        var result = PhenotypePersistence.reattach(prior, List.of(stillSupported, contradicted), "P001");

        assertEquals(1, result.kept());
        assertEquals("CD8_T", stillSupported.getCommitted());
        assertEquals(Provenance.MANUAL, stillSupported.getProvenance());
        assertEquals(1, result.resurfaced().size());
        assertEquals(2L, result.resurfaced().get(0).getLabel());
    }
}
