package qupath.ext.flowpath.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.flowpath.cohort.CohortIdentity;
import qupath.ext.flowpath.cohort.CohortSession;
import qupath.ext.flowpath.engine.AlignmentLookup;
import qupath.ext.flowpath.engine.CleanMask;
import qupath.ext.flowpath.engine.GatingEngine;
import qupath.ext.flowpath.engine.PhenotypeClassWriter;
import qupath.ext.flowpath.engine.TreeResolver;
import qupath.ext.flowpath.ingest.DetectionIngest;
import qupath.ext.flowpath.ingest.IngestResult;
import qupath.ext.flowpath.io.PhenotypeCsvExporter;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.PopulationStats;
import qupath.ext.flowpath.model.RegionMask;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Gates one slide at a time, headlessly: no JavaFX, no {@code QuPathGUI}, no {@code Project} —
 * slides arrive through {@link BatchSlide}, and the run around them is {@link FlowPathBatch}'s.
 * Each slide is gated on
 * {@code TreeResolver.resolve(tree, slide.id(), alignments)}, the same applied values the
 * user saw (CLAUDE.md "one resolution point"), not a shared {@code deepCopy()} — so a
 * misalignment correction, a manual override or a skipped gate all follow the slide it was
 * set for.
 */
public final class BatchRunner {

    private static final Logger logger = LoggerFactory.getLogger(BatchRunner.class);

    public static final String COMBINED_FILE = "batch_populations.csv";
    public static final String PHENO_SUFFIX = "_gate_pheno.csv";

    /**
     * @param isOpen whether a slide id is open in the viewer <em>now</em> — asked live, from the
     *               background thread, at every write-back check (see {@link #writeBack}), because
     *               the user can open or close slides while the run goes. Must be thread-safe.
     */
    public record Settings(GateTree tree, AlignmentLookup alignments, File outputDir, Predicate<String> isOpen,
                           boolean writeBack, int colorRootIndex) {
        public Settings {
            Objects.requireNonNull(tree, "tree");
            Objects.requireNonNull(outputDir, "outputDir");
            tree = tree.deepCopy();   // frozen once: an edit made while the run goes never reaches it
            alignments = alignments == null ? AlignmentLookup.NONE : alignments;
            isOpen = isOpen == null ? id -> false : isOpen;
        }

        /**
         * For a caller whose open slide cannot change during the run — headless, where it is
         * null: one fixed id (or none), and colour root -1 (a root's own colours, or the last
         * enabled root's when none is chosen), the default for a caller with no viewer to match.
         */
        public Settings(GateTree tree, AlignmentLookup alignments, File outputDir, String openSlideId,
                        boolean writeBack) {
            this(tree, alignments, outputDir, openSlideId == null ? null : openSlideId::equals, writeBack, -1);
        }
    }

    private BatchRunner() {}

    /** One slide, gated, with what {@link FlowPathBatch} reports beyond the {@link BatchResult}; nulls on failure. */
    public record Gated(BatchResult result, CellIndex index, GatingEngine.AssignmentResult assignment, MarkerStats stats,
                        boolean[] qualityMask, boolean[] roi, boolean roiWithoutAnnotation) {}

    public static BatchResult gateOne(BatchSlide slide, String phenoFileName, Settings settings) {
        return gateDetailed(slide, phenoFileName, settings).result();
    }

