package qupath.ext.flowpath.cohort;

import qupath.ext.flowpath.engine.AlignmentLookup;
import qupath.ext.flowpath.engine.GatingEngine;
import qupath.ext.flowpath.engine.TreeResolver;
import qupath.ext.flowpath.model.GateAxis;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.MeasuredColumn;
import qupath.ext.flowpath.model.cohort.Alignment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Every sampled slide's values for one gate, for the editor's All slides view: each slide's own
 * parent population (its resolved tree walked by the engine's predicate, as
 * {@link ReviewScorer} does), mapped into reference units by that slide's correction for the
 * axis — {@link TreeResolver#correctionFor}, never a map of its own, so the curves are drawn
 * through the same correction the slide is gated with.
 */
public final class CohortCurves {

    /**
     * One slide's values, in reference units. {@code y} is null for a one-axis gate; for a
     * two-axis gate {@code x[i]} and {@code y[i]} are the same cell. Finite values only: a cell
     * unmeasured on any axis is left out rather than drawn at a made-up position.
     */
    public record SlideValues(String slideId, String name, boolean current, double[] x, double[] y) {}

    private CohortCurves() {}

    /**
     * @param liveTree       the live gate tree ({@code liveGate} is one of its nodes)
     * @param samples        the cohort's samples, in the order their curves are stacked
     * @param currentSlideId the open slide's id, whose entry is marked {@code current}
     * @return one entry per sample whose index carries every channel the gate reads
     */
    public static List<SlideValues> of(GateTree liveTree, GateNode liveGate, List<SlideSample> samples,
                                       AlignmentLookup lookup, String currentSlideId) {
        int axes = GateAxis.axisCount(liveGate);
        List<SlideValues> out = new ArrayList<>();
        for (SlideSample s : samples) {
            MeasuredColumn[] columns = new MeasuredColumn[axes];
            boolean usable = axes > 0;
            for (int k = 0; k < axes && usable; k++) {
                String channel = GateAxis.of(liveGate, k).channel();
                if (channel == null || s.index().getMarkerIndex(channel) < 0) {
                    usable = false;
                } else {
                    columns[k] = s.index().column(liveGate, k, s.stats());
                    usable = columns[k] != null;
                }
            }
            if (!usable) continue;

            TreeResolver.ResolvedTree r = TreeResolver.resolve(liveTree, s.slideId(), lookup);
            boolean[] parent = GatingEngine.computeAncestorMask(r.tree(), r.resolvedOf(liveGate),
                    s.index(), s.stats(), s.clean());
            Alignment[] align = new Alignment[axes];
            for (int k = 0; k < axes; k++) {
                align[k] = TreeResolver.correctionFor(liveTree, liveGate, k, s.slideId(), lookup);
            }
            double[] rawX = columns[0].values();
            double[] rawY = axes == 2 ? columns[1].values() : null;
            double[] x = new double[parent.length];
            double[] y = rawY == null ? null : new double[parent.length];
            int n = 0;
            for (int i = 0; i < parent.length; i++) {
                if (!parent[i] || !Double.isFinite(rawX[i]) || (rawY != null && !Double.isFinite(rawY[i]))) continue;
                x[n] = align[0].inverse(rawX[i]);
                if (y != null) y[n] = align[1].inverse(rawY[i]);
                n++;
            }
            out.add(new SlideValues(s.slideId(), s.name(), s.slideId().equals(currentSlideId),
                    Arrays.copyOf(x, n), y == null ? null : Arrays.copyOf(y, n)));
        }
        return out;
    }
}
