package qupath.ext.flowpath.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.QualityFilter;
import qupath.ext.flowpath.model.Statistic;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FlowPathSerializerTest {

    @TempDir
    Path tempDir;

    @Test
    void saveAndLoadRoundTrip() throws IOException {
        var tree = new GateTree();
        var root = new GateNode("CD45", 1.5);
        root.setPositiveName("Immune+");
        root.setNegativeName("Immune-");
        root.setPositiveColor((0 << 16) | (255 << 8) | 0);
        root.setNegativeColor((128 << 16) | (128 << 8) | 128);
        root.setClipPercentileLow(2.0);
        root.setClipPercentileHigh(98.0);
        root.setExcludeOutliers(true);
        tree.addRoot(root);

        File file = tempDir.resolve("test.json").toFile();
        FlowPathSerializer.save(tree, file);
        GateTree loaded = FlowPathSerializer.load(file);

        assertEquals(1, loaded.getRoots().size());
        GateNode loadedRoot = loaded.getRoots().get(0);
        assertEquals("CD45", loadedRoot.getChannel());
        assertEquals(1.5, loadedRoot.getThreshold());
        assertEquals("Immune+", loadedRoot.getPositiveName());
        assertEquals("Immune-", loadedRoot.getNegativeName());
        assertEquals((0 << 16) | (255 << 8) | 0, loadedRoot.getPositiveColor());
        assertEquals((128 << 16) | (128 << 8) | 128, loadedRoot.getNegativeColor());
        assertEquals(2.0, loadedRoot.getClipPercentileLow());
        assertEquals(98.0, loadedRoot.getClipPercentileHigh());
        assertTrue(loadedRoot.isExcludeOutliers());
    }

    @Test
    void nestedChildrenRoundTrip() throws IOException {
        var tree = new GateTree();
        var root = new GateNode("CD45", 0.0);
        var child = new GateNode("CD3", 1.0);
        var grandchild = new GateNode("CD8", 2.0);
        child.getPositiveChildren().add(grandchild);
        root.getPositiveChildren().add(child);
        root.getNegativeChildren().add(new GateNode("PANCK", -0.5));
        tree.addRoot(root);

        File file = tempDir.resolve("nested.json").toFile();
        FlowPathSerializer.save(tree, file);
        GateTree loaded = FlowPathSerializer.load(file);

        GateNode lr = loaded.getRoots().get(0);
        assertEquals(1, lr.getPositiveChildren().size());
        assertEquals(1, lr.getNegativeChildren().size());
        assertEquals("CD3", lr.getPositiveChildren().get(0).getChannel());
        assertEquals("PANCK", lr.getNegativeChildren().get(0).getChannel());

        GateNode loadedGrandchild = lr.getPositiveChildren().get(0).getPositiveChildren().get(0);
        assertEquals("CD8", loadedGrandchild.getChannel());
        assertEquals(2.0, loadedGrandchild.getThreshold());
    }

    @Test
    void qualityFilterRoundTrip() throws IOException {
        var tree = new GateTree();
        var qf = new QualityFilter();
        qf.setMin(QualityFilter.AREA, 25);
        qf.setMax(QualityFilter.AREA, 500);
        qf.setMin(QualityFilter.TOTAL_INTENSITY, 100);
        qf.setMax(QualityFilter.ECCENTRICITY, 0.9);
        qf.setMin(QualityFilter.SOLIDITY, 0.5);
        tree.setQualityFilter(qf);
        tree.addRoot(new GateNode("CD45"));

        File file = tempDir.resolve("qf.json").toFile();
        FlowPathSerializer.save(tree, file);
        GateTree loaded = FlowPathSerializer.load(file);

        QualityFilter lqf = loaded.getQualityFilter();
        assertEquals(25, lqf.range(QualityFilter.AREA).min());
        assertEquals(500, lqf.range(QualityFilter.AREA).max());
        assertEquals(100, lqf.range(QualityFilter.TOTAL_INTENSITY).min());
        assertEquals(0.9, lqf.range(QualityFilter.ECCENTRICITY).max());
        assertEquals(0.5, lqf.range(QualityFilter.SOLIDITY).min());
        // New fields left unconstrained since not set
        assertEquals(Double.NEGATIVE_INFINITY, lqf.range(QualityFilter.ECCENTRICITY).min());
        assertEquals(Double.POSITIVE_INFINITY, lqf.range(QualityFilter.SOLIDITY).max());
        assertEquals(Double.POSITIVE_INFINITY, lqf.range(QualityFilter.TOTAL_INTENSITY).max());
        assertEquals(Double.NEGATIVE_INFINITY, lqf.range(QualityFilter.PERIMETER).min());
        assertEquals(Double.POSITIVE_INFINITY, lqf.range(QualityFilter.PERIMETER).max());
    }


    /**
     * A statistic FlowPath does not ship a constant for must survive a save/load cycle.
     * <p>
     * {@code parseStatistic} used to be {@code Statistic.valueOf(...)} inside a bare
     * {@code catch (Exception ignored)} that fell through to {@code Statistic.MEAN}. Now
     * that the vocabulary is open that is no longer a harmless default: a workspace saved
     * against an export carrying {@code CD3: Cell: REDSEA} would silently reload pinned to
     * Mean, resolve to a measurement key that is not in the file, and read NaN for every
     * cell. Nothing would throw and the gate would still draw.
     */
    @Test
    void anUnknownStatisticSurvivesTheRoundTrip() throws IOException {
        var tree = new GateTree();
        var root = new GateNode("CD3", 1.0);
        root.setStatistic(Statistic.of("REDSEA"));
        tree.addRoot(root);

        File file = tempDir.resolve("redsea.json").toFile();
        FlowPathSerializer.save(tree, file);
        GateTree loaded = FlowPathSerializer.load(file);

        assertEquals(Statistic.of("REDSEA"), loaded.getRoots().get(0).getStatistic(),
                "an unrecognised statistic must not be silently downgraded to Mean");
    }

    /** A statistic token is read case-insensitively: {@code "MEDIAN"} is {@code Median}. */
    @Test
    void aStatisticWrittenAsAnEnumNameStillLoads() throws IOException {
        String json = """
                {
                  "version": 5,
                  "qualityFilter": {"ranges": {}},
                  "gates": [
                    {
                      "type": "threshold",
                      "clipPercentileLow": 1.0,
                      "clipPercentileHigh": 99.0,
                      "excludeOutliers": false,
                      "correctStaining": false,
                      "channel": "CD45",
                      "threshold": 0.0,
                      "compartment": "NUCLEAR",
                      "statistic": "MEDIAN",
                      "positiveName": "CD45+",
                      "negativeName": "CD45-",
                      "positiveColor": [0, 200, 0],
                      "negativeColor": [128, 128, 128],
                      "positiveChildren": [],
                      "negativeChildren": []
                    }
                  ]
                }
                """;
        File file = tempDir.resolve("enumname.json").toFile();
        try (var writer = new BufferedWriter(new FileWriter(file))) {
            writer.write(json);
        }

        assertEquals(Statistic.MEDIAN, FlowPathSerializer.load(file).getRoots().get(0).getStatistic(),
                "parsing is case-insensitive, so MEDIAN and Median are the same statistic");
    }


    @Test
    void futureVersionThrowsIOException() throws IOException {
        String json = """
                {
                  "version": 999,
                  "gates": []
                }
                """;
        File file = tempDir.resolve("future.json").toFile();
        try (var writer = new BufferedWriter(new FileWriter(file))) {
            writer.write(json);
        }

        assertThrows(IOException.class, () -> FlowPathSerializer.load(file));
    }

    @Test
    void roiFilterEnabledRoundTrip() throws IOException {
        var tree = new GateTree();
        tree.addRoot(new GateNode("CD45"));
        tree.setRoiFilterEnabled(true);

        File file = tempDir.resolve("roi.json").toFile();
        FlowPathSerializer.save(tree, file);
        GateTree loaded = FlowPathSerializer.load(file);

        assertTrue(loaded.isRoiFilterEnabled());
    }

    @Test
    void qualityFilterNewFieldsRoundTrip() throws IOException {
        var tree = new GateTree();
        var qf = new QualityFilter();
        qf.setMin(QualityFilter.AREA, 25);
        qf.setMax(QualityFilter.AREA, 500);
        qf.setMin(QualityFilter.ECCENTRICITY, 0.2);
        qf.setMax(QualityFilter.ECCENTRICITY, 0.9);
        qf.setMin(QualityFilter.SOLIDITY, 0.3);
        qf.setMax(QualityFilter.SOLIDITY, 0.85);
        qf.setMin(QualityFilter.TOTAL_INTENSITY, 100);
        qf.setMax(QualityFilter.TOTAL_INTENSITY, 8000);
        qf.setMin(QualityFilter.PERIMETER, 10);
        qf.setMax(QualityFilter.PERIMETER, 300);
        tree.setQualityFilter(qf);
        tree.addRoot(new GateNode("CD45"));

        File file = tempDir.resolve("qf_new.json").toFile();
        FlowPathSerializer.save(tree, file);
        GateTree loaded = FlowPathSerializer.load(file);

        QualityFilter lqf = loaded.getQualityFilter();
        assertEquals(25, lqf.range(QualityFilter.AREA).min());
        assertEquals(500, lqf.range(QualityFilter.AREA).max());
        assertEquals(0.2, lqf.range(QualityFilter.ECCENTRICITY).min());
        assertEquals(0.9, lqf.range(QualityFilter.ECCENTRICITY).max());
        assertEquals(0.3, lqf.range(QualityFilter.SOLIDITY).min());
        assertEquals(0.85, lqf.range(QualityFilter.SOLIDITY).max());
        assertEquals(100, lqf.range(QualityFilter.TOTAL_INTENSITY).min());
        assertEquals(8000, lqf.range(QualityFilter.TOTAL_INTENSITY).max());
        assertEquals(10, lqf.range(QualityFilter.PERIMETER).min());
        assertEquals(300, lqf.range(QualityFilter.PERIMETER).max());
    }



    @Test
    void gateWithNoChannelRoundTripsInsteadOfThrowing() throws IOException {
        // A gate can legitimately hold a null channel — GateNode's no-arg constructor
        // leaves it null, and the editor's channel combo can be cleared. Serializing
        // that writes a JSON null, which must read back as a null channel rather than
        // as an exception escaping load()'s IOException contract.
        var tree = new GateTree();
        tree.addRoot(new GateNode());

        File file = tempDir.resolve("null-channel.json").toFile();
        FlowPathSerializer.save(tree, file);

        GateTree loaded = assertDoesNotThrow(() -> FlowPathSerializer.load(file));
        assertEquals(1, loaded.getRoots().size());
        assertNull(loaded.getRoots().get(0).getChannel());
    }

    @Test
    void explicitJsonNullChannelIsReadAsNull() throws IOException {
        File file = tempDir.resolve("explicit-null.json").toFile();
        try (BufferedWriter w = new BufferedWriter(new FileWriter(file))) {
            w.write("""
                    {"version": 5, "gates": [
                      {"type": "threshold", "channel": null, "threshold": 0.0,
                       "clipPercentileLow": 1.0, "clipPercentileHigh": 99.0,
                       "excludeOutliers": false, "correctStaining": false,
                       "compartment": "WHOLE_CELL", "statistic": "Mean"}
                    ]}""");
        }

        GateTree loaded = assertDoesNotThrow(() -> FlowPathSerializer.load(file));
        assertNull(loaded.getRoots().get(0).getChannel());
    }
}
