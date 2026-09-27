package qupath.ext.flowpath.batch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.flowpath.io.AlignmentCacheFile;
import qupath.ext.flowpath.io.FlowPathSerializer;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.testing.Cells;
import qupath.ext.flowpath.testing.GateTreeFixtures;
import qupath.ext.flowpath.testing.InMemoryImageServerBuilder;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.projects.Projects;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.*;

/** The public entry points, on a real QuPath {@link Project} on disk: what a Groovy script calls. */
class FlowPathBatchProjectTest {

    /** A project of three images, a, b and c, each with 200 detections saved into its data file. */
    static Project<BufferedImage> project(Path dir) throws Exception {
        Project<BufferedImage> project = Projects.createProject(dir.resolve("project").toFile(), BufferedImage.class);
        for (String name : List.of("a", "b", "c")) {
            ProjectImageEntry<BufferedImage> entry =
                    project.addImage(InMemoryImageServerBuilder.serverBuilder(dir.resolve(name + ".tif").toUri()));
            entry.setImageName(name + ".tif");
            ImageData<BufferedImage> data = entry.readImageData();
            data.getHierarchy().addObjects(Cells.of(200).atGrid(10, 10).marker("CD3", i -> i)
                    .marker("CD8", i -> 2.0 * i).area(100.0).detections());
            entry.saveImageData(data);
        }
        project.syncChanges();
        return project;
    }

    static File treeFile(Path dir, Project<BufferedImage> project) throws Exception {
        GateTree tree = GateTreeFixtures.twoRootsOnCd3AndCd8(100, 150);
        tree.setReferenceSlideId(project.getImageList().get(0).getID());
        File file = dir.resolve("tree.json").toFile();
        FlowPathSerializer.save(tree, file);
        return file;
    }

    static boolean classified(ProjectImageEntry<BufferedImage> entry) throws Exception {
        return entry.readHierarchy().getDetectionObjects().stream().map(PathObject::getPathClass).allMatch(c -> c != null);
    }

    static List<String> runInfo(File out) throws Exception {
        return Files.readAllLines(out.toPath().resolve(FlowPathBatch.RUN_INFO));
    }

    @Test
    void theScriptEntryPointGatesWritesBackAndRecordsTheSampleSizeInTheCache(@TempDir Path dir) throws Exception {
        Project<BufferedImage> project = project(dir);
        File out = dir.resolve("out").toFile();
        FlowPathBatch.Run run = FlowPathBatch.run(project, treeFile(dir, project), out, 50);

        assertEquals(3, run.slides().size());
        assertTrue(run.slides().stream().allMatch(r -> r.result().succeeded()));
        for (ProjectImageEntry<BufferedImage> e : project.getImageList()) assertTrue(classified(e), e.getImageName());
        for (String f : List.of("flowpath.json", "gating_manifest.csv", "qc_summary.csv", "run_info.txt",
                "batch_populations.csv", RunState.FILE)) {
            assertTrue(new File(out, f).isFile(), f);
        }
        assertTrue(runInfo(out).contains("sample_size_source=argument"), runInfo(out).toString());
        assertTrue(runInfo(out).contains("reference_slide_name=a.tif"), runInfo(out).toString());
        Path cache = AlignmentCacheFile.pathFor(project.getPath().getParent());
        assertEquals(OptionalInt.of(50), AlignmentCacheFile.sampledCellsPerSlide(cache));
    }

    /** I2: with no argument, the sample size the project's cache records (the GUI's) is used, not a preference. */
    @Test
    void theRecordedSampleSizeIsUsedAndItsCacheReused(@TempDir Path dir) throws Exception {
        Project<BufferedImage> project = project(dir);
        File tree = treeFile(dir, project);
        FlowPathBatch.run(project, tree, dir.resolve("seed").toFile(), 60);   // as the GUI would have: 60 cells

        File out = dir.resolve("out").toFile();
        FlowPathBatch.run(project, tree, out);
        List<String> info = runInfo(out);
        assertTrue(info.contains("sampled_cells_per_slide=60"), info.toString());
        assertTrue(info.contains("sample_size_source=alignment-cache"), info.toString());
        assertTrue(info.contains("alignment_cache_hits=3/3"), info.toString());
    }

    /** I6: from the script editor, the image open in the viewer is gated but its data file is not written. */
    @Test
    void theOpenImageIsNeverWrittenBehindQuPathsBack(@TempDir Path dir) throws Exception {
        Project<BufferedImage> project = project(dir);
        ProjectImageEntry<BufferedImage> open = project.getImageList().get(1);
        File out = dir.resolve("out").toFile();
        FlowPathBatch.Run run = FlowPathBatch.run(project, treeFile(dir, project), out, open.getID());

        assertEquals(BatchResult.WriteBack.SKIPPED_OPEN_SLIDE, run.slides().get(1).result().writeBack());
        assertFalse(classified(open), "the open image's data file is untouched");
        assertTrue(classified(project.getImageList().get(0)));
        assertTrue(new File(out, "b.tif" + FlowPathBatch.PHENO_SUFFIX).isFile(), "but it is gated");
    }

    /** Refusals reach the script: a tree from another project fails fast. */
    @Test
    void aForeignTreeIsRefusedAtTheEntryPoint(@TempDir Path dir) throws Exception {
        Project<BufferedImage> project = project(dir);
        GateTree tree = GateTreeFixtures.twoRootsOnCd3AndCd8(100, 150);
        tree.setSlideNames(Map.of(project.getImageList().get(0).getID(), "not-in-this-project.tif"));
        File file = dir.resolve("foreign.json").toFile();
        FlowPathSerializer.save(tree, file);
        assertThrows(IllegalStateException.class, () -> FlowPathBatch.run(project, file, dir.resolve("out").toFile()));
        assertTrue(project.getImageList().stream().noneMatch(e -> {
            try { return classified(e); } catch (Exception ex) { throw new AssertionError(ex); }
        }));
    }
}
