package qupath.ext.flowpath.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.flowpath.engine.AlignmentLookup;
import qupath.ext.flowpath.engine.GatingEngine;
import qupath.ext.flowpath.engine.TreeResolver;
import qupath.ext.flowpath.ingest.DetectionIngest;
import qupath.ext.flowpath.ingest.IngestResult;
import qupath.ext.flowpath.io.PhenotypeCsvExporter;
import qupath.ext.flowpath.io.PopulationStatsExporter;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.PopulationStats;
import qupath.ext.flowpath.model.RegionMask;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;

import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/**
 * Runs the gate tree over every slide, headlessly: no JavaFX, no {@code QuPathGUI}, no
 * {@code Project} — slides arrive through {@link BatchSlide}. Each slide is gated on
 * {@code TreeResolver.resolve(tree, slide.id(), alignments)}, the same applied values the
 * user saw (CLAUDE.md "one resolution point"), not a shared {@code deepCopy()} — so a
 * misalignment correction, a manual override or a skipped gate all follow the slide it was
 * set for.
 */
public final class BatchRunner {

    private static final Logger logger = LoggerFactory.getLogger(BatchRunner.class);

    public static final String COMBINED_FILE = "batch_populations.csv";

    public record Settings(GateTree tree, AlignmentLookup alignments, File outputDir, String openSlideId,
                           boolean writeBack) {
        public Settings {
            Objects.requireNonNull(tree, "tree");
            Objects.requireNonNull(outputDir, "outputDir");
            tree = tree.deepCopy();   // frozen once: an edit made while the run goes never reaches it
            alignments = alignments == null ? AlignmentLookup.NONE : alignments;
        }
    }

    private BatchRunner() {}

    /**
     * Gate every slide in order, reporting progress before each one starts and honoring
     * cancellation between slides. Per spec §7, cancellation "stops before the next slide":
     * a slide whose progress has already been announced is still gated.
     */
    public static List<BatchResult> run(List<BatchSlide> slides, Settings settings,
                                        BiConsumer<Integer, String> progress, BooleanSupplier cancelled) {
        List<BatchResult> results = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (int i = 0; i < slides.size(); i++) {
            if (cancelled.getAsBoolean()) break;
            BatchSlide slide = slides.get(i);
            progress.accept(i, slide.name());
            results.add(gateOne(slide, phenoFileName(slide.name(), used), settings));
        }
        return results;
    }

    public static BatchResult gateOne(BatchSlide slide, String phenoFileName, Settings settings) {
        try {
            ImageData<BufferedImage> data = slide.read();
            List<PathObject> detections = new ArrayList<>(data.getHierarchy().getDetectionObjects());
            if (detections.isEmpty()) return BatchResult.failed(slide.id(), slide.name(), "no detections on this slide");
            IngestResult ingest = DetectionIngest.read(detections, data);
            CellIndex index = ingest.index();

            TreeResolver.ResolvedTree resolved = TreeResolver.resolve(settings.tree(), slide.id(), settings.alignments());
            GateTree tree = resolved.tree();

            RegionMask regions = null;
            if (tree.isRoiFilterEnabled()) {
                RegionMask computed = RegionMask.compute(index, new ArrayList<>(data.getHierarchy().getAnnotationObjects()));
                regions = computed.isEmpty() ? null : computed;
            }
            boolean[] roi = regions == null ? null : regions.included();
            MarkerStats stats = tree.getQualityFilter() == null
                    ? MarkerStats.compute(index, roi)
                    : GatingEngine.recomputeStats(index, tree.getQualityFilter(), roi);
            GatingEngine.AssignmentResult result = GatingEngine.assignAll(tree, index, stats, roi,
                    regions == null ? null : regions.regionOf(), regions == null ? 0 : regions.regionNames().size());
            PopulationStats population = PopulationStats.of(tree, result.getTally(),
                    regions == null ? List.of() : regions.regionNames(), null, null);
            PhenotypeCsvExporter.export(new File(settings.outputDir(), phenoFileName), index, result, tree, stats, regions);

            BatchResult ok = new BatchResult(slide.id(), slide.name(), index.size(), ingest.markerNames(), population,
                    resolved, ingest.report(), BatchResult.WriteBack.NOT_REQUESTED, null, null);
            return writeBack(ok, slide, data, index, result, settings);
        } catch (Exception | Error ex) {
            // Error too: gating every cell of a large slide is where an OutOfMemoryError is plausible.
            logger.warn("Batch gating failed for {}", slide.name(), ex);
            return BatchResult.failed(slide.id(), slide.name(),
                    ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        }
    }

    /** Task 9 fills this in; until then nothing is written back. */
    static BatchResult writeBack(BatchResult ok, BatchSlide slide, ImageData<BufferedImage> data, CellIndex index,
                                 GatingEngine.AssignmentResult result, Settings settings) {
        return ok;
    }

    public static String phenoFileName(String imageName, Set<String> used) {
        String base = (imageName == null || imageName.isBlank() ? "image" : imageName).replaceAll("[^A-Za-z0-9._-]", "_");
        String candidate = base;
        for (int k = 2; !used.add(candidate); k++) candidate = base + "_" + k;
        return candidate + "_gate_pheno.csv";
    }

    /** One header, then every successful slide's rows stamped with its image name. */
    public static void writeCombined(File file, List<BatchResult> results) throws IOException {
        try (BufferedWriter w = new BufferedWriter(new FileWriter(file, StandardCharsets.UTF_8))) {
            PopulationStatsExporter.writeHeader(w, true);
            for (BatchResult r : results) {
                if (r.succeeded()) PopulationStatsExporter.writeRows(w, r.stats(), r.imageName());
            }
        }
    }
}
