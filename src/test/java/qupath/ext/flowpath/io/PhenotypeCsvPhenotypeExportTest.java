package qupath.ext.flowpath.io;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.CellPhenotype;
import qupath.ext.flowpath.model.PhenotypeNode;
import qupath.ext.flowpath.model.PhenotypeTree;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PhenotypeCsvPhenotypeExportTest {

    private PhenotypeTree tree() {
        PhenotypeTree t = new PhenotypeTree();
        PhenotypeNode tumour = new PhenotypeNode("Tumour", null, Map.of(), 0x804000, true);
        t.addRoot(tumour); t.register(tumour);
        return t;
    }

    private CellPhenotype committed() {
        Map<String, Number> m = new HashMap<>();
        m.put("label", 5.0);
        m.put("n_candidates", 1.0);
        m.put("empty_type", 0.0);
        m.put("provenance", 1.0);
        m.put("pheno_score: Tumour", 0.9);
        return CellPhenotype.fromMeasurements(m, "Tumour", tree());
    }

    @Test
    void writesCommittedOutcomeProvenanceCandidates() throws Exception {
        File out = File.createTempFile("pheno", ".csv");
        PhenotypeCsvExporter.exportPhenotypes(out, List.of(committed()), tree());
        List<String> lines = Files.readAllLines(out.toPath());
        assertEquals("cell_label,committed_phenotype,outcome,provenance,candidates", lines.get(0));
        assertEquals("5,Tumour,PHENOTYPE,MANUAL,Tumour:0.9000", lines.get(1));
    }
}
