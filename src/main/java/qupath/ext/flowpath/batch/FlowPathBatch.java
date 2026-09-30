package qupath.ext.flowpath.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.flowpath.cohort.CohortExclusions;
import qupath.ext.flowpath.cohort.AlignmentModel;
import qupath.ext.flowpath.cohort.CohortPrefs;
import qupath.ext.flowpath.cohort.CohortSampler;
import qupath.ext.flowpath.cohort.MarkerRules;
import qupath.ext.flowpath.cohort.ReviewScorer;
import qupath.ext.flowpath.cohort.SlideSource;
import qupath.ext.flowpath.engine.GateReadout;
import qupath.ext.flowpath.engine.GatingEngine;
import qupath.ext.flowpath.engine.TreeResolver;
import qupath.ext.flowpath.io.AlignmentCacheFile;
import qupath.ext.flowpath.io.CellTable;
import qupath.ext.flowpath.io.FlowPathSerializer;
import qupath.ext.flowpath.io.PopulationStatsExporter;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.GateWalk;
import qupath.ext.flowpath.model.cohort.Alignment;
import qupath.lib.images.ImageData;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.projects.Project;

import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * "Run on all slides", headless or from the GUI (spec §7 "Robust runs"): resumable per slide,
 * never fatal per slide, and leaving a provenance bundle behind. From a QuPath script — on a
 * cluster, say:
 * <pre>{@code
 * qupath.ext.flowpath.batch.FlowPathBatch.run(getProject(), new File('tree.json'), new File('out'))
 * }</pre>
 * The GUI's "Run on all slides" runs the same {@link #step} per slide and the same {@link #finish}
 * at the end (through {@code ui/BatchRunCoordinator}, which schedules one slide per background
 * task); only the scheduling and where the cohort evidence comes from differ.
 * <p>
 * Outputs in {@code outDir}: per slide {@code <image>_gate_pheno.csv}, {@code <image>_populations.csv}
 * and {@code <image>_qc.csv}, written as each slide finishes; then {@code batch_populations.csv}
 * and {@code qc_summary.csv}, concatenated from them, and {@code gating_manifest.csv},
 * {@code flowpath.json} (the tree as run) and {@code run_info.txt}.
 */
public final class FlowPathBatch {

    private static final Logger logger = LoggerFactory.getLogger(FlowPathBatch.class);

    public static final String PHENO_SUFFIX = BatchRunner.PHENO_SUFFIX;
    public static final String POPULATIONS_SUFFIX = "_populations.csv";
    public static final String QC_SUFFIX = "_qc.csv";
    public static final String QC_SUMMARY = "qc_summary.csv";
    public static final String TREE_FILE = "flowpath.json";
    public static final String RUN_INFO = "run_info.txt";
    public static final String QC_HEADER = "image_id,image_name,metric,subject,value";
    public static final int MIN_CELLS = 100;

    /**
     * One slide's outcome. {@code resumed}: done by an earlier run under the same fingerprint and
     * not gated again — its {@code result().stats()} is null, since its tables are already on disk.
     */
    public record SlideRun(BatchResult result, String fileBase, List<String> sanity, boolean resumed) {
        public SlideRun {
            sanity = List.copyOf(sanity);
        }
    }

    /** The slides a run reached, in order, and the alignment cache it ended with (to write back). */
    public record Run(List<SlideRun> slides, AlignmentModel.Cache cache) {
        public Run {
            slides = List.copyOf(slides);
        }
    }

    private FlowPathBatch() {}

    /**
     * The headless entry point, with no slide open in any viewer: every image is written back. See
     * {@link #run(Project, File, File, int, String)}; the sample size is the one the project's
     * alignment cache records (the GUI's), else this machine's preference.
     */
    public static Run run(Project<?> project, File treeJson, File outDir) throws IOException {
        return runProject(project, treeJson, outDir, null, null);
    }

    /**
     * As {@link #run(Project, File, File)}, from QuPath's script editor with an image open: that
     * image is gated but never written behind QuPath's back. See
     * {@link #run(Project, File, File, int, String)} for the Groovy idiom.
     */
    public static Run run(Project<?> project, File treeJson, File outDir, String openSlideId) throws IOException {
        return runProject(project, treeJson, outDir, null, openSlideId);
    }

