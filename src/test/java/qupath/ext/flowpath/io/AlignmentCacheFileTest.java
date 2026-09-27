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
        AlignmentCacheFile.write(file, cache);
        AlignmentModel.Cache back = AlignmentCacheFile.read(file);
        assertEquals(123.5, back.cofactors().get("CD8: Cell: Median"));
        Landmarks lm = back.slides().get("a3f").columns().get("CD8: Cell: Median");
        assertEquals(1.25, lm.l1());
        assertFalse(lm.hasL2());
        assertEquals("fp", back.slides().get("a3f").fingerprint());
    }

    @Test
    void aMissingOrCorruptFileIsAnEmptyCache(@TempDir Path project) throws Exception {
        Path file = AlignmentCacheFile.pathFor(project);
        assertTrue(AlignmentCacheFile.read(file).slides().isEmpty());
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{not json");
        assertTrue(AlignmentCacheFile.read(file).cofactors().isEmpty());
    }
}
