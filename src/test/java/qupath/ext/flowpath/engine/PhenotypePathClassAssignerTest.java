package qupath.ext.flowpath.engine;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.*;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PhenotypePathClassAssignerTest {

    private static PathObject cell() {
        return PathObjects.createDetectionObject(ROIs.createPointsROI(0, 0, ImagePlane.getDefaultPlane()));
    }

    private PhenotypeTree tree() {
        PhenotypeTree t = new PhenotypeTree();
        PhenotypeNode tumour = new PhenotypeNode("Tumour", null, Map.of(), 0x804000, true);
        t.addRoot(tumour); t.register(tumour);
        t.setReservedColor("Conflict", 0xE68C00);
        return t;
    }

    private CellPhenotype pheno(String cls, int nCand, int emptyType, PhenotypeTree t) {
        Map<String, Number> m = new HashMap<>();
        m.put("n_candidates", (double) nCand);
        m.put("empty_type", (double) emptyType);
        if (t.findByName(cls) != null) m.put("pheno_score: " + cls, 1.0);
        return CellPhenotype.fromMeasurements(m, cls, t);
    }

    @Test
    void assignsCommittedAndReservedClassesOnePerCell() {
        PhenotypeTree t = tree();
        PathObject a = cell();
        PathObject b = cell();
        PhenotypePathClassAssigner.assign(List.of(a, b),
                List.of(pheno("Tumour", 1, 0, t), pheno("Conflict", 0, 1, t)), t);

        assertEquals("Tumour", a.getPathClass().getName());
        assertEquals("Conflict", b.getPathClass().getName());
        // committed class name has no ": " composite separator
        assertFalse(a.getPathClass().toString().contains(": "));
    }
}