    /** As {@link #run(Project, File, File)}, sampling {@code cellsPerSlide}; see {@link #run(Project, File, File, int, String)}. */
    public static Run run(Project<?> project, File treeJson, File outDir, int cellsPerSlide) throws IOException {
        return runProject(project, treeJson, outDir, cellsPerSlide, null);
    }

    /**
     * Gate every image of {@code project} with the tree saved in {@code treeJson}, into
     * {@code outDir} — resumably: a slide an earlier run into {@code outDir} finished under the
     * same fingerprint is not gated again.
     * <ul>
     *   <li><b>Refusals</b> ({@link BatchRunner#refusal}, the GUI's rule) happen before anything is
     *       read: a tree with no enabled gate, or one from another project.</li>
     *   <li><b>Alignment</b> reuses the project's alignment cache where its sample fingerprints
     *       match and recomputes the rest from the same fixed seed, so it reproduces what the GUI
     *       showed; the cache is written back afterwards, with the sample size.</li>
     *   <li><b>Cancel:</b> interrupting the calling thread (QuPath's "Kill running script") stops
     *       before the next slide; what was done is kept and the next run resumes after it.</li>
     * </ul>
     * <b>From QuPath's script editor</b>, pass the image open in the viewer, so its data file is
     * never written behind QuPath's back (QuPath would overwrite it on its next save) — it is still
     * gated, and the next run writes it once it is closed:
     * <pre>{@code
     * import qupath.ext.flowpath.batch.FlowPathBatch
     * FlowPathBatch.run(getProject(), new File('/path/tree.json'), new File('/path/out'), getProjectEntry()?.getID())
     * }</pre>
     * Run the script once, with <b>Run</b> — <b>never "Run for project"</b>: that runs the script
     * once per image, with each image open in turn, so the whole project would be gated once per
     * image and the image QuPath has open would not be the one named here. On a cluster
     * ({@code QuPath script ... --project=...}) nothing is open: pass null or use the 3-argument form.
     *
     * @param cellsPerSlide the cohort sample size. Leave it to the 3-argument forms unless you know
     *                      better: they use the size the project's alignment cache records — the
     *                      one the GUI's alignments were found with. An argument that disagrees
     *                      with that recorded size is used, with a warning in the log, since the
     *                      alignments will then differ from what was reviewed.
     * @param openSlideId   the project entry id of the image open in the viewer, or null
     * @throws IllegalStateException when the tree has no enabled gate or belongs to another project
     */
    public static Run run(Project<?> project, File treeJson, File outDir, int cellsPerSlide, String openSlideId)
            throws IOException {
        return runProject(project, treeJson, outDir, cellsPerSlide, openSlideId);
    }

    @SuppressWarnings("unchecked")
    private static Run runProject(Project<?> project, File treeJson, File outDir, Integer explicitCells,
                                  String openSlideId) throws IOException {
        Project<BufferedImage> p = (Project<BufferedImage>) project;
        Path cacheFile = AlignmentCacheFile.pathFor(project.getPath().getParent());
        OptionalInt recorded = AlignmentCacheFile.sampledCellsPerSlide(cacheFile);
        int cells;
        String source;
        if (explicitCells != null) {
            cells = Math.max(0, explicitCells);
            source = CohortEvidence.FROM_ARGUMENT;
            if (recorded.isPresent() && recorded.getAsInt() != cells) {
                logger.warn("FlowPath batch: sampling {} cells per slide, but the alignment cache was built from {}; "
                        + "the alignments will differ from those reviewed in the GUI", cells, recorded.getAsInt());
            }
        } else if (recorded.isPresent()) {
            cells = recorded.getAsInt();
            source = CohortEvidence.FROM_CACHE;
        } else {
            cells = CohortPrefs.sampledCellsPerSlide(CohortPrefs.node());
            source = CohortEvidence.FROM_PREFERENCE;
        }
        List<BatchSlide> slides = batchSlides(p);
        Thread caller = Thread.currentThread();
        Run run = run(slides, FlowPathSerializer.load(treeJson), outDir, AlignmentCacheFile.read(cacheFile), cells, source,
                openSlideId, (i, name) -> logger.info("FlowPath batch: slide {} of {} — {}", i + 1, slides.size(), name),
                caller::isInterrupted);
        try {
            AlignmentCacheFile.write(cacheFile, run.cache(), cells);
        } catch (IOException | RuntimeException e) {
            // Derived data: the next run recomputes it; the run itself succeeded.
            logger.warn("Could not write the alignment cache {}", cacheFile, e);
        }
        logger.info("FlowPath batch finished.\n{}", summary(outDir, run.slides(), slides.size(),
                run.slides().size() < slides.size(), openSlideId == null ? id -> false : openSlideId::equals));
        return run;
    }

