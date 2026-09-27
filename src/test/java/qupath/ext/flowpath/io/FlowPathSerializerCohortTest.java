package qupath.ext.flowpath.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.GateValues;
import qupath.ext.flowpath.model.PolygonGate;
import qupath.ext.flowpath.model.SlideSetting;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FlowPathSerializerCohortTest {

    @Test
    void theLineageTickRoundTripsAndDefaultsOff(@TempDir Path dir) throws Exception {
        GateTree tree = new GateTree();
        GateNode a = new GateNode("CD3", 1.0);
        a.setLineageMarker(true);
        tree.addRoot(a);
        tree.addRoot(new GateNode("CD3", 2.0));
        File file = dir.resolve("t.json").toFile();
        FlowPathSerializer.save(tree, file);
        String json = Files.readString(file.toPath());
        assertEquals(1, json.split("\"lineageMarker\"", -1).length - 1, "written only when set");
        GateTree loaded = FlowPathSerializer.load(file);
        assertTrue(loaded.getRoots().get(0).isLineageMarker());
        assertFalse(loaded.getRoots().get(1).isLineageMarker());
        assertTrue(a.deepCopy().isLineageMarker());
        assertFalse(new GateNode("CD3", 1.0).isLineageMarker(), "defaults off");
    }

    @Test
    void version4RoundTripsSettingsForTwoSameChannelRoots(@TempDir Path dir) throws Exception {
        GateTree tree = new GateTree();
        GateNode a = new GateNode("CD8", 400.0);
        GateNode b = new GateNode("CD8", 600.0);
        PolygonGate poly = new PolygonGate("CD3", "CD4");
        poly.setVertices(List.of(new double[]{0, 0}, new double[]{2, 0}, new double[]{1, 3}));
        a.setSlideSetting("s1", new SlideSetting.Skip());
        b.setSlideSetting("s1", new SlideSetting.Manual(GateValues.of(new double[]{612.5})));
        b.setSlideSetting("s2", new SlideSetting.Reviewed(GateValues.of(new double[]{588.25})));
        b.setCorrectStaining(false);
        poly.setSlideSetting("s1", new SlideSetting.Manual(GateValues.read(poly).map(v -> v + 1, v -> v * 2)));
        tree.addRoot(a);
        tree.addRoot(b);
        tree.addRoot(poly);
        tree.setReferenceSlideId("ref-id");
        tree.setSlideNames(java.util.Map.of("ref-id", "ref.tif", "s1", "one.tif"));

        File file = dir.resolve("t.json").toFile();
        FlowPathSerializer.save(tree, file);
        assertTrue(Files.readString(file.toPath()).contains("\"version\": 4"));

        GateTree loaded = FlowPathSerializer.load(file);
        assertEquals("ref-id", loaded.getReferenceSlideId());
        assertEquals(java.util.Map.of("ref-id", "ref.tif", "s1", "one.tif"), loaded.getSlideNames());
        GateNode la = loaded.getRoots().get(0);
        GateNode lb = loaded.getRoots().get(1);
        assertInstanceOf(SlideSetting.Skip.class, la.slideSetting("s1"));
        assertNull(la.slideSetting("s2"));
        assertTrue(la.isCorrectStaining());
        assertFalse(lb.isCorrectStaining());
        assertTrue(((SlideSetting.Manual) lb.slideSetting("s1")).values()
                .matches(GateValues.of(new double[]{612.5})));
        assertTrue(((SlideSetting.Reviewed) lb.slideSetting("s2")).appliedValues()
                .matches(GateValues.of(new double[]{588.25})));
        assertTrue(((SlideSetting.Manual) loaded.getRoots().get(2).slideSetting("s1")).values()
                .matches(GateValues.read(poly).map(v -> v + 1, v -> v * 2)));
    }

    @Test
    void aLegacyTreeLoadsWithCorrectionOffSoNoNumberChanges(@TempDir Path dir) throws Exception {
        File file = dir.resolve("v3.json").toFile();
        Files.writeString(file.toPath(), "{\"version\":3,\"gates\":["
                + "{\"type\":\"threshold\",\"channel\":\"CD3\",\"threshold\":5.0},"
                + "{\"type\":\"quadrant\",\"channelX\":\"CD3\",\"channelY\":\"CD8\"}]}");
        GateTree loaded = FlowPathSerializer.load(file);
        assertNull(loaded.getReferenceSlideId());
        assertTrue(loaded.getSlideNames().isEmpty(), "no names recorded: matches any project");
        for (GateNode gate : loaded.getRoots()) {
            assertFalse(gate.isCorrectStaining(), gate.getGateType());
            assertTrue(gate.getSlideSettings().isEmpty());
        }
    }

    @Test
    void theTransientSkipFlagIsNeverWritten(@TempDir Path dir) throws Exception {
        GateTree tree = new GateTree();
        GateNode a = new GateNode("CD8", 1.0);
        a.setSkippedOnSlide(true);
        tree.addRoot(a);
        File file = dir.resolve("t.json").toFile();
        FlowPathSerializer.save(tree, file);
        assertFalse(Files.readString(file.toPath()).contains("skipped"));
        assertFalse(FlowPathSerializer.load(file).getRoots().get(0).isSkippedOnSlide());
    }
}
