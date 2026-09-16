package qupath.ext.flowpath.io;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.flowpath.model.Branch;
import qupath.ext.flowpath.model.Compartment;
import qupath.ext.flowpath.model.EllipseGate;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.PolygonGate;
import qupath.ext.flowpath.model.QualityFilter;
import qupath.ext.flowpath.model.QuadrantGate;
import qupath.ext.flowpath.model.RectangleGate;
import qupath.ext.flowpath.model.Region2DGate;
import qupath.ext.flowpath.model.Statistic;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Edge cases of {@link FlowPathSerializer} not pinned by {@code FlowPathSerializerTest},
 * {@code CompartmentSerializationTest}, {@code GateTypeRegistryTest} or the 2D round-trip
 * tests in {@code CsvCorrectnessTest}: whole-tree field-by-field equality, idempotent
 * rewrites, the quality filter's open bounds, hostile strings, non-finite numbers and
 * malformed input staying inside {@code load}'s {@code IOException} contract.
 */
class SerializerEdgeCaseTest {

    @TempDir
    Path tempDir;

    private int fileCounter;

    // =====================================================================
    //  Whole-tree fidelity
    // =====================================================================

    /**
     * Every field of every gate type, with children under every branch of every type
     * (all four quadrant branches, inside AND outside of each region gate), unknown
     * compartment/statistic tokens on the Y axes, non-default clip/outlier/enabled/z-score
     * flags, custom names and colours, unsorted polygon vertices, and a fully constrained
     * quality filter. Pins a deep structural equality AND that write -> read -> write is
     * byte-identical (the timestamp line aside).
     */
    @Test
    void everyFieldOfEveryGateTypeSurvivesAndRewritesByteIdentically() throws IOException {
        GateTree tree = richTree();

        String first = saveText(tree);
        GateTree loaded = loadText(first);
        assertSameTree(tree, loaded);
        assertEquals(tree.getQualityFilter().ranges(), loaded.getQualityFilter().ranges(),
                "a fully constrained quality filter, including an unknown slug");

        String second = saveText(loaded);
        assertEquals(first, second, "write -> read -> write must be byte-identical");
    }

    /** A 60-deep chain alternating all five gate types keeps depth, order and types. */
    @Test
    void aDeepChainAcrossAllGateTypesSurvives() throws IOException {
        GateTree tree = new GateTree();
        GateNode top = new GateNode("L0", 0.0);
        tree.addRoot(top);
        GateNode parent = top;
        for (int depth = 1; depth < 60; depth++) {
            GateNode child = switch (depth % 5) {
                case 0 -> new GateNode("L" + depth, depth);
                case 1 -> new QuadrantGate("X" + depth, "Y" + depth, depth, -depth);
                case 2 -> new RectangleGate("X" + depth, "Y" + depth, 0, depth, 0, depth);
                case 3 -> new EllipseGate("X" + depth, "Y" + depth, 1, 1, depth, depth);
                default -> {
                    PolygonGate p = new PolygonGate("X" + depth, "Y" + depth);
                    p.setVertices(new ArrayList<>(List.of(new double[]{0, 0},
                            new double[]{depth, 0}, new double[]{0, depth})));
                    yield p;
                }
            };
            List<Branch> branches = parent.getBranches();
            // Hang each level off the LAST branch, so non-first branches are exercised.
            branches.get(branches.size() - 1).getChildren().add(child);
            parent = child;
        }

        GateTree loaded = roundTrip(tree);
        assertSameTree(tree, loaded);
    }

    /**
     * Two roots gating the same channel with default (identical) branch names are both
     * kept, in order, with their own settings — nothing de-duplicates by channel or path.
     */
    @Test
    void twoRootsOnTheSameChannelStayDistinctAndOrdered() throws IOException {
        GateTree tree = new GateTree();
        GateNode a = new GateNode("CD3", 0.5);
        GateNode b = new GateNode("CD3", -1.25);
        b.setEnabled(false);
        b.setThresholdIsZScore(false);
        b.getPositiveChildren().add(new GateNode("CD8", 2.0));
        tree.addRoot(a);
        tree.addRoot(b);

        GateTree loaded = roundTrip(tree);

        assertEquals(2, loaded.getRoots().size());
        assertEquals(0.5, loaded.getRoots().get(0).getThreshold());
        assertTrue(loaded.getRoots().get(0).isEnabled());
        assertEquals(-1.25, loaded.getRoots().get(1).getThreshold());
        assertFalse(loaded.getRoots().get(1).isEnabled());
        assertEquals(loaded.getRoots().get(0).getPositiveName(),
                loaded.getRoots().get(1).getPositiveName(),
                "identical default names are expected and must not collapse the roots");
        assertSameTree(tree, loaded);
    }

