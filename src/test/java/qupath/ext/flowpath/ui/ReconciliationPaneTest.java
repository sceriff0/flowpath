package qupath.ext.flowpath.ui;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.engine.ReconciliationController;
import qupath.ext.flowpath.model.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ReconciliationPaneTest {

    private PhenotypeTree tree() {
        PhenotypeTree t = new PhenotypeTree();
        PhenotypeNode cd8 = new PhenotypeNode("CD8_T", null, Map.of("CD8", 1), 0x10FF10, true);
        t.addRoot(cd8); t.register(cd8);
        return t;
    }

    private CellPhenotype ambiguous(long label) {
        Map<String, Number> m = new HashMap<>();
        m.put("label", (double) label);
        m.put("n_candidates", 2.0);
        m.put("pheno_score: CD8_T", 0.6);
        m.put("pheno_score: Other", 0.4);
        return CellPhenotype.fromMeasurements(m, "Ambiguous", tree());
    }

    @Test
    void commitAdvancesToNextItemAndFiresNavigation() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        PhenotypeTree t = tree();
        ReconciliationController ctrl = new ReconciliationController(t);
        ReconciliationPane pane = FxTestSupport.onFx(() -> new ReconciliationPane(t, ctrl));
        List<Long> navigated = new ArrayList<>();
        FxTestSupport.onFxRun(() -> {
            pane.setOnNavigate(item -> navigated.add(item.cell().getLabel()));
            pane.setItems(List.of(ambiguous(1), ambiguous(2)));
        });

        CellPhenotype first = FxTestSupport.onFx(pane::getCurrent);
        assertEquals(1L, first.getLabel());
        FxTestSupport.onFxRun(() -> pane.commitCurrent("CD8_T"));

        // The committed cell is stamped manual; the pane advances to the next residue item.
        assertEquals(Provenance.MANUAL, first.getProvenance());
        assertEquals("CD8_T", first.getCommitted());
        assertEquals(2L, FxTestSupport.onFx(() -> pane.getCurrent().getLabel()));
        assertTrue(navigated.contains(1L) && navigated.contains(2L));
    }
}
