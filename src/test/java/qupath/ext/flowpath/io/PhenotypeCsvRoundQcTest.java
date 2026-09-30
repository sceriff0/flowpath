package qupath.ext.flowpath.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.flowpath.engine.CleanMask;
import qupath.ext.flowpath.engine.GatingEngine;
import qupath.ext.flowpath.ingest.DetectionIngest;
import qupath.ext.flowpath.ingest.IngestOptions;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.QualityFilter;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.testing.Cells;
import qupath.ext.flowpath.testing.MirageSample;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The per-cell CSV says which imaging rounds a cell failed, and keeps its raw values. */
class PhenotypeCsvRoundQcTest {

    static List<String> export(CellIndex index, boolean filtered, Path dir) throws Exception {
        GateTree tree = new GateTree();
        QualityFilter f = new QualityFilter();
        if (filtered) f.setMin("qcround/nuclear_retention", 0.5);
        tree.setQualityFilter(f);
        GateNode cd3 = new GateNode("CD3", 100);
        cd3.setStatistic(Statistic.MEAN);
        tree.addRoot(cd3);
        CleanMask clean = CleanMask.of(index, f, false, List.of());
        MarkerStats stats = MarkerStats.compute(index, clean.combined(), clean.rounds());
        GatingEngine.AssignmentResult r = GatingEngine.assignAll(tree, index, stats);
        File out = dir.resolve("pheno.csv").toFile();
        PhenotypeCsvExporter.export(out, index, r, tree, stats, null);
        return Files.readAllLines(out.toPath());
    }

    static String field(List<String> lines, int row, String column) {
        List<String> header = CsvTestSupport.split(lines.get(0));
        return CsvTestSupport.split(lines.get(row + 1)).get(header.indexOf(column));
    }

    @Test
    void aFailedRoundIsNamedAndItsSignIsBlankButTheRawValueStays(@TempDir Path dir) throws Exception {
        CellIndex index = DetectionIngest.read(MirageSample.cells(), IngestOptions.none()).index();
        List<String> lines = export(index, true, dir);
        assertTrue(CsvTestSupport.split(lines.get(0)).contains("QC_failed_rounds"), lines.get(0));
        assertEquals("", field(lines, 0, "QC_failed_rounds"));
        assertEquals("[CD3, CD8]", field(lines, 1, "QC_failed_rounds"));
        assertEquals("", field(lines, 1, "CD3_sign"), "unmeasured is not negative");
        assertEquals("12.0000", field(lines, 1, "CD3_raw"), "the measurement itself is kept");
        assertEquals("True", field(lines, 1, "Unmeasured"));
    }

    @Test
    void anExportWithoutRoundQcHasNoColumn(@TempDir Path dir) throws Exception {
        CellIndex index = Cells.of(2).marker("CD3", 50, 150).build();
        List<String> lines = export(index, true, dir);
        assertFalse(lines.get(0).contains("QC_failed_rounds"), lines.get(0));
    }
}