    /** An empty tree writes an explicit empty "gates" array and reads back with no roots. */
    @Test
    void anEmptyTreeRoundTrips() throws IOException {
        GateTree tree = new GateTree();
        String text = saveText(tree);

        JsonObject json = JsonParser.parseString(text).getAsJsonObject();
        assertTrue(json.getAsJsonArray("gates").isEmpty());

        GateTree loaded = loadText(text);
        assertNotNull(loaded.getRoots());
        assertTrue(loaded.getRoots().isEmpty());
        assertFalse(loaded.isRoiFilterEnabled());
    }

    /** A null channel on either axis of quadrant and region gates reads back as null. */
    @Test
    void nullAxisChannelsOnTwoAxisGatesRoundTrip() throws IOException {
        GateTree tree = new GateTree();
        QuadrantGate q = new QuadrantGate();
        q.setChannelX("CD3");               // Y left null
        RectangleGate r = new RectangleGate();
        r.setChannelY("CD8");               // X left null
        EllipseGate e = new EllipseGate();  // both null
        PolygonGate p = new PolygonGate();  // both null, no vertices
        tree.addRoot(q);
        tree.addRoot(r);
        tree.addRoot(e);
        tree.addRoot(p);

        GateTree loaded = assertDoesNotThrow(() -> roundTrip(tree));
        assertSameTree(tree, loaded);
        assertNull(((QuadrantGate) loaded.getRoots().get(0)).getChannelY());
        assertNull(((RectangleGate) loaded.getRoots().get(1)).getChannelX());
        assertTrue(((PolygonGate) loaded.getRoots().get(3)).getVertices().isEmpty());
    }

    // =====================================================================
    //  Strings and numbers
    // =====================================================================

    /** Quotes, backslashes, newlines, tabs, non-BMP unicode and empty strings survive verbatim. */
    @Test
    void hostileStringsInNamesAndChannelsSurviveVerbatim() throws IOException {
        String nasty = "a \"quoted\" \\back\\slash\nnew line\ttab µm CD3ε 日本 🧬 </script>";
        GateTree tree = new GateTree();
        GateNode t = new GateNode(nasty, 1.0);
        t.setPositiveName(nasty + "+");
        t.setNegativeName("");
        QuadrantGate q = new QuadrantGate("Ki-67: µ", "\"CD8\"");
        q.getBranchNN().setName("\\");
        t.getNegativeChildren().add(q);
        PolygonGate p = new PolygonGate("ch\n1", "ch\\2");
        p.getInsideBranch().setName("inside \u0000 nul");
        t.getPositiveChildren().add(p);
        tree.addRoot(t);

        GateTree loaded = roundTrip(tree);
        assertSameTree(tree, loaded);
        assertEquals(nasty, loaded.getRoots().get(0).getChannel());
    }

    /**
     * NaN and +/-Infinity in thresholds, rectangle bounds, ellipse parameters and polygon
     * vertices are not representable in strict JSON. Whatever is written must read back as
     * the same value — not throw on save, not load as 0.
     */
    @Test
    void nonFiniteGateNumbersRoundTrip() throws IOException {
        GateTree tree = new GateTree();
        GateNode t = new GateNode("CD3", Double.NaN);
        t.setClipPercentileHigh(Double.POSITIVE_INFINITY);
        tree.addRoot(t);
        tree.addRoot(new QuadrantGate("CD3", "CD8", Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY));
        tree.addRoot(new RectangleGate("CD3", "CD8",
                Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NaN, 1.0));
        tree.addRoot(new EllipseGate("CD3", "CD8", Double.NaN, 0, Double.POSITIVE_INFINITY, 1));
        PolygonGate p = new PolygonGate("CD3", "CD8");
        p.setVertices(new ArrayList<>(List.of(new double[]{Double.NaN, 1},
                new double[]{Double.NEGATIVE_INFINITY, 2}, new double[]{3, Double.POSITIVE_INFINITY})));
        tree.addRoot(p);

        GateTree loaded = assertDoesNotThrow(() -> roundTrip(tree));
        assertSameTree(tree, loaded);
    }

