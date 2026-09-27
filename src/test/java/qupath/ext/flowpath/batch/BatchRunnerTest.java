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
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
        List<BatchResult> results = gateAll(
                List.of(slide("ref", cells(), new ArrayList<>()), slide("s1", cells(), new ArrayList<>())),
                settings(tree(), dir.toFile()));

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
        gateAll(List.of(slide("s1", cells(), new ArrayList<>())), settings(tree, dir.toFile()));
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
        BatchResult r = gateAll(List.of(slide("s1", cells(), new ArrayList<>())),
                settings(tree, dir.toFile())).get(0);
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
        List<BatchResult> results = gateAll(
                List.of(slide("a", cells(), new ArrayList<>()), broken, slide("e", Cells.of(0), new ArrayList<>()),
                        slide("c", cells(), new ArrayList<>())),
                settings(tree(), dir.toFile()));
        assertEquals(4, results.size());
        assertTrue(results.get(1).failure().contains("Java heap space"));
        assertEquals("no detections on this slide", results.get(2).failure());
        assertTrue(results.get(3).succeeded());
    }

    /** Every slide through {@link BatchRunner#gateOne}, one stem each, as a run names them. */
    static List<BatchResult> gateAll(List<BatchSlide> slides, BatchRunner.Settings settings) {
        Set<String> used = BatchRunner.newFileBases();
        return slides.stream().map(s -> BatchRunner.gateOne(s,
                BatchRunner.fileBase(s.name(), used) + BatchRunner.PHENO_SUFFIX, settings)).toList();
    }

    @Test
    void fileStemsAreSafeAndUniqueIgnoringCase() {
        Set<String> used = BatchRunner.newFileBases();
        assertEquals("slide_01.ome.tiff", BatchRunner.fileBase("slide 01.ome.tiff", used));
        assertEquals("slide_01.ome.tiff_2", BatchRunner.fileBase("slide/01.ome.tiff", used));
        assertEquals("SLIDE_01.ome.tiff_3", BatchRunner.fileBase("SLIDE 01.ome.tiff", used),
                "one file on a case-insensitive file system");
        assertEquals("Batch_2", BatchRunner.fileBase("Batch", used), "the combined table's stem is reserved in any case");
    }

    /** The combined table: one header, every successful slide's rows, failures left out. */
    @Test
    void finishWritesOneCombinedTableAndTheManifest(@TempDir Path dir) throws Exception {
        BatchRunner.Settings s = settings(tree(), dir.toFile());
        RunState state = RunState.load(dir.toFile());
        List<FlowPathBatch.SlideRun> runs = new ArrayList<>();
        for (String id : List.of("a", "b")) {
            runs.add(FlowPathBatch.step(slide(id, cells(), new ArrayList<>()), id + ".tif", s, state, java.util.Map.of()));
        }
        runs.add(new FlowPathBatch.SlideRun(BatchResult.failed("x", "x.tif", "no server"), "x.tif", List.of(), false));
        // The tree the results were resolved from: Settings froze its own copy.
        FlowPathBatch.finish(dir.toFile(), s.tree(), runs, NO_COHORT);

        List<String> lines = Files.readAllLines(dir.resolve(BatchRunner.COMBINED_FILE));
        assertEquals(1, lines.stream().filter(l -> l.startsWith("image,")).count());
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("a.tif,")));
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("b.tif,")));
        assertTrue(lines.stream().noneMatch(l -> l.startsWith("x.tif,")));
        List<String> manifest = Files.readAllLines(dir.resolve(GatingManifestExporter.FILE));
        assertEquals(1 + 2 * 3, manifest.size(), "header + one row per enabled gate axis on each gated slide");
    }

    static final CohortEvidence NO_COHORT = new CohortEvidence(qupath.ext.flowpath.cohort.AlignmentModel.empty(),
            new qupath.ext.flowpath.cohort.ReviewScorer.Result(List.of(), List.of()), AlignmentLookup.NONE,
            new CohortEvidence.Provenance(0, CohortEvidence.FROM_ARGUMENT, -1, 0, null));

    /** Review Focus 4. */
    @Test
    void theOpenSlideIsGatedButNeverSaved(@TempDir Path dir) {
        List<String> saved = new ArrayList<>();
        Cells open = cells();
        Cells other = cells();
        List<BatchResult> results = gateAll(
                List.of(slide("open", open, saved), slide("s1", other, saved)),
                new BatchRunner.Settings(tree(), AlignmentLookup.NONE, dir.toFile(), "open", true));

        assertEquals(List.of("s1"), saved, "only the closed slide's .qpdata is written");
        assertEquals(BatchResult.WriteBack.SKIPPED_OPEN_SLIDE, results.get(0).writeBack());
        assertEquals(BatchResult.WriteBack.SAVED, results.get(1).writeBack());
        assertTrue(new File(dir.toFile(), "open.tif_gate_pheno.csv").isFile(), "the open slide is still gated and exported");
        assertTrue(open.detections().stream().allMatch(o -> o.getPathClass() == null), "no class written behind QuPath's back");
        assertTrue(other.detections().stream().allMatch(o -> o.getPathClass() != null));
    }

    @Test
    void aSaveFailureIsReportedButTheSlideStillSucceeded(@TempDir Path dir) {
        BatchSlide unsavable = new BatchSlide() {
            final BatchSlide inner = slide("s1", cells(), new ArrayList<>());
            @Override public String id() { return "s1"; }
            @Override public String name() { return "s1.tif"; }
            @Override public ImageData<BufferedImage> read() throws Exception { return inner.read(); }
            @Override public void save(ImageData<BufferedImage> d) throws Exception { throw new java.io.IOException("disk full"); }
        };
        BatchResult r = gateAll(List.of(unsavable),
                new BatchRunner.Settings(tree(), AlignmentLookup.NONE, dir.toFile(), null, true)).get(0);
        assertTrue(r.succeeded());
        assertEquals(BatchResult.WriteBack.FAILED, r.writeBack());
        assertEquals("disk full", r.writeBackError());
    }

    @Test
    void theSummaryNamesEveryOutcome(@TempDir Path dir) {
        List<BatchResult> results = List.of(
                new BatchResult("a", "a.tif", 10, List.of(), null, null, null, BatchResult.WriteBack.SAVED, null, null),
                new BatchResult("o", "open.tif", 10, List.of(), null, null, null,
                        BatchResult.WriteBack.SKIPPED_OPEN_SLIDE, null, null),
                new BatchResult("d", "d.tif", 10, List.of(), null, null, null, BatchResult.WriteBack.FAILED, "disk full", null),
                BatchResult.failed("x", "x.tif", "no detections on this slide"));
        String s = BatchRunner.summary(dir.toFile(), results, 5, true);
        assertTrue(s.startsWith("Cancelled after 4 of 5 slide(s). 3 gated."), s);
        assertTrue(s.contains("Wrote batch_populations.csv, gating_manifest.csv and one phenotype CSV per slide"), s);
        assertTrue(s.contains("Skipped:\n  x.tif — no detections on this slide"), s);
        assertTrue(s.contains("Not saved, open in the viewer:\n  open.tif — already classified; save it from QuPath"), s);
        assertTrue(s.contains("Could not save:\n  d.tif — disk full"), s);
    }

    @Test
    void aFinishedRunSaysSoAndListsNothingThatDidNotHappen(@TempDir Path dir) {
        List<BatchResult> results = List.of(
                new BatchResult("a", "a.tif", 10, List.of(), null, null, null, BatchResult.WriteBack.SAVED, null, null));
        String s = BatchRunner.summary(dir.toFile(), results, 1, false);
        assertTrue(s.startsWith("1 of 1 slide(s) processed. 1 gated."), s);
        assertFalse(s.contains("Skipped:") || s.contains("Not saved") || s.contains("Could not save"), s);
        assertFalse(s.contains("last saved file"), "no slide was open: nothing to say about one");
    }

    /**
     * A tree whose recorded slide names contradict the slides being run belongs to another
     * project: its per-slide settings would land on different images here, so the run is refused.
     */
    @Test
    void aForeignTreeIsRefusedAndThisProjectsTreeIsNot() {
        GateTree tree = tree();
        List<BatchSlide> slides = List.of(slide("1", cells(), new ArrayList<>()), slide("2", cells(), new ArrayList<>()));
        assertNull(BatchRunner.refusal(tree, slides), "no recorded names: nothing contradicts");

        tree.setSlideNames(java.util.Map.of("1", "1.tif", "9", "gone.tif"));
        assertNull(BatchRunner.refusal(tree, slides), "same names; an id the project lacks is no contradiction");

        tree.setSlideNames(java.util.Map.of("1", "other_project_slide.tif"));
        String refusal = BatchRunner.refusal(tree, slides);
        assertNotNull(refusal);
        assertTrue(refusal.startsWith(qupath.ext.flowpath.cohort.CohortSession.FOREIGN_TREE), refusal);
    }

    // ---- the open slide, asked live (review fix round 1) -----------------------------------

    /** A slide whose {@code read()} runs {@code onRead} first, recording saves into {@code saved}. */
    static BatchSlide slideThat(String id, Cells cells, List<String> saved, Runnable onRead) {
        BatchSlide inner = slide(id, cells, saved);
        return new BatchSlide() {
            @Override public String id() { return id; }
            @Override public String name() { return inner.name(); }
            @Override public ImageData<BufferedImage> read() throws Exception { onRead.run(); return inner.read(); }
            @Override public void save(ImageData<BufferedImage> d) throws Exception { inner.save(d); }
        };
    }

    @Test
    void aSlideOpenedAfterItsReadIsNeitherClassifiedNorSaved(@TempDir Path dir) {
        AtomicBoolean open = new AtomicBoolean(false);
        List<String> saved = new ArrayList<>();
        Cells cells = cells();
        BatchResult r = gateAll(List.of(slideThat("s1", cells, saved, () -> open.set(true))),
                new BatchRunner.Settings(tree(), AlignmentLookup.NONE, dir.toFile(), id -> open.get(), true, -1)).get(0);

        assertTrue(r.succeeded(), "still gated and exported");
        assertEquals(BatchResult.WriteBack.SKIPPED_OPEN_SLIDE, r.writeBack());
        assertEquals(List.of(), saved, "save is never called for a slide the viewer now holds");
        assertTrue(cells.detections().stream().allMatch(o -> o.getPathClass() == null), "no class written either");
        // Both same-channel CD3 roots were still gated on it.
        assertEquals(10, count(r, 0, "CD3+"));
        assertEquals(10, count(r, 2, "CD3+"));
    }

    @Test
    void aSlideOpenWhenTheRunStartedButClosedBeforeItIsReachedIsSaved(@TempDir Path dir) {
        AtomicReference<String> viewer = new AtomicReference<>("s1");
        List<String> saved = new ArrayList<>();
        List<BatchResult> results = gateAll(List.of(
                        slideThat("a", cells(), saved, () -> viewer.set(null)),   // the user closes s1 meanwhile
                        slide("s1", cells(), saved)),
                new BatchRunner.Settings(tree(), AlignmentLookup.NONE, dir.toFile(), id -> id.equals(viewer.get()), true, -1));

        assertEquals(BatchResult.WriteBack.SAVED, results.get(1).writeBack());
        assertEquals(List.of("a", "s1"), saved);
        assertEquals(10, count(results.get(1), 0, "CD3+"));
        assertEquals(10, count(results.get(1), 2, "CD3+"));
    }

    @Test
    void aSlideOpenWhenItWasReadIsNotSavedEvenIfClosedBeforeTheWrite(@TempDir Path dir) {
        AtomicBoolean open = new AtomicBoolean(true);
        List<String> saved = new ArrayList<>();
        // Closed during the read: QuPath may have saved it between the check and the close.
        BatchResult r = gateAll(List.of(slideThat("s1", cells(), saved, () -> open.set(false))),
                new BatchRunner.Settings(tree(), AlignmentLookup.NONE, dir.toFile(), id -> open.get(), true, -1)).get(0);
        assertEquals(BatchResult.WriteBack.SKIPPED_OPEN_SLIDE, r.writeBack());
        assertEquals(List.of(), saved);
    }

    @Test
    void theSummaryDoesNotSendTheUserToQuPathForASlideClosedSince(@TempDir Path dir) {
        List<BatchResult> results = List.of(
                new BatchResult("o", "open.tif", 10, List.of(), null, null, null,
                        BatchResult.WriteBack.SKIPPED_OPEN_SLIDE, null, null),
                new BatchResult("c", "closed.tif", 10, List.of(), null, null, null,
                        BatchResult.WriteBack.SKIPPED_OPEN_SLIDE, null, null));
        String s = BatchRunner.summary(dir.toFile(), results, 2, false, "o"::equals);
        assertTrue(s.contains("open.tif — already classified; save it from QuPath"), s);
        assertTrue(s.contains("closed.tif — closed since"), s);
        assertFalse(s.contains("closed.tif — already classified"), s);
        // Final review M7: its tables came from the file on disk, not from the viewer's edits.
        assertTrue(s.contains("A slide open in the viewer was gated from its last saved file: its phenotype CSV "
                + "and population rows do not include changes made in QuPath since it was saved."), s);
    }

    // ---- manifest landmarks follow the run's alignments ------------------------------------

    static qupath.ext.flowpath.cohort.SlideSample sample(String id, long seed, double shift) {
        java.util.Random r = new java.util.Random(seed);
        int n = 3000;
        double[] raw = new double[n];
        for (int i = 0; i < n; i++) raw[i] = 100 * Math.sinh((r.nextDouble() < 0.3 ? 4.0 : 1.0) + shift + 0.3 * r.nextGaussian());
        qupath.ext.flowpath.model.CellIndex index = Cells.of(n).marker("CD3", raw).marker("CD8", raw).build();
        boolean[] clean = Cells.allTrue(n);
        return new qupath.ext.flowpath.cohort.SlideSample(id, id + ".tif", index, clean,
                qupath.ext.flowpath.model.MarkerStats.compute(index, clean), n, "f-" + id);
    }

    @Test
    void theManifestReportsLandmarksOnlyWhenTheRunCorrectedWithThatModel() {
        GateTree tree = tree();
        List<qupath.ext.flowpath.cohort.SlideSample> samples = List.of(sample("ref", 1, 0.0), sample("s1", 2, 0.2));
        qupath.ext.flowpath.cohort.AlignmentModel model = qupath.ext.flowpath.cohort.AlignmentModel.build("ref", samples,
                qupath.ext.flowpath.cohort.AlignmentModel.columnsOf(tree), qupath.ext.flowpath.cohort.AlignmentModel.Cache.empty());
        String column = qupath.ext.flowpath.cohort.AlignmentModel.columnsOf(tree).stream()
                .map(qupath.ext.flowpath.cohort.AlignmentModel.ColumnRef::key)
                .filter(k -> model.landmarks("s1", k) != null && model.referenceLandmarks(k) != null)
                .findFirst().orElseThrow(() -> new AssertionError("fixture check: the model found landmarks"));
        qupath.ext.flowpath.cohort.ReviewScorer.Result review = new qupath.ext.flowpath.cohort.ReviewScorer.Result(List.of(), List.of());

        GatingManifestExporter.Annotations corrected = GatingManifestExporter.Annotations.of(model, review, model::alignment);
        assertNotNull(corrected.reference(column));
        assertNotNull(corrected.slide("s1", column));

        GatingManifestExporter.Annotations off = GatingManifestExporter.Annotations.of(model, review, AlignmentLookup.NONE);
        assertNull(off.reference(column), "correction off: no landmarks beside thresholds they never moved");
        assertNull(off.slide("s1", column));
        assertEquals("", off.flags("s1", 0, "CD3"));
    }
}
