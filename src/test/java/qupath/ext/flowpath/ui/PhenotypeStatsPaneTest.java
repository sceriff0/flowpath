package qupath.ext.flowpath.ui;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.PhenotypeStats;
import qupath.ext.flowpath.model.PhenotypeOutcome;
import qupath.ext.flowpath.model.CellPhenotype;
import qupath.ext.flowpath.model.PhenotypeTree;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PhenotypeStatsPaneTest {

    @Test
    void progressTextFormatterIsToolkitFree() {
        assertEquals("1 of 2 Uncertain resolved", PhenotypeStatsPane.progressText(1, 2));
        assertEquals("0 of 0 Uncertain resolved", PhenotypeStatsPane.progressText(0, 0));
    }

    @Test
    void rateFormatterReadsFromStats() {
        Map<String, Number> m = new HashMap<>();
        m.put("n_candidates", 3.0); m.put("empty_type", 0.0);
        CellPhenotype amb = CellPhenotype.fromMeasurements(m, "Ambiguous", new PhenotypeTree());
        PhenotypeStats s = PhenotypeStats.compute(List.of(amb));
        assertEquals("100.0%", PhenotypeStatsPane.ratePercent(s, PhenotypeOutcome.AMBIGUOUS));
    }
}
