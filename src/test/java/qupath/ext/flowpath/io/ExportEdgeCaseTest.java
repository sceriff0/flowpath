package qupath.ext.flowpath.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.flowpath.analysis.session.AnalysisSession;
import qupath.ext.flowpath.engine.GatingEngine;
import qupath.ext.flowpath.engine.GatingEngine.AssignmentResult;
import qupath.ext.flowpath.model.Branch;
import qupath.ext.flowpath.model.BranchTally;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.Compartment;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.PopulationStats;
import qupath.ext.flowpath.model.QuadrantGate;
import qupath.ext.flowpath.model.QualityFilter;
import qupath.ext.flowpath.model.RegionMask;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.testing.AnalysisFixtures;
import qupath.ext.flowpath.testing.Cells;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Edge cases of the three CSV exporters that the older CSV tests do not pin: strict RFC-4180
 * validity for awkward names, non-finite and zero-denominator rendering, locale independence
 * of the population table, zero cells, no enabled roots, two roots on one channel, and --
 * most importantly -- cell-by-cell agreement between the phenotype CSV and the engine's own
 * classification and branch counts.
 * <p>
 * Every file is parsed with {@link #parseCsv}, a strict RFC-4180 reader over the whole file
 * (not line by line), so a quoted newline is one field and a stray quote is an error rather
 * than something a lenient splitter papers over.
 */
class ExportEdgeCaseTest {

    @TempDir
    Path tempDir;

    // =====================================================================================
    // Strict RFC-4180 parser
    // =====================================================================================

    /**
     * Parse a complete CSV document. Records end at LF, CRLF, or a bare CR -- the last
     * because that is how Python's {@code csv} module and pandas' C parser treat an unquoted
     * CR, which is exactly the consumer {@code join_flowpath.py} is. Inside quotes every
     * character, line breaks included, is field content and {@code ""} is one quote. A quote
     * inside an unquoted field, or anything but a separator after a closing quote, throws.
     * A trailing line break does not create an empty final record.
     */
    static List<List<String>> parseCsv(String text) {
        List<List<String>> records = new ArrayList<>();
        List<String> record = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        int i = 0, n = text.length();
        boolean fieldStarted = false;   // anything consumed for the current record
        while (i < n) {
            char c = text.charAt(i);
            if (field.isEmpty() && c == '"') {
                // quoted field
                i++;
                boolean closed = false;
                while (i < n) {
                    char q = text.charAt(i);
                    if (q == '"') {
                        if (i + 1 < n && text.charAt(i + 1) == '"') {
                            field.append('"');
                            i += 2;
                        } else {
                            i++;
                            closed = true;
                            break;
                        }
                    } else {
                        field.append(q);
                        i++;
                    }
                }
                if (!closed) throw new IllegalArgumentException("unterminated quoted field");
                if (i < n) {
                    char after = text.charAt(i);
                    if (after != ',' && after != '\n' && after != '\r') {
                        throw new IllegalArgumentException(
                                "text after a closing quote at offset " + i + ": '" + after + "'");
                    }
                }
                fieldStarted = true;
                continue;
            }
            if (c == ',') {
                record.add(field.toString());
                field.setLength(0);
                fieldStarted = true;
                i++;
            } else if (c == '\n' || c == '\r') {
                record.add(field.toString());
                field.setLength(0);
                records.add(record);
                record = new ArrayList<>();
                fieldStarted = false;
                i += (c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') ? 2 : 1;
            } else if (c == '"') {
                throw new IllegalArgumentException("bare quote inside an unquoted field at offset " + i);
            } else {
                field.append(c);
                fieldStarted = true;
                i++;
            }
        }
        if (fieldStarted || !field.isEmpty()) {
            record.add(field.toString());
            records.add(record);
        }
        return records;
    }

    /** A parsed file: header plus data rows, with every row asserted to be header-width. */
    record Table(List<String> header, List<List<String>> rows) {
        int col(String name) {
            int idx = header.indexOf(name);
            assertTrue(idx >= 0, "column '" + name + "' not in header: " + header);
            return idx;
        }

        String val(int row, String name) {
            return rows.get(row).get(col(name));
        }
    }

    private static Table table(String text) {
        List<List<String>> records = parseCsv(text);
        assertFalse(records.isEmpty(), "no header at all");
        List<String> header = records.get(0);
        List<List<String>> rows = records.subList(1, records.size());
        for (int r = 0; r < rows.size(); r++) {
            assertEquals(header.size(), rows.get(r).size(),
                    "row " + r + " has a different width than the header: " + rows.get(r));
        }
        return new Table(header, rows);
    }

    private Table exportPhenotypes(String name, CellIndex index, AssignmentResult result,
                                   GateTree tree, MarkerStats stats, RegionMask regions)
            throws IOException {
        File f = tempDir.resolve(name).toFile();
        PhenotypeCsvExporter.export(f, index, result, tree, stats, regions);
        return table(Files.readString(f.toPath(), StandardCharsets.UTF_8));
    }

    private static String populationCsv(PopulationStats stats) throws IOException {
        StringWriter w = new StringWriter();
        PopulationStatsExporter.writeHeader(w, false);
        PopulationStatsExporter.writeRows(w, stats, null);
        return w.toString();
    }

    private static GateNode rawGate(String channel, double threshold) {
        GateNode g = new GateNode(channel, threshold);
        g.setStatistic(Statistic.MEAN);
        g.setThresholdIsZScore(false);
        return g;
    }

    private static GateTree treeOf(GateNode... roots) {
        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        for (GateNode r : roots) tree.addRoot(r);
        return tree;
    }

    private static PathObject rectAnnotation(double x, double y, double w, double h) {
        return PathObjects.createAnnotationObject(
                ROIs.createRectangleROI(x, y, w, h, ImagePlane.getDefaultPlane()));
    }

    // =====================================================================================
    // 1. Quoting and escaping
    // =====================================================================================

    /**
     * Pins: branch names containing a double quote, an LF, leading/trailing spaces and
     * non-ASCII text (µ, ², accented, CJK, superscript) survive the phenotype CSV byte-exact
     * under a strict parser, including when two roots compose them with ": ". The existing
     * escape test only covers a comma.
     */
    @Test
    void awkwardBranchNamesRoundTripThroughTheCompositePhenotype() throws IOException {
        CellIndex index = Cells.columns(List.of("CD45", "CD3"),
                new double[][] {{1, 9, 1, 9}, {1, 1, 9, 9}}).build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(4));

        GateNode a = rawGate("CD45", 5);
        a.setPositiveName("He said \"hi\"");
        a.setNegativeName("line1\nline2");
        GateNode b = rawGate("CD3", 5);
        b.setPositiveName("  padded  ");
        b.setNegativeName("µm² é 细胞 CD8⁺");
        GateTree tree = treeOf(a, b);

        AssignmentResult result = GatingEngine.assignAll(tree, index, stats);
        Table t = exportPhenotypes("awkward.csv", index, result, tree, stats, null);

        assertEquals(4, t.rows().size(), "one row per cell, a quoted LF does not split a row");
        String[] expected = {
                "line1\nline2: µm² é 细胞 CD8⁺",
                "He said \"hi\": µm² é 细胞 CD8⁺",
                "line1\nline2:   padded  ",
                "He said \"hi\":   padded  "};
        for (int i = 0; i < 4; i++) {
            assertEquals(expected[i], t.val(i, "phenotype"), "cell " + i);
            assertEquals(result.getPhenotypes()[i], t.val(i, "phenotype"), "cell " + i);
            assertEquals(String.valueOf(i), t.val(i, "cell_id"));
        }
    }

    /**
     * Pins: marker (channel) names containing a double quote, an LF and non-ASCII produce
     * header fields that are whole, valid quoted fields with the {@code _raw/_zscore/_sign}
     * suffix inside the quotes (only the comma case was pinned before), and the file is UTF-8.
     */
    @Test
    void awkwardMarkerNamesProduceValidQuotedHeaderFields() throws IOException {
        String quoted = "CD3 \"bright\"";
        String newline = "Ki67\nclone";
        String unicode = "PD-L1 µ² é 细胞";
        CellIndex index = Cells.of(3)
                .marker(quoted, 1, 5, 9)
                .marker(newline, 2, 4, 6)
                .marker(unicode, 3, 3, 8)
                .area(100.0)
                .build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(3));
        GateTree tree = treeOf(rawGate(quoted, 4), rawGate(unicode, 5));

        AssignmentResult result = GatingEngine.assignAll(tree, index, stats);
        File f = tempDir.resolve("markers.csv").toFile();
        PhenotypeCsvExporter.export(f, index, result, tree, stats);
        String text = Files.readString(f.toPath(), StandardCharsets.UTF_8);
        Table t = table(text);

        for (String m : List.of(quoted, newline, unicode)) {
            for (String suffix : List.of("_raw", "_zscore", "_sign")) {
                assertTrue(t.header().contains(m + suffix),
                        "header must carry '" + m + suffix + "': " + t.header());
            }
        }
        assertEquals(3, t.rows().size());
        assertEquals("5.0000", t.val(1, quoted + "_raw"));
        assertEquals("+", t.val(1, quoted + "_sign"));
        assertEquals("8.0000", t.val(2, unicode + "_raw"));
        assertEquals("", t.val(0, newline + "_sign"), "ungated marker has no sign");
        assertTrue(text.contains("细胞"), "written as UTF-8, not replaced");
    }

    /**
     * Pins RFC 4180 §2.6: a field containing a line break must be quoted, and a bare CR is a
     * line break to Python's csv module and pandas (the {@code join_flowpath.py} consumer).
     * {@code CellTable.escape} only checks for {@code \n}, so a name containing {@code \r}
     * is written unquoted and splits the record in two for those readers.
     */
    @Test
    void carriageReturnInANameIsQuoted() throws IOException {
        CellIndex index = Cells.columns(List.of("CD45"), new double[][] {{1, 9}}).build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(2));
        GateNode g = rawGate("CD45", 5);
        g.setPositiveName("pos\rbright");
        GateTree tree = treeOf(g);

        AssignmentResult result = GatingEngine.assignAll(tree, index, stats);
        File f = tempDir.resolve("cr.csv").toFile();
        PhenotypeCsvExporter.export(f, index, result, tree, stats);
        List<List<String>> records = parseCsv(Files.readString(f.toPath(), StandardCharsets.UTF_8));

        assertEquals(3, records.size(),
                "header + 2 cells; an unquoted CR split a record: " + records);
        assertEquals("\"pos\rbright\"", CellTable.escape("pos\rbright"));
    }

    /**
     * Documents current behaviour, deliberately not a fix: values that a spreadsheet would
     * read as a formula ({@code =}, {@code +}, {@code -}, {@code @}) are written verbatim,
     * unquoted and without an apostrophe guard. Prefixing would corrupt every ordinary
     * {@code "CD45-"} style name for the pandas consumer, so verbatim is the defensible
     * choice for a data file; this test exists so a change to it is a conscious one.
     */
    @Test
    void formulaLookingNamesAreWrittenVerbatim() throws IOException {
        CellIndex index = Cells.columns(List.of("CD45", "CD3"),
                new double[][] {{1, 9}, {9, 1}}).build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(2));
        GateNode a = rawGate("CD45", 5);
        a.setPositiveName("=SUM(A1)");
        a.setNegativeName("@cmd");
        GateNode b = rawGate("CD3", 5);
        b.setPositiveName("+1");
        b.setNegativeName("-2");
        GateTree tree = treeOf(a, b);

        AssignmentResult result = GatingEngine.assignAll(tree, index, stats);
        File f = tempDir.resolve("formula.csv").toFile();
        PhenotypeCsvExporter.export(f, index, result, tree, stats);
        String text = Files.readString(f.toPath(), StandardCharsets.UTF_8);
        Table t = table(text);

        assertEquals("@cmd: +1", t.val(0, "phenotype"));
        assertEquals("=SUM(A1): -2", t.val(1, "phenotype"));
        assertTrue(text.contains(",=SUM(A1): -2,"), "unquoted, no guard prefix: " + text);
        assertFalse(text.contains("'="), "no apostrophe guard is added");
    }

    /**
     * Pins: in the population table, region names and branch names containing quotes, LF,
     * commas and non-ASCII survive a strict parse, every row is header-width, and two regions
     * are still told apart by {@code region_index}.
     */
    @Test
    void populationCsvRoundTripsAwkwardRegionAndBranchNames() throws IOException {
        CellIndex index = Cells.columns(List.of("CD45"),
                new double[][] {{1, 2, 3, 4, 5, 6, 7, 8, 9, 10}}).build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(10));
        GateNode g = rawGate("CD45", 5.5);
        g.setPositiveName("pos, \"bright\"");
        g.setNegativeName("neg\né 细胞");
        GateTree tree = treeOf(g);
        int[] regionOf = {0, 0, 0, 1, 1, 1, 1, 1, 0, 0};
        List<String> regionNames = List.of("Tumour \"A\"", "Margin,\nzone µ²");
        BranchTally tally = GatingEngine.assignAll(tree, index, stats, null, regionOf, 2).getTally();

        PopulationStats ps = PopulationStats.of(tree, tally, regionNames, null, null);
        Table t = table(populationCsv(ps));

        assertEquals(ps.rows().size(), t.rows().size());
        for (int r = 0; r < t.rows().size(); r++) {
            PopulationStats.Row row = ps.rows().get(r);
            assertEquals(row.regionName() == null ? "" : row.regionName(), t.val(r, "region"));
            assertEquals(String.valueOf(row.regionIndex()), t.val(r, "region_index"));
            assertEquals(row.path(), t.val(r, "path"));
            assertEquals(row.branchName(), t.val(r, "branch"));
            assertEquals(String.valueOf(row.count()), t.val(r, "count"));
        }
        assertTrue(t.rows().stream().anyMatch(r -> r.contains("Margin,\nzone µ²")));
    }

    // =====================================================================================
    // 2. Locale and non-finite values
    // =====================================================================================

    private static final Pattern PLAIN_DECIMAL = Pattern.compile("-?\\d+(\\.\\d+)?");

    /**
     * Pins: the population table formats every number with a period under a comma-decimal
     * default locale. The existing test relies on the JVM happening to be en_IT rather than
     * setting a locale, so it proves nothing on a CI machine whose default is en_US.
     */
    @Test
    void populationCsvDecimalsUnderAGermanDefaultLocale() throws IOException {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            AnalysisSession.AnalysisInput in = AnalysisFixtures.partiallyKnownRegionAreasInput();
            Branch denominator = in.tree().getRoots().get(0).getBranches().get(0);
            PopulationStats ps = PopulationStats.of(in.tree(), in.tally(), in.regionNames(),
                    in.regionAreasMm2(), denominator);
            Table t = table(populationCsv(ps));

            assertEquals(20, t.header().size());
            int firstNumeric = t.col("depth");
            for (List<String> row : t.rows()) {
                for (int c = firstNumeric; c < row.size(); c++) {
                    String v = row.get(c);
                    assertTrue(v.isEmpty() || PLAIN_DECIMAL.matcher(v).matches(),
                            "column " + t.header().get(c) + " not a plain US decimal: '" + v + "'");
                }
            }
            // a real density exists at ANNOTATION_K for region R0 (area 2.0)
            assertTrue(t.rows().stream().anyMatch(r -> r.get(t.col("area_mm2")).equals("2.0000")));
            assertEquals("12.5000", CellTable.fmtLabel(12.5), "a non-integral label too");
        } finally {
            Locale.setDefault(previous);
        }
    }

    /**
     * Documents {@code CellTable.fmt}/{@code fmtLabel} on non-finite input: NaN is an empty
     * field (the documented rule); infinities are written as Java's {@code Infinity} /
     * {@code -Infinity}, which pandas parses as float inf. Nothing produces "∞".
     */
    @Test
    void nonFiniteFormatting() {
        assertEquals("", CellTable.fmt(Double.NaN));
        assertEquals("Infinity", CellTable.fmt(Double.POSITIVE_INFINITY));
        assertEquals("-Infinity", CellTable.fmt(Double.NEGATIVE_INFINITY));
        assertEquals("", CellTable.fmtLabel(Double.NaN));
        assertEquals("Infinity", CellTable.fmtLabel(Double.POSITIVE_INFINITY));
        assertEquals("7", CellTable.fmtLabel(7.0));
        assertEquals("-0.0000", CellTable.fmt(-0.0), "negative zero keeps its sign");
    }

    /**
     * Pins the zero-denominator rendering in the population CSV: no denominator chosen and a
     * chosen-but-empty denominator both give a blank {@code percent_of_denominator}; an empty
     * parent/region gives {@code 0.0000}; a zero area gives a blank density (never
     * Infinity); and no field anywhere reads NaN/Infinity/∞.
     */
    @Test
    void populationCsvZeroDenominatorsNeverLeakNonFiniteLiterals() throws IOException {
        CellIndex index = Cells.columns(List.of("CD45"),
                new double[][] {{1, 2, 3, 4, 5, 6, 7, 8, 9, 10}}).build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(10));
        GateNode g = rawGate("CD45", 100.0);   // positive branch always empty
        GateTree tree = treeOf(g);
        int[] regionOf = new int[10];          // all cells in region 0; region 1 is empty
        BranchTally tally = GatingEngine.assignAll(tree, index, stats, null, regionOf, 2).getTally();
        Branch emptyPos = g.getBranches().get(0);

        for (Branch denominator : new Branch[] {null, emptyPos}) {
            PopulationStats ps = PopulationStats.of(tree, tally, List.of("Zero area", "Empty"),
                    new double[] {0.0, 2.0}, denominator);
            String csv = populationCsv(ps);
            Table t = table(csv);
            assertFalse(csv.contains("NaN") || csv.contains("Infinity") || csv.contains("∞"), csv);

            for (int r = 0; r < t.rows().size(); r++) {
                assertEquals("", t.val(r, "percent_of_denominator"),
                        "denominator " + denominator + ", row " + t.rows().get(r));
                assertEquals("0", t.val(r, "denominator_count"));
                String scope = t.val(r, "scope");
                String regionIdx = t.val(r, "region_index");
                if (scope.equals("ANNOTATION_K") && regionIdx.equals("0")) {
                    assertEquals("0.0000", t.val(r, "area_mm2"));
                    assertEquals("", t.val(r, "density_per_mm2"), "zero area -> blank, not Infinity");
                }
                if (scope.equals("ANNOTATION_K") && regionIdx.equals("1")) {
                    assertEquals("0", t.val(r, "count"));
                    assertEquals("0.0000", t.val(r, "percent_of_parent"));
                    assertEquals("0.0000", t.val(r, "percent_of_total"));
                    assertEquals("0.0000", t.val(r, "percent_of_clean_parent"));
                    assertEquals("0.0000", t.val(r, "percent_of_clean_total"));
                    assertEquals("0.0000", t.val(r, "density_per_mm2"));
                }
            }
        }
    }

    // =====================================================================================
    // 3. Degenerate populations and trees
    // =====================================================================================

    /**
     * Pins: zero cells export a header-only phenotype CSV (no blank data line) and a
     * population table whose counts are 0 and percentages 0.0000, never NaN.
     */
    @Test
    void zeroCellsExportHeaderOnlyAndZeroPercentages() throws IOException {
        CellIndex index = Cells.of(0).marker("CD45", i -> i).area(100.0).build();
        MarkerStats stats = MarkerStats.compute(index, new boolean[0]);
        GateNode g = rawGate("CD45", 5);
        GateTree tree = treeOf(g);
        AssignmentResult result = GatingEngine.assignAll(tree, index, stats, null, null, 0);

        Table t = exportPhenotypes("zero.csv", index, result, tree, stats, null);
        assertEquals(0, t.rows().size());
        assertTrue(t.header().contains("CD45_raw"), t.header().toString());

        String csv = populationCsv(PopulationStats.of(tree, result.getTally(), List.of(), null, null));
        assertFalse(csv.contains("NaN") || csv.contains("Infinity"), csv);
        Table p = table(csv);
        assertEquals(2, p.rows().size(), "pos and neg rows, at WHOLE_SLIDE only");
        for (int r = 0; r < 2; r++) {
            assertEquals("0", p.val(r, "count"));
            assertEquals("0.0000", p.val(r, "percent_of_parent"));
            assertEquals("0.0000", p.val(r, "percent_of_clean_total"));
            assertEquals("", p.val(r, "area_mm2"));
        }
    }

    /**
     * Pins: a tree whose roots are all disabled exports every cell Unclassified, emits no
     * columns for the disabled gate's non-default (nuclear median) axis, leaves every sign
     * blank, and yields a header-only population table.
     */
    @Test
    void noEnabledRootsExportsUnclassifiedWithoutGateColumnsOrRows() throws IOException {
        CellIndex index = Cells.of(4).mirageMedianMarker("CD3", i -> i + 1.0).area(100.0).build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(4));
        GateNode nuclear = rawGate("CD3", 2);
        nuclear.setCompartment(Compartment.NUCLEAR);
        nuclear.setStatistic(Statistic.MEDIAN);
        nuclear.setEnabled(false);
        GateNode plain = rawGate("CD3", 2);
        plain.setEnabled(false);
        GateTree tree = treeOf(nuclear, plain);

        AssignmentResult result = GatingEngine.assignAll(tree, index, stats);
        Table t = exportPhenotypes("disabled.csv", index, result, tree, stats, null);

        assertEquals(4, t.rows().size());
        assertFalse(t.header().stream().anyMatch(h -> h.startsWith("CD3_Nucleus")), t.header().toString());
        for (int i = 0; i < 4; i++) {
            assertEquals("Unclassified", t.val(i, "phenotype"));
            assertEquals("", t.val(i, "CD3_sign"));
            assertEquals("False", t.val(i, "Unmeasured"));
        }

        Table p = table(populationCsv(PopulationStats.of(tree, result.getTally(), List.of(), null, null)));
        assertEquals(0, p.rows().size(), "no enabled gate, no population rows");
    }

    // =====================================================================================
    // 4. Regions
    // =====================================================================================

    /**
     * Pins the region column for exactly one region whose name comes from an awkward
     * PathClass (no own name): quoted correctly, blank for a cell outside it. Also an empty
     * annotation list writes no region column at all, and an Ignore*-only set names the
     * implicit "Whole image" region, blank for the subtracted cells.
     */
    @Test
    void regionColumnForOneRegionEmptyMaskAndIgnoreOnly() throws IOException {
        CellIndex index = Cells.of(6).marker("CD3", i -> i).at(i -> i * 10.0, i -> 0.0).area(100.0).build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(6));
        GateTree tree = treeOf(rawGate("CD3", 2.5));

        // one region, named by its classification
        PathObject ann = rectAnnotation(-5, -5, 30, 10);   // cells 0,1,2
        PathClass awkward = PathClass.fromString("Tumour, \"core\" µ²");
        ann.setPathClass(awkward);
        RegionMask one = RegionMask.compute(index, List.of(ann));
        AssignmentResult r1 = GatingEngine.assignAll(tree, index, stats, one.included());
        Table t1 = exportPhenotypes("one.csv", index, r1, tree, stats, one);
        for (int i = 0; i < 6; i++) {
            assertEquals(i <= 2 ? awkward.toString() : "", t1.val(i, "region"), "cell " + i);
            assertEquals(i <= 2 ? "False" : "True", t1.val(i, "Out_of_annotation"), "cell " + i);
        }

        // no annotations -> empty mask -> no column
        RegionMask none = RegionMask.compute(index, List.of());
        AssignmentResult r0 = GatingEngine.assignAll(tree, index, stats);
        Table t0 = exportPhenotypes("none.csv", index, r0, tree, stats, none);
        assertFalse(t0.header().contains("region"), t0.header().toString());

        // Ignore* only -> "Whole image" minus the ignored cells
        PathObject ignore = rectAnnotation(35, -5, 20, 10);   // cells 4,5
        ignore.setPathClass(PathClass.fromString("Ignore*"));
        RegionMask ign = RegionMask.compute(index, List.of(ignore));
        AssignmentResult r2 = GatingEngine.assignAll(tree, index, stats, ign.included());
        Table t2 = exportPhenotypes("ignore.csv", index, r2, tree, stats, ign);
        for (int i = 0; i < 6; i++) {
            assertEquals(i >= 4 ? "" : RegionMask.WHOLE_IMAGE, t2.val(i, "region"), "cell " + i);
        }
    }

    // =====================================================================================
    // 5. Multi-root
    // =====================================================================================

    /**
     * Pins, with two un-renamed roots on the SAME channel: the phenotype CSV composes each
     * root's contribution in root order, and counting CSV rows by contribution position
     * reproduces each root's own branch counts (10 vs 5 positives) -- so a consumer can
     * recover per-root populations from the export. The population CSV, fully parsed,
     * has a unique (scope, region_index, root_index, path) key per row and root_index 0/1
     * carries each root's own counts.
     */
    @Test
    void twoRootsOnTheSameChannelStayDistinctInBothExports() throws IOException {
        AnalysisSession.AnalysisInput in = AnalysisFixtures.twoRootsSameChannelInput();
        AssignmentResult result = GatingEngine.assignAll(in.tree(), in.index(), in.stats());
        Table t = exportPhenotypes("tworoots.csv", in.index(), result, in.tree(), in.stats(), null);
        List<GateNode> roots = in.tree().getRoots();

        assertEquals(20, t.rows().size());
        for (int i = 0; i < 20; i++) {
            String expected = (i >= 10 ? "CD45+" : "CD45-") + ": " + (i >= 15 ? "CD45+" : "CD45-");
            assertEquals(expected, t.val(i, "phenotype"), "cell " + i);
        }
        for (int k = 0; k < 2; k++) {
            for (Branch b : roots.get(k).getBranches()) {
                int kk = k;
                long csvCount = t.rows().stream()
                        .map(r -> r.get(t.col("phenotype")).split(": ")[kk])
                        .filter(b.getName()::equals).count();
                assertEquals(result.getTally().clean(b), csvCount, "root " + k + " " + b.getName());
                assertEquals(b.getCount(), csvCount, "root " + k + " " + b.getName());
            }
        }

        PopulationStats ps = PopulationStats.of(in.tree(), result.getTally(), List.of(), null, null);
        Table p = table(populationCsv(ps));
        Set<String> keys = new HashSet<>();
        for (int r = 0; r < p.rows().size(); r++) {
            String key = p.val(r, "scope") + "|" + p.val(r, "region_index") + "|"
                    + p.val(r, "root_index") + "|" + p.val(r, "path");
            assertTrue(keys.add(key), "duplicate row key " + key);
            if (p.val(r, "path").equals("CD45+")) {
                assertEquals(p.val(r, "root_index").equals("0") ? "10" : "5", p.val(r, "count"));
            }
        }
        assertEquals(4, keys.size());
    }

    /**
     * Pins: writeHeader once then writeRows many times yields exactly one header record and
     * uniform widths, both with and without the image column, and an image name containing a
     * comma, quote and LF is one field.
     */
    @Test
    void combinedPopulationExportHasOneHeaderAndUniformWidth() throws IOException {
        PopulationStats ps = AnalysisFixtures.stats();
        for (boolean withImage : new boolean[] {false, true}) {
            StringWriter w = new StringWriter();
            PopulationStatsExporter.writeHeader(w, withImage);
            String[] images = {"a, \"b\"\nc.svs", "plain.svs", "é 细胞.ome.tiff"};
            for (String img : images) PopulationStatsExporter.writeRows(w, ps, withImage ? img : null);
            Table t = table(w.toString());

            assertEquals(withImage ? 21 : 20, t.header().size());
            assertEquals(3 * ps.rows().size(), t.rows().size());
            assertEquals(1, parseCsv(w.toString()).stream()
                    .filter(r -> r.contains("scope") && r.contains("root_index")).count());
            if (withImage) {
                for (int i = 0; i < images.length; i++) {
                    assertEquals(images[i], t.val(i * ps.rows().size(), "image"));
                }
            }
        }
    }

    // =====================================================================================
    // 6. The export agrees with the engine, cell by cell
    // =====================================================================================

    /**
     * The central property: over a mixed tree (threshold root with outlier clipping, a
     * quadrant gate under its positive branch, a threshold gate on a marker absent from some
     * cells under its negative branch) plus a quality filter and an annotation filter, every
     * row of the phenotype CSV:
     * <ul>
     *   <li>is the engine's phenotype/Outlier/Out_of_annotation/Unmeasured for that cell;</li>
     *   <li>matches a phenotype re-derived independently from the raw CSV values through the
     *       gates' own public geometry (a clipped or filtered cell still carries its
     *       would-have-been phenotype; an unmeasured cell stops at its parent branch);</li>
     *   <li>and, counted back up, reproduces every branch's {@code Branch.getCount()} and
     *       {@code BranchTally} total/clean -- the numbers the tree view and Analysis window
     *       show.</li>
     * </ul>
     */
    @Test
    void everyPhenotypeRowAgreesWithTheEngineAndItsBranchCounts() throws IOException {
        int n = 300;
        Random rnd = new Random(20260916L);
        double[] cd45 = new double[n], cd3 = new double[n], cd8 = new double[n], ki67 = new double[n];
        double[] area = new double[n];
        for (int i = 0; i < n; i++) {
            cd45[i] = rnd.nextDouble() * 100;
            cd3[i] = rnd.nextDouble() * 10;
            cd8[i] = rnd.nextDouble() * 10;
            ki67[i] = rnd.nextDouble() * 4;
            area[i] = i % 17 == 0 ? 5 : 100;          // quality-filter failures
        }
        cd45[7] = -1e4;                                // clipped low
        cd45[8] = 1e4;                                 // clipped high
        cd45[40] = 50.0;                               // exactly on the threshold
        cd3[41] = 5.0; cd8[41] = 5.0;                  // exactly on both quadrant cuts

        CellIndex index = Cells.of(n)
                .at(i -> i, i -> 0.0)
                .marker("CD45", i -> cd45[i])
                .marker("CD3", i -> cd3[i])
                .marker("CD8", i -> cd8[i])
                .marker("Ki67", i -> ki67[i]).absentOn(i -> i % 11 == 3)
                .area(i -> area[i])
                .build();

        QualityFilter qf = new QualityFilter();
        qf.setMinArea(50);
        MarkerStats stats = MarkerStats.compute(index, GatingEngine.computeQualityMask(index, qf));

        GateNode root = rawGate("CD45", 50);
        root.setExcludeOutliers(true);
        root.setClipPercentileLow(0.5);
        root.setClipPercentileHigh(99.5);
        QuadrantGate quad = new QuadrantGate("CD3", "CD8", 5.0, 5.0);
        quad.setThresholdIsZScore(false);
        quad.setStatisticX(Statistic.MEAN);   // a fresh quadrant defaults to MEDIAN, which
        quad.setStatisticY(Statistic.MEAN);   // this legacy-shaped fixture does not carry
        GateNode ki = rawGate("Ki67", 2.0);
        root.getPositiveChildren().add(quad);
        root.getNegativeChildren().add(ki);
        GateTree tree = new GateTree();
        tree.setQualityFilter(qf);
        tree.addRoot(root);

        PathObject core = rectAnnotation(-0.5, -1, 150, 2);     // cells 0..149
        core.setName("Core");
        PathObject margin = rectAnnotation(199.5, -1, 60, 2);   // cells 200..259
        margin.setName("Margin, é");
        RegionMask regions = RegionMask.compute(index, List.of(core, margin));

        AssignmentResult result = GatingEngine.assignAll(tree, index, stats, regions.included(),
                regions.regionOf(), regions.regionNames().size());
        Table t = exportPhenotypes("agreement.csv", index, result, tree, stats, regions);

        assertEquals(n, t.rows().size(), "row count == cell count");
        List<Branch> rootB = root.getBranches();
        List<Branch> quadB = quad.getBranches();
        List<Branch> kiB = ki.getBranches();
        int anyUnmeasured = 0, anyClipped = 0;
        for (int i = 0; i < n; i++) {
            String id = String.valueOf(i);
            assertEquals(id, t.val(i, "cell_id"));
            assertEquals(result.getPhenotypes()[i], t.val(i, "phenotype"), "cell " + i);
            assertEquals(tf(result.getOutlier()[i]), t.val(i, "Outlier"), "cell " + i);
            assertEquals(tf(result.getOutOfAnnotation()[i]), t.val(i, "Out_of_annotation"), "cell " + i);
            assertEquals(tf(result.getUnmeasured()[i]), t.val(i, "Unmeasured"), "cell " + i);
            String region = regions.regionNameOf(i);
            assertEquals(region == null ? "" : region, t.val(i, "region"), "cell " + i);

            // Independent re-derivation from the exported raw values and the gates' geometry.
            double vCd45 = Double.parseDouble(t.val(i, "CD45_raw"));
            String kiRaw = t.val(i, "Ki67_raw");
            String expected;
            boolean expectUnmeasured = false;
            if (rootB.get(root.branchFor(vCd45, 0)) == rootB.get(0)) {
                double x = Double.parseDouble(t.val(i, "CD3_raw"));
                double y = Double.parseDouble(t.val(i, "CD8_raw"));
                expected = quadB.get(quad.branchFor(x, y)).getName();
            } else if (kiRaw.isEmpty()) {
                expected = rootB.get(1).getName();
                expectUnmeasured = true;
            } else {
                expected = kiB.get(ki.branchFor(Double.parseDouble(kiRaw), 0)).getName();
            }
            assertEquals(expected, t.val(i, "phenotype"), "independent re-derivation, cell " + i);
            assertEquals(tf(expectUnmeasured), t.val(i, "Unmeasured"), "cell " + i);
            assertEquals(vCd45 >= 50 ? "+" : "-", t.val(i, "CD45_sign"), "cell " + i);
            if (area[i] < 50) assertEquals("True", t.val(i, "Outlier"), "QF failure, cell " + i);
            if (expectUnmeasured) anyUnmeasured++;
            if (area[i] >= 50 && t.val(i, "Outlier").equals("True")) anyClipped++;
        }
        assertTrue(names(quadB).contains(t.val(40, "phenotype")),
                "on-threshold CD45 is positive, so the cell reaches the quadrant: " + t.val(40, "phenotype"));
        assertEquals("+", t.val(40, "CD45_sign"));
        if (cd45[41] >= 50) {
            assertEquals("CD3+/CD8+", t.val(41, "phenotype"), "on both quadrant cuts is ++");
        }
        assertTrue(anyUnmeasured > 0, "fixture must exercise Unmeasured");
        assertTrue(anyClipped > 0, "fixture must exercise gate clipping");

        // Count the CSV back up and compare with what the tree view / Analysis window show.
        BranchTally tally = result.getTally();
        for (Branch leaf : concat(quadB, kiB)) {
            assertEquals(tally.total(leaf), countRows(t, Set.of(leaf.getName()), false), leaf.getName());
            assertEquals(tally.clean(leaf), countRows(t, Set.of(leaf.getName()), true), leaf.getName());
            assertEquals(leaf.getCount(), countRows(t, Set.of(leaf.getName()), true), leaf.getName());
        }
        Set<String> underPos = names(quadB);
        assertEquals(tally.total(rootB.get(0)), countRows(t, underPos, false), "CD45+ total");
        assertEquals(tally.clean(rootB.get(0)), countRows(t, underPos, true), "CD45+ clean");
        Set<String> underNeg = names(kiB);
        underNeg.add(rootB.get(1).getName());
        assertEquals(tally.total(rootB.get(1)), countRows(t, underNeg, false), "CD45- total");
        assertEquals(tally.clean(rootB.get(1)), countRows(t, underNeg, true), "CD45- clean");
        // Unmeasured cells reached CD45- but no Ki67 branch.
        long unmeasuredRows = t.rows().stream().filter(r -> r.get(t.col("Unmeasured")).equals("True")).count();
        assertEquals(tally.total(rootB.get(1)) - tally.total(kiB.get(0)) - tally.total(kiB.get(1)),
                unmeasuredRows);
        // Per region, clean counts agree too.
        for (int reg = 0; reg < regions.regionNames().size(); reg++) {
            String rn = regions.regionNames().get(reg);
            for (Branch leaf : concat(quadB, kiB)) {
                long csv = t.rows().stream()
                        .filter(r -> r.get(t.col("region")).equals(rn)
                                && r.get(t.col("phenotype")).equals(leaf.getName())
                                && r.get(t.col("Outlier")).equals("False")
                                && r.get(t.col("Out_of_annotation")).equals("False"))
                        .count();
                assertEquals(tally.cleanInRegion(leaf, reg), csv, rn + " / " + leaf.getName());
            }
        }
    }

    private static String tf(boolean b) {
        return b ? "True" : "False";
    }

    private static List<Branch> concat(List<Branch> a, List<Branch> b) {
        List<Branch> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static Set<String> names(List<Branch> branches) {
        Set<String> out = new HashSet<>();
        for (Branch b : branches) out.add(b.getName());
        return out;
    }

    /** Rows whose phenotype is in {@code phenotypes}; {@code cleanOnly} drops Outlier / Out_of_annotation. */
    private static long countRows(Table t, Set<String> phenotypes, boolean cleanOnly) {
        int p = t.col("phenotype"), o = t.col("Outlier"), a = t.col("Out_of_annotation");
        return t.rows().stream()
                .filter(r -> phenotypes.contains(r.get(p)))
                .filter(r -> !cleanOnly || (r.get(o).equals("False") && r.get(a).equals("False")))
                .count();
    }
}
