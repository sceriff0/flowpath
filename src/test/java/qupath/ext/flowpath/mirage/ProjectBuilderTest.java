package qupath.ext.flowpath.mirage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.flowpath.testing.InMemoryImageServerBuilder;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathCellObject;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectIO;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;

import static org.junit.jupiter.api.Assertions.*;

/** A real QuPath project on disk, fed MIRAGE-shaped GeoJSON (bin/export_geojson.py's build_feature). */
class ProjectBuilderTest {

    /** Any path opens as a blank in-memory image; no thumbnail. */
    static final ProjectBuilder BUILDER = new ProjectBuilder(
            p -> InMemoryImageServerBuilder.serverBuilder(p.toUri()), server -> null);

    /**
     * {@code n} cells as MIRAGE writes them: a whole-cell square, a top-level
     * {@code nucleusGeometry} when {@code withNucleus}, and QuPath's measurement list.
     */
    static String mirageGeojson(int n, boolean withNucleus) {
        StringJoiner features = new StringJoiner(",");
        for (int i = 0; i < n; i++) {
            double x = 10 + 20 * i;
            String cell = square(x, 10, 8);
            String nucleus = withNucleus ? ",\"nucleusGeometry\":" + square(x, 10, 3) : "";
            features.add("{\"type\":\"Feature\",\"geometry\":" + cell + nucleus
                    + ",\"properties\":{\"objectType\":\"cell\",\"classification\":{\"name\":\"Cell\",\"colorRGB\":-16711936},"
                    + "\"isLocked\":false,\"measurements\":[{\"name\":\"label\",\"value\":" + (i + 1) + "},"
                    + "{\"name\":\"CD3\",\"value\":" + i + "},{\"name\":\"CD3: Cell: Median\",\"value\":" + i + "}]}}");
        }
        return "{\"type\":\"FeatureCollection\",\"features\":[" + features + "]}";
    }

    private static String square(double cx, double cy, double r) {
        return String.format(java.util.Locale.US,
                "{\"type\":\"Polygon\",\"coordinates\":[[[%1$.1f,%2$.1f],[%3$.1f,%2$.1f],[%3$.1f,%4$.1f],[%1$.1f,%4$.1f],[%1$.1f,%2$.1f]]]}",
                cx - r, cy - r, cx + r, cy + r);
    }

    static MirageRun.Patient patient(Path dir, String id, String geojson) throws IOException {
        Path pyramid = dir.resolve(id + ".ome.tiff");
        Files.writeString(pyramid, "");
        Path cells = dir.resolve(id + "_cells.geojson");
        Files.writeString(cells, geojson);
        return new MirageRun.Patient(id, pyramid, cells, MirageRun.Status.READY, null);
    }

    static Project<BufferedImage> reload(Project<BufferedImage> project) throws IOException {
        return ProjectIO.loadProject(project.getPath().toFile(), BufferedImage.class);
    }

    static Collection<PathObject> savedCells(ProjectImageEntry<BufferedImage> entry) throws IOException {
        return entry.readHierarchy().getDetectionObjects();
    }

    @Test
    void aPatientIsAddedNamedTypedAndItsCellsSaved(@TempDir Path dir) throws Exception {
        Project<BufferedImage> project = ProjectBuilder.openOrCreate(dir.resolve("qupath_project"));
        int added = BUILDER.addPatient(project, patient(dir, "P1", mirageGeojson(5, true)));

        assertEquals(5, added);
        Project<BufferedImage> onDisk = reload(project);
        assertEquals(List.of("P1"), onDisk.getImageList().stream().map(ProjectImageEntry::getImageName).toList());
        ProjectImageEntry<BufferedImage> entry = onDisk.getImageList().get(0);
        ImageData<BufferedImage> data = entry.readImageData();
        assertEquals(ImageData.ImageType.FLUORESCENCE, data.getImageType());
        Collection<PathObject> cells = savedCells(entry);
        assertEquals(5, cells.size());
        PathObject first = cells.iterator().next();
        assertInstanceOf(PathCellObject.class, first);
        assertNotNull(((PathCellObject) first).getNucleusROI(), "cells.geojson carries the nucleus outline");
        assertTrue(first.getMeasurementList().containsKey("CD3: Cell: Median"));
    }

    /** cells_wholecell.geojson is the same cells without nucleusGeometry: cells, no nucleus. */
    @Test
    void theWholeCellFileGivesCellsWithoutANucleus(@TempDir Path dir) throws Exception {
        Project<BufferedImage> project = ProjectBuilder.openOrCreate(dir.resolve("qupath_project"));
        BUILDER.addPatient(project, patient(dir, "P1", mirageGeojson(3, false)));

        PathObject first = savedCells(reload(project).getImageList().get(0)).iterator().next();
        assertInstanceOf(PathCellObject.class, first);
        assertNull(((PathCellObject) first).getNucleusROI());
    }

