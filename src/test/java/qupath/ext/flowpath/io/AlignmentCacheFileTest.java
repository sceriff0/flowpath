package qupath.ext.flowpath.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.flowpath.cohort.AlignmentModel;
import qupath.ext.flowpath.model.cohort.Landmarks;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AlignmentCacheFileTest {

    @Test
    void roundTripsIncludingAnAbsentLandmark(@TempDir Path project) throws Exception {
        Path file = AlignmentCacheFile.pathFor(project);
        assertEquals(project.resolve("flowpath").resolve("alignment-cache.json"), file);
        AlignmentModel.Cache cache = new AlignmentModel.Cache(Map.of("CD8: Cell: Median", 123.5),
                Map.of("a3f", new AlignmentModel.SlideEntry("fp", Map.of("CD8: Cell: Median",
                        new Landmarks(123.5, 1.25, Double.NaN)))));
        AlignmentCacheFile.write(file, cache, 5000);
        AlignmentModel.Cache back = AlignmentCacheFile.read(file);
        assertEquals(123.5, back.cofactors().get("CD8: Cell: Median"));
        Landmarks lm = back.slides().get("a3f").columns().get("CD8: Cell: Median");
        assertEquals(1.25, lm.l1());
        assertFalse(lm.hasL2());
        assertEquals("fp", back.slides().get("a3f").fingerprint());
        assertEquals(java.util.OptionalInt.of(5000), AlignmentCacheFile.sampledCellsPerSlide(file),
                "the sample size the landmarks were found from is recorded");
    }

    @Test
    void aMissingOrCorruptFileIsAnEmptyCache(@TempDir Path project) throws Exception {
        Path file = AlignmentCacheFile.pathFor(project);
        assertTrue(AlignmentCacheFile.read(file).slides().isEmpty());
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{not json");
        assertTrue(AlignmentCacheFile.read(file).cofactors().isEmpty());
        assertTrue(AlignmentCacheFile.sampledCellsPerSlide(file).isEmpty());
    }

    /** An empty cache never overwrites landmarks and fixed cofactors already on disk. */
    @Test
    void anEmptyCacheNeverOverwritesAStoredOne(@TempDir Path project) throws Exception {
        Path file = AlignmentCacheFile.pathFor(project);
        AlignmentModel.Cache cache = new AlignmentModel.Cache(Map.of("CD8", 42.0), Map.of());
        AlignmentCacheFile.write(file, cache, 5000);
        AlignmentCacheFile.write(file, AlignmentModel.Cache.empty(), 1);
        assertEquals(42.0, AlignmentCacheFile.read(file).cofactors().get("CD8"));

        Path fresh = AlignmentCacheFile.pathFor(project.resolve("other"));
        AlignmentCacheFile.write(fresh, AlignmentModel.Cache.empty(), 1);
        assertFalse(Files.exists(fresh), "nothing worth keeping is not written");
    }
}
