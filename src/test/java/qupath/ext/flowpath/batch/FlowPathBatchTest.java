package qupath.ext.flowpath.batch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.flowpath.cohort.AlignmentModel;
import qupath.ext.flowpath.cohort.CohortSession;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.SlideSetting;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.testing.Cells;
import qupath.ext.flowpath.testing.GateTreeFixtures;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.WrappedBufferedImageServer;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class FlowPathBatchTest {

    static BatchSlide slide(String id, Cells cells, List<String> reads, List<String> saves) {
        ImageData<BufferedImage> data = new ImageData<>(new WrappedBufferedImageServer(id,
                new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB)));
        data.getHierarchy().addObjects(cells.detections());
        return new BatchSlide() {
            @Override public String id() { return id; }
            @Override public String name() { return id + ".tif"; }
            @Override public ImageData<BufferedImage> read() { reads.add(id); return data; }
            @Override public PathObjectHierarchy readHierarchy() { return data.getHierarchy(); }
            @Override public void save(ImageData<BufferedImage> d) { saves.add(id); }
        };
    }

    static Cells cells(int n) {
        return Cells.of(n).atGrid(10, 10).marker("CD3", i -> i).marker("CD8", i -> 2.0 * i).area(100.0);
    }

    static List<BatchSlide> slides(List<String> reads, List<String> saves) {
        return List.of(slide("a", cells(200), reads, saves), slide("b", cells(200), reads, saves), slide("c", cells(200), reads, saves));
    }

    static FlowPathBatch.Run run(List<BatchSlide> slides, GateTree tree, File out, AtomicBoolean cancel) throws Exception {
        return FlowPathBatch.run(slides, tree, out, AlignmentModel.Cache.empty(), 0, null, (i, n) -> {}, cancel::get);
    }

    static GateTree tree() {
        GateTree tree = GateTreeFixtures.twoRootsOnCd3AndCd8(100, 150);
        tree.setReferenceSlideId("a");
        return tree;
    }

    @Test
    void aCrashedRunResumesWhereItStopped(@TempDir Path dir) throws Exception {
        List<String> reads = new ArrayList<>(), saves = new ArrayList<>();
        AtomicBoolean cancel = new AtomicBoolean();
        FlowPathBatch.run(slides(reads, saves), tree(), dir.toFile(), AlignmentModel.Cache.empty(), 0, null,
                (i, n) -> { if (i == 2) cancel.set(true); }, cancel::get);
        assertEquals(List.of("a", "b"), reads, "the 'crash' stopped before c");

        reads.clear();
        FlowPathBatch.Run second = run(slides(reads, saves), tree(), dir.toFile(), new AtomicBoolean());
        assertEquals(List.of("c"), reads, "a and b were done and are not read again");
        assertEquals(List.of(true, true, false), second.slides().stream().map(FlowPathBatch.SlideRun::resumed).toList());
        List<String> combined = Files.readAllLines(dir.resolve("batch_populations.csv"));
        for (String id : List.of("a", "b", "c")) {
            assertTrue(combined.stream().anyMatch(l -> l.startsWith(id + ".tif,")), id);
        }
        assertEquals(1, combined.stream().filter(l -> l.startsWith("image,")).count(), "one header");
    }

    /** A real crash: the run dies mid-way, never reaching {@code finish}; the state file is all that survives. */
    @Test
    void aRunThatDiesMidWayResumesAtTheNextSlide(@TempDir Path dir) throws Exception {
        List<String> reads = new ArrayList<>(), saves = new ArrayList<>();
        assertThrows(IllegalStateException.class, () -> FlowPathBatch.run(slides(reads, saves), tree(), dir.toFile(),
                AlignmentModel.Cache.empty(), 0, null,
                (i, n) -> { if (i == 2) throw new IllegalStateException("killed"); }, () -> false));
        assertEquals(List.of("a", "b"), reads);
        assertTrue(dir.resolve(RunState.FILE).toFile().isFile());

        reads.clear();
        FlowPathBatch.Run second = run(slides(reads, saves), tree(), dir.toFile(), new AtomicBoolean());
        assertEquals(List.of("c"), reads);
        assertTrue(second.slides().stream().allMatch(r -> r.result().succeeded()));
        assertTrue(dir.resolve("batch_populations.csv").toFile().isFile());
    }

    @Test
    void aChangedTreeInvalidatesTheResumeButAnotherSlidesAnswerDoesNot(@TempDir Path dir) throws Exception {
        List<String> reads = new ArrayList<>(), saves = new ArrayList<>();
        GateTree tree = tree();
        run(slides(reads, saves), tree, dir.toFile(), new AtomicBoolean());
        reads.clear();
        tree.getRoots().get(0).setSlideSetting("c", new SlideSetting.Skip());
        run(slides(reads, saves), tree, dir.toFile(), new AtomicBoolean());
        assertEquals(List.of("c"), reads, "only the slide whose own settings changed re-runs");
        reads.clear();
        tree.getRoots().get(0).setThreshold(120);
        run(slides(reads, saves), tree, dir.toFile(), new AtomicBoolean());
        assertEquals(List.of("a", "b", "c"), reads);
    }

    /**
     * Answering a review item records the slide's name on the tree as well as its setting
     * ({@code ReviewAnswers}); a tree-wide name map in the fingerprint would re-run every slide.
     */
    @Test
    void answeringSlideBNeverReRunsSlideA(@TempDir Path dir) throws Exception {
        List<String> reads = new ArrayList<>(), saves = new ArrayList<>();
        GateTree tree = tree();
        run(slides(reads, saves), tree, dir.toFile(), new AtomicBoolean());
        reads.clear();
        GateNode cd3 = tree.getRoots().get(0);
        cd3.setSlideSetting("b", new SlideSetting.Reviewed(qupath.ext.flowpath.model.GateValues.read(cd3)));
        tree.setSlideNames(Map.of("b", "b.tif"));
        run(slides(reads, saves), tree, dir.toFile(), new AtomicBoolean());
        assertEquals(List.of("b"), reads);
    }

    @Test
    void sanityFlagsAreRecordedAndNeverFatal(@TempDir Path dir) throws Exception {
        GateTree tree = tree();
        tree.setRoiFilterEnabled(true);
        GateNode ghost = new GateNode("CD99", 1);
        ghost.setStatistic(Statistic.MEAN);
        tree.addRoot(ghost);
        List<String> none = new ArrayList<>();
        FlowPathBatch.Run r = run(List.of(slide("a", cells(50), none, none), slide("b", cells(200), none, none)),
                tree, dir.toFile(), new AtomicBoolean());
        FlowPathBatch.SlideRun a = r.slides().get(0);
        assertTrue(a.result().succeeded());
        assertEquals(List.of("fewer-than-100-cells", "roi-filter-without-annotation", "missing-channel:CD99"), a.sanity());
        assertEquals(List.of("roi-filter-without-annotation", "missing-channel:CD99"), r.slides().get(1).sanity());
        List<String> qc = Files.readAllLines(dir.resolve(FlowPathBatch.QC_SUMMARY));
        assertEquals(FlowPathBatch.QC_HEADER, qc.get(0));
        assertTrue(qc.contains("a,a.tif,sanity,missing-channel:CD99,1"), qc.toString());
        assertTrue(qc.contains("a,a.tif,cells,,50"), qc.toString());
        assertTrue(qc.contains("a,a.tif,pct_unmeasured,2:CD99,100.0000"), "rootIndex keeps roots apart: " + qc);
        assertTrue(qc.contains("a,a.tif,pct_unmeasured,0:CD3,0.0000"), qc.toString());
        assertTrue(FlowPathBatch.summary(dir.toFile(), r.slides(), 2, false).contains("fewer-than-100-cells"));
    }

    @Test
    void qcSummaryKeepsTwoSameChannelRootsApart(@TempDir Path dir) throws Exception {
        // Two CD3 roots, each with a CD8 child under CD3+: byte-identical paths and rule labels
        // but for the root index.
        GateTree tree = new GateTree();
        for (int k = 0; k < 2; k++) {
            GateNode cd3 = new GateNode("CD3", 100);
            cd3.setStatistic(Statistic.MEAN);
            GateNode cd8 = new GateNode("CD8", 150);
            cd8.setStatistic(Statistic.MEAN);
            cd3.getBranches().get(0).getChildren().add(cd8);
            tree.addRoot(cd3);
        }
        tree.setReferenceSlideId("a");
        // An answer on the second CD3 root, slide b only: answered is judged per root and per slide.
        tree.getRoots().get(1).setSlideSetting("b", new SlideSetting.Skip());
        List<String> none = new ArrayList<>();
        run(List.of(slide("a", cells(200), none, none), slide("b", cells(200), none, none)),
                tree, dir.toFile(), new AtomicBoolean());
        List<String> qc = Files.readAllLines(dir.resolve(FlowPathBatch.QC_SUMMARY));
        for (int root = 0; root < 2; root++) {
            assertTrue(qc.contains("a,a.tif,pct_unmeasured," + root + ":CD3,0.0000"), qc.toString());
            assertTrue(qc.contains("a,a.tif,pct_unmeasured," + root + ":CD3+/CD8,0.0000"), qc.toString());
            // CD8+ is 2i >= 150 (i >= 75, 125 cells); of those, i in 75..99 are not CD3+: 25/125.
            assertTrue(qc.contains("a,a.tif,rule_violation_pct," + root + ":CD8+ => " + root + ":CD3+,20.0000"),
                    qc.toString());
        }
        assertEquals(2, qc.stream().filter(l -> l.startsWith("b,b.tif,rule_violation_pct,")).count(), qc.toString());
        assertTrue(qc.stream().anyMatch(l -> l.matches("a,a\\.tif,open_flags,,\\d+")), qc.toString());
        assertTrue(qc.contains("a,a.tif,reviewed_flags,,0"), qc.toString());
        assertTrue(qc.contains("b,b.tif,reviewed_flags,,1"), qc.toString());
    }

    @Test
    void aForeignTreeIsRefusedBeforeAnythingIsReadOrWritten(@TempDir Path dir) {
        List<String> reads = new ArrayList<>(), saves = new ArrayList<>();
        GateTree tree = tree();
        tree.setSlideNames(Map.of("a", "another-project-slide.tif"));
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> run(slides(reads, saves), tree, dir.toFile(), new AtomicBoolean()));
        assertTrue(refused.getMessage().startsWith(CohortSession.FOREIGN_TREE), refused.getMessage());
        assertEquals(List.of(), reads);
        assertEquals(List.of(), saves);
        assertArrayEquals(new String[0], dir.toFile().list());
    }

    @Test
    void anOutOfMemoryErrorOnOneSlideIsRecordedAndTheRunContinues(@TempDir Path dir) throws Exception {
        List<String> reads = new ArrayList<>(), saves = new ArrayList<>();
        BatchSlide broken = new BatchSlide() {
            @Override public String id() { return "x"; }
            @Override public String name() { return "x.tif"; }
            @Override public ImageData<BufferedImage> read() { throw new OutOfMemoryError("Java heap space"); }
            @Override public PathObjectHierarchy readHierarchy() { throw new OutOfMemoryError("Java heap space"); }
            @Override public void save(ImageData<BufferedImage> d) {}
        };
        FlowPathBatch.Run r = run(List.of(slide("a", cells(200), reads, saves), broken, slide("b", cells(200), reads, saves)),
                tree(), dir.toFile(), new AtomicBoolean());
        assertEquals("Java heap space", r.slides().get(1).result().failure());
        assertTrue(r.slides().get(2).result().succeeded());
        List<String> combined = Files.readAllLines(dir.resolve("batch_populations.csv"));
        assertTrue(combined.stream().anyMatch(l -> l.startsWith("b.tif,")));
        assertTrue(combined.stream().noneMatch(l -> l.startsWith("x.tif,")));
    }

    /** Its phenotypes were never written back, so "done" would make the resume skip the one write it still owes. */
    @Test
    void aSlideSkippedAsOpenInTheViewerIsNotMarkedDone(@TempDir Path dir) throws Exception {
        List<String> reads = new ArrayList<>(), saves = new ArrayList<>();
        FlowPathBatch.run(slides(reads, saves), tree(), dir.toFile(), AlignmentModel.Cache.empty(), 0, "b",
                (i, n) -> {}, () -> false);
        assertEquals(List.of("a", "c"), saves);
        reads.clear();
        run(slides(reads, saves), tree(), dir.toFile(), new AtomicBoolean());
        assertEquals(List.of("b"), reads);
        assertEquals(List.of("a", "c", "b"), saves);
    }

    @Test
    void theProvenanceBundleIsWrittenAndEverySlideIsWrittenBackHeadless(@TempDir Path dir) throws Exception {
        List<String> reads = new ArrayList<>(), saves = new ArrayList<>();
        run(slides(reads, saves), tree(), dir.toFile(), new AtomicBoolean());
        for (String f : List.of("flowpath.json", "gating_manifest.csv", "qc_summary.csv", "run_info.txt", "batch_populations.csv")) {
            assertTrue(dir.resolve(f).toFile().isFile(), f);
        }
        List<String> info = Files.readAllLines(dir.resolve("run_info.txt"));
        assertTrue(info.contains("sampled_cells_per_slide=0"), info.toString());
        assertTrue(info.contains("reference_slide=a"), info.toString());
        assertTrue(info.contains("slides=3"), info.toString());
        assertTrue(info.stream().anyMatch(l -> l.matches("date=\\d{4}-\\d\\d-\\d\\dT.*Z")), info.toString());
        assertEquals(List.of("a", "b", "c"), saves, "no open slide headless: every image is written back");
        assertEquals(tree().getRoots().size(),
                qupath.ext.flowpath.io.FlowPathSerializer.load(dir.resolve("flowpath.json").toFile()).getRoots().size());
    }

    @Test
    void noImageNameCanWriteOverTheCombinedTable() {
        java.util.Set<String> used = BatchRunner.newFileBases();
        assertEquals("batch_2", BatchRunner.fileBase("batch", used));
        assertNotEquals(BatchRunner.COMBINED_FILE, BatchRunner.fileBase("batch", used) + FlowPathBatch.POPULATIONS_SUFFIX);
    }

    /** Headless alignment is recomputed from the same seed, so two runs from an empty cache agree to the byte. */
    @Test
    void headlessAlignmentIsDeterministic(@TempDir Path dir) throws Exception {
        List<String> none = new ArrayList<>();
        GateTree tree = tree();
        FlowPathBatch.Run one = FlowPathBatch.run(slides(none, none), tree, dir.resolve("1").toFile(),
                AlignmentModel.Cache.empty(), 50, null, (i, n) -> {}, () -> false);
        FlowPathBatch.Run two = FlowPathBatch.run(slides(none, none), tree, dir.resolve("2").toFile(),
                AlignmentModel.Cache.empty(), 50, null, (i, n) -> {}, () -> false);
        assertEquals(one.cache(), two.cache());
        assertFalse(one.cache().slides().isEmpty());
        assertEquals(Files.readAllLines(dir.resolve("1").resolve(GatingManifestExporter.FILE)),
                Files.readAllLines(dir.resolve("2").resolve(GatingManifestExporter.FILE)));
    }
}
