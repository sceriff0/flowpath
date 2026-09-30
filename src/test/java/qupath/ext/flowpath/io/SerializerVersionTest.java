package qupath.ext.flowpath.io;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import qupath.ext.flowpath.model.Compartment;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.PolygonGate;
import qupath.ext.flowpath.model.QualityFilter;
import qupath.ext.flowpath.model.QuadrantGate;
import qupath.ext.flowpath.model.Statistic;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The one saved format. FlowPath 0.10.0 writes version 5 on every save and reads nothing else:
 * an older file is refused with a message naming its version, a newer one as newer, and a
 * version-5 file missing a required key is refused naming the key, never loaded with a default.
 */
class SerializerVersionTest {

    @TempDir
    Path tempDir;

    private int counter;

    private File write(String json) throws IOException {
        File f = tempDir.resolve("tree-" + (counter++) + ".json").toFile();
        Files.writeString(f.toPath(), json, StandardCharsets.UTF_8);
        return f;
    }

    private static GateTree oneThresholdGate() {
        GateTree tree = new GateTree();
        tree.addRoot(new GateNode("CD3", 1.0));
        return tree;
    }

    /** A current file with the tree built in code, as {@link FlowPathSerializer#toJson} writes it. */
    private static JsonObject currentJson(GateTree tree) {
        return JsonParser.parseString(FlowPathSerializer.toJson(tree)).getAsJsonObject();
    }

    @Test
    void saveWritesVersion5() throws IOException {
        File f = tempDir.resolve("saved.json").toFile();
        FlowPathSerializer.save(oneThresholdGate(), f);
        JsonObject json = JsonParser.parseString(Files.readString(f.toPath())).getAsJsonObject();
        assertEquals(5, json.get("version").getAsInt());
        assertEquals(5, FlowPathSerializer.CURRENT_VERSION);
        assertEquals(5, currentJson(oneThresholdGate()).get("version").getAsInt(),
                "toJson writes the same version as save");
    }

    @Test
    void aFileWithNoVersionIsRefusedAsOlderThan0_10_0() throws IOException {
        JsonObject json = currentJson(oneThresholdGate());
        json.remove("version");
        File f = write(json.toString());

        IOException e = assertThrows(IOException.class, () -> FlowPathSerializer.load(f));
        assertTrue(e.getMessage().contains("0.10.0"), e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4})
    void everyOlderVersionIsRefusedNamingItsVersion(int version) throws IOException {
        JsonObject json = currentJson(oneThresholdGate());
        json.addProperty("version", version);
        File f = write(json.toString());

        IOException e = assertThrows(IOException.class, () -> FlowPathSerializer.load(f));
        assertTrue(e.getMessage().contains("0.10.0"), e.getMessage());
        assertTrue(e.getMessage().contains("version " + version), e.getMessage());
    }

    @Test
    void aNewerVersionIsRefusedAsNewer() throws IOException {
        JsonObject json = currentJson(oneThresholdGate());
        json.addProperty("version", 6);
        File f = write(json.toString());

        IOException e = assertThrows(IOException.class, () -> FlowPathSerializer.load(f));
        assertTrue(e.getMessage().contains("6"), e.getMessage());
        assertTrue(e.getMessage().contains("newer"), e.getMessage());
    }

    /** Each required key, removed from an otherwise valid current file, is refused by name. */
    @ParameterizedTest
    @ValueSource(strings = {"type", "clipPercentileLow", "clipPercentileHigh", "excludeOutliers",
            "correctStaining", "compartment", "statistic"})
    void aThresholdGateMissingARequiredKeyIsRefusedNamingIt(String key) throws IOException {
        JsonObject json = currentJson(oneThresholdGate());
        JsonObject gate = json.getAsJsonArray("gates").get(0).getAsJsonObject();
        assertTrue(gate.has(key), "the fixture must carry " + key + " before removing it");
        gate.remove(key);
        File f = write(json.toString());

        IOException e = assertThrows(IOException.class, () -> FlowPathSerializer.load(f));
        assertTrue(e.getMessage().contains("\"" + key + "\""), e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"compartmentX", "compartmentY", "statisticX", "statisticY", "correctStaining"})
    void aTwoAxisGateMissingARequiredKeyIsRefusedNamingIt(String key) throws IOException {
        GateTree tree = new GateTree();
        tree.addRoot(new QuadrantGate("CD3", "CD8", 1.0, 2.0));
        tree.addRoot(new PolygonGate("CD3", "CD8"));
        for (int i = 0; i < 2; i++) {
            JsonObject json = currentJson(tree);
            JsonObject gate = json.getAsJsonArray("gates").get(i).getAsJsonObject();
            assertTrue(gate.has(key), "the fixture must carry " + key + " before removing it");
            gate.remove(key);
            File f = write(json.toString());

            IOException e = assertThrows(IOException.class, () -> FlowPathSerializer.load(f));
            assertTrue(e.getMessage().contains("\"" + key + "\""), e.getMessage());
        }
    }

    /** A key missing on a gate deep in a subtree fails the whole load too. */
    @Test
    void aChildGateMissingCorrectStainingIsRefused() throws IOException {
        GateTree tree = new GateTree();
        GateNode root = new GateNode("CD45", 1.0);
        root.getPositiveChildren().add(new GateNode("CD3", 2.0));
        tree.addRoot(root);
        JsonObject json = currentJson(tree);
        json.getAsJsonArray("gates").get(0).getAsJsonObject()
                .getAsJsonArray("positiveChildren").get(0).getAsJsonObject().remove("correctStaining");
        File f = write(json.toString());

        IOException e = assertThrows(IOException.class, () -> FlowPathSerializer.load(f));
        assertTrue(e.getMessage().contains("\"correctStaining\""), e.getMessage());
    }

