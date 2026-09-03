package qupath.ext.flowpath.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.flowpath.io.FlowPathSerializer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gate-type hierarchy is sealed, and its {@code permits} clauses are the registry of
 * gate types this codebase knows how to persist.
 * <p>
 * The failure this guards against is specific and shipped-shaped. {@code
 * FlowPathSerializer} writes the discriminator polymorphically — every node emits its own
 * {@code getGateType()} — but chooses the body beside it with an {@code instanceof} chain.
 * A gate type added to the model and forgotten in that chain used to fall through to the
 * threshold branch, so <b>save succeeded</b> and produced a file whose {@code "type"} and
 * body disagreed; the only symptom was {@code "Unknown gate type"} on load, in a later
 * session, after the user's work was already on disk.
 * <p>
 * Two defences are pinned here. Sealing means a new gate type cannot exist without editing
 * a {@code permits} clause, and {@link #thePermittedGateTypesAreTheOnesWeCanPersist()}
 * fails the moment one does — pointing at every other place that needs a case. And
 * {@link #everyGateTypeWritesItsOwnBodyNotAThresholdBody()} pins the property the old
 * {@code else} branch silently broke: the {@code "type"} a file names and the fields it
 * carries must describe the same gate.
 */
class GateTypeRegistryTest {

    @TempDir
    Path tempDir;

    /**
     * The registry itself. If you are here because you added a gate type, the other places
     * that need a case are named in the failure message.
     */
    @Test
    void thePermittedGateTypesAreTheOnesWeCanPersist() {
        assertTrue(GateNode.class.isSealed(), "GateNode must stay sealed: the permits "
                + "clause is what forces a new gate type to be declared rather than merely "
                + "compiled");
        assertEquals(Set.of(QuadrantGate.class, Region2DGate.class),
                Set.of(GateNode.class.getPermittedSubclasses()),
                "A gate type was added under GateNode. Also add: a case in "
                + "FlowPathSerializer.serializeNode, a branch in "
                + "FlowPathSerializer.deserializeNode, a display name in "
                + "FlowPathCell.regionTypeName and in GateEditorPane's label switch, and "
                + "a factory in ScatterPlotCanvas.");

        assertTrue(Region2DGate.class.isSealed(), "Region2DGate must stay sealed");
        assertEquals(Set.of(PolygonGate.class, RectangleGate.class, EllipseGate.class),
                Set.of(Region2DGate.class.getPermittedSubclasses()),
                "A 2D region gate was added. Also add: a case in "
                + "FlowPathSerializer.serializeNode, a branch in "
                + "FlowPathSerializer.deserializeNode's 2D dispatch, and drawing support "
                + "in ScatterPlotCanvas.");
    }

    /** Two gate types must never answer {@code getGateType()} with the same token. */
    @Test
    void everyGateTypeHasItsOwnDiscriminator() {
        List<GateNode> all = oneOfEach();
        List<String> tokens = all.stream().map(GateNode::getGateType).toList();
        assertEquals(tokens.size(), Set.copyOf(tokens).size(),
                "gate type tokens must be unique, but were: " + tokens);
    }

    /**
     * The regression test for the silent write. Each gate type is saved on its own and the
     * JSON is read back raw: the {@code "type"} must name that gate, the body must carry
     * that gate's own discriminating field, and — for everything that is not a threshold
     * gate — the body must <b>not</b> carry the threshold gate's fields, which is exactly
     * what the old trailing {@code else} produced.
     */
    @Test
    void everyGateTypeWritesItsOwnBodyNotAThresholdBody() throws IOException {
        for (GateNode gate : oneOfEach()) {
            JsonObject json = saveAndReadFirstGate(gate);
            String type = gate.getGateType();

            assertEquals(type, json.get("type").getAsString(),
                    "the discriminator must name the gate that was written");

            String required = switch (type) {
                case "polygon" -> "vertices";
                case "rectangle" -> "minX";
                case "ellipse" -> "centerX";
                case "quadrant" -> "channelX";
                case "threshold" -> "channel";
                default -> throw new AssertionError(
                        "unregistered gate type reached this test: " + type);
            };
            assertTrue(json.has(required),
                    "a '" + type + "' gate must carry '" + required + "', but wrote: "
                            + json.keySet());

            if (!"threshold".equals(type)) {
                assertFalse(json.has("channel") && json.has("threshold"),
                        "a '" + type + "' gate wrote a threshold gate's body. This is the "
                        + "silent-corruption failure: the file names '" + type + "' but "
                        + "carries threshold fields, so it saves cleanly and only fails on "
                        + "load. Add a serializeNode case for this type.");
            }
        }
    }

    /** Everything a save produces must survive a load — checked per gate type. */
    @Test
    void everyGateTypeSurvivesARoundTrip() throws IOException {
        for (GateNode gate : oneOfEach()) {
            GateTree tree = new GateTree();
            tree.addRoot(gate);
            File file = tempDir.resolve("rt-" + gate.getGateType() + ".json").toFile();
            FlowPathSerializer.save(tree, file);
            GateTree loaded = FlowPathSerializer.load(file);

            assertEquals(1, loaded.getRoots().size());
            GateNode back = loaded.getRoots().get(0);
            assertEquals(gate.getClass(), back.getClass(),
                    "a '" + gate.getGateType() + "' gate came back as a "
                            + back.getClass().getSimpleName());
            assertEquals(gate.getBranches().size(), back.getBranches().size(),
                    "branch count changed across a round trip for " + gate.getGateType());
        }
    }

    // --- helpers -------------------------------------------------------------

    /** One populated instance of every gate type the registry permits. */
    private static List<GateNode> oneOfEach() {
        GateNode threshold = new GateNode("CD45", 1.5);

        QuadrantGate quadrant = new QuadrantGate();
        quadrant.setChannelX("CD3");
        quadrant.setChannelY("CD8");
        quadrant.setThresholdX(1.0);
        quadrant.setThresholdY(2.0);

        PolygonGate polygon = new PolygonGate();
        polygon.setChannelX("CD3");
        polygon.setChannelY("CD8");
        polygon.setVertices(List.of(
                new double[]{0, 0}, new double[]{1, 0}, new double[]{1, 1}));

        RectangleGate rectangle = new RectangleGate();
        rectangle.setChannelX("CD3");
        rectangle.setChannelY("CD8");
        rectangle.setMinX(0); rectangle.setMaxX(2);
        rectangle.setMinY(0); rectangle.setMaxY(2);

        EllipseGate ellipse = new EllipseGate();
        ellipse.setChannelX("CD3");
        ellipse.setChannelY("CD8");
        ellipse.setCenterX(1); ellipse.setCenterY(1);
        ellipse.setRadiusX(1); ellipse.setRadiusY(1);

        return List.of(threshold, quadrant, polygon, rectangle, ellipse);
    }

    private JsonObject saveAndReadFirstGate(GateNode gate) throws IOException {
        GateTree tree = new GateTree();
        tree.addRoot(gate);
        File file = tempDir.resolve("body-" + gate.getGateType() + ".json").toFile();
        FlowPathSerializer.save(tree, file);
        JsonObject root = JsonParser.parseString(Files.readString(file.toPath()))
                .getAsJsonObject();
        return root.getAsJsonArray("gates").get(0).getAsJsonObject();
    }
}
