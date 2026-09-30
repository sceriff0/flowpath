package qupath.ext.flowpath.io;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import qupath.ext.flowpath.model.Branch;
import qupath.ext.flowpath.model.ColorUtils;
import qupath.ext.flowpath.model.Compartment;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.EllipseGate;
import qupath.ext.flowpath.model.GateValues;
import qupath.ext.flowpath.model.PolygonGate;
import qupath.ext.flowpath.model.QualityFilter;
import qupath.ext.flowpath.model.QuadrantGate;
import qupath.ext.flowpath.model.RectangleGate;
import qupath.ext.flowpath.model.Region2DGate;
import qupath.ext.flowpath.model.SlideSetting;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serializes and deserializes {@link GateTree} instances to/from JSON files.
 */
public class FlowPathSerializer {

    // One format, one version. FlowPath 0.10.0 dropped every older reader: a file written by an
    // earlier FlowPath is refused with a message naming its version rather than half-read, because
    // each older format needed a conversion (z-score thresholds, unnamespaced filter keys, missing
    // compartments) whose absence would load plausible, wrong numbers without an error.
    // Every field is written on every save and required on every load.
    static final int CURRENT_VERSION = 5;

    private FlowPathSerializer() {
        // static utility class
    }

    /**
     * What produced a gate tree, recorded alongside it.
     * <p>
     * A gate tree on its own says which channels it gates and at what thresholds, but not
     * which image it was drawn against, over how many cells, or by which version of
     * FlowPath. Reloaded against the wrong slide it half-resolves -- gates pointing at
     * channels that are not there, reading NaN for every cell -- and the file gives a
     * reader nothing to notice that with. For a figure or a supplement, a gate tree that
     * cannot state its own provenance is not reproducible.
     * <p>
     * Every field is optional; {@link #none()} records only what can be known without an
     * image.
     *
     * @param imageName the image the gates were drawn against, or {@code null}
     * @param cellCount cells in the index at save time, or {@code -1} if unknown
     * @param channels  the marker panel discovered on that image; never null, often empty
     */
    public record Provenance(String imageName, int cellCount, List<String> channels) {

        public Provenance {
            channels = channels == null ? List.of() : List.copyOf(channels);
        }

        /** No image context -- the saved file still records the version and the timestamp. */
        public static Provenance none() {
            return new Provenance(null, -1, List.of());
        }
    }

    /**
     * Save a gate tree to a JSON file, with no image provenance.
     *
     * @param tree the gate tree to save
     * @param file the destination file
     * @throws IOException if writing fails
     */
    public static void save(GateTree tree, File file) throws IOException {
        save(tree, file, Provenance.none());
    }