    /** Extreme finite doubles (MAX_VALUE, MIN_VALUE, -0.0) keep their exact bits. */
    @Test
    void extremeFiniteDoublesKeepTheirExactBits() throws IOException {
        GateTree tree = new GateTree();
        tree.addRoot(new GateNode("a", Double.MAX_VALUE));
        tree.addRoot(new GateNode("b", Double.MIN_VALUE));
        tree.addRoot(new GateNode("c", -0.0));
        tree.addRoot(new GateNode("d", 0.1 + 0.2));

        GateTree loaded = roundTrip(tree);
        for (int i = 0; i < 4; i++) {
            assertEquals(Double.doubleToRawLongBits(tree.getRoots().get(i).getThreshold()),
                    Double.doubleToRawLongBits(loaded.getRoots().get(i).getThreshold()),
                    "root " + i);
        }
    }

    // =====================================================================
    //  Quality filter
    // =====================================================================

    /**
     * A slug FlowPath has no name for, and one-sided bounds on both known and unknown slugs,
     * survive. The open side is omitted from the "ranges" entry — no "Infinity" token is
     * written into the ranges block — and reads back as infinite.
     */
    @Test
    void unknownSlugsAndOneSidedRangesSurvive() throws IOException {
        GateTree tree = new GateTree();
        QualityFilter qf = new QualityFilter();
        qf.setRange("major_axis_length", new QualityFilter.Range(2.0, Double.POSITIVE_INFINITY));
        qf.setRange("minor_axis_length", new QualityFilter.Range(Double.NEGATIVE_INFINITY, 30.0));
        qf.setRange(QualityFilter.AREA, new QualityFilter.Range(Double.NEGATIVE_INFINITY, 500.0));
        qf.setRange(QualityFilter.PERIMETER, new QualityFilter.Range(-5.0, Double.POSITIVE_INFINITY));
        tree.setQualityFilter(qf);

        String text = saveText(tree);
        JsonObject ranges = JsonParser.parseString(text).getAsJsonObject()
                .getAsJsonObject("qualityFilter").getAsJsonObject("ranges");
        assertEquals(2.0, ranges.getAsJsonObject("major_axis_length").get("min").getAsDouble());
        assertFalse(ranges.getAsJsonObject("major_axis_length").has("max"));
        assertFalse(ranges.getAsJsonObject("minor_axis_length").has("min"));
        assertFalse(ranges.toString().contains("Infinity"), ranges.toString());

        QualityFilter back = loadText(text).getQualityFilter();
        assertEquals(qf.range("major_axis_length"), back.range("major_axis_length"));
        assertEquals(qf.range("minor_axis_length"), back.range("minor_axis_length"));
        assertEquals(qf.range(QualityFilter.AREA), back.range(QualityFilter.AREA),
                "an open lower bound on a legacy slug must not pick up the legacy 0 floor");
        assertEquals(qf.range(QualityFilter.PERIMETER), back.range(QualityFilter.PERIMETER),
                "a negative lower bound must survive the legacy minPerimeter property");
    }

    /**
     * The unconstrained filter (QualityFilter's own "excludes nothing" state) must reload
     * as unconstrained. Suspected defect: the five legacy properties are written from the
     * legacy getters, which report an open bound as 0 / 1.0 / Double.MAX_VALUE, and are
     * read back through the legacy setters, which store those as real closed ranges; the
     * "ranges" object that is meant to win omits open slugs, so it cannot correct them.
     */
    @Test
    void anUnconstrainedQualityFilterReloadsUnconstrained() throws IOException {
        GateTree tree = new GateTree();
        assertTrue(tree.getQualityFilter().isEmpty(), "precondition");

        String first = saveText(tree);
        GateTree loaded = loadText(first);
        QualityFilter back = loaded.getQualityFilter();

        for (String slug : List.of(QualityFilter.AREA, QualityFilter.ECCENTRICITY,
                QualityFilter.SOLIDITY, QualityFilter.TOTAL_INTENSITY, QualityFilter.PERIMETER)) {
            assertTrue(back.range(slug).isOpen(),
                    slug + " was open before save but reloaded as " + back.range(slug));
        }
        assertTrue(back.isEmpty(), "reloaded filter carries ranges " + back.ranges());
        assertEquals(first, saveText(loaded),
                "saving the reloaded (unchanged) tree must write the same file");
    }