    /** {@link #run(List, GateTree, File, AlignmentModel.Cache, int, String, String, BiConsumer, BooleanSupplier)} with an explicit sample size. */
    static Run run(List<BatchSlide> slides, GateTree tree, File outDir, AlignmentModel.Cache cache, int cellsPerSlide,
                   String openSlideId, BiConsumer<Integer, String> progress, BooleanSupplier cancelled)
            throws IOException {
        return run(slides, tree, outDir, cache, cellsPerSlide, CohortEvidence.FROM_ARGUMENT, openSlideId, progress,
                cancelled);
    }

    /**
     * Sample the cohort, gate every slide in order (resuming what {@code outDir} records as done),
     * then {@link #finish}. Cancellation "stops before the next slide" (spec §7, pre-flight ruling
     * A2): it is asked before each slide's progress is announced and again after it, so a slide
     * whose progress was announced is <em>not started</em> once cancel is set — the same meaning
     * the GUI's {@code BatchRunCoordinator} gives it, which submits the next slide only after asking.
     *
     * @param openSlideId the slide open in a viewer, gated but never written back; null headless
     */
    static Run run(List<BatchSlide> slides, GateTree tree, File outDir, AlignmentModel.Cache cache, int cellsPerSlide,
                   String sampleSizeSource, String openSlideId, BiConsumer<Integer, String> progress,
                   BooleanSupplier cancelled) throws IOException {
        String refusal = BatchRunner.refusal(tree, slides);
        if (refusal != null) throw new IllegalStateException(refusal);
        Files.createDirectories(outDir.toPath());
        CohortEvidence.Sampled sampled = CohortEvidence.sample(slides, tree, cache, cellsPerSlide, sampleSizeSource,
                cancelled);
        CohortEvidence evidence = sampled.evidence();
        BatchRunner.Settings settings = new BatchRunner.Settings(tree, evidence.lookup(), outDir, openSlideId, true);
        RunState state = RunState.load(outDir);
        List<SlideRun> runs = new ArrayList<>();
        Set<String> used = BatchRunner.newFileBases();
        for (int i = 0; i < slides.size(); i++) {
            if (cancelled.getAsBoolean()) break;
            BatchSlide slide = slides.get(i);
            progress.accept(i, slide.name());
            if (cancelled.getAsBoolean()) break;
            runs.add(step(slide, BatchRunner.fileBase(slide.name(), used), settings, state,
                    sampled.detectionFingerprints()));
        }
        finish(outDir, settings.tree(), runs, evidence);
        return new Run(runs, evidence.model().cache());
    }

