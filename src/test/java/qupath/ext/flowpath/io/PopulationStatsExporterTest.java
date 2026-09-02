package qupath.ext.flowpath.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.flowpath.analysis.session.AnalysisSession;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.PopulationStats;
import qupath.ext.flowpath.testing.AnalysisFixtures;

import java.io.File;
import java.io.StringWriter;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PopulationStatsExporterTest {

    @TempDir
    java.nio.file.Path tempDir;

    @Test
    void headerNamesEveryColumnIncludingScopeRegionAndRootIndex() throws Exception {
        StringWriter sw = new StringWriter();
        PopulationStatsExporter.writeHeader(sw, false);
        String header = sw.toString();

        // Every column PopulationStats.Row carries, by name -- an omission here is a
        // column silently missing from every export, not merely from this test.
        for (String column : List.of("scope", "region", "path", "branch", "gate_channel",
                "depth", "root_index", "count", "clean_count", "parent_count",
                "clean_parent_count", "denominator_count", "percent_of_parent",
                "percent_of_total", "percent_of_denominator", "percent_of_clean_parent",
                "percent_of_clean_total", "area_mm2", "density_per_mm2")) {
            assertTrue(header.contains(column), "header missing column: " + column);
        }
        assertFalse(header.contains("image"), "withImage=false must not emit an image column");
    }

    @Test
    void headerCarriesTheCleanPercentageColumnsAfterTheDenominator() throws Exception {
        StringWriter w = new StringWriter();
        PopulationStatsExporter.writeHeader(w, false);
        String header = w.toString().trim();
        assertTrue(header.contains("percent_of_denominator,percent_of_clean_parent,percent_of_clean_total"),
                header);
        assertTrue(header.contains("area_mm2"), header);
        assertEquals(header.split(",").length,
                java.util.Arrays.stream(header.split(",")).distinct().count(),
                "no duplicated column names: " + header);
    }

    @Test
    void withImageTrueEmitsAnImageColumnFirst() throws Exception {
        StringWriter sw = new StringWriter();
        PopulationStatsExporter.writeHeader(sw, true);
        String header = sw.toString();
        assertTrue(header.startsWith("image,"), "image column must lead the header: " + header);
    }

    @Test
    void branchNameContainingACommaIsQuoted() throws Exception {
        AnalysisSession.AnalysisInput input = AnalysisFixtures.simpleInput();
        GateNode root = input.tree().getRoots().get(0);
        // Renaming after the tally was built is safe: BranchTally is keyed on branch
        // identity, not name, so the counts already recorded still belong to this branch.
        root.getBranches().get(0).setName("Pos, Bright");

        PopulationStats stats = PopulationStats.of(input.tree(), input.tally(),
                input.regionNames(), input.regionAreasMm2(), null);

        File f = tempDir.resolve("stats.csv").toFile();
        PopulationStatsExporter.export(f, stats);
        String csv = Files.readString(f.toPath());

        assertTrue(csv.contains("\"Pos, Bright\""),
                "a branch name with a comma must be quoted, not split into two columns: " + csv);
    }

    @Test
    void decimalsUseAPeriodWhateverTheDefaultLocale() throws Exception {
        File f = tempDir.resolve("stats.csv").toFile();
        PopulationStatsExporter.export(f, AnalysisFixtures.stats());
        assertTrue(Files.readString(f.toPath()).contains("50.0000"),
                "the JVM default here is en_IT, which would write 50,0000 and add a column");
    }

    @Test
    void rootIndexDistinguishesTwoRootsSharingTheSameBranchName() throws Exception {
        AnalysisSession.AnalysisInput input = AnalysisFixtures.twoRootsSameChannelInput();
        PopulationStats stats = PopulationStats.of(input.tree(), input.tally(),
                input.regionNames(), input.regionAreasMm2(), null);

        File f = tempDir.resolve("stats.csv").toFile();
        PopulationStatsExporter.export(f, stats);
        List<String> lines = Files.readAllLines(f.toPath());

        // Both roots emit a byte-identical "CD45+" path; only the row's root_index column
        // tells them apart. Assert both rootIndex values (0 and 1) appear on a "CD45+" row.
        // "WHOLE_SLIDE" scope, empty region name, region_index -1 (not a per-region scope).
        List<String> cd45PlusRows = lines.stream()
                .filter(l -> l.startsWith("WHOLE_SLIDE,,-1,CD45+,"))
                .toList();
        assertEquals(2, cd45PlusRows.size(),
                "expected one CD45+ row per root at WHOLE_SLIDE scope: " + lines);

        List<String> header = List.of(PopulationStatsExporterTest.headerColumns());
        int rootIndexCol = header.indexOf("root_index");
        assertTrue(rootIndexCol >= 0);

        java.util.Set<String> rootIndices = new java.util.HashSet<>();
        for (String row : cd45PlusRows) {
            rootIndices.add(row.split(",")[rootIndexCol]);
        }
        assertEquals(java.util.Set.of("0", "1"), rootIndices,
                "root_index must be the discriminator between the two identically-named roots");
    }

    private static String[] headerColumns() throws Exception {
        StringWriter sw = new StringWriter();
        PopulationStatsExporter.writeHeader(sw, false);
        return sw.toString().strip().split(",");
    }

    @Test
    void writeHeaderThenWriteRowsForMultipleImagesComposesIntoOneCombinedFile() throws Exception {
        // The batch-gating handoff this split exists for: one header, then each image's
        // rows appended after it, sharing this class's field order and formatting.
        File f = tempDir.resolve("combined.csv").toFile();
        try (var writer = Files.newBufferedWriter(f.toPath())) {
            PopulationStatsExporter.writeHeader(writer, true);
            PopulationStatsExporter.writeRows(writer, AnalysisFixtures.stats(), "image-A.svs");
            PopulationStatsExporter.writeRows(writer, AnalysisFixtures.stats(), "image-B.svs");
        }
        List<String> lines = Files.readAllLines(f.toPath());
        assertTrue(lines.get(0).startsWith("image,"));
        long headerLines = lines.stream().filter(l -> l.startsWith("image,scope,")).count();
        assertEquals(1, headerLines, "exactly one header line in the combined file");
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("image-A.svs,")));
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("image-B.svs,")));
    }
}