    /**
     * Same defect, one-sided: constraining only the area minimum must not close the area
     * maximum or any of the other four legacy slugs on reload.
     */
    @Test
    void aOneSidedLegacyConstraintDoesNotCloseTheOtherBounds() throws IOException {
        GateTree tree = new GateTree();
        tree.getQualityFilter().setMinArea(25);

        QualityFilter back = roundTrip(tree).getQualityFilter();

        assertEquals(new QualityFilter.Range(25, Double.POSITIVE_INFINITY),
                back.range(QualityFilter.AREA));
        assertEquals(tree.getQualityFilter().ranges(), back.ranges(),
                "only the area constraint was set; nothing else may appear after a reload");
    }

    /** When a file carries both a legacy property and a "ranges" entry, "ranges" wins. */
    @Test
    void rangesEntryOverridesTheLegacyPropertyForTheSameSlug() throws IOException {
        GateTree loaded = loadText("""
                {"version": 3,
                 "qualityFilter": {"minArea": 50, "maxArea": 1000,
                                   "ranges": {"area": {"min": 10}}},
                 "gates": []}""");
        assertEquals(new QualityFilter.Range(10, Double.POSITIVE_INFINITY),
                loaded.getQualityFilter().range(QualityFilter.AREA));
    }

    // =====================================================================
    //  Legacy / defaults / tolerance
    // =====================================================================

    /** No "version" key and no "type" key: a pre-versioned file loads as v1 threshold gates. */
    @Test
    void aFileWithNoVersionAndNoTypeLoadsAsThresholdGates() throws IOException {
        GateTree loaded = loadText("""
                {"gates": [{"channel": "CD45", "threshold": 0.75,
                            "positiveChildren": [{"channel": "CD3", "threshold": 1.0}]}]}""");
        GateNode root = loaded.getRoots().get(0);
        assertEquals(GateNode.class, root.getClass());
        assertEquals(0.75, root.getThreshold());
        assertEquals(GateNode.class, root.getPositiveChildren().get(0).getClass());
        assertEquals("CD3", root.getPositiveChildren().get(0).getChannel());
    }

    /** A minimal threshold body picks up every documented default. */
    @Test
    void aMinimalThresholdGateTakesTheDocumentedDefaults() throws IOException {
        GateNode g = loadText("""
                {"version": 3, "gates": [{"type": "threshold"}]}""").getRoots().get(0);
        assertTrue(g.isEnabled());
        assertEquals(1.0, g.getClipPercentileLow());
        assertEquals(99.0, g.getClipPercentileHigh());
        assertFalse(g.isExcludeOutliers());
        assertTrue(g.isThresholdIsZScore());
        assertNull(g.getChannel());
        assertEquals(0.0, g.getThreshold());
        assertEquals(Compartment.WHOLE_CELL, g.getCompartment());
        assertEquals(Statistic.MEAN, g.getStatistic(), "absent statistic means v1 -> Mean");
        assertEquals(2, g.getBranches().size());
        assertTrue(g.isLeaf());
    }

    /** Minimal quadrant and region bodies (no branches, no axes) still produce whole gates. */
    @Test
    void minimalTwoAxisGatesTakeTheirDefaults() throws IOException {
        GateTree loaded = loadText("""
                {"version": 3, "gates": [
                  {"type": "quadrant"}, {"type": "polygon"},
                  {"type": "rectangle"}, {"type": "ellipse"}]}""");
        QuadrantGate q = (QuadrantGate) loaded.getRoots().get(0);
        assertEquals(4, q.getBranches().size());
        assertEquals(Statistic.MEAN, q.getStatisticX());
        assertEquals(Statistic.MEAN, q.getStatisticY());
        assertEquals(Compartment.WHOLE_CELL, q.getCompartmentY());
        for (int i = 1; i < 4; i++) {
            Region2DGate r = (Region2DGate) loaded.getRoots().get(i);
            assertEquals("Inside", r.getInsideBranch().getName());
            assertEquals("Outside", r.getOutsideBranch().getName());
            assertEquals(Statistic.MEAN, r.getStatisticY());
            assertTrue(r.isLeaf());
        }
        assertTrue(((PolygonGate) loaded.getRoots().get(1)).getVertices().isEmpty());
    }