    /**
     * One slide, the unit both the GUI and a headless run are made of.
     * <ul>
     *   <li>Skipped when {@code state} records it done under the same fingerprint with its files
     *       present.</li>
     *   <li>Otherwise its entry is forgotten — persisted before anything is overwritten — then it
     *       is gated and its per-slide files written.</li>
     *   <li>It is recorded done only once its phenotypes are in its data file too (or no
     *       write-back was asked for): a slide skipped for being open in the viewer, or whose save
     *       failed, still owes that write, and a resume must not skip it.</li>
     *   <li>A slide that fails has its per-slide files deleted, so nothing half-written can be
     *       mistaken for a result.</li>
     * </ul>
     * Never throws — a failure of any kind, an {@code OutOfMemoryError} included, is a failed
     * {@link SlideRun}.
     *
     * @param detectionFingerprints each slide's {@link CohortSampler#detectionFingerprint}, taken
     *                              while the sampler held its hierarchy; a slide missing from it
     *                              is read once more for its fingerprint
     */
    public static SlideRun step(BatchSlide slide, String fileBase, BatchRunner.Settings settings, RunState state,
                                Map<String, String> detectionFingerprints) {
        try {
            String detections = detectionFingerprints.get(slide.id());
            if (detections == null) detections = detectionFingerprint(slide);
            TreeResolver.ResolvedTree resolved = TreeResolver.resolve(settings.tree(), slide.id(), settings.alignments());
            String fingerprint = RunState.fingerprint(resolved.tree(), slide.id(), detections, version(),
                    settings.colorRootIndex());
            if (state.isDone(slide.id(), fingerprint, settings.outputDir(), fileBase)) {
                RunState.Entry e = state.entry(slide.id());
                return new SlideRun(new BatchResult(slide.id(), slide.name(), e.cells(), e.markers(), null, resolved,
                        null, BatchResult.WriteBack.NOT_REQUESTED, null, null), fileBase, e.sanity(), true);
            }
            state.forget(slide.id());
            BatchRunner.Gated g = BatchRunner.gateDetailed(slide, fileBase + PHENO_SUFFIX, settings);
            if (!g.result().succeeded()) {
                deleteOutputs(settings.outputDir(), fileBase);
                return new SlideRun(g.result(), fileBase, List.of(), false);
            }
            List<String> sanity = sanity(settings.tree(), g);
            try (Writer w = writer(new File(settings.outputDir(), fileBase + POPULATIONS_SUFFIX))) {
                PopulationStatsExporter.writeHeader(w, true);
                PopulationStatsExporter.writeRows(w, g.result().stats(), g.result().imageName());
            }
            try (Writer w = writer(new File(settings.outputDir(), fileBase + QC_SUFFIX))) {
                writeSlideQc(w, settings.tree(), g, sanity);
            }
            BatchResult.WriteBack wb = g.result().writeBack();
            if (wb == BatchResult.WriteBack.SAVED || wb == BatchResult.WriteBack.NOT_REQUESTED) {
                try {
                    state.record(slide.id(), new RunState.Entry(fingerprint, fileBase, g.index().size(),
                            g.result().markers(), sanity));
                } catch (IOException | RuntimeException e) {
                    // The slide is done and its files are written; only the next run's shortcut is lost.
                    logger.warn("Could not record {} as done; a resumed run will gate it again", slide.name(), e);
                }
            }
            return new SlideRun(g.result(), fileBase, sanity, false);
        } catch (Exception | Error ex) {
            // Error too: one slide's OutOfMemoryError is that slide's failure, not the run's.
            BatchRunner.restoreInterrupt(ex);
            logger.warn("Batch step failed for {}", slide.name(), ex);
            try {
                state.forget(slide.id());
            } catch (IOException | RuntimeException e) {
                logger.warn("Could not forget {} in the run state", slide.name(), e);
            }
            deleteOutputs(settings.outputDir(), fileBase);
            return new SlideRun(BatchResult.failed(slide.id(), slide.name(), BatchRunner.describe(ex)),
                    fileBase, List.of(), false);
        }
    }

    /**
     * The slide's detection fingerprint from a read of its own. A method of its own so the
     * hierarchy is unreachable once it returns: gating reads the slide again, and a million-cell
     * hierarchy must not be held twice.
     */
    private static String detectionFingerprint(BatchSlide slide) throws Exception {
        PathObjectHierarchy hierarchy = slide.readHierarchy();
        return CohortSampler.detectionFingerprint(new ArrayList<>(hierarchy.getDetectionObjects()));
    }

    /** Remove a slide's per-slide files: a failed slide must leave nothing to be taken for its result. */
    private static void deleteOutputs(File outDir, String fileBase) {
        for (String suffix : List.of(PHENO_SUFFIX, POPULATIONS_SUFFIX, QC_SUFFIX)) {
            try {
                Files.deleteIfExists(new File(outDir, fileBase + suffix).toPath());
            } catch (IOException | RuntimeException e) {
                logger.warn("Could not delete {}{}", fileBase, suffix, e);
            }
        }
    }