    /** {@link #gateOne}, keeping the index, masks and assignment for a caller that reports more than the result. */
    public static Gated gateDetailed(BatchSlide slide, String phenoFileName, Settings settings) {
        try {
            // Asked before the read: a slide open now may be saved from QuPath after this read,
            // and writing back what was read would then lose those edits.
            boolean openAtRead = settings.isOpen().test(slide.id());
            ImageData<BufferedImage> data = slide.read();
            List<PathObject> detections = new ArrayList<>(data.getHierarchy().getDetectionObjects());
            if (detections.isEmpty()) return failed(slide, "no detections on this slide");
            IngestResult ingest = DetectionIngest.read(detections, data);
            CellIndex index = ingest.index();

            TreeResolver.ResolvedTree resolved = TreeResolver.resolve(settings.tree(), slide.id(), settings.alignments());
            GateTree tree = resolved.tree();

            // The one quality-then-ROI composition the live pass and the cohort's samples use too.
            CleanMask clean = CleanMask.of(index, tree.getQualityFilter(), tree.isRoiFilterEnabled(),
                    tree.isRoiFilterEnabled() ? new ArrayList<>(data.getHierarchy().getAnnotationObjects()) : List.of());
            RegionMask regions = clean.regions();
            boolean[] roi = clean.roi();
            boolean[] quality = clean.quality();
            MarkerStats stats = MarkerStats.compute(index, clean.combined());
            GatingEngine.AssignmentResult result = GatingEngine.assignAll(tree, index, stats, roi,
                    regions == null ? null : regions.regionOf(), regions == null ? 0 : regions.regionNames().size());
            PopulationStats population = PopulationStats.of(tree, result.getTally(),
                    regions == null ? List.of() : regions.regionNames(), null, null);
            PhenotypeCsvExporter.export(new File(settings.outputDir(), phenoFileName), index, result, tree, stats, regions);

            BatchResult ok = new BatchResult(slide.id(), slide.name(), index.size(), ingest.markerNames(), population,
                    resolved, ingest.report(), BatchResult.WriteBack.NOT_REQUESTED, null, null);
            return new Gated(writeBack(ok, slide, data, index, result, settings, openAtRead), index, result, stats,
                    quality, roi, tree.isRoiFilterEnabled() && regions == null);
        } catch (Exception | Error ex) {
            // Error too: gating every cell of a large slide is where an OutOfMemoryError is plausible.
            restoreInterrupt(ex);
            logger.warn("Batch gating failed for {}", slide.name(), ex);
            return failed(slide, describe(ex));
        }
    }

    private static Gated failed(BatchSlide slide, String reason) {
        return new Gated(BatchResult.failed(slide.id(), slide.name(), reason), null, null, null, null, null, false);
    }

    /**
     * Re-set the thread's interrupt flag when {@code ex} is an {@link InterruptedException}: caught
     * and recorded as a slide's failure, it would otherwise swallow the cancel a script's
     * "Kill running script" (a thread interrupt) is asking for.
     */
    static void restoreInterrupt(Throwable ex) {
        if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
    }

