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
import qupath.ext.flowpath.model.PolygonGate;
import qupath.ext.flowpath.model.QualityFilter;
import qupath.ext.flowpath.model.QuadrantGate;
import qupath.ext.flowpath.model.RectangleGate;
import qupath.ext.flowpath.model.Region2DGate;

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
import java.util.List;

/**
 * Serializes and deserializes {@link GateTree} instances to/from JSON files.
 */
public class FlowPathSerializer {

    // v2 adds per-channel compartment + statistic on threshold and quadrant gates.
    // v3 extends the same to the 2D region gates (polygon / rectangle / ellipse).
    // Older files load unchanged: a missing compartment defaults to whole-cell and a
    // missing statistic to mean, which is exactly how those gates used to behave.
    //
    // The "meta" block added alongside v3 deliberately did NOT bump this. It is pure
    // provenance -- nothing in it changes how a gate resolves -- so a file carrying it is
    // structurally a v3 file and an older FlowPath can still load it. Bumping would have
    // made those readers throw "Unsupported gate tree version" over a block they were
    // free to ignore.
    private static final int CURRENT_VERSION = 3;

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
        JsonObject root = new JsonObject();
        root.addProperty("version", CURRENT_VERSION);
        root.add("meta", serializeMeta(provenance));
        root.add("qualityFilter", serializeQualityFilter(tree.getQualityFilter()));
        root.addProperty("roiFilterEnabled", tree.isRoiFilterEnabled());
        root.add("gates", serializeNodeList(tree.getRoots()));

        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file, StandardCharsets.UTF_8))) {
            writer.write(gson.toJson(root));
        }
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
                 | UnsupportedOperationException e) {
            // UnsupportedOperationException is what Gson throws for a typed read of a
            // JSON null; catching it keeps every malformed-file path inside the
            // documented IOException contract.
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
        int version = root.has("version") ? root.get("version").getAsInt() : 1;
        if (version > CURRENT_VERSION) {
            throw new IOException("Unsupported gate tree version: " + version
                    + " (maximum supported: " + CURRENT_VERSION + ")");
        }

        GateTree tree = new GateTree();

        if (root.has("qualityFilter")) {
            tree.setQualityFilter(deserializeQualityFilter(root.getAsJsonObject("qualityFilter")));
        }

        if (root.has("roiFilterEnabled")) {
            tree.setRoiFilterEnabled(root.get("roiFilterEnabled").getAsBoolean());
        }

        if (root.has("gates")) {
            tree.setRoots(deserializeNodeList(root.getAsJsonArray("gates")));
        }

        return tree;
    }

    // -----------------------------------------------------------------------
    //  Quality filter
    // -----------------------------------------------------------------------

    /**
     * The quality filter, as one entry per constrained field.
     * <p>
     * The five legacy {@code minArea}/{@code maxArea}/... properties are still written, so
     * a file stays readable by an older FlowPath, and a {@code "ranges"} object carries
     * the full set including fields FlowPath has no name for. A reader that understands
     * only the legacy keys loses the extra constraints but reads the rest correctly, which
     * is the right failure: it under-filters visibly rather than mis-filtering silently.
     */
    private static JsonObject serializeQualityFilter(QualityFilter qf) {
        JsonObject obj = new JsonObject();
        obj.addProperty("minArea", qf.getMinArea());
        obj.addProperty("maxArea", qf.getMaxArea());
        obj.addProperty("minEccentricity", qf.getMinEccentricity());
        obj.addProperty("maxEccentricity", qf.getMaxEccentricity());
        obj.addProperty("minSolidity", qf.getMinSolidity());
        obj.addProperty("maxSolidity", qf.getMaxSolidity());
        obj.addProperty("minTotalIntensity", qf.getMinTotalIntensity());
        obj.addProperty("maxTotalIntensity", qf.getMaxTotalIntensity());
        obj.addProperty("minPerimeter", qf.getMinPerimeter());
        obj.addProperty("maxPerimeter", qf.getMaxPerimeter());

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

    private static QualityFilter deserializeQualityFilter(JsonObject obj) {
        QualityFilter qf = new QualityFilter();
        // Legacy properties first, so a v1..v3 file loads exactly as it did.
        if (obj.has("minArea")) qf.setMinArea(obj.get("minArea").getAsDouble());
        if (obj.has("maxArea")) qf.setMaxArea(obj.get("maxArea").getAsDouble());
        if (obj.has("minEccentricity")) qf.setMinEccentricity(obj.get("minEccentricity").getAsDouble());
        if (obj.has("maxEccentricity")) qf.setMaxEccentricity(obj.get("maxEccentricity").getAsDouble());
        if (obj.has("minSolidity")) qf.setMinSolidity(obj.get("minSolidity").getAsDouble());
        if (obj.has("maxSolidity")) qf.setMaxSolidity(obj.get("maxSolidity").getAsDouble());
        if (obj.has("minTotalIntensity")) qf.setMinTotalIntensity(obj.get("minTotalIntensity").getAsDouble());
        if (obj.has("maxTotalIntensity")) qf.setMaxTotalIntensity(obj.get("maxTotalIntensity").getAsDouble());
        if (obj.has("minPerimeter")) qf.setMinPerimeter(obj.get("minPerimeter").getAsDouble());
        if (obj.has("maxPerimeter")) qf.setMaxPerimeter(obj.get("maxPerimeter").getAsDouble());

        // Then the general form, which wins where both describe the same field: it is the
        // one that can express a constraint on a field FlowPath has no name for.
        if (obj.has("ranges") && obj.get("ranges").isJsonObject()) {
            for (var entry : obj.getAsJsonObject("ranges").entrySet()) {
                if (!entry.getValue().isJsonObject()) continue;
                JsonObject r = entry.getValue().getAsJsonObject();
                double lo = r.has("min") ? r.get("min").getAsDouble() : Double.NEGATIVE_INFINITY;
                double hi = r.has("max") ? r.get("max").getAsDouble() : Double.POSITIVE_INFINITY;
                qf.setRange(entry.getKey(), new QualityFilter.Range(lo, hi));
            }
        }
        // "hideFiltered" silently ignored for backward compat with v1 files
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
            obj.addProperty("thresholdIsZScore", qg.isThresholdIsZScore());
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
        } else {
            // Threshold gate (default) — backward-compatible format
            obj.addProperty("channel", node.getChannel());
            obj.addProperty("threshold", node.getThreshold());
            obj.addProperty("thresholdIsZScore", node.isThresholdIsZScore());
            obj.addProperty("compartment", node.getCompartment().name());
            obj.addProperty("statistic", node.getStatistic().token());
            obj.addProperty("positiveName", node.getPositiveName());
            obj.addProperty("negativeName", node.getNegativeName());
            obj.add("positiveColor", ColorUtils.toJsonArray(node.getPositiveColor()));
            obj.add("negativeColor", ColorUtils.toJsonArray(node.getNegativeColor()));
            obj.add("positiveChildren", serializeNodeList(node.getPositiveChildren()));
            obj.add("negativeChildren", serializeNodeList(node.getNegativeChildren()));
        }

        return obj;
    }

    /** Write the axis block shared by every 2D region gate (polygon / rectangle / ellipse). */
    private static void serializeRegionAxes(JsonObject obj, Region2DGate gate) {
        obj.addProperty("channelX", gate.getChannelX());
        obj.addProperty("channelY", gate.getChannelY());
        obj.addProperty("thresholdIsZScore", gate.isThresholdIsZScore());
        obj.addProperty("compartmentX", gate.getCompartmentX().name());
        obj.addProperty("compartmentY", gate.getCompartmentY().name());
        obj.addProperty("statisticX", gate.getStatisticX().token());
        obj.addProperty("statisticY", gate.getStatisticY().token());
    }

    private static List<GateNode> deserializeNodeList(JsonArray array) throws IOException {
        List<GateNode> nodes = new ArrayList<>();
        for (JsonElement elem : array) {
            nodes.add(deserializeNode(elem.getAsJsonObject()));
        }
        return nodes;
    }

    private static GateNode deserializeNode(JsonObject obj) throws IOException {
        String type = obj.has("type") ? obj.get("type").getAsString() : "threshold";

        // Shared fields
        boolean enabled = !obj.has("enabled") || obj.get("enabled").getAsBoolean();
        double clipLow = obj.has("clipPercentileLow") ? obj.get("clipPercentileLow").getAsDouble() : 1.0;
        double clipHigh = obj.has("clipPercentileHigh") ? obj.get("clipPercentileHigh").getAsDouble() : 99.0;
        boolean excludeOutliers = false;
        if (obj.has("excludeOutliers"))
            excludeOutliers = obj.get("excludeOutliers").getAsBoolean();
        else if (obj.has("hideOutliers"))
            excludeOutliers = obj.get("hideOutliers").getAsBoolean();

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
        return result;
    }

    /** Parse a Compartment enum from a property, defaulting to whole-cell (v1 / unknown). */
    /**
     * Parse a compartment from a property, accepting <b>either</b> spelling.
     * <p>
     * Compartments are written as the enum {@code name()} ({@code "NUCLEAR"}) while
     * statistics are written as their display {@code token()} ({@code "Median"}), so the
     * two sit adjacent in the same object in different dialects. The asymmetry is forced
     * rather than chosen: {@link Statistic} stopped being an enum when MIRAGE's vocabulary
     * opened up, so it has no {@code name()} left to write. Rather than break every
     * existing file to make the JSON symmetric, this reads both -- the enum name and the
     * measurement-key token ({@code "Nucleus"}) -- case-insensitively.
     * <p>
     * The bare {@code valueOf} inside {@code catch (Exception ignored)} that stood here
     * was the same defect {@link #parseStatistic} was just cured of: an unrecognised
     * compartment became {@code WHOLE_CELL}, so a gate pinned to a nuclear column silently
     * reloaded pointing at the whole cell -- a different population, no error, and a
     * number that still looks plausible. An unknown token now falls back only because
     * there is genuinely nothing else to do, and a <em>missing</em> property still means
     * a v1 file, whose gates really were whole-cell.
     */
    private static Compartment parseCompartment(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return Compartment.WHOLE_CELL;
        String raw;
        try {
            raw = obj.get(key).getAsString();
        } catch (Exception ignored) {
            // Not a string primitive -- malformed; fall back.
            return Compartment.WHOLE_CELL;
        }
        if (raw == null || raw.isBlank()) return Compartment.WHOLE_CELL;
        String trimmed = raw.trim();
        for (Compartment c : Compartment.known()) {
            if (c.name().equalsIgnoreCase(trimmed) || c.token().equalsIgnoreCase(trimmed)) {
                return c;
            }
        }
        // Not one of the three, but the file named something. Honour it rather than
        // silently substituting whole-cell: since the vocabulary opened, an unrecognised
        // compartment is a gate pinned to a real column FlowPath has not met, and turning
        // it into WHOLE_CELL would point that gate at a different population with no error.
        return Compartment.of(trimmed);
    }

    /**
     * Parse a statistic from a property.
     * <p>
     * <b>Absent means v1; present means honour it verbatim.</b> This used to be
     * {@code Statistic.valueOf(...)} inside a bare {@code catch (Exception ignored)}
     * falling through to {@link Statistic#MEAN}, which turned "I do not recognise this
     * statistic" into "it is a mean". Since {@link Statistic} became an open vocabulary
     * that is no longer a harmless default: a workspace saved against a MIRAGE export
     * carrying {@code CD3: Cell: REDSEA} would reload pinned to {@code Mean}, resolve to a
     * measurement key that is not in the file, and read NaN for every cell — plausible,
     * silent and wrong, exactly the class of defect {@code MeasuredColumn} exists to
     * prevent. An unrecognised token is now kept as itself.
     * <p>
     * Mean remains the answer only when the property is <em>missing</em>, which means a v1
     * workspace, whose bare column genuinely is the whole-cell mean.
     * <p>
     * Written as {@link Statistic#token} (e.g. {@code "Median"}) rather than the old enum
     * {@code name()} (e.g. {@code "MEDIAN"}); parsing is case-insensitive, so workspaces
     * written by either version load identically.
     */
    private static Statistic parseStatistic(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            try {
                Statistic parsed = Statistic.fromToken(obj.get(key).getAsString());
                if (parsed != null) return parsed;
            } catch (Exception ignored) {
                // Not a string primitive — fall through to the v1 default.
            }
        }
        return Statistic.MEAN;
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
        if (obj.has("thresholdIsZScore"))
            node.setThresholdIsZScore(obj.get("thresholdIsZScore").getAsBoolean());
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
        if (obj.has("thresholdIsZScore"))
            gate.setThresholdIsZScore(obj.get("thresholdIsZScore").getAsBoolean());
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

    private static GateNode deserialize2DNode(GateNode gate, JsonObject obj,
                                                double clipLow, double clipHigh, boolean excludeOutliers) throws IOException {
        gate.setClipPercentileLow(clipLow);
        gate.setClipPercentileHigh(clipHigh);
        gate.setExcludeOutliers(excludeOutliers);

        // Shared axis block for all 2D region gate types: channels, z-score flag,
        // and the per-axis compartment/statistic (absent in v1/v2 files, which then
        // default to whole-cell mean and behave exactly as before).
        if (gate instanceof Region2DGate region) {
            region.setChannelX(optString(obj, "channelX"));
            region.setChannelY(optString(obj, "channelY"));
            region.setCompartmentX(parseCompartment(obj, "compartmentX"));
            region.setCompartmentY(parseCompartment(obj, "compartmentY"));
            region.setStatisticX(parseStatistic(obj, "statisticX"));
            region.setStatisticY(parseStatistic(obj, "statisticY"));
        }
        if (obj.has("thresholdIsZScore"))
            gate.setThresholdIsZScore(obj.get("thresholdIsZScore").getAsBoolean());

        if (gate instanceof PolygonGate pg) {
            if (obj.has("vertices")) {
                List<double[]> verts = new ArrayList<>();
                for (JsonElement elem : obj.getAsJsonArray("vertices")) {
                    JsonArray pt = elem.getAsJsonArray();
                    verts.add(new double[]{pt.get(0).getAsDouble(), pt.get(1).getAsDouble()});
                }
                pg.setVertices(verts);
            }
        } else if (gate instanceof RectangleGate rg) {
            if (obj.has("minX")) rg.setMinX(obj.get("minX").getAsDouble());
            if (obj.has("maxX")) rg.setMaxX(obj.get("maxX").getAsDouble());
            if (obj.has("minY")) rg.setMinY(obj.get("minY").getAsDouble());
            if (obj.has("maxY")) rg.setMaxY(obj.get("maxY").getAsDouble());
        } else if (gate instanceof EllipseGate eg) {
            if (obj.has("centerX")) eg.setCenterX(obj.get("centerX").getAsDouble());
            if (obj.has("centerY")) eg.setCenterY(obj.get("centerY").getAsDouble());
            if (obj.has("radiusX")) eg.setRadiusX(obj.get("radiusX").getAsDouble());
            if (obj.has("radiusY")) eg.setRadiusY(obj.get("radiusY").getAsDouble());
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
