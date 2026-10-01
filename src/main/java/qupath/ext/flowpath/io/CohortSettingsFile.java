package qupath.ext.flowpath.io;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.flowpath.model.cohort.LogScale;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code <project>/flowpath/cohort-settings.json}: the project's log scale for cohort alignment,
 * so a headless run uses the one the GUI did. A missing or unreadable file is {@link LogScale#LN}
 * (logged), never an error.
 */
public final class CohortSettingsFile {

    private static final Logger logger = LoggerFactory.getLogger(CohortSettingsFile.class);
    private static final int VERSION = 1;

    private CohortSettingsFile() {}

    public static Path pathFor(Path projectDir) {
        return projectDir.resolve("flowpath").resolve("cohort-settings.json");
    }

    public static LogScale read(Path file) {
        if (!Files.isRegularFile(file)) return LogScale.LN;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            return LogScale.ofToken(root.has("logScale") ? root.get("logScale").getAsString() : null);
        } catch (IOException | RuntimeException e) {
            logger.warn("Ignoring unreadable cohort settings {}; using the ln scale", file, e);
            return LogScale.LN;
        }
    }

    public static void write(Path file, LogScale scale) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        root.addProperty("logScale", scale.token());
        Files.createDirectories(file.getParent());
        Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(root), StandardCharsets.UTF_8);
    }
}