    /** When both "excludeOutliers" and legacy "hideOutliers" are present, the new key wins. */
    @Test
    void excludeOutliersTakesPrecedenceOverLegacyHideOutliers() throws IOException {
        GateNode g = loadText("""
                {"version": 1, "gates": [{"channel": "CD3",
                  "excludeOutliers": false, "hideOutliers": true}]}""").getRoots().get(0);
        assertFalse(g.isExcludeOutliers());
    }

    /** Unknown keys at root, meta, quality filter, range entry, gate and branch level are ignored. */
    @Test
    void unknownExtraFieldsAreIgnoredAtEveryLevel() throws IOException {
        GateTree loaded = loadText("""
                {"version": 3, "futureRootKey": {"x": [1, 2]},
                 "meta": {"imageName": "img", "someNewProvenance": true},
                 "qualityFilter": {"newQcKnob": 3,
                                   "ranges": {"area": {"min": 5, "unit": "um2"}}},
                 "gates": [
                   {"type": "quadrant", "channelX": "CD3", "channelY": "CD8", "opacity": 0.4,
                    "branches": [{"name": "PP", "pinned": true, "children": []}]}
                 ]}""");
        assertEquals(5.0, loaded.getQualityFilter().range(QualityFilter.AREA).min());
        QuadrantGate q = (QuadrantGate) loaded.getRoots().get(0);
        assertEquals("CD8", q.getChannelY());
        assertEquals("PP", q.getBranchPP().getName());
    }

    /** A quadrant file listing fewer than four branches keeps the unlisted branches' defaults. */
    @Test
    void aQuadrantWithFewerBranchEntriesKeepsTheRest() throws IOException {
        QuadrantGate q = (QuadrantGate) loadText("""
                {"version": 3, "gates": [{"type": "quadrant", "channelX": "A", "channelY": "B",
                  "branches": [{"name": "only", "children": [{"channel": "C"}]}]}]}""")
                .getRoots().get(0);
        assertEquals(4, q.getBranches().size());
        assertEquals("only", q.getBranchPP().getName());
        assertEquals("C", q.getBranchPP().getChildren().get(0).getChannel());
        assertTrue(q.getBranchNN().getChildren().isEmpty());
    }

    // =====================================================================
    //  Failing loudly
    // =====================================================================

    /** An unknown "type" at the root fails with an IOException naming the type. */
    @Test
    void anUnknownRootGateTypeFailsNamingTheType() throws IOException {
        IOException e = assertThrows(IOException.class, () -> loadText("""
                {"version": 3, "gates": [{"type": "hexagon"}]}"""));
        assertTrue(e.getMessage().contains("hexagon"), e.getMessage());
    }

    /**
     * An unknown "type" buried in a subtree must fail the whole load, not drop that subtree
     * and hand back a plausible but truncated tree.
     */
    @Test
    void anUnknownGateTypeDeepInASubtreeFailsTheWholeLoad() {
        IOException e = assertThrows(IOException.class, () -> loadText("""
                {"version": 3, "gates": [
                  {"type": "threshold", "channel": "CD45"},
                  {"type": "quadrant", "channelX": "A", "channelY": "B", "branches": [
                    {"name": "pp"}, {"name": "np"}, {"name": "pn", "children": [
                      {"type": "polygon", "branches": [{"name": "in"}, {"name": "out", "children": [
                        {"type": "spline"}]}]}]}]}]}"""));
        assertTrue(e.getMessage().contains("spline"), e.getMessage());
    }

    /** Truncated, empty, non-object and wrongly-shaped files all fail with IOException. */
    @Test
    void structurallyMalformedFilesThrowIOException() {
        List<String> bad = List.of(
                "",                                                        // empty file
                "   \n  ",                                                 // whitespace only
                "{\"version\": 3, \"gates\": [ {\"type\": \"threshold\"",   // truncated
                "[]",                                                      // top-level array
                "\"just a string\"",                                       // top-level primitive
                "{\"version\": 3, \"gates\": {}}",                         // gates not an array
                "{\"version\": 3, \"gates\": [42]}",                       // gate not an object
                "{\"version\": 3, \"gates\": null}",                       // null gates
                "{\"version\": 3, \"qualityFilter\": [], \"gates\": []}",  // qf not an object
                "{\"version\": 3, \"qualityFilter\": {\"minArea\": null}, \"gates\": []}",
                "{\"version\": 3, \"gates\": [{\"type\": \"polygon\", \"vertices\": [[1]]}]}",
                "{\"version\": 3, \"gates\": [{\"type\": null}]}",
                "{\"version\": 3, \"gates\": [{\"type\": \"quadrant\", \"branches\": [null]}]}");
        for (String text : bad) {
            assertThrows(IOException.class, () -> loadText(text),
                    "expected IOException for: " + text);
        }
    }

