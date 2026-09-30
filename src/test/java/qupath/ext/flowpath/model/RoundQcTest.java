package qupath.ext.flowpath.model;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.testing.Cells;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RoundQcTest {

    static final List<String> CD3_CD8 = List.of("CD3", "CD8");
    static final List<String> CD68 = List.of("CD68");

    /** Four cells, two rounds; cell 1 lost its nucleus in [CD3, CD8], cell 2 has no evidence there. */
    static CellIndex index() {
        return Cells.of(4)
                .marker("DAPI", 900).marker("CD3", 10, 20, 30, 40).marker("CD8", 1, 2, 3, 4).marker("CD68", 5, 6, 7, 8)
                .round("Nuclear retention", CD3_CD8, 1.0, 0.04, 1.0, 0.9).absentOn(i -> i == 2)
                .round("Registration Dice", CD3_CD8, 0.9, 0.9, 0.9, 0.2)
                .round("Nuclear retention", CD68, 1.0, 1.0, 0.1, 1.0)
                .build();
    }

    @Test
    void roundsAndMetricsAreDiscoveredFromTheKeys() {
        RoundQc qc = index().roundQc();
        assertEquals(List.of(CD3_CD8, CD68), qc.rounds().stream().map(RoundQc.Round::markers).toList());
        assertEquals("[CD3, CD8]", qc.rounds().get(0).label());
        assertEquals(List.of("qcround/nuclear_retention", "qcround/registration_dice"),
                qc.metrics().stream().map(RoundQc.Metric::slug).toList());
        assertEquals(0, qc.roundOf("CD3"));
        assertEquals(1, qc.roundOf("CD68"));
        assertEquals(-1, qc.roundOf("DAPI"), "a reference-round marker is never round-masked");
        assertTrue(Double.isNaN(qc.value(1, 1, 0)), "Dice was not measured for the CD68 round");
    }

    @Test
    void noRangeMeansNoMask() {
        CellIndex index = index();
        RoundMask mask = index.roundQc().mask(new QualityFilter());
        assertTrue(mask.isEmpty());
        assertFalse(mask.fails("CD3", 1));
    }

    @Test
    void aFailingCellIsMaskedOnlyForItsRoundsMarkersAndNaNPasses() {
        CellIndex index = index();
        QualityFilter f = new QualityFilter();
        f.setMin("qcround/nuclear_retention", 0.5);
        RoundMask mask = index.roundQc().mask(f);

        assertTrue(mask.fails("CD3", 1));
        assertTrue(mask.fails("CD8", 1));
        assertFalse(mask.fails("CD68", 1), "cell 1 kept its nucleus in the CD68 round");
        assertFalse(mask.fails("DAPI", 1));
        assertFalse(mask.fails("CD3", 2), "no retention value is no evidence, not a failure");
        assertTrue(mask.fails("CD68", 2));
        assertEquals(1, mask.failedCount(0));
        assertEquals(1, mask.failedCount(1));
    }

    @Test
    void anyFailingMetricFailsTheRound() {
        QualityFilter f = new QualityFilter();
        f.setMin("qcround/nuclear_retention", 0.5);
        f.setMin("qcround/registration_dice", 0.5);
        RoundMask mask = index().roundQc().mask(f);
        assertTrue(mask.fails("CD3", 1));
        assertTrue(mask.fails("CD3", 3), "Dice 0.2 fails although retention passes");
        assertEquals(2, mask.failedCount(0));
    }

    @Test
    void masksWithTheSameFailuresAreEqual() {
        CellIndex index = index();
        QualityFilter a = new QualityFilter();
        a.setMin("qcround/nuclear_retention", 0.5);
        QualityFilter b = new QualityFilter();
        b.setMin("qcround/nuclear_retention", 0.6);
        assertEquals(index.roundQc().mask(a), index.roundQc().mask(b));
        QualityFilter c = new QualityFilter();
        c.setMin("qcround/nuclear_retention", 0.95);
        assertNotEquals(index.roundQc().mask(a), index.roundQc().mask(c));
        assertEquals(RoundMask.NONE, index.roundQc().mask(new QualityFilter()));
    }

    @Test
    void anExportWithoutRoundKeysHasNoRounds() {
        CellIndex index = Cells.of(2).marker("CD3", 1, 2).build();
        assertTrue(index.roundQc().isEmpty());
        QualityFilter f = new QualityFilter();
        f.setMin("qcround/nuclear_retention", 0.5);
        assertTrue(index.roundQc().mask(f).isEmpty());
    }

    @Test
    void theFailedRoundsOfACellAreListed() {
        QualityFilter f = new QualityFilter();
        f.setMin("qcround/nuclear_retention", 0.5);
        RoundMask mask = index().roundQc().mask(f);
        assertEquals(List.of("[CD3, CD8]"), mask.failedRoundLabels(1));
        assertEquals(List.of("[CD68]"), mask.failedRoundLabels(2));
        assertEquals(List.of(), mask.failedRoundLabels(0));
    }
}