    /**
     * What to check on a gated slide — recorded, never fatal: fewer than {@value #MIN_CELLS} cells;
     * the ROI filter on but no area annotation on the slide (the whole slide was used); an enabled
     * gate's channel the slide does not measure (its cells are unmeasured, not negative).
     */
    static List<String> sanity(GateTree tree, BatchRunner.Gated g) {
        List<String> out = new ArrayList<>();
        if (g.index().size() < MIN_CELLS) out.add("fewer-than-" + MIN_CELLS + "-cells");
        if (g.roiWithoutAnnotation()) out.add("roi-filter-without-annotation");
        Set<String> missing = new LinkedHashSet<>();
        for (GateWalk.Entry e : GateWalk.enabled(tree)) {
            for (String ch : e.gate().getChannels()) {
                if (ch != null && !ch.isEmpty() && !g.result().markers().contains(ch)) missing.add(ch);
            }
        }
        missing.forEach(ch -> out.add("missing-channel:" + ch));
        return out;
    }

    /**
     * The slide's own {@code qc_summary.csv} rows. {@code pct_unmeasured} is, of the cells reaching
     * each enabled gate, the share its {@link GateReadout#branchIgnoringClip} reads
     * {@link GateReadout#UNMEASURED} — the gate predicate's own answer, not a second test.
     */
    private static void writeSlideQc(Writer w, GateTree tree, BatchRunner.Gated g, List<String> sanity) throws IOException {
        String id = g.result().slideId(), name = g.result().imageName();
        int n = g.index().size();
        qc(w, id, name, "cells", "", count(n));
        qc(w, id, name, "cells_clean", "", count(g.assignment().getTally().cellsClean()));
        qc(w, id, name, "pct_quality_filtered", "", pct(countFalse(g.qualityMask()), n));
        qc(w, id, name, "pct_outside_roi", "", pct(countFalse(g.roi()), n));
        TreeResolver.ResolvedTree resolved = g.result().resolved();
        GateReadout readout = GateReadout.compile(resolved.tree(), g.index(), g.stats());
        boolean[] base = g.roi() == null ? g.qualityMask()
                : g.qualityMask() == null ? g.roi() : GatingEngine.combineMasks(g.qualityMask(), g.roi());
        for (GateWalk.Entry e : GateWalk.enabled(tree)) {
            GateNode target = resolved.resolvedOf(e.gate());
            boolean[] reaching = GatingEngine.computeAncestorMask(resolved.tree(), target, g.index(), g.stats(), base);
            int reach = 0, unmeasured = 0;
            for (int i = 0; i < reaching.length; i++) {
                if (!reaching[i]) continue;
                reach++;
                if (readout.branchIgnoringClip(target, i) == GateReadout.UNMEASURED) unmeasured++;
            }
            qc(w, id, name, "pct_unmeasured", e.rootIndex() + ":" + e.gatePath(), pct(unmeasured, reach));
        }
        for (String s : sanity) qc(w, id, name, "sanity", s, count(1));
    }