    /**
     * A value of the wrong scalar type — a string where a number is expected — is a
     * malformed file too, and {@code load}'s javadoc promises an IOException for "the
     * format is invalid". Suspected defect: Gson's {@code getAsDouble}/{@code getAsInt} on a
     * non-numeric string primitive throws {@link NumberFormatException}, which is not in
     * the catch list at {@code FlowPathSerializer.load}, so it escapes unchecked.
     */
    @Test
    void aNonNumericStringWhereANumberBelongsThrowsIOException() {
        List<String> bad = List.of(
                "{\"version\": \"three\", \"gates\": []}",
                "{\"version\": 3, \"gates\": [{\"type\": \"threshold\", \"threshold\": \"high\"}]}",
                "{\"version\": 3, \"gates\": [{\"type\": \"rectangle\", \"minX\": \"left\"}]}",
                "{\"version\": 3, \"gates\": [{\"type\": \"threshold\", \"positiveColor\": [\"red\", 0, 0]}]}",
                "{\"version\": 3, \"qualityFilter\": {\"ranges\": {\"area\": {\"min\": \"big\"}}}, \"gates\": []}");
        List<String> escaped = new ArrayList<>();
        for (String text : bad) {
            Throwable t = assertThrows(Throwable.class, () -> loadText(text));
            if (!(t instanceof IOException)) escaped.add(t.getClass().getSimpleName() + " <- " + text);
        }
        assertTrue(escaped.isEmpty(), "unchecked exceptions escaped load():\n" + String.join("\n", escaped));
    }

    // =====================================================================
    //  Helpers
    // =====================================================================

