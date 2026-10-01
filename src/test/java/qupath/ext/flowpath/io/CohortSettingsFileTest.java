package qupath.ext.flowpath.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.flowpath.model.cohort.LogScale;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CohortSettingsFileTest {

    @Test
    void pathIsUnderTheProjectsFlowpathFolder(@TempDir Path dir) {
        assertEquals(dir.resolve("flowpath").resolve("cohort-settings.json"), CohortSettingsFile.pathFor(dir));
    }

    @Test
    void roundTrip(@TempDir Path dir) throws Exception {
        Path f = CohortSettingsFile.pathFor(dir);
        CohortSettingsFile.write(f, LogScale.LN1P);
        assertEquals(LogScale.LN1P, CohortSettingsFile.read(f));
        CohortSettingsFile.write(f, LogScale.LN);
        assertEquals(LogScale.LN, CohortSettingsFile.read(f));
    }

    @Test
    void missingFileIsLn(@TempDir Path dir) {
        assertEquals(LogScale.LN, CohortSettingsFile.read(CohortSettingsFile.pathFor(dir)));
    }

    @Test
    void garbageIsLn(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("s.json");
        Files.writeString(f, "{{ not json");
        assertEquals(LogScale.LN, CohortSettingsFile.read(f));
    }

    @Test
    void aWrittenTokenIsRead(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("s.json");
        Files.writeString(f, "{\"version\":1,\"logScale\":\"ln1p\"}");
        assertEquals(LogScale.LN1P, CohortSettingsFile.read(f));
    }
}