    /**
     * The run's combined outputs and provenance bundle, from the per-slide files every successful
     * slide — gated now or resumed — left in {@code outDir}.
     *
     * @param tree     the tree as run, {@code BatchRunner.Settings.tree()}: each result's resolved
     *                 tree is identity-keyed on its nodes
     * @param evidence the cohort the run was gated against, and how it was sampled; see {@link CohortEvidence}
     */
    public static void finish(File outDir, GateTree tree, List<SlideRun> runs, CohortEvidence evidence)
            throws IOException {
        // A slide whose per-slide file vanished since its step (deleted by hand, a full disk
        // mid-write) is left out and logged, never the end of the bundle; the run state still
        // checks the files, so the next run gates it again.
        List<SlideRun> done = runs.stream().filter(r -> r.result().succeeded() && outputsExist(outDir, r)).toList();
        try (Writer w = writer(new File(outDir, BatchRunner.COMBINED_FILE))) {
            PopulationStatsExporter.writeHeader(w, true);
            for (SlideRun r : done) appendAfterHeader(w, new File(outDir, r.fileBase() + POPULATIONS_SUFFIX), true);
        }
        try (Writer w = writer(new File(outDir, QC_SUMMARY))) {
            w.write(QC_HEADER + "\n");
            for (SlideRun r : done) {
                appendAfterHeader(w, new File(outDir, r.fileBase() + QC_SUFFIX), false);
                writeCohortQc(w, tree, r.result(), evidence);
            }
        }
        GatingManifestExporter.write(new File(outDir, GatingManifestExporter.FILE), tree,
                runs.stream().map(SlideRun::result).toList(), evidence.annotations());
        FlowPathSerializer.save(tree, new File(outDir, TREE_FILE));
        try (Writer w = writer(new File(outDir, RUN_INFO))) {
            w.write("flowpath_version=" + version() + "\n");
            w.write("date=" + Instant.now().truncatedTo(ChronoUnit.SECONDS) + "\n");
            CohortEvidence.Provenance p = evidence.provenance();
            w.write("sampled_cells_per_slide=" + p.cellsPerSlide() + "\n");
            w.write("sample_size_source=" + p.sampleSizeSource() + "\n");
            if (p.cacheHits() >= 0) w.write("alignment_cache_hits=" + p.cacheHits() + "/" + p.sampled() + "\n");
            w.write("reference_slide=" + (tree.getReferenceSlideId() == null ? "none" : tree.getReferenceSlideId()) + "\n");
            if (p.referenceSlideName() != null) w.write("reference_slide_name=" + p.referenceSlideName() + "\n");
            w.write("slides=" + runs.size() + "\n");
        }
    }

    /**
     * The slide's cohort rows: its alignment per column (what the run corrected with — none when
     * the lookup answers nothing), its marker-rule rates as the review computed them, and its
     * review state. {@code staining_offset} is the slide's L1 minus the reference's, in asinh
     * units ({@link Alignment#shift}). Rule subjects carry root indices
     * ({@link MarkerRules.Rule#indexedLabel}). {@code reviewed_flags} counts the enabled gates
     * {@link ReviewScorer#answered} on this slide.
     */
    private static void writeCohortQc(Writer w, GateTree tree, BatchResult r, CohortEvidence evidence)
            throws IOException {
        String id = r.slideId(), name = r.imageName();
        if (evidence.excluded().contains(id)) qc(w, id, name, "cohort_excluded", "", count(1));
        for (AlignmentModel.ColumnRef col : AlignmentModel.columnsOf(tree)) {
            Alignment a = evidence.lookup().alignment(id, col.key());
            if (a == null) continue;
            qc(w, id, name, "staining_offset", col.key(), decimal(a.shift()));
            qc(w, id, name, "staining_stretch", col.key(), decimal(a.stretch()));
        }
        for (MarkerRules.RuleRate rate : evidence.review().rules().rates()) {
            if (!rate.slideId().equals(id)) continue;
            qc(w, id, name, "rule_violation_pct", rate.rule().indexedLabel(), pct(rate.violations(), rate.judged()));
        }
        qc(w, id, name, "open_flags", "", count(evidence.review().items().stream()
                .filter(i -> i.key().slideId().equals(id)).count()));
        int reviewed = 0;
        for (GateWalk.Entry e : GateWalk.enabled(tree)) {
            TreeResolver.Applied applied = r.resolved().applied(e.gate());
            if (applied != null && ReviewScorer.answered(e.gate(), id, applied.applied())) reviewed++;
        }
        qc(w, id, name, "reviewed_flags", "", count(reviewed));
    }

    private static void qc(Writer w, String id, String name, String metric, String subject, String value)
            throws IOException {
        w.write(String.join(",", CellTable.escape(id), CellTable.escape(name), metric, CellTable.escape(subject), value));
        w.write('\n');
    }

    private static String count(long n) {
        return Long.toString(n);
    }

    /** {@code 100 * part / whole}, blank when {@code whole} is 0 — a share of nothing is not 0%. */
    private static String pct(long part, long whole) {
        return whole == 0 ? "" : decimal(100.0 * part / whole);
    }

    private static String decimal(double v) {
        return Double.isFinite(v) ? String.format(Locale.US, "%.4f", v) : "";
    }

    /** {@link BatchRunner#summary} plus what only this run knows: resumed slides and sanity flags; for tests. */
    static String summary(File outDir, List<SlideRun> runs, int total, boolean cancelled) {
        return summary(outDir, runs, total, cancelled, id -> false);
    }