    /** What a failure says: its message, or its type when it has none (an {@code OutOfMemoryError} may not). */
    static String describe(Throwable ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    /**
     * Phenotypes into the slide's own .qpdata — except a slide open in the viewer, whose file is
     * never written behind QuPath's back (QuPath would overwrite it on its next save, and a save
     * from QuPath between this run's read and its write would be lost); the live pass classifies
     * an open slide itself.
     * <p>
     * "Open" is asked live, three times: before the read ({@code openAtRead}), before the classes
     * are written, and right before the save. A slide open at any of them is skipped. What remains
     * is the gap between the last check and the end of {@code slide.save} — milliseconds to write
     * one file — during which a slide opened <em>and</em> saved from QuPath would still be
     * overwritten; closing it entirely would need a lock QuPath does not offer.
     */
    static BatchResult writeBack(BatchResult ok, BatchSlide slide, ImageData<BufferedImage> data, CellIndex index,
                                 GatingEngine.AssignmentResult result, Settings settings, boolean openAtRead) {
        if (!settings.writeBack()) return ok;
        BatchResult skipped = ok.withWriteBack(BatchResult.WriteBack.SKIPPED_OPEN_SLIDE, null);
        if (openAtRead || settings.isOpen().test(slide.id())) return skipped;
        try {
            PhenotypeClassWriter.apply(result, index, settings.colorRootIndex());
            if (settings.isOpen().test(slide.id())) return skipped;
            slide.save(data);
            return ok.withWriteBack(BatchResult.WriteBack.SAVED, null);
        } catch (Exception | Error ex) {
            // Error too: the slide was already gated and exported, so a save failure (or an
            // OutOfMemoryError writing a large .qpdata) is a write-back failure, not a slide failure.
            restoreInterrupt(ex);
            logger.warn("Could not save phenotypes into {}", slide.name(), ex);
            return ok.withWriteBack(BatchResult.WriteBack.FAILED, describe(ex));
        }
    }

    /**
     * A fresh {@code used} set for {@link #fileBase}, one per run. It starts out holding
     * {@code "batch"}: an image of that name, in any case, would otherwise write its per-slide
     * {@code batch_populations.csv} over the run's combined table of the same name.
     */
    public static Set<String> newFileBases() {
        Set<String> used = new HashSet<>();
        used.add(COMBINED_FILE.substring(0, COMBINED_FILE.indexOf("_populations.csv")).toLowerCase(Locale.ROOT));
        return used;
    }

    /**
     * A file-system-safe name for {@code imageName}, unique among {@code used} (which it joins, lower-cased):
     * the stem every per-slide output of one run shares. Deterministic in slide order, so a
     * resumed run gives each slide the same stem as the run it resumes.
     */
    public static String fileBase(String imageName, Set<String> used) {
        String base = (imageName == null || imageName.isBlank() ? "image" : imageName).replaceAll("[^A-Za-z0-9._-]", "_");
        String candidate = base;
        // Compared case-insensitively: macOS and Windows file systems are, so "A.tif" and "a.tif"
        // would otherwise share every per-slide file.
        for (int k = 2; !used.add(candidate.toLowerCase(Locale.ROOT)); k++) candidate = base + "_" + k;
        return candidate;
    }

    public static final String NO_ENABLED_GATE = "No enabled gates to run.";

    /** Whether {@code tree} has anything to run: the one rule the GUI's button and every run's {@link #refusal} use. */
    public static boolean hasEnabledGate(GateTree tree) {
        return tree.getRoots().stream().anyMatch(GateNode::isEnabled);
    }

    /**
     * Why a run of {@code tree} over {@code slides} must not start, or null: the tree has no
     * enabled gate ({@value #NO_ENABLED_GATE}), or it belongs to another project. A tree whose recorded
     * slide names contradict these slides' names ({@link CohortIdentity}) carries another project's
     * per-slide settings: entry ids restart in every project, so they would land on different
     * images here. The live view merely switches them off; a run would write them into files.
     */
    public static String refusal(GateTree tree, List<BatchSlide> slides) {
        if (!hasEnabledGate(tree)) return NO_ENABLED_GATE;
        Map<String, String> names = new LinkedHashMap<>();
        for (BatchSlide s : slides) names.put(s.id(), s.name());
        if (CohortIdentity.matches(tree, names)) return null;
        return CohortSession.FOREIGN_TREE + ".\n\nRunning it on this project would apply those settings to other "
                + "images, so the run was not started. Load this project's gate tree to run it here.";
    }

    /** {@link #summary(File, List, int, boolean, Predicate)} with every skipped slide taken as still open. */
    public static String summary(File dir, List<BatchResult> results, int total, boolean cancelled) {
        return summary(dir, results, total, cancelled, id -> true);
    }

    /**
     * Plain words for the run report: a silently short table would hide which slides are missing
     * and why.
     *
     * @param openNow whether a slide is open in the viewer as the report is shown: a slide skipped
     *                for being open, but closed since, can no longer be saved from QuPath
     */
    public static String summary(File dir, List<BatchResult> results, int total, boolean cancelled,
                                 Predicate<String> openNow) {
        long ok = results.stream().filter(BatchResult::succeeded).count();
        StringBuilder sb = new StringBuilder();
        sb.append(cancelled ? "Cancelled after " + results.size() + " of " + total + " slide(s). "
                        : results.size() + " of " + total + " slide(s) processed. ")
          .append(ok).append(" gated.\n\nWrote ").append(COMBINED_FILE).append(", ")
          .append(GatingManifestExporter.FILE).append(" and one phenotype CSV per slide to ").append(dir.getPath());
        section(sb, "Skipped:", results.stream().filter(r -> !r.succeeded())
                .map(r -> r.imageName() + " — " + r.failure()).toList());
        section(sb, "Not saved, open in the viewer:", results.stream()
                .filter(r -> r.writeBack() == BatchResult.WriteBack.SKIPPED_OPEN_SLIDE)
                .map(r -> r.imageName() + (openNow.test(r.slideId())
                        ? " — already classified; save it from QuPath"
                        : " — closed since; its phenotypes were not written, run again to write them")).toList());
        section(sb, "Could not save:", results.stream()
                .filter(r -> r.writeBack() == BatchResult.WriteBack.FAILED)
                .map(r -> r.imageName() + " — " + r.writeBackError()).toList());
        return sb.toString();
    }

    private static void section(StringBuilder sb, String title, List<String> lines) {
        if (lines.isEmpty()) return;
        sb.append("\n\n").append(title);
        for (String l : lines) sb.append("\n  ").append(l);
    }
}
