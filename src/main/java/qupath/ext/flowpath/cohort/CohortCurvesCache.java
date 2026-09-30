package qupath.ext.flowpath.cohort;

import qupath.ext.flowpath.engine.AlignmentLookup;
import qupath.ext.flowpath.model.Branch;
import qupath.ext.flowpath.model.GateAxis;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.GateValues;
import qupath.ext.flowpath.model.SlideSetting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The last {@link CohortCurves#of} answer, kept while nothing it depends on has changed. The All
 * slides view asks on every editor refresh, and a refresh comes with every resync, every selection
 * and every quality-filter tick, while each answer resolves the tree and walks the parent
 * population once per sample.
 * <p>
 * The key is what the answer is a function of:
 * <ul>
 *   <li>the shown gate by identity, plus its axis columns, its "Correct staining" flag and the
 *       slides it is skipped on;</li>
 *   <li>each ancestor on its path, by value: type, columns, values, enabled, clipping,
 *       correction, per-slide settings and the branch taken;</li>
 *   <li>the samples, element by element by identity;</li>
 *   <li>the alignment model by identity, the tree's reference slide and the open slide.</li>
 * </ul>
 * The shown gate's own numbers are deliberately <em>not</em> in the key. Its curves are its parent
 * population, so dragging its threshold must not recompute them.
 */
public final class CohortCurvesCache {

    /** The computation cached; {@link CohortCurves#of} outside tests. */
    @FunctionalInterface
    interface Compute {
        List<CohortCurves.SlideValues> of(GateTree tree, GateNode gate, List<SlideSample> samples,
                                          AlignmentLookup lookup, String currentSlideId);
    }

    private final Compute compute;

    private GateNode lastGate;
    private List<SlideSample> lastSamples;
    private Object lastModel;
    private List<Object> lastFingerprint;
    private List<CohortCurves.SlideValues> lastValues;

    public CohortCurvesCache() {
        this(CohortCurves::of);
    }

    CohortCurvesCache(Compute compute) {
        this.compute = compute;
    }

    /**
     * {@code CohortCurves.of(tree, gate, samples, lookup, currentSlideId)}, recomputed only when the
     * key changed. {@code model} is the alignment model {@code lookup} currently reads, compared by
     * identity. It stands for the lookup, which is one stable instance.
     */
    public List<CohortCurves.SlideValues> get(GateTree tree, GateNode gate, List<SlideSample> samples,
                                              Object model, AlignmentLookup lookup, String currentSlideId) {
        List<Object> fingerprint = fingerprint(tree, gate, currentSlideId);
        if (lastValues != null && gate == lastGate && model == lastModel
                && sameElements(samples, lastSamples) && fingerprint.equals(lastFingerprint)) {
            return lastValues;
        }
        List<CohortCurves.SlideValues> values = List.copyOf(compute.of(tree, gate, samples, lookup, currentSlideId));
        lastGate = gate;
        lastSamples = List.copyOf(samples);
        lastModel = model;
        lastFingerprint = fingerprint;
        lastValues = values;
        return values;
    }

    private static boolean sameElements(List<SlideSample> a, List<SlideSample> b) {
        if (b == null || a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (a.get(i) != b.get(i)) return false;
        return true;
    }

    private static List<Object> fingerprint(GateTree tree, GateNode gate, String currentSlideId) {
        List<Object> key = new ArrayList<>();
        key.add(tree.getReferenceSlideId());
        key.add(currentSlideId);
        key.add(columns(gate));
        key.add(gate.isCorrectStaining());
        TreeSet<String> skipped = new TreeSet<>();
        gate.getSlideSettings().forEach((slide, setting) -> {
            if (setting instanceof SlideSetting.Skip) skipped.add(slide);
        });
        key.add(skipped);
        List<Object> path = new ArrayList<>();
        key.add(pathTo(tree.getRoots(), gate, path) ? path : "absent");
        return key;
    }

    private static List<Object> columns(GateNode gate) {
        return List.of(gate.getGateType(), new ArrayList<>(gate.getChannels()),
                GateAxis.axesOf(gate).stream().map(GateAxis::signal).toList());
    }

    /** Appends one entry per ancestor of {@code target}, root first; false when it is not in the tree. */
    private static boolean pathTo(List<GateNode> level, GateNode target, List<Object> path) {
        for (int i = 0; i < level.size(); i++) {
            GateNode node = level.get(i);
            if (node == target) return true;
            List<Branch> branches = node.getBranches();
            for (int b = 0; b < branches.size(); b++) {
                path.add(ancestor(node, b));
                if (pathTo(branches.get(b).getChildren(), target, path)) return true;
                path.remove(path.size() - 1);
            }
        }
        return false;
    }

    private static List<Object> ancestor(GateNode node, int branch) {
        Map<String, SlideSetting> settings = new LinkedHashMap<>(node.getSlideSettings());
        return List.of(columns(node), GateValues.read(node), node.isEnabled(),
                node.getClipPercentileLow(), node.getClipPercentileHigh(), node.isExcludeOutliers(),
                node.isCorrectStaining(), settings, branch);
    }
}
