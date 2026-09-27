package qupath.ext.flowpath.io;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.flowpath.cohort.AlignmentModel;
import qupath.ext.flowpath.model.cohort.Landmarks;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * {@code <project>/flowpath/alignment-cache.json}: derived data, safe to delete, never in undo.
 * An unreadable file is an empty cache — the samples are simply re-aligned.
 */
public final class AlignmentCacheFile {

    private static final Logger logger = LoggerFactory.getLogger(AlignmentCacheFile.class);
    private static final int VERSION = 1;

    private AlignmentCacheFile() {}

    public static Path pathFor(Path projectDir) {
        return projectDir.resolve("flowpath").resolve("alignment-cache.json");
    }

    /** The cache stored at {@code file}, or an empty one when it is missing or unreadable (logged). */
    public static AlignmentModel.Cache read(Path file) {
        if (!Files.isRegularFile(file)) return AlignmentModel.Cache.empty();
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            Map<String, Double> cofactors = new HashMap<>();
            root.getAsJsonObject("cofactors").entrySet()
                    .forEach(e -> cofactors.put(e.getKey(), e.getValue().getAsDouble()));
            Map<String, AlignmentModel.SlideEntry> slides = new HashMap<>();
            for (var slide : root.getAsJsonObject("slides").entrySet()) {
                JsonObject s = slide.getValue().getAsJsonObject();
                Map<String, Landmarks> columns = new HashMap<>();
                for (var col : s.getAsJsonObject("columns").entrySet()) {
                    JsonObject c = col.getValue().getAsJsonObject();
                    double cofactor = cofactors.getOrDefault(col.getKey(), 1.0);
                    columns.put(col.getKey(), new Landmarks(cofactor, optDouble(c, "l1"), optDouble(c, "l2")));
                }
                slides.put(slide.getKey(), new AlignmentModel.SlideEntry(s.get("fingerprint").getAsString(), columns));
            }
            return new AlignmentModel.Cache(cofactors, slides);
        } catch (IOException | RuntimeException e) {
            logger.warn("Ignoring unreadable alignment cache {}; slides will be re-aligned", file, e);
            return AlignmentModel.Cache.empty();
        }
    }

    /** Writes {@code cache} to {@code file}, creating the {@code flowpath} directory if needed. */
    public static void write(Path file, AlignmentModel.Cache cache) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        JsonObject cofactors = new JsonObject();
        cache.cofactors().forEach(cofactors::addProperty);
        root.add("cofactors", cofactors);
        JsonObject slides = new JsonObject();
        cache.slides().forEach((id, entry) -> {
            JsonObject s = new JsonObject();
            s.addProperty("fingerprint", entry.fingerprint());
            JsonObject columns = new JsonObject();
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
