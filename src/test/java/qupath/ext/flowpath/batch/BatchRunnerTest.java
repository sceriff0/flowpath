package qupath.ext.flowpath.batch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.flowpath.engine.AlignmentLookup;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.PopulationStats;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.model.cohort.Alignment;
import qupath.ext.flowpath.model.cohort.Landmarks;
import qupath.ext.flowpath.testing.Cells;
import qupath.ext.flowpath.testing.GateTreeFixtures;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.WrappedBufferedImageServer;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class BatchRunnerTest {

    static final Alignment SHIFT = Alignment.between(new Landmarks(1.0, 1.0, Double.NaN), new Landmarks(1.0, 1.3, Double.NaN));

    static BatchSlide slide(String id, Cells cells, List<String> saved) {
        return new BatchSlide() {
            final ImageData<BufferedImage> data = new ImageData<>(new WrappedBufferedImageServer(id,
                    new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB)));
            { data.getHierarchy().addObjects(cells.detections()); }
            @Override public String id() { return id; }
            @Override public String name() { return id + ".tif"; }
            @Override public ImageData<BufferedImage> read() { return data; }
            @Override public void save(ImageData<BufferedImage> d) { saved.add(id); }
        };
    }

    static Cells cells() {
        return Cells.of(20).atGrid(10, 10).marker("CD3", i -> i).marker("CD8", i -> 2.0 * i).area(100.0);
    }

    /** CD3 (0), CD8 (1), a second CD3 root (2) with correction off. */
    static GateTree tree() {
        GateTree tree = GateTreeFixtures.twoRootsOnCd3AndCd8(10.0, 20.0);
        GateNode second = new GateNode("CD3", 10.0);
        second.setStatistic(Statistic.MEAN);
        second.setCorrectStaining(false);
        tree.addRoot(second);
        tree.setReferenceSlideId("ref");
        return tree;
    }

    static BatchRunner.Settings settings(GateTree tree, File out) {
        AlignmentLookup lookup = (slide, column) -> "s1".equals(slide) && "CD3".equals(column) ? SHIFT : null;
        return new BatchRunner.Settings(tree, lookup, out, null, false);
    }

    static int count(BatchResult r, int rootIndex, String branch) {
        return r.stats().rows(PopulationStats.Scope.WHOLE_SLIDE).stream()
                .filter(row -> row.rootIndex() == rootIndex && row.branchName().equals(branch))
                .findFirst().orElseThrow().count();
    }

    @Test
    void eachSlideIsGatedWithItsOwnAppliedValuesAndTwoSameChannelRootsStayApart(@TempDir Path dir) {
        List<BatchResult> results = BatchRunner.run(
                List.of(slide("ref", cells(), new ArrayList<>()), slide("s1", cells(), new ArrayList<>())),
                settings(tree(), dir.toFile()), (i, n) -> {}, () -> false);

        assertEquals(10, count(results.get(0), 0, "CD3+"), "reference: CD3 >= 10 is cells 10..19");
        long corrected = java.util.stream.IntStream.range(0, 20).filter(i -> i >= SHIFT.apply(10.0)).count();
        assertNotEquals(10, corrected, "fixture check");
        assertEquals(corrected, count(results.get(1), 0, "CD3+"), "s1: root 0 corrected");
        assertEquals(10, count(results.get(1), 2, "CD3+"), "s1: root 2 on the same channel, correction off");
        assertTrue(new File(dir.toFile(), "s1.tif_gate_pheno.csv").isFile());
    }

    @Test
    void theCallersTreeIsNeverMutated(@TempDir Path dir) {
        GateTree tree = tree();
        tree.getRoots().get(0).getBranches().get(0).setCount(999);
        BatchRunner.run(List.of(slide("s1", cells(), new ArrayList<>())), settings(tree, dir.toFile()),
                (i, n) -> {}, () -> false);
        assertEquals(999, tree.getRoots().get(0).getBranches().get(0).getCount());
        assertEquals(10.0, tree.getRoots().get(0).getThreshold());
    }

    /** Review Focus 2. */
    @Test
    void aSlideMissingAGatedChannelIsGatedNotFailed(@TempDir Path dir) {
        GateTree tree = tree();
        GateNode ghost = new GateNode("CD99", 1.0);
        ghost.setStatistic(Statistic.MEAN);
        tree.addRoot(ghost);
        BatchResult r = BatchRunner.run(List.of(slide("s1", cells(), new ArrayList<>())),
                settings(tree, dir.toFile()), (i, n) -> {}, () -> false).get(0);
        assertTrue(r.succeeded(), String.valueOf(r.failure()));
        assertEquals(0, count(r, 3, "CD99+"));
        assertEquals(0, count(r, 3, "CD99-"), "unmeasured is not negative");
        assertFalse(r.markers().contains("CD99"));
    }

    @Test
    void aFailingSlideIsRecordedAndTheRunContinues(@TempDir Path dir) {
        BatchSlide broken = new BatchSlide() {
            @Override public String id() { return "bad"; }
            @Override public String name() { return "bad.tif"; }
            @Override public ImageData<BufferedImage> read() { throw new OutOfMemoryError("Java heap space"); }
            @Override public void save(ImageData<BufferedImage> d) {}
        };
        List<BatchResult> results = BatchRunner.run(
                List.of(slide("a", cells(), new ArrayList<>()), broken, slide("e", Cells.of(0), new ArrayList<>()),
                        slide("c", cells(), new ArrayList<>())),
                settings(tree(), dir.toFile()), (i, n) -> {}, () -> false);
        assertEquals(4, results.size());
        assertTrue(results.get(1).failure().contains("Java heap space"));
        assertEquals("no detections on this slide", results.get(2).failure());
        assertTrue(results.get(3).succeeded());
    }

    @Test
    void cancellationStopsBeforeTheNextSlideAndProgressIsInOrder(@TempDir Path dir) {
        AtomicBoolean cancel = new AtomicBoolean();
        List<String> seen = new ArrayList<>();
        List<BatchResult> results = BatchRunner.run(
                List.of(slide("a", cells(), new ArrayList<>()), slide("b", cells(), new ArrayList<>())),
                settings(tree(), dir.toFile()), (i, n) -> { seen.add(i + ":" + n); cancel.set(true); }, cancel::get);
        assertEquals(1, results.size());
        assertEquals(List.of("0:a.tif"), seen);
    }

    @Test
    void theCombinedTableHasOneHeaderAndOmitsFailures(@TempDir Path dir) throws Exception {
        List<BatchResult> results = new ArrayList<>(BatchRunner.run(
                List.of(slide("a", cells(), new ArrayList<>()), slide("b", cells(), new ArrayList<>())),
                settings(tree(), dir.toFile()), (i, n) -> {}, () -> false));
        results.add(BatchResult.failed("x", "x.tif", "no server"));
        File out = dir.resolve(BatchRunner.COMBINED_FILE).toFile();
        BatchRunner.writeCombined(out, results);
        List<String> lines = Files.readAllLines(out.toPath());
        assertEquals(1, lines.stream().filter(l -> l.startsWith("image,")).count());
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("a.tif,")));
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("b.tif,")));
        assertTrue(lines.stream().noneMatch(l -> l.startsWith("x.tif,")));
    }

    @Test
    void phenoFileNamesAreSafeAndUnique() {
        Set<String> used = new HashSet<>();
        assertEquals("slide_01.ome.tiff_gate_pheno.csv", BatchRunner.phenoFileName("slide 01.ome.tiff", used));
        assertEquals("slide_01.ome.tiff_2_gate_pheno.csv", BatchRunner.phenoFileName("slide/01.ome.tiff", used));
    }
}
