package qupath.ext.flowpath.io;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.flowpath.cohort.AlignmentModel;
import qupath.ext.flowpath.model.cohort.Landmarks;
import qupath.ext.flowpath.model.cohort.LogScale;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;

/**
 * {@code <project>/flowpath/alignment-cache.json}: derived data, safe to delete, never in undo.
 * An unreadable file is an empty cache — the samples are simply re-aligned.
 */
public final class AlignmentCacheFile {

    private static final Logger logger = LoggerFactory.getLogger(AlignmentCacheFile.class);
    private static final int VERSION = 2;
    private static final String SAMPLED_CELLS = "sampledCellsPerSlide";

    private AlignmentCacheFile() {}

    public static Path pathFor(Path projectDir) {
        return projectDir.resolve("flowpath").resolve("alignment-cache.json");
    }

    /** The cache stored at {@code file}, or an empty one when it is missing or unreadable (logged). */
    public static AlignmentModel.Cache read(Path file) {
        if (!Files.isRegularFile(file)) return AlignmentModel.Cache.empty();
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!root.has("version") || root.get("version").getAsInt() != VERSION) {
                logger.info("alignment cache from an earlier FlowPath ignored; slides will be re-aligned");
                return AlignmentModel.Cache.empty();
            }
            Map<String, AlignmentModel.SlideEntry> slides = new HashMap<>();
            for (var slide : root.getAsJsonObject("slides").entrySet()) {
                JsonObject s = slide.getValue().getAsJsonObject();
                LogScale scale = LogScale.ofToken(s.has("scale") ? s.get("scale").getAsString() : null);
                Map<String, Landmarks> columns = new HashMap<>();
                for (var col : s.getAsJsonObject("columns").entrySet()) {
                    JsonObject c = col.getValue().getAsJsonObject();
                    columns.put(col.getKey(), new Landmarks(scale, optDouble(c, "l1"), optDouble(c, "l2")));
                }
                slides.put(slide.getKey(), new AlignmentModel.SlideEntry(s.get("fingerprint").getAsString(), columns));
            }
            return new AlignmentModel.Cache(slides);
        } catch (IOException | RuntimeException e) {
            logger.warn("Ignoring unreadable alignment cache {}; slides will be re-aligned", file, e);
            return AlignmentModel.Cache.empty();
        }
    }

    /**
     * The cells-per-slide sample size the cache at {@code file} was built from, when it records
     * one. A headless run samples with it, so it reproduces the alignments the GUI showed rather
     * than whatever the machine it runs on has in its preferences.
     */
    public static OptionalInt sampledCellsPerSlide(Path file) {
        if (!Files.isRegularFile(file)) return OptionalInt.empty();
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            return root.has(SAMPLED_CELLS) ? OptionalInt.of(root.get(SAMPLED_CELLS).getAsInt()) : OptionalInt.empty();
        } catch (IOException | RuntimeException e) {
            logger.warn("Ignoring unreadable alignment cache {}", file, e);
            return OptionalInt.empty();
        }
    }

    /**
     * Writes {@code cache} to {@code file}, creating the {@code flowpath} directory if needed,
     * recording the sample size its landmarks were found from and the scale each
     * slide's landmarks are in. An empty cache is never written: it holds nothing worth
     * keeping, and over an existing file it would throw away every landmark.
     */
    public static void write(Path file, AlignmentModel.Cache cache, int sampledCellsPerSlide) throws IOException {
        if (cache.isEmpty()) return;
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        root.addProperty(SAMPLED_CELLS, sampledCellsPerSlide);
        JsonObject slides = new JsonObject();
        cache.slides().forEach((id, entry) -> {
            JsonObject s = new JsonObject();
            s.addProperty("fingerprint", entry.fingerprint());
            JsonObject columns = new JsonObject();
            LogScale scale = entry.columns().values().stream().findFirst().map(Landmarks::scale).orElse(LogScale.LN);
            s.addProperty("scale", scale.token());
            entry.columns().forEach((key, lm) -> {
                JsonObject c = new JsonObject();
                if (lm.hasL1()) c.addProperty("l1", lm.l1());
                if (lm.hasL2()) c.addProperty("l2", lm.l2());
                columns.add(key, c);
            });
            s.add("columns", columns);
            slides.add(id, s);
        });
        root.add("slides", slides);
        Files.createDirectories(file.getParent());
        Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(root), StandardCharsets.UTF_8);
    }

    private static double optDouble(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsDouble() : Double.NaN;
    }
}