    @Test
    void aQualityFilterWithoutRangesIsRefused() throws IOException {
        JsonObject json = currentJson(oneThresholdGate());
        JsonObject qf = new JsonObject();
        qf.addProperty("minArea", 50);   // the retired flat form
        json.add("qualityFilter", qf);
        File f = write(json.toString());

        IOException e = assertThrows(IOException.class, () -> FlowPathSerializer.load(f));
        assertTrue(e.getMessage().contains("ranges"), e.getMessage());
    }

    @Test
    void aMixedTreeWithNonDefaultAxesSurvivesSaveAndLoad() throws IOException {
        GateTree tree = new GateTree();
        QualityFilter qf = new QualityFilter();
        qf.setMin(QualityFilter.AREA, 20);
        qf.setMax(QualityFilter.SOLIDITY, 0.95);
        tree.setQualityFilter(qf);

        GateNode threshold = new GateNode("CD45", 2.5);
        threshold.setCompartment(Compartment.NUCLEAR);
        threshold.setStatistic(Statistic.SUM);
        threshold.setCorrectStaining(false);

        QuadrantGate quad = new QuadrantGate("CD3", "CD8", 1.25, -0.5);
        quad.setCompartmentX(Compartment.CYTOPLASMIC);
        quad.setStatisticX(Statistic.MEDIAN);
        quad.setCompartmentY(Compartment.NUCLEAR);
        quad.setStatisticY(Statistic.of("Median Z"));
        threshold.getPositiveChildren().add(quad);

        PolygonGate poly = new PolygonGate("CD20", "CD19");
        poly.setVertices(List.of(new double[]{0, 0}, new double[]{4, 0}, new double[]{2, 3}));
        poly.setCompartmentX(Compartment.NUCLEAR);
        poly.setStatisticX(Statistic.MEDIAN);
        poly.setCompartmentY(Compartment.of("Membrane"));
        poly.setStatisticY(Statistic.of("REDSEA"));
        poly.setExcludeOutliers(true);
        poly.setClipPercentileLow(2.0);
        quad.getBranchPP().getChildren().add(poly);
        tree.addRoot(threshold);

        File f = tempDir.resolve("mixed.json").toFile();
        FlowPathSerializer.save(tree, f);
        GateTree loaded = FlowPathSerializer.load(f);

        QualityFilter lqf = loaded.getQualityFilter();
        assertEquals(new QualityFilter.Range(20, Double.POSITIVE_INFINITY), lqf.range(QualityFilter.AREA));
        assertEquals(new QualityFilter.Range(Double.NEGATIVE_INFINITY, 0.95), lqf.range(QualityFilter.SOLIDITY));

        GateNode lt = loaded.getRoots().get(0);
        assertEquals(GateNode.class, lt.getClass());
        assertEquals("CD45", lt.getChannel());
        assertEquals(2.5, lt.getThreshold());
        assertEquals(Compartment.NUCLEAR, lt.getCompartment());
        assertEquals(Statistic.SUM, lt.getStatistic());
        assertFalse(lt.isCorrectStaining());

        QuadrantGate lq = (QuadrantGate) lt.getPositiveChildren().get(0);
        assertEquals(1.25, lq.getThresholdX());
        assertEquals(-0.5, lq.getThresholdY());
        assertEquals(Compartment.CYTOPLASMIC, lq.getCompartmentX());
        assertEquals(Statistic.MEDIAN, lq.getStatisticX());
        assertEquals(Compartment.NUCLEAR, lq.getCompartmentY());
        assertEquals(Statistic.of("Median Z"), lq.getStatisticY());
        assertTrue(lq.isCorrectStaining());

        PolygonGate lp = (PolygonGate) lq.getBranchPP().getChildren().get(0);
        assertEquals(3, lp.getVertices().size());
        assertArrayEquals(new double[]{2, 3}, lp.getVertices().get(2));
        assertEquals(Compartment.NUCLEAR, lp.getCompartmentX());
        assertEquals(Statistic.MEDIAN, lp.getStatisticX());
        assertEquals(Compartment.of("Membrane"), lp.getCompartmentY());
        assertEquals(Statistic.of("REDSEA"), lp.getStatisticY());
        assertTrue(lp.isExcludeOutliers());
        assertEquals(2.0, lp.getClipPercentileLow());
    }

    /** Namespaced ranges, round QC included, are saved and loaded under their exact slugs. */
    @Test
    void namespacedQualityRangesRoundTrip(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        qupath.ext.flowpath.model.GateTree tree = new qupath.ext.flowpath.model.GateTree();
        qupath.ext.flowpath.model.QualityFilter f = new qupath.ext.flowpath.model.QualityFilter();
        f.setMin("morph/area", 12.5);
        f.setMax("qc/total_intensity", 900);
        f.setMin("qcround/nuclear_retention", 0.5);
        f.setMax("qcround/registration_displacement", 2.0);
        tree.setQualityFilter(f);
        java.io.File file = dir.resolve("t.json").toFile();
        FlowPathSerializer.save(tree, file);
        qupath.ext.flowpath.model.QualityFilter back = FlowPathSerializer.load(file).getQualityFilter();
        assertEquals(f.ranges(), back.ranges());
    }
}