    /** As {@link #summary(File, List, int, boolean)}; {@code openNow} as {@link BatchRunner#summary}. */
    public static String summary(File outDir, List<SlideRun> runs, int total, boolean cancelled,
                                 Predicate<String> openNow) {
        StringBuilder sb = new StringBuilder(BatchRunner.summary(outDir, runs.stream().map(SlideRun::result).toList(),
                total, cancelled, openNow));
        sb.append("\n\nWith ").append(QC_SUMMARY).append(", ").append(TREE_FILE).append(" and ").append(RUN_INFO)
          .append(" beside them.");
        List<String> resumed = runs.stream().filter(SlideRun::resumed).map(r -> r.result().imageName()).toList();
        if (!resumed.isEmpty()) {
            sb.append("\n\nAlready done by an earlier run, not gated again:\n  ").append(String.join("\n  ", resumed));
        }
        List<String> sanity = runs.stream().filter(r -> !r.sanity().isEmpty())
                .map(r -> r.result().imageName() + " — " + String.join(", ", r.sanity())).toList();
        if (!sanity.isEmpty()) sb.append("\n\nCheck:\n  ").append(String.join("\n  ", sanity));
        return sb.toString();
    }

    /** The project's images as the sampler sees them: one adapter, shared with {@link #batchSlides}. */
    public static List<SlideSource> slideSources(Project<BufferedImage> project) {
        return batchSlides(project).stream().map(FlowPathBatch::asSource).toList();
    }

    /** The project's images as a run sees them: read and written through their {@code ProjectImageEntry}. */
    public static List<BatchSlide> batchSlides(Project<BufferedImage> project) {
        return project.getImageList().stream().<BatchSlide>map(e -> new BatchSlide() {
            @Override public String id() { return e.getID(); }
            @Override public String name() { return e.getImageName(); }
            @Override public ImageData<BufferedImage> read() throws Exception { return e.readImageData(); }
            @Override public PathObjectHierarchy readHierarchy() throws Exception { return e.readHierarchy(); }
            @Override public void save(ImageData<BufferedImage> data) throws Exception { e.saveImageData(data); }
            @Override public boolean cohortExcluded() { return "true".equals(e.getMetadataValue(CohortExclusions.KEY)); }
        }).toList();
    }

    static SlideSource asSource(BatchSlide slide) {
        return new SlideSource() {
            @Override public String id() { return slide.id(); }
            @Override public String name() { return slide.name(); }
            @Override public PathObjectHierarchy readHierarchy() throws Exception { return slide.readHierarchy(); }
        };
    }

    /** The JAR's implementation version; {@code "dev"} from a classes directory (tests, an IDE). */
    static String version() {
        String v = FlowPathBatch.class.getPackage().getImplementationVersion();
        return v == null || v.isBlank() ? "dev" : v;
    }

    private static Writer writer(File f) throws IOException {
        return new BufferedWriter(new FileWriter(f, StandardCharsets.UTF_8));
    }

    /** Copy {@code f} into {@code w} byte for byte, without its first line when {@code skipHeader}. */
    private static void appendAfterHeader(Writer w, File f, boolean skipHeader) throws IOException {
        String text = Files.readString(f.toPath(), StandardCharsets.UTF_8);
        if (skipHeader) {
            int nl = text.indexOf('\n');
            text = nl < 0 ? "" : text.substring(nl + 1);
        }
        w.write(text);
    }

    private static boolean outputsExist(File outDir, SlideRun r) {
        for (String suffix : List.of(PHENO_SUFFIX, POPULATIONS_SUFFIX, QC_SUFFIX)) {
            if (!new File(outDir, r.fileBase() + suffix).isFile()) {
                logger.warn("{}{} is missing; {} is left out of the combined outputs and will be gated again next run",
                        r.fileBase(), suffix, r.result().imageName());
                return false;
            }
        }
        return true;
    }

    private static int countFalse(boolean[] mask) {
        if (mask == null) return 0;
        int n = 0;
        for (boolean b : mask) if (!b) n++;
        return n;
    }
}
