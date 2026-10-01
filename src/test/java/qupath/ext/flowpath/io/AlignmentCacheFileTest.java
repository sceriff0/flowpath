package qupath.ext.flowpath.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.flowpath.cohort.AlignmentModel;
import qupath.ext.flowpath.model.cohort.Landmarks;
import qupath.ext.flowpath.model.cohort.LogScale;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AlignmentCacheFileTest {

    @Test
    void roundTripsIncludingAnAbsentLandmark(@TempDir Path project) throws Exception {
        Path file = AlignmentCacheFile.pathFor(project);
        assertEquals(project.resolve("flowpath").resolve("alignment-cache.json"), file);
        AlignmentModel.Cache cache = new AlignmentModel.Cache(
                Map.of("a3f", new AlignmentModel.SlideEntry("fp", Map.of("CD8: Cell: Median",
                        new Landmarks(LogScale.LN, 1.25, Double.NaN)))));
        AlignmentCacheFile.write(file, cache, 5000);
        AlignmentModel.Cache back = AlignmentCacheFile.read(file);
        Landmarks lm = back.slides().get("a3f").columns().get("CD8: Cell: Median");
        assertEquals(1.25, lm.l1());
        assertEquals(LogScale.LN, lm.scale());
        assertFalse(lm.hasL2());
        assertEquals("fp", back.slides().get("a3f").fingerprint());
        assertEquals(java.util.OptionalInt.of(5000), AlignmentCacheFile.sampledCellsPerSlide(file),
                "the sample size the landmarks were found from is recorded");
        assertTrue(Files.readString(file).contains("\"version\": 2"));
        assertTrue(Files.readString(file).contains("\"scale\": \"ln\""));
    }

    @Test
    void theScaleRoundTripsPerSlide(@TempDir Path project) throws Exception {
        Path file = AlignmentCacheFile.pathFor(project);
        AlignmentModel.Cache cache = new AlignmentModel.Cache(
                Map.of("now", new AlignmentModel.SlideEntry("f1", Map.of("CD8", new Landmarks(LogScale.LN1P, 1.0, 3.0)))));
        AlignmentCacheFile.write(file, cache, 5000);
        assertEquals(LogScale.LN1P, AlignmentCacheFile.read(file).slides().get("now").columns().get("CD8").scale());
    }

    /** An earlier FlowPath's cache (version 1, asinh landmarks) is ignored, never converted. */
    @Test
    void anEarlierVersionIsAnEmptyCache(@TempDir Path project) throws Exception {
        Path file = AlignmentCacheFile.pathFor(project);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"version\":1,\"cofactors\":{\"CD8\":77.0},"
                + "\"slides\":{\"a\":{\"fingerprint\":\"f\",\"columns\":{\"CD8\":{\"l1\":1.0}}}}}");
        assertTrue(AlignmentCacheFile.read(file).isEmpty());
        Files.writeString(file, "{\"slides\":{}}");
        assertTrue(AlignmentCacheFile.read(file).isEmpty(), "no version: ignored");
    }

    @Test
    void aMissingOrCorruptFileIsAnEmptyCache(@TempDir Path project) throws Exception {
        Path file = AlignmentCacheFile.pathFor(project);
        assertTrue(AlignmentCacheFile.read(file).slides().isEmpty());
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{not json");
        assertTrue(AlignmentCacheFile.read(file).isEmpty());
        assertTrue(AlignmentCacheFile.sampledCellsPerSlide(file).isEmpty());
    }

    /** An empty cache never overwrites landmarks already on disk. */
    @Test
    void anEmptyCacheNeverOverwritesAStoredOne(@TempDir Path project) throws Exception {
        Path file = AlignmentCacheFile.pathFor(project);
        AlignmentModel.Cache cache = new AlignmentModel.Cache(
                Map.of("a", new AlignmentModel.SlideEntry("f", Map.of("CD8", new Landmarks(LogScale.LN, 1.0, 2.0)))));
        AlignmentCacheFile.write(file, cache, 5000);
        AlignmentCacheFile.write(file, AlignmentModel.Cache.empty(), 1);
        assertEquals(1.0, AlignmentCacheFile.read(file).slides().get("a").columns().get("CD8").l1());

        Path fresh = AlignmentCacheFile.pathFor(project.resolve("other"));
        AlignmentCacheFile.write(fresh, AlignmentModel.Cache.empty(), 1);
        assertFalse(Files.exists(fresh), "nothing worth keeping is not written");
    }
}
