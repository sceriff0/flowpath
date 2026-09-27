package qupath.ext.flowpath.batch;

import qupath.ext.flowpath.cohort.AlignmentModel;
import qupath.ext.flowpath.cohort.ReviewScorer;
import qupath.ext.flowpath.engine.AlignmentLookup;
import qupath.ext.flowpath.engine.TreeResolver;
import qupath.ext.flowpath.io.CellTable;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.GateWalk;
import qupath.ext.flowpath.model.SlideSetting;
import qupath.ext.flowpath.model.cohort.Landmarks;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.StringJoiner;

/**
 * {@code gating_manifest.csv}: the exact threshold used on every slide, per gate and axis, and
 * why. What makes per-slide thresholds acceptable in a methods section.
 * <p>
 * Lives in {@code batch}, not {@code io} (pre-flight ruling C7): {@code batch} already imports
 * {@code io.*} (for {@link CellTable}, {@code PhenotypeCsvExporter}, …), so an {@code io} class
 * importing {@link BatchResult} would create a package cycle.
 */
public final class GatingManifestExporter {

    public static final String FILE = "gating_manifest.csv";
    public static final String HEADER = "image_id,image_name,root_index,gate_path,axis,column,reference_value,"
            + "applied_value,source,ref_L1,ref_L2,slide_L1,slide_L2,review,flags";

    public interface Annotations {
        Landmarks reference(String columnKey);
        Landmarks slide(String slideId, String columnKey);
        String flags(String slideId, int rootIndex, String gatePath);

        Annotations NONE = new Annotations() {
            @Override public Landmarks reference(String columnKey) { return null; }
            @Override public Landmarks slide(String slideId, String columnKey) { return null; }
            @Override public String flags(String slideId, int rootIndex, String gatePath) { return ""; }
        };

        /**
         * The cohort's model and review as they stood when the run started. The landmark columns
         * describe the correction the run applied, so they are blank when {@code lookup} — the
         * run's alignments — is {@link AlignmentLookup#NONE}: correction disabled, a foreign
         * tree, or a model built for another reference would otherwise put landmarks beside
         * thresholds they never moved. The review flags stand either way.
         */
        static Annotations of(AlignmentModel model, ReviewScorer.Result review, AlignmentLookup lookup) {
            boolean corrected = lookup != null && lookup != AlignmentLookup.NONE;
            return new Annotations() {
                @Override public Landmarks reference(String columnKey) {
                    return corrected ? model.referenceLandmarks(columnKey) : null;
                }
                @Override public Landmarks slide(String slideId, String columnKey) {
                    return corrected ? model.landmarks(slideId, columnKey) : null;
                }
                @Override public String flags(String slideId, int rootIndex, String gatePath) {
                    return review.flagsFor(slideId, rootIndex, gatePath);
                }
            };
        }
    }

    private GatingManifestExporter() {}

    /**
     * @param liveTree the tree the results were resolved from — for a batch run,
     *                 {@code BatchRunner.Settings.tree()} — because {@link TreeResolver.ResolvedTree#applied}
     *                 is identity-keyed on that tree's nodes; a gate it cannot find writes no rows.
     */
    public static void write(File file, GateTree liveTree, List<BatchResult> results, Annotations annotations)
            throws IOException {
        List<GateWalk.Entry> gates = GateWalk.enabled(liveTree);
        try (BufferedWriter w = new BufferedWriter(new FileWriter(file, StandardCharsets.UTF_8))) {
            w.write(HEADER);
            w.write('\n');
            for (BatchResult r : results) {
                if (!r.succeeded()) continue;
                for (GateWalk.Entry e : gates) {
                    TreeResolver.Applied applied = r.resolved().applied(e.gate());
                    if (applied == null) continue;
                    boolean reviewed = e.gate().slideSetting(r.slideId()) instanceof SlideSetting.Reviewed rv
                            && rv.appliedValues().matches(applied.applied());
                    String flags = annotations.flags(r.slideId(), e.rootIndex(), e.gatePath());
                    List<String> channels = e.gate().getChannels();
                    for (int k = 0; k < applied.sources().size(); k++) {
                        String column = applied.columns().get(k);
                        TreeResolver.Source source = applied.sources().get(k);
                        boolean absent = k >= channels.size() || !r.markers().contains(channels.get(k));
                        boolean blank = source == TreeResolver.Source.SKIPPED || absent;
                        Landmarks ref = column == null ? null : annotations.reference(column);
                        Landmarks slide = column == null ? null : annotations.slide(r.slideId(), column);
                        StringJoiner row = new StringJoiner(",");
                        row.add(CellTable.escape(r.slideId()));
                        row.add(CellTable.escape(r.imageName()));
                        row.add(String.valueOf(e.rootIndex()));
                        row.add(CellTable.escape(e.gatePath()));
                        row.add(k == 0 ? "x" : "y");
                        row.add(CellTable.escape(column == null ? "" : column));
                        row.add(joined(applied.reference().axis(k)));
                        row.add(blank ? "" : joined(applied.applied().axis(k)));
                        row.add(source.token());
                        row.add(ref == null || !ref.hasL1() ? "" : Double.toString(ref.l1()));
                        row.add(ref == null || !ref.hasL2() ? "" : Double.toString(ref.l2()));
                        row.add(slide == null || !slide.hasL1() ? "" : Double.toString(slide.l1()));
                        row.add(slide == null || !slide.hasL2() ? "" : Double.toString(slide.l2()));
                        row.add(reviewed ? "ok" : "");
                        row.add(CellTable.escape(flags == null ? "" : flags));
                        w.write(row.toString());
                        w.write('\n');
                    }
                }
            }
        }
    }

    private static String joined(double[] values) {
        StringJoiner j = new StringJoiner(";");
        for (double v : values) j.add(Double.toString(v));
        return j.toString();
    }
}
