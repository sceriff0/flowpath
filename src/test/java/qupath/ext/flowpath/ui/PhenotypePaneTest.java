package qupath.ext.flowpath.ui;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.CellPhenotype;
import qupath.ext.flowpath.model.PhenotypeNode;
import qupath.ext.flowpath.model.PhenotypeTree;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PhenotypePaneTest {

    private static PathObject cell() {
        return PathObjects.createDetectionObject(ROIs.createPointsROI(0, 0, ImagePlane.getDefaultPlane()));
    }

    private PhenotypeTree tree() {
        PhenotypeTree t = new PhenotypeTree();
        PhenotypeNode tumour = new PhenotypeNode("Tumour", null, Map.of(), 0x804000, true);
        t.addRoot(tumour); t.register(tumour);
        t.setReservedColor("Ambiguous", 0x969696);
        return t;
    }

    private CellPhenotype pheno(String cls, int nCand, int emptyType, PhenotypeTree t) {
        Map<String, Number> m = new HashMap<>();
        m.put("n_candidates", (double) nCand);
        m.put("empty_type", (double) emptyType);
        if (t.findByName(cls) != null) m.put("pheno_score: " + cls, 1.0);
        else { m.put("pheno_score: Tumour", 0.5); m.put("pheno_score: Other", 0.5); }
        return CellPhenotype.fromMeasurements(m, cls, t);
    }

    @Test
    void loadAssignsClassesAndPopulatesWorklist() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        PhenotypeTree t = tree();
        PhenotypePane pane = FxTestSupport.onFx(() -> new PhenotypePane(t));
        PathObject a = cell();
        PathObject b = cell();
        FxTestSupport.onFxRun(() -> pane.load(
                List.of(pheno("Tumour", 1, 0, t), pheno("Ambiguous", 2, 0, t)),
                List.of(a, b)));

        assertEquals("Tumour", a.getPathClass().getName());
        assertEquals("Ambiguous", b.getPathClass().getName());
        assertEquals(1, FxTestSupport.onFx(pane::worklistSize)); // only the Ambiguous cell queued
    }
}