    /** No image without its cells: cohort gating would show it as a slide with no detections. */
    @Test
    void aPatientWhoseCellsCannotBeReadLeavesNoEntry(@TempDir Path dir) throws Exception {
        Project<BufferedImage> project = ProjectBuilder.openOrCreate(dir.resolve("qupath_project"));
        BUILDER.addPatient(project, patient(dir, "P1", mirageGeojson(2, true)));

        assertThrows(IOException.class, () -> BUILDER.addPatient(project, patient(dir, "P2", "{not json")));

        assertEquals(List.of("P1"), project.getImageList().stream().map(ProjectImageEntry::getImageName).toList());
        assertEquals(List.of("P1"), reload(project).getImageList().stream().map(ProjectImageEntry::getImageName).toList());
    }

    @Test
    void aGeojsonWithNoCellsIsAFailureNotAnEmptySlide(@TempDir Path dir) throws Exception {
        Project<BufferedImage> project = ProjectBuilder.openOrCreate(dir.resolve("qupath_project"));
        IOException e = assertThrows(IOException.class,
                () -> BUILDER.addPatient(project, patient(dir, "P1", mirageGeojson(0, true))));
        assertTrue(e.getMessage().contains("no cells"), e.getMessage());
        assertTrue(reload(project).getImageList().isEmpty());
    }

    @Test
    void anImageThatCannotBeOpenedLeavesNoEntry(@TempDir Path dir) throws Exception {
        ProjectBuilder refusing = new ProjectBuilder(p -> { throw new IOException("no reader for " + p); }, s -> null);
        Project<BufferedImage> project = ProjectBuilder.openOrCreate(dir.resolve("qupath_project"));
        assertThrows(IOException.class, () -> refusing.addPatient(project, patient(dir, "P1", mirageGeojson(2, true))));
        assertTrue(reload(project).getImageList().isEmpty());
    }

    @Test
    void aThumbnailThatFailsDoesNotFailThePatient(@TempDir Path dir) throws Exception {
        ProjectBuilder noThumbs = new ProjectBuilder(p -> InMemoryImageServerBuilder.serverBuilder(p.toUri()),
                s -> { throw new IOException("no thumbnail"); });
        Project<BufferedImage> project = ProjectBuilder.openOrCreate(dir.resolve("qupath_project"));
        assertEquals(2, noThumbs.addPatient(project, patient(dir, "P1", mirageGeojson(2, true))));
        assertEquals(1, reload(project).getImageList().size());
    }

    @Test
    void anExistingProjectIsOpenedAndItsImageNamesKnown(@TempDir Path dir) throws Exception {
        Path projectDir = dir.resolve("qupath_project");
        BUILDER.addPatient(ProjectBuilder.openOrCreate(projectDir), patient(dir, "P1", mirageGeojson(2, true)));

        Project<BufferedImage> again = ProjectBuilder.openOrCreate(projectDir);
        assertEquals(Set.of("P1"), ProjectBuilder.imageNames(again));
    }

    @Test
    void anEmptyExistingFolderGetsANewProject(@TempDir Path dir) throws Exception {
        Path projectDir = Files.createDirectories(dir.resolve("empty"));
        Project<BufferedImage> project = ProjectBuilder.openOrCreate(projectDir);
        assertTrue(project.getImageList().isEmpty());
        assertTrue(Files.isRegularFile(projectDir.resolve(ProjectBuilder.PROJECT_FILE)));
    }

    @Test
    void aNonEmptyFolderWithoutAProjectIsRefused(@TempDir Path dir) throws Exception {
        Path projectDir = Files.createDirectories(dir.resolve("busy"));
        Files.writeString(projectDir.resolve("notes.txt"), "mine");
        IOException e = assertThrows(IOException.class, () -> ProjectBuilder.openOrCreate(projectDir));
        assertTrue(e.getMessage().contains("not empty"), e.getMessage());
    }

    /** The dialog's preview reads names without creating anything. */
    @Test
    void existingNamesAreReadWithoutCreatingAProject(@TempDir Path dir) throws Exception {
        Path none = dir.resolve("none");
        assertEquals(Set.of(), ProjectBuilder.existingImageNames(none));
        assertFalse(Files.exists(none), "nothing created");

        Path projectDir = dir.resolve("qupath_project");
        BUILDER.addPatient(ProjectBuilder.openOrCreate(projectDir), patient(dir, "P1", mirageGeojson(1, true)));
        assertEquals(Set.of("P1"), ProjectBuilder.existingImageNames(projectDir));
    }
}