    /**
     * Save a gate tree to a JSON file, recording what produced it.
     *
     * @param tree       the gate tree to save
     * @param file       the destination file
     * @param provenance what this tree was gated against; never null
     * @throws IOException if writing fails
     */
    public static void save(GateTree tree, File file, Provenance provenance) throws IOException {
        JsonObject root = serializeTree(tree, serializeMeta(provenance));
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file, StandardCharsets.UTF_8))) {
            writer.write(gson.toJson(root));
        }
    }

    /**
     * The tree exactly as {@link #save} writes it, but with no {@code meta} block: what the tree
     * says, and nothing about when or where it was saved. {@code meta.savedAt} changes every
     * second, so a hash of the saved text would never match itself across two runs — a batch
     * run's resume fingerprint hashes this instead (pre-flight ruling B20).
     */
    public static String toJson(GateTree tree) {
        return new GsonBuilder().setPrettyPrinting().create().toJson(serializeTree(tree, null));
    }

    /** The saved document; {@code meta} is omitted when null. */
    private static JsonObject serializeTree(GateTree tree, JsonObject meta) {
        JsonObject root = new JsonObject();
        root.addProperty("version", CURRENT_VERSION);
        if (meta != null) root.add("meta", meta);
        root.add("qualityFilter", serializeQualityFilter(tree.getQualityFilter()));
        root.addProperty("roiFilterEnabled", tree.isRoiFilterEnabled());
        if (tree.getReferenceSlideId() != null) root.addProperty("referenceSlideId", tree.getReferenceSlideId());
        if (!tree.getSlideNames().isEmpty()) {
            JsonObject names = new JsonObject();
            tree.getSlideNames().forEach(names::addProperty);
            root.add("slideNames", names);
        }
        root.add("gates", serializeNodeList(tree.getRoots()));
        return root;
    }

    /**
     * The provenance block. Fields that cannot be known are omitted rather than written
     * as a placeholder, so a reader can distinguish "not recorded" from "recorded as
     * unknown" -- the latter being indistinguishable from a real image called "unknown".
     */
    private static JsonObject serializeMeta(Provenance provenance) {
        Provenance p = provenance == null ? Provenance.none() : provenance;
        JsonObject meta = new JsonObject();

        // Read from the JAR manifest, which the qupath-conventions plugin stamps. Absent
        // when running from a classes directory (tests, an IDE), hence the null check.
        String version = FlowPathSerializer.class.getPackage().getImplementationVersion();
        if (version != null && !version.isBlank()) meta.addProperty("flowpathVersion", version);

        meta.addProperty("savedAt", DateTimeFormatter.ISO_INSTANT.format(
                Instant.now().truncatedTo(ChronoUnit.SECONDS)));

        if (p.imageName() != null && !p.imageName().isBlank()) {
            meta.addProperty("imageName", p.imageName());
        }
        if (p.cellCount() >= 0) meta.addProperty("cellCount", p.cellCount());
        if (!p.channels().isEmpty()) {
            JsonArray channels = new JsonArray();
            for (String c : p.channels()) channels.add(c);
            meta.add("channels", channels);
        }
        return meta;
    }

    /**
     * Load a gate tree from a JSON file.
     *
     * @param file the source file
     * @return the deserialized gate tree
     * @throws IOException if reading fails or the format is invalid
     */
    public static GateTree load(File file) throws IOException {
        JsonObject root;
        try (BufferedReader reader = new BufferedReader(new FileReader(file, StandardCharsets.UTF_8))) {
            try {
                root = JsonParser.parseReader(reader).getAsJsonObject();
            } catch (com.google.gson.JsonSyntaxException | IllegalStateException e) {
                throw new IOException("Invalid FlowPath file (bad JSON): " + e.getMessage(), e);
            }
        }

        try {
            return parseGateTree(root);
        } catch (com.google.gson.JsonSyntaxException | IllegalStateException | ClassCastException
                 | NullPointerException | IndexOutOfBoundsException
                 | UnsupportedOperationException | NumberFormatException e) {
            // UnsupportedOperationException is what Gson throws for a typed read of a
            // JSON null; catching it keeps every malformed-file path inside the
            // documented IOException contract. NumberFormatException is Gson's answer to
            // a string where a number belongs ("threshold": "high").
            throw new IOException("Invalid FlowPath file structure: " + e.getMessage(), e);
        }
    }

    /**
     * Read a string property, tolerating both an absent key and an explicit JSON
     * null. A gate may legitimately carry a null channel (the no-arg constructor
     * leaves it null, and the editor's channel combo can be cleared), which
     * {@code addProperty} writes as {@code null}. Calling {@code getAsString} on
     * that throws {@link UnsupportedOperationException} — an unchecked type that
     * escapes {@link #load}'s {@code IOException} contract.
     */
    private static String optString(JsonObject obj, String key) {
        if (!obj.has(key)) return null;
        JsonElement elem = obj.get(key);
        return elem.isJsonNull() ? null : elem.getAsString();
    }

    private static GateTree parseGateTree(JsonObject root) throws IOException {
        if (!root.has("version")) {
            throw new IOException("Not a FlowPath " + CURRENT_VERSION + " gate tree: the file has no version. "
                    + "It was saved by a FlowPath older than 0.10.0, which this version no longer reads.");
        }
        int version = root.get("version").getAsInt();
        if (version > CURRENT_VERSION) {
            throw new IOException("Unsupported gate tree version: " + version
                    + " (maximum supported: " + CURRENT_VERSION + "). "
                    + "This file may have been created by a newer version of FlowPath.");
        }
        if (version < CURRENT_VERSION) {
            throw new IOException("This gate tree was saved by a FlowPath older than 0.10.0 (format version "
                    + version + "), which this version no longer reads. Re-create the gates, or open the "
                    + "file in the FlowPath that saved it to read its thresholds.");
        }

        GateTree tree = new GateTree();

        if (root.has("qualityFilter")) {
            tree.setQualityFilter(deserializeQualityFilter(root.getAsJsonObject("qualityFilter")));
        }

        if (root.has("roiFilterEnabled")) {
            tree.setRoiFilterEnabled(root.get("roiFilterEnabled").getAsBoolean());
        }

        tree.setReferenceSlideId(optString(root, "referenceSlideId"));
        // Absent when the tree never met a project: no names, which CohortIdentity treats as
        // matching any project (nothing recorded to contradict).
        if (root.has("slideNames") && root.get("slideNames").isJsonObject()) {
            Map<String, String> names = new LinkedHashMap<>();
            for (var e : root.getAsJsonObject("slideNames").entrySet()) {
                if (e.getValue().isJsonPrimitive()) names.put(e.getKey(), e.getValue().getAsString());
            }
            tree.setSlideNames(names);
        }

        if (root.has("gates")) {
            tree.setRoots(deserializeNodeList(root.getAsJsonArray("gates")));
        }

        return tree;
    }

    // -----------------------------------------------------------------------
    //  Quality filter
    // -----------------------------------------------------------------------

    /** The quality filter, as one entry per constrained field. */
    private static JsonObject serializeQualityFilter(QualityFilter qf) {
        JsonObject obj = new JsonObject();
        JsonObject ranges = new JsonObject();
        qf.ranges().forEach((slug, range) -> {
            JsonObject r = new JsonObject();
            if (range.min() > Double.NEGATIVE_INFINITY) r.addProperty("min", range.min());
            if (range.max() < Double.POSITIVE_INFINITY) r.addProperty("max", range.max());
            ranges.add(slug, r);
        });
        obj.add("ranges", ranges);
        return obj;
    }

    private static void readRanges(JsonObject ranges, QualityFilter qf) {
        for (var entry : ranges.entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject r = entry.getValue().getAsJsonObject();
            double lo = r.has("min") ? r.get("min").getAsDouble() : Double.NEGATIVE_INFINITY;
            double hi = r.has("max") ? r.get("max").getAsDouble() : Double.POSITIVE_INFINITY;
            qf.setRange(entry.getKey(), new QualityFilter.Range(lo, hi));
        }
    }

    private static QualityFilter deserializeQualityFilter(JsonObject obj) throws IOException {
        if (!obj.has("ranges") || !obj.get("ranges").isJsonObject()) {
            throw new IOException("Invalid FlowPath file structure: the quality filter has no \"ranges\" object");
        }
        QualityFilter qf = new QualityFilter();
        readRanges(obj.getAsJsonObject("ranges"), qf);
        return qf;
    }

    // -----------------------------------------------------------------------
    //  Gate nodes
    // -----------------------------------------------------------------------

    private static void serializeTwoBranches(JsonObject obj, List<Branch> branches) {
        JsonArray arr = new JsonArray();
        for (Branch b : branches) {
            JsonObject bo = new JsonObject();
            bo.addProperty("name", b.getName());
            bo.add("color", ColorUtils.toJsonArray(b.getColor()));
            bo.add("children", serializeNodeList(b.getChildren()));
            arr.add(bo);
        }
        obj.add("branches", arr);
    }

    private static JsonArray serializeNodeList(List<GateNode> nodes) {
        JsonArray array = new JsonArray();
        for (GateNode node : nodes) {
            array.add(serializeNode(node));
        }
        return array;
    }

    private static JsonObject serializeNode(GateNode node) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", node.getGateType());
        if (!node.isEnabled()) {
            obj.addProperty("enabled", false);
        }
        obj.addProperty("clipPercentileLow", node.getClipPercentileLow());
        obj.addProperty("clipPercentileHigh", node.getClipPercentileHigh());
        obj.addProperty("excludeOutliers", node.isExcludeOutliers());
        obj.addProperty("correctStaining", node.isCorrectStaining());
        if (node.isLineageMarker()) obj.addProperty("lineageMarker", true);
        if (!node.getSlideSettings().isEmpty()) {
            JsonObject settings = new JsonObject();
            node.getSlideSettings().forEach((slideId, setting) -> settings.add(slideId, serializeSlideSetting(setting)));
            obj.add("slideSettings", settings);
        }

        if (node instanceof PolygonGate pg) {
            serializeRegionAxes(obj, pg);
            JsonArray verts = new JsonArray();
            for (double[] v : pg.getVertices()) {
                JsonArray pt = new JsonArray();
                pt.add(v[0]); pt.add(v[1]);
                verts.add(pt);
            }
            obj.add("vertices", verts);
            serializeTwoBranches(obj, pg.getBranches());
        } else if (node instanceof RectangleGate rg) {
            serializeRegionAxes(obj, rg);
            obj.addProperty("minX", rg.getMinX());
            obj.addProperty("maxX", rg.getMaxX());
            obj.addProperty("minY", rg.getMinY());
            obj.addProperty("maxY", rg.getMaxY());
            serializeTwoBranches(obj, rg.getBranches());
        } else if (node instanceof EllipseGate eg) {
            serializeRegionAxes(obj, eg);
            obj.addProperty("centerX", eg.getCenterX());
            obj.addProperty("centerY", eg.getCenterY());
            obj.addProperty("radiusX", eg.getRadiusX());
            obj.addProperty("radiusY", eg.getRadiusY());
            serializeTwoBranches(obj, eg.getBranches());
        } else if (node instanceof QuadrantGate qg) {
            obj.addProperty("channelX", qg.getChannelX());
            obj.addProperty("channelY", qg.getChannelY());
            obj.addProperty("thresholdX", qg.getThresholdX());
            obj.addProperty("thresholdY", qg.getThresholdY());
            obj.addProperty("compartmentX", qg.getCompartmentX().name());
            obj.addProperty("compartmentY", qg.getCompartmentY().name());
            obj.addProperty("statisticX", qg.getStatisticX().token());
            obj.addProperty("statisticY", qg.getStatisticY().token());
            // Serialize 4 branches
            JsonArray branches = new JsonArray();
            for (Branch b : qg.getBranches()) {
                JsonObject bo = new JsonObject();
                bo.addProperty("name", b.getName());
                bo.add("color", ColorUtils.toJsonArray(b.getColor()));
                bo.add("children", serializeNodeList(b.getChildren()));
                branches.add(bo);
            }
            obj.add("branches", branches);
        } else if (node.getClass() == GateNode.class) {
            // Threshold gate — GateNode itself doubles as the 1-D threshold gate.
            // Matched on the exact class rather than as a trailing `else`: the `else`
            // spelling meant any gate type added to the model without a case above was
            // written with a threshold gate's body under its own "type" string, so save()
            // succeeded and the file only failed on load, in a later session, after the
            // user's work was already on disk. See the refusal below.
            obj.addProperty("channel", node.getChannel());
            obj.addProperty("threshold", node.getThreshold());
            obj.addProperty("compartment", node.getCompartment().name());
            obj.addProperty("statistic", node.getStatistic().token());
            obj.addProperty("positiveName", node.getPositiveName());
            obj.addProperty("negativeName", node.getNegativeName());
            obj.add("positiveColor", ColorUtils.toJsonArray(node.getPositiveColor()));
            obj.add("negativeColor", ColorUtils.toJsonArray(node.getNegativeColor()));
            obj.add("positiveChildren", serializeNodeList(node.getPositiveChildren()));
            obj.add("negativeChildren", serializeNodeList(node.getNegativeChildren()));
        } else {
            // A gate type reached the model without reaching this method. Refusing is the
            // whole point: writing it as a threshold gate would produce a file whose
            // "type" and body disagree, which save() cannot detect and load() reports only
            // as "Unknown gate type" much later. save() builds the entire document in
            // memory before it opens the writer, so throwing here leaves any existing file
            // untouched rather than truncated.
            throw new IllegalStateException(
                    "No serializer case for gate type '" + node.getGateType() + "' ("
                    + node.getClass().getName() + "). Add one to "
                    + "FlowPathSerializer.serializeNode, a matching branch to "
                    + "deserializeNode, and a display name to FlowPathCell.regionTypeName "
                    + "and GateEditorPane's label switch (the editor itself is chosen in GateTypeEditors.forGate).");
        }

        return obj;
    }

    /** Write the axis block shared by every 2D region gate (polygon / rectangle / ellipse). */
    private static void serializeRegionAxes(JsonObject obj, Region2DGate gate) {
        obj.addProperty("channelX", gate.getChannelX());
        obj.addProperty("channelY", gate.getChannelY());
        obj.addProperty("compartmentX", gate.getCompartmentX().name());
        obj.addProperty("compartmentY", gate.getCompartmentY().name());
        obj.addProperty("statisticX", gate.getStatisticX().token());
        obj.addProperty("statisticY", gate.getStatisticY().token());
    }

    private static JsonObject serializeSlideSetting(SlideSetting setting) {
        JsonObject o = new JsonObject();
        switch (setting) {
            case SlideSetting.Skip s -> o.addProperty("kind", "skip");
            case SlideSetting.Manual m -> { o.addProperty("kind", "manual"); writeValues(o, m.values()); }
            case SlideSetting.Reviewed r -> { o.addProperty("kind", "reviewed"); writeValues(o, r.appliedValues()); }
        }
        return o;
    }

    private static void writeValues(JsonObject o, GateValues values) {
        JsonArray axes = new JsonArray();
        for (int k = 0; k < values.axisCount(); k++) {
            JsonArray axis = new JsonArray();
            for (double v : values.axis(k)) axis.add(v);
            axes.add(axis);
        }
        o.add("values", axes);
    }

    private static SlideSetting deserializeSlideSetting(JsonObject o) throws IOException {
        String kind = optString(o, "kind");
        if ("skip".equals(kind)) return new SlideSetting.Skip();
        JsonArray axes = o.getAsJsonArray("values");
        double[][] read = new double[axes.size()][];
        for (int k = 0; k < axes.size(); k++) {
            JsonArray axis = axes.get(k).getAsJsonArray();
            read[k] = new double[axis.size()];
            for (int i = 0; i < axis.size(); i++) read[k][i] = axis.get(i).getAsDouble();
        }
        GateValues values = read.length == 1 ? GateValues.of(read[0]) : GateValues.of(read[0], read[1]);
        if ("manual".equals(kind)) return new SlideSetting.Manual(values);
        if ("reviewed".equals(kind)) return new SlideSetting.Reviewed(values);
        throw new IOException("Unknown slide setting kind: \"" + kind + "\"");
    }

    private static List<GateNode> deserializeNodeList(JsonArray array) throws IOException {
        List<GateNode> nodes = new ArrayList<>();
        for (JsonElement elem : array) {
            nodes.add(deserializeNode(elem.getAsJsonObject()));
        }
        return nodes;
    }

    private static GateNode deserializeNode(JsonObject obj) throws IOException {
        String type = required(obj, "type").getAsString();

        // Shared fields. "enabled" is written only when false.
        boolean enabled = !obj.has("enabled") || obj.get("enabled").getAsBoolean();
        double clipLow = required(obj, "clipPercentileLow").getAsDouble();
        double clipHigh = required(obj, "clipPercentileHigh").getAsDouble();
        boolean excludeOutliers = required(obj, "excludeOutliers").getAsBoolean();

        GateNode result;
        if ("quadrant".equals(type)) {
            result = deserializeQuadrantNode(obj, clipLow, clipHigh, excludeOutliers);
        } else if ("polygon".equals(type)) {
            result = deserialize2DNode(new PolygonGate(), obj, clipLow, clipHigh, excludeOutliers);
        } else if ("rectangle".equals(type)) {
            result = deserialize2DNode(new RectangleGate(), obj, clipLow, clipHigh, excludeOutliers);
        } else if ("ellipse".equals(type)) {
            result = deserialize2DNode(new EllipseGate(), obj, clipLow, clipHigh, excludeOutliers);
        } else if ("threshold".equals(type)) {
            result = deserializeThresholdNode(obj, clipLow, clipHigh, excludeOutliers);
        } else {
            throw new IOException("Unknown gate type: \"" + type + "\". "
                    + "This file may have been created by a newer version of FlowPath.");
        }
        result.setEnabled(enabled);
        result.setCorrectStaining(required(obj, "correctStaining").getAsBoolean());
        // Written only when ticked.
        result.setLineageMarker(obj.has("lineageMarker") && obj.get("lineageMarker").getAsBoolean());
        if (obj.has("slideSettings")) {
            for (var entry : obj.getAsJsonObject("slideSettings").entrySet()) {
                result.setSlideSetting(entry.getKey(), deserializeSlideSetting(entry.getValue().getAsJsonObject()));
            }
        }
        return result;
    }

    /** A property every current file carries; its absence means the file is not a valid one. */
    private static JsonElement required(JsonObject obj, String key) throws IOException {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            throw new IOException("Invalid FlowPath file structure: missing \"" + key + "\"");
        }
        return obj.get(key);
    }

    /**
     * A compartment, written as the enum {@code name()} ({@code "NUCLEAR"}). An unrecognised
     * token is honoured as a compartment FlowPath has not met rather than replaced: turning it
     * into whole-cell would point the gate at a different population with no error.
     */
    private static Compartment parseCompartment(JsonObject obj, String key) throws IOException {
        String trimmed = required(obj, key).getAsString().trim();
        for (Compartment c : Compartment.known()) {
            if (c.name().equals(trimmed)) return c;
        }
        return Compartment.of(trimmed);
    }

    /**
     * A statistic, written as its {@link Statistic#token} ({@code "Median"}). An unrecognised
     * token is kept as itself: substituting Mean would resolve a key not in the file and read
     * NaN for every cell.
     */
    private static Statistic parseStatistic(JsonObject obj, String key) throws IOException {
        Statistic parsed = Statistic.fromToken(required(obj, key).getAsString());
        if (parsed == null) throw new IOException("Invalid FlowPath file structure: blank \"" + key + "\"");
        return parsed;
    }

    private static GateNode deserializeThresholdNode(JsonObject obj,
                                                      double clipLow, double clipHigh, boolean excludeOutliers) throws IOException {
        GateNode node = new GateNode();
        node.setClipPercentileLow(clipLow);
        node.setClipPercentileHigh(clipHigh);
        node.setExcludeOutliers(excludeOutliers);

        node.setChannel(optString(obj, "channel"));
        if (obj.has("threshold"))
            node.setThreshold(obj.get("threshold").getAsDouble());
        node.setCompartment(parseCompartment(obj, "compartment"));
        node.setStatistic(parseStatistic(obj, "statistic"));
        if (obj.has("positiveName"))
            node.setPositiveName(obj.get("positiveName").getAsString());
        if (obj.has("negativeName"))
            node.setNegativeName(obj.get("negativeName").getAsString());
        if (obj.has("positiveColor"))
            node.setPositiveColor(ColorUtils.fromJsonArray(obj.getAsJsonArray("positiveColor")));
        if (obj.has("negativeColor"))
            node.setNegativeColor(ColorUtils.fromJsonArray(obj.getAsJsonArray("negativeColor")));
        if (obj.has("positiveChildren"))
            node.setPositiveChildren(deserializeNodeList(obj.getAsJsonArray("positiveChildren")));
        if (obj.has("negativeChildren"))
            node.setNegativeChildren(deserializeNodeList(obj.getAsJsonArray("negativeChildren")));

        return node;
    }

    private static QuadrantGate deserializeQuadrantNode(JsonObject obj,
                                                         double clipLow, double clipHigh, boolean excludeOutliers) throws IOException {
        QuadrantGate gate = new QuadrantGate();
        gate.setClipPercentileLow(clipLow);
        gate.setClipPercentileHigh(clipHigh);
        gate.setExcludeOutliers(excludeOutliers);

        gate.setChannelX(optString(obj, "channelX"));
        gate.setChannelY(optString(obj, "channelY"));
        if (obj.has("thresholdX"))
            gate.setThresholdX(obj.get("thresholdX").getAsDouble());
        if (obj.has("thresholdY"))
            gate.setThresholdY(obj.get("thresholdY").getAsDouble());
        gate.setCompartmentX(parseCompartment(obj, "compartmentX"));
        gate.setCompartmentY(parseCompartment(obj, "compartmentY"));
        gate.setStatisticX(parseStatistic(obj, "statisticX"));
        gate.setStatisticY(parseStatistic(obj, "statisticY"));

        if (obj.has("branches")) {
            JsonArray branches = obj.getAsJsonArray("branches");
            List<Branch> gateBranches = gate.getBranches();
            for (int i = 0; i < branches.size() && i < gateBranches.size(); i++) {
                JsonObject bo = branches.get(i).getAsJsonObject();
                Branch b = gateBranches.get(i);
                if (bo.has("name")) b.setName(bo.get("name").getAsString());
                if (bo.has("color")) b.setColor(ColorUtils.fromJsonArray(bo.getAsJsonArray("color")));
                if (bo.has("children")) b.setChildren(deserializeNodeList(bo.getAsJsonArray("children")));
            }
        }

        return gate;
    }

    private static Region2DGate deserialize2DNode(Region2DGate gate, JsonObject obj,
                                                double clipLow, double clipHigh, boolean excludeOutliers) throws IOException {
        gate.setClipPercentileLow(clipLow);
        gate.setClipPercentileHigh(clipHigh);
        gate.setExcludeOutliers(excludeOutliers);

        // Shared axis block for all 2D region gate types: channels and per-axis compartment/statistic.
        gate.setChannelX(optString(obj, "channelX"));
        gate.setChannelY(optString(obj, "channelY"));
        gate.setCompartmentX(parseCompartment(obj, "compartmentX"));
        gate.setCompartmentY(parseCompartment(obj, "compartmentY"));
        gate.setStatisticX(parseStatistic(obj, "statisticX"));
        gate.setStatisticY(parseStatistic(obj, "statisticY"));

        // A genuine per-type dispatch over Region2DGate's sealed permits: exhaustive with no
        // default, so a new region shape fails to compile here instead of silently loading
        // with none of its own fields set.
        switch (gate) {
            case PolygonGate pg -> {
                if (obj.has("vertices")) {
                    List<double[]> verts = new ArrayList<>();
                    for (JsonElement elem : obj.getAsJsonArray("vertices")) {
                        JsonArray pt = elem.getAsJsonArray();
                        verts.add(new double[]{pt.get(0).getAsDouble(), pt.get(1).getAsDouble()});
                    }
                    pg.setVertices(verts);
                }
            }
            case RectangleGate rg -> {
                if (obj.has("minX")) rg.setMinX(obj.get("minX").getAsDouble());
                if (obj.has("maxX")) rg.setMaxX(obj.get("maxX").getAsDouble());
                if (obj.has("minY")) rg.setMinY(obj.get("minY").getAsDouble());
                if (obj.has("maxY")) rg.setMaxY(obj.get("maxY").getAsDouble());
            }
            case EllipseGate eg -> {
                if (obj.has("centerX")) eg.setCenterX(obj.get("centerX").getAsDouble());
                if (obj.has("centerY")) eg.setCenterY(obj.get("centerY").getAsDouble());
                if (obj.has("radiusX")) eg.setRadiusX(obj.get("radiusX").getAsDouble());
                if (obj.has("radiusY")) eg.setRadiusY(obj.get("radiusY").getAsDouble());
            }
        }

        // Deserialize branches
        if (obj.has("branches")) {
            JsonArray branches = obj.getAsJsonArray("branches");
            List<Branch> gateBranches = gate.getBranches();
            for (int i = 0; i < branches.size() && i < gateBranches.size(); i++) {
                JsonObject bo = branches.get(i).getAsJsonObject();
                Branch b = gateBranches.get(i);
                if (bo.has("name")) b.setName(bo.get("name").getAsString());
                if (bo.has("color")) b.setColor(ColorUtils.fromJsonArray(bo.getAsJsonArray("color")));
                if (bo.has("children")) b.setChildren(deserializeNodeList(bo.getAsJsonArray("children")));
            }
        }

        return gate;
    }

}