    private GateTree richTree() {
        GateTree tree = new GateTree();

        GateNode root = new GateNode("CD45", 1.25);
        root.setThresholdIsZScore(false);
        root.setCompartment(Compartment.NUCLEAR);
        root.setStatistic(Statistic.SUM);
        root.setPositiveName("Immune");
        root.setNegativeName("Non-immune");
        root.setPositiveColor(0x12AB34);
        root.setNegativeColor(0xFE0102);
        root.setClipPercentileLow(2.5);
        root.setClipPercentileHigh(97.5);
        root.setExcludeOutliers(true);

        QuadrantGate quad = new QuadrantGate("CD3", "CD8", 0.5, -0.75);
        quad.setCompartmentX(Compartment.CYTOPLASMIC);
        quad.setStatisticX(Statistic.MEAN);
        quad.setCompartmentY(Compartment.of("Membrane"));
        quad.setStatisticY(Statistic.of("REDSEA"));
        quad.setThresholdIsZScore(false);
        quad.setEnabled(false);
        quad.setClipPercentileLow(0.0);
        quad.setClipPercentileHigh(100.0);
        String[] qn = {"T-dp", "CD8 only", "CD4ish", "DN"};
        int[] qc = {0x010203, 0x0A0B0C, 0xFFFFFF, 0x000000};
        for (int i = 0; i < 4; i++) {
            quad.getBranches().get(i).setName(qn[i]);
            quad.getBranches().get(i).setColor(qc[i]);
        }
        root.getPositiveChildren().add(quad);

        PolygonGate poly = new PolygonGate("CD20", "CD19");
        poly.setVertices(new ArrayList<>(List.of(new double[]{3, 1}, new double[]{0, 0},
                new double[]{5, -2}, new double[]{2.5, 4.75}, new double[]{-1e-9, 1e9})));
        poly.setCompartmentX(Compartment.NUCLEAR);
        poly.setStatisticX(Statistic.MEDIAN);
        poly.setCompartmentY(Compartment.CYTOPLASMIC);
        poly.setStatisticY(Statistic.of("Median RobustZ"));
        poly.setThresholdIsZScore(true);
        poly.getInsideBranch().setName("B cells");
        poly.getInsideBranch().setColor(0x336699);
        poly.getOutsideBranch().setName("not B");
        poly.getOutsideBranch().setColor(0x996633);
        poly.setExcludeOutliers(true);
        quad.getBranchPP().getChildren().add(poly);

        RectangleGate rectIn = new RectangleGate("Ki67", "PCNA", -1.5, 2.5, 0.25, 7.0);
        rectIn.setCompartmentY(Compartment.NUCLEAR);
        rectIn.setStatisticX(Statistic.SUM);
        rectIn.setEnabled(false);
        poly.getInsideBranch().getChildren().add(rectIn);

        EllipseGate ellOut = new EllipseGate("CD68", "CD163", 1.0, -2.0, 0.5, 3.25);
        ellOut.setCompartmentX(Compartment.CYTOPLASMIC);
        ellOut.setStatisticY(Statistic.SUM);
        ellOut.setThresholdIsZScore(false);
        poly.getOutsideBranch().getChildren().add(ellOut);
        ellOut.getInsideBranch().getChildren().add(new GateNode("CD206", 0.1));
        ellOut.getOutsideBranch().getChildren().add(new GateNode("HLA-DR", -0.1));

        EllipseGate ellNP = new EllipseGate("A", "B", 0, 0, 1, 1);
        quad.getBranchNP().getChildren().add(ellNP);
        RectangleGate rectPN = new RectangleGate("C", "D", 0, 1, 0, 1);
        rectPN.getOutsideBranch().getChildren().add(new GateNode("E", 3.0));
        quad.getBranchPN().getChildren().add(rectPN);
        GateNode ki = new GateNode("Ki67", 0.0);
        ki.setCompartment(Compartment.of("Membrane"));
        ki.setStatistic(Statistic.of("REDSEA"));
        quad.getBranchNN().getChildren().add(ki);

        root.getNegativeChildren().add(new GateNode("PanCK", -0.5));
        root.getNegativeChildren().add(new GateNode("SMA", 0.5));   // two siblings, order matters
        tree.addRoot(root);

        GateNode sameChannel = new GateNode("CD45", -0.5);
        sameChannel.setEnabled(false);
        tree.addRoot(sameChannel);

        RectangleGate rectRoot = new RectangleGate("DAPI", "CD45", 0, 10, 0, 10);
        rectRoot.setClipPercentileLow(5);
        tree.addRoot(rectRoot);

        QualityFilter qf = new QualityFilter();
        qf.setMinArea(25);
        qf.setMaxArea(500);
        qf.setMinEccentricity(0.1);
        qf.setMaxEccentricity(0.9);
        qf.setMinSolidity(0.3);
        qf.setMaxSolidity(0.95);
        qf.setMinTotalIntensity(100);
        qf.setMaxTotalIntensity(9000);
        qf.setMinPerimeter(10);
        qf.setMaxPerimeter(300);
        qf.setRange("major_axis_length", new QualityFilter.Range(2, 40));
        tree.setQualityFilter(qf);
        tree.setRoiFilterEnabled(true);
        return tree;
    }

    private GateTree roundTrip(GateTree tree) throws IOException {
        return loadText(saveText(tree));
    }

    /** Save and return the file text with the (time-dependent) savedAt line removed. */
    private String saveText(GateTree tree) throws IOException {
        File f = tempDir.resolve("save-" + (fileCounter++) + ".json").toFile();
        FlowPathSerializer.save(tree, f);
        String text = Files.readString(f.toPath(), StandardCharsets.UTF_8);
        return text.replaceAll("(?m)^\\s*\"savedAt\": \"[^\"]*\",?\\R", "");
    }

    private GateTree loadText(String json) throws IOException {
        File f = tempDir.resolve("load-" + (fileCounter++) + ".json").toFile();
        Files.writeString(f.toPath(), json, StandardCharsets.UTF_8);
        return FlowPathSerializer.load(f);
    }

    private static void assertSameTree(GateTree expected, GateTree actual) {
        assertEquals(expected.isRoiFilterEnabled(), actual.isRoiFilterEnabled(), "roiFilterEnabled");
        // The quality filter is deliberately NOT compared here: an unconstrained filter does
        // not survive a round trip (see anUnconstrainedQualityFilterReloadsUnconstrained),
        // and folding that into every gate test would hide the gate results behind it.
        assertSameNodes(expected.getRoots(), actual.getRoots(), "roots");
    }

    private static void assertSameNodes(List<GateNode> expected, List<GateNode> actual, String path) {
        assertEquals(expected.size(), actual.size(), path + ": child count");
        for (int i = 0; i < expected.size(); i++) {
            assertSameNode(expected.get(i), actual.get(i), path + "[" + i + "]");
        }
    }

