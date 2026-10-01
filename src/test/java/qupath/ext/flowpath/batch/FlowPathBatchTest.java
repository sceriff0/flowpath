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

    /** Every call a run makes on its slides, and failures to inject into {@code read()}. */
    static final class Log {
        final List<String> reads = new ArrayList<>(), saves = new ArrayList<>(), hierarchyReads = new ArrayList<>();
        final Map<String, Throwable> readFailures = new java.util.HashMap<>();
        final java.util.Set<String> hierarchyFailures = new java.util.HashSet<>();
    }

    static BatchSlide slide(String id, Cells cells, Log log) {
        ImageData<BufferedImage> data = new ImageData<>(new WrappedBufferedImageServer(id,
                new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB)));
        data.getHierarchy().addObjects(cells.detections());
        return new BatchSlide() {
            @Override public String id() { return id; }
            @Override public String name() { return id + ".tif"; }
            @Override public ImageData<BufferedImage> read() throws Exception {
                log.reads.add(id);
                Throwable t = log.readFailures.get(id);
                if (t instanceof Error e) throw e;
                if (t != null) throw (Exception) t;
                return data;
            }
            @Override public PathObjectHierarchy readHierarchy() throws java.io.IOException {
                log.hierarchyReads.add(id);
                if (log.hierarchyFailures.contains(id)) throw new java.io.IOException("no hierarchy");
                return data.getHierarchy();
            }
            @Override public void save(ImageData<BufferedImage> d) { log.saves.add(id); }
        };
    }

    static List<BatchSlide> slides(Log log) {
        return List.of(slide("a", cells(200), log), slide("b", cells(200), log), slide("c", cells(200), log));
    }

    static List<String> runInfo(Path dir) throws Exception {
        return Files.readAllLines(dir.resolve(FlowPathBatch.RUN_INFO));
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
        assertTrue(info.contains("reference_slide_name=a.tif"), info.toString());
        assertTrue(info.contains("sample_size_source=argument"), info.toString());
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

    // ---- fix round 1 ------------------------------------------------------------------------

    /** I1: re-quantified in place — same centroids, new values — so neither resume nor cache may hit. */
    @Test
    void aReQuantifiedSlideIsGatedAgainAndRealigned(@TempDir Path dir) throws Exception {
        Log log = new Log();
        FlowPathBatch.Run first = run(slides(log), tree(), dir.toFile(), new AtomicBoolean());
        log.reads.clear();
        Cells requantified = Cells.of(200).atGrid(10, 10).marker("CD3", i -> 1.5 * i + 5).marker("CD8", i -> 2.0 * i)
                .area(100.0);
        FlowPathBatch.Run second = FlowPathBatch.run(
                List.of(slide("a", cells(200), log), slide("b", requantified, log), slide("c", cells(200), log)),
                tree(), dir.toFile(), first.cache(), 0, null, (i, n) -> {}, () -> false);
        assertEquals(List.of("b"), log.reads, "only the re-quantified slide is gated again");
        assertNotEquals(first.cache().slides().get("b").fingerprint(), second.cache().slides().get("b").fingerprint());
        assertEquals(first.cache().slides().get("a").fingerprint(), second.cache().slides().get("a").fingerprint());
        assertTrue(runInfo(dir).contains("alignment_cache_hits=2/3"), "b's landmarks were recomputed: " + runInfo(dir));
    }

    /** I2: a cache seeded by an earlier run (the GUI's, say) is reused for every slide. */
    @Test
    void aSeededMatchingCacheIsReusedForEverySlide(@TempDir Path dir) throws Exception {
        Log log = new Log();
        FlowPathBatch.Run seed = run(slides(log), tree(), dir.resolve("1").toFile(), new AtomicBoolean());
        assertTrue(runInfo(dir.resolve("1")).contains("alignment_cache_hits=0/3"), runInfo(dir.resolve("1")).toString());
        FlowPathBatch.Run reused = FlowPathBatch.run(slides(log), tree(), dir.resolve("2").toFile(), seed.cache(), 0, null,
                (i, n) -> {}, () -> false);
        assertTrue(runInfo(dir.resolve("2")).contains("alignment_cache_hits=3/3"), runInfo(dir.resolve("2")).toString());
        assertEquals(seed.cache(), reused.cache());
    }

    /**
     * I3: v1 run; v2 with b open (b re-gated with v2 numbers but never recorded); back to v1. The old
     * v1 entry for b must not survive the v2 re-gate, or b would "resume" with v2's files.
     */
    @Test
    void aReGateThatEndsUnrecordedNeverLeavesItsFilesUnderTheOldEntry(@TempDir Path dir) throws Exception {
        Log log = new Log();
        GateTree tree = tree();
        run(slides(log), tree, dir.toFile(), new AtomicBoolean());
        tree.getRoots().get(0).setThreshold(120);
        FlowPathBatch.run(slides(log), tree, dir.toFile(), AlignmentModel.Cache.empty(), 0, "b", (i, n) -> {}, () -> false);
        tree.getRoots().get(0).setThreshold(100);
        log.reads.clear();
        run(slides(log), tree, dir.toFile(), new AtomicBoolean());
        assertTrue(log.reads.contains("b"), "b holds v2 files, so it is gated again: " + log.reads);
    }

    @Test
    void aSlideThatFailsLeavesNoPerSlideFilesBehind(@TempDir Path dir) throws Exception {
        Log log = new Log();
        GateTree tree = tree();
        run(slides(log), tree, dir.toFile(), new AtomicBoolean());
        assertTrue(dir.resolve("b.tif" + FlowPathBatch.POPULATIONS_SUFFIX).toFile().isFile());
        tree.getRoots().get(0).setThreshold(120);
        log.readFailures.put("b", new java.io.IOException("unreadable"));
        FlowPathBatch.Run r = run(slides(log), tree, dir.toFile(), new AtomicBoolean());
        assertEquals("unreadable", r.slides().get(1).result().failure());
        for (String suffix : List.of(FlowPathBatch.PHENO_SUFFIX, FlowPathBatch.POPULATIONS_SUFFIX, FlowPathBatch.QC_SUFFIX)) {
            assertFalse(dir.resolve("b.tif" + suffix).toFile().exists(), suffix);
        }
        assertNull(RunState.load(dir.toFile()).entry("b"));
    }

    /** The step's own catch (its fingerprint read failing) forgets the slide and deletes its files too. */
    @Test
    void aSlideWhoseFingerprintReadFailsLeavesNoPerSlideFilesBehind(@TempDir Path dir) throws Exception {
        Log log = new Log();
        GateTree noReference = tree();
        noReference.setReferenceSlideId(null);   // nothing sampled: step reads each hierarchy itself
        run(slides(log), noReference, dir.toFile(), new AtomicBoolean());
        log.hierarchyFailures.add("b");
        FlowPathBatch.Run r = run(slides(log), noReference, dir.toFile(), new AtomicBoolean());
        assertEquals("no hierarchy", r.slides().get(1).result().failure());
        assertFalse(dir.resolve("b.tif" + FlowPathBatch.POPULATIONS_SUFFIX).toFile().exists());
        assertNull(RunState.load(dir.toFile()).entry("b"));
        assertTrue(r.slides().get(2).resumed(), "c still resumes");
    }

    /** I4: the sampler's read yields the resume fingerprint; no second hierarchy read of a sampled slide. */
    @Test
    void aSampledSlidesHierarchyIsReadOnceAndAnUnsampledOneOnceForItsFingerprint(@TempDir Path dir) throws Exception {
        Log sampled = new Log();
        run(slides(sampled), tree(), dir.resolve("1").toFile(), new AtomicBoolean());
        assertEquals(List.of("a", "b", "c"), sampled.hierarchyReads);

        Log unsampled = new Log();
        GateTree noReference = tree();
        noReference.setReferenceSlideId(null);
        run(slides(unsampled), noReference, dir.resolve("2").toFile(), new AtomicBoolean());
        assertEquals(List.of("a", "b", "c"), unsampled.hierarchyReads, "nothing to align: one read each, in step");
    }

    /** M2: cancel reaches sampling too. */
    @Test
    void aCancelBeforeTheRunReadsNothing(@TempDir Path dir) throws Exception {
        Log log = new Log();
        FlowPathBatch.Run r = run(slides(log), tree(), dir.toFile(), new AtomicBoolean(true));
        assertEquals(List.of(), log.hierarchyReads);
        assertEquals(List.of(), r.slides());
    }

    /** M2: an interrupt caught as one slide's failure still cancels the rest, and the bundle is still written. */
    @Test
    void anInterruptCaughtAsASlidesFailureStillStopsTheRun(@TempDir Path dir) throws Exception {
        Log log = new Log();
        log.readFailures.put("b", new InterruptedException("killed"));
        boolean interrupted;
        try {
            FlowPathBatch.run(slides(log), tree(), dir.toFile(), AlignmentModel.Cache.empty(), 0, null, (i, n) -> {},
                    Thread.currentThread()::isInterrupted);
        } finally {
            interrupted = Thread.interrupted();
        }
        assertTrue(interrupted, "the interrupt survives the slide's catch");
        assertEquals(List.of("a", "b"), log.reads, "c is not started");
        assertTrue(Files.readAllLines(dir.resolve("batch_populations.csv")).stream().anyMatch(l -> l.startsWith("a.tif,")));
    }

    /** M3: headless refuses what the button refuses. */
    @Test
    void aTreeWithNoEnabledGateIsRefusedHeadless(@TempDir Path dir) {
        GateTree tree = tree();
        tree.getRoots().forEach(g -> g.setEnabled(false));
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> run(slides(new Log()), tree, dir.toFile(), new AtomicBoolean()));
        assertEquals(BatchRunner.NO_ENABLED_GATE, refused.getMessage());
    }

    /** M4: the colour root decides the classes written back. */
    @Test
    void theColourRootIsPartOfTheResumeFingerprint() {
        GateTree tree = tree();
        assertNotEquals(RunState.fingerprint(tree, "a", "d", "v", -1), RunState.fingerprint(tree, "a", "d", "v", 1));
        assertEquals(RunState.fingerprint(tree, "a", "d", "v", 1), RunState.fingerprint(tree, "a", "d", "v", 1));
    }

    /** M6: a done slide whose file has gone is gated again, and a file gone mid-run never aborts the bundle. */
    @Test
    void aMissingPerSlideFileMeansGateAgainNeverAnAbortedBundle(@TempDir Path dir) throws Exception {
        Log log = new Log();
        FlowPathBatch.Run first = run(slides(log), tree(), dir.toFile(), new AtomicBoolean());
        Files.delete(dir.resolve("a.tif" + FlowPathBatch.POPULATIONS_SUFFIX));
        log.reads.clear();
        run(slides(log), tree(), dir.toFile(), new AtomicBoolean());
        assertEquals(List.of("a"), log.reads);

        Files.delete(dir.resolve("c.tif" + FlowPathBatch.QC_SUFFIX));
        CohortEvidence none = new CohortEvidence(AlignmentModel.empty(),
                new qupath.ext.flowpath.cohort.ReviewScorer.Result(List.of(), List.of()), null,
                new CohortEvidence.Provenance(0, CohortEvidence.FROM_ARGUMENT, -1, 0, null), java.util.Set.of());
        BatchRunner.Settings settings = new BatchRunner.Settings(tree(), null, dir.toFile(), (String) null, true);
        FlowPathBatch.finish(dir.toFile(), settings.tree(), first.slides(), none);
        List<String> combined = Files.readAllLines(dir.resolve("batch_populations.csv"));
        assertTrue(combined.stream().anyMatch(l -> l.startsWith("a.tif,")));
        assertTrue(combined.stream().noneMatch(l -> l.startsWith("c.tif,")), "left out, not fatal");
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

    /** {@code slide} with its cohort exclusion flag set, everything else delegated. */
    static BatchSlide excluded(BatchSlide slide) {
        return new BatchSlide() {
            @Override public String id() { return slide.id(); }
            @Override public String name() { return slide.name(); }
            @Override public ImageData<BufferedImage> read() throws Exception { return slide.read(); }
            @Override public PathObjectHierarchy readHierarchy() throws Exception { return slide.readHierarchy(); }
            @Override public void save(ImageData<BufferedImage> d) throws Exception { slide.save(d); }
            @Override public boolean cohortExcluded() { return true; }
        };
    }

    @Test
    void anExcludedSlideIsGatedUncorrectedAndFlaggedInQc(@TempDir Path dir) throws Exception {
        java.util.function.Supplier<Cells> shifted = () -> Cells.of(200).atGrid(10, 10)
                .marker("CD3", i -> 1.5 * i + 5).marker("CD8", i -> 2.0 * i).area(100.0);
        Log log = new Log();
        List<BatchSlide> slides = List.of(slide("a", cells(200), log), slide("b", shifted.get(), log),
                excluded(slide("c", shifted.get(), log)));
        run(slides, tree(), dir.toFile(), new AtomicBoolean());

        List<String> qc = Files.readAllLines(dir.resolve("qc_summary.csv"));
        assertTrue(qc.contains("c,c.tif,cohort_excluded,,1"), "1: " + qc);
        assertTrue(qc.stream().anyMatch(l -> l.startsWith("b,b.tif,staining_factor,CD3,") && !l.endsWith(",1.0000")),
                "the same data on an included slide IS corrected: " + qc);
        assertTrue(qc.stream().noneMatch(l -> l.startsWith("c,c.tif,staining_factor,")), "3: " + qc);

        List<String> manifest = Files.readAllLines(dir.resolve(GatingManifestExporter.FILE));
        List<String> rows = manifest.stream().filter(l -> l.startsWith("c,c.tif,")).toList();
        assertFalse(rows.isEmpty(), "the excluded slide is still gated");
        for (String row : rows) {
            String[] f = row.split(",", -1);
            assertEquals(f[6], f[7], "2: applied equals reference: " + row);
        }
    }

    /** Final review item 1: an excluded reference switches correction off in a batch, as a missing one does. */
    @Test
    void anExcludedReferenceSwitchesCorrectionOff() {
        java.util.function.Supplier<Cells> shifted = () -> Cells.of(200).atGrid(10, 10)
                .marker("CD3", i -> 1.5 * i + 5).marker("CD8", i -> 2.0 * i).area(100.0);
        Log log = new Log();
        List<BatchSlide> slides = List.of(excluded(slide("a", cells(200), log)), slide("b", shifted.get(), log),
                slide("c", shifted.get(), log));
        CohortEvidence evidence = CohortEvidence.sample(slides, tree(), AlignmentModel.Cache.empty(), 0,
                CohortEvidence.FROM_ARGUMENT, () -> false).evidence();
        assertSame(qupath.ext.flowpath.engine.AlignmentLookup.NONE, evidence.lookup());
    }
}