    private static void assertSameNode(GateNode e, GateNode a, String path) {
        assertEquals(e.getClass(), a.getClass(), path + ": class");
        assertEquals(e.isEnabled(), a.isEnabled(), path + ": enabled");
        assertEquals(e.getClipPercentileLow(), a.getClipPercentileLow(), path + ": clipLow");
        assertEquals(e.getClipPercentileHigh(), a.getClipPercentileHigh(), path + ": clipHigh");
        assertEquals(e.isExcludeOutliers(), a.isExcludeOutliers(), path + ": excludeOutliers");
        assertEquals(e.isThresholdIsZScore(), a.isThresholdIsZScore(), path + ": thresholdIsZScore");

        if (e instanceof QuadrantGate eq) {
            QuadrantGate aq = (QuadrantGate) a;
            assertEquals(eq.getChannelX(), aq.getChannelX(), path + ": channelX");
            assertEquals(eq.getChannelY(), aq.getChannelY(), path + ": channelY");
            assertEquals(eq.getThresholdX(), aq.getThresholdX(), path + ": thresholdX");
            assertEquals(eq.getThresholdY(), aq.getThresholdY(), path + ": thresholdY");
            assertSame(eq.getCompartmentX(), aq.getCompartmentX(), path + ": compartmentX");
            assertSame(eq.getCompartmentY(), aq.getCompartmentY(), path + ": compartmentY");
            assertSame(eq.getStatisticX(), aq.getStatisticX(), path + ": statisticX");
            assertSame(eq.getStatisticY(), aq.getStatisticY(), path + ": statisticY");
        } else if (e instanceof Region2DGate er) {
            Region2DGate ar = (Region2DGate) a;
            assertEquals(er.getChannelX(), ar.getChannelX(), path + ": channelX");
            assertEquals(er.getChannelY(), ar.getChannelY(), path + ": channelY");
            assertSame(er.getCompartmentX(), ar.getCompartmentX(), path + ": compartmentX");
            assertSame(er.getCompartmentY(), ar.getCompartmentY(), path + ": compartmentY");
            assertSame(er.getStatisticX(), ar.getStatisticX(), path + ": statisticX");
            assertSame(er.getStatisticY(), ar.getStatisticY(), path + ": statisticY");
            if (e instanceof PolygonGate ep) {
                List<double[]> ev = ep.getVertices();
                List<double[]> av = ((PolygonGate) a).getVertices();
                assertEquals(ev.size(), av.size(), path + ": vertex count");
                for (int i = 0; i < ev.size(); i++) {
                    assertArrayEquals(ev.get(i), av.get(i), path + ": vertex " + i + " (order matters)");
                }
            } else if (e instanceof RectangleGate eg) {
                RectangleGate ag = (RectangleGate) a;
                assertEquals(eg.getMinX(), ag.getMinX(), path + ": minX");
                assertEquals(eg.getMaxX(), ag.getMaxX(), path + ": maxX");
                assertEquals(eg.getMinY(), ag.getMinY(), path + ": minY");
                assertEquals(eg.getMaxY(), ag.getMaxY(), path + ": maxY");
            } else if (e instanceof EllipseGate eg) {
                EllipseGate ag = (EllipseGate) a;
                assertEquals(eg.getCenterX(), ag.getCenterX(), path + ": centerX");
                assertEquals(eg.getCenterY(), ag.getCenterY(), path + ": centerY");
                assertEquals(eg.getRadiusX(), ag.getRadiusX(), path + ": radiusX");
                assertEquals(eg.getRadiusY(), ag.getRadiusY(), path + ": radiusY");
            }
        } else {
            assertEquals(e.getChannel(), a.getChannel(), path + ": channel");
            assertEquals(e.getThreshold(), a.getThreshold(), path + ": threshold");
            assertSame(e.getCompartment(), a.getCompartment(), path + ": compartment");
            assertSame(e.getStatistic(), a.getStatistic(), path + ": statistic");
        }

        List<Branch> eb = e.getBranches();
        List<Branch> ab = a.getBranches();
        assertEquals(eb.size(), ab.size(), path + ": branch count");
        for (int i = 0; i < eb.size(); i++) {
            String bp = path + ".branch" + i;
            assertEquals(eb.get(i).getName(), ab.get(i).getName(), bp + ": name");
            assertEquals(eb.get(i).getColor(), ab.get(i).getColor(), bp + ": color");
            assertSameNodes(eb.get(i).getChildren(), ab.get(i).getChildren(), bp);
        }
    }
}
