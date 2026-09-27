package qupath.ext.flowpath.cohort;

import qupath.ext.flowpath.engine.GatingEngine;
import qupath.ext.flowpath.engine.TreeResolver;
import qupath.ext.flowpath.model.GateAxis;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.GateValues;
import qupath.ext.flowpath.model.GateWalk;
import qupath.ext.flowpath.model.MeasuredColumn;
import qupath.ext.flowpath.model.Region2DGate;
import qupath.ext.flowpath.model.SlideSetting;
import qupath.ext.flowpath.model.cohort.Density;
import qupath.ext.flowpath.model.cohort.Landmarks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Scores every slide x gate against the four flags of spec §6, top-down in tree order: no
 * landmark, unusual staining, on a peak, can't judge. Pure — no JavaFX, no mutation.
 */
public final class ReviewScorer {

    public static final int MIN_PARENT_CELLS = 300;
    public static final double MIN_COVERAGE = 0.90;
    public static final double ON_PEAK_FRACTION = 0.5;

    public record Result(List<ReviewItem> items, List<ReviewItem.Info> infos) {
        /** The manifest's {@code flags} column for {@code (slideId, rootIndex, gatePath)}: {@code ;}-joined tokens, {@code ""} when none. */
        public String flagsFor(String slideId, int rootIndex, String gatePath) {
            StringJoiner j = new StringJoiner(";");
            ReviewItem.Key key = new ReviewItem.Key(slideId, rootIndex, gatePath);
            for (ReviewItem i : items) {
                if (i.key().equals(key)) {
                    for (ReviewItem.Flag f : i.flags()) j.add(f.token());
                }
            }
            return j.toString();
        }
    }

    private ReviewScorer() {}

    public static Result score(GateTree tree, List<SlideSample> samples, AlignmentModel model) {
        Map<String, TreeResolver.ResolvedTree> resolved = new HashMap<>();
        for (SlideSample s : samples) {
            resolved.put(s.slideId(), TreeResolver.resolve(tree, s.slideId(), model::alignment));
        }
        List<ReviewItem> items = new ArrayList<>();
        List<ReviewItem.Info> infos = new ArrayList<>();
        for (GateWalk.Entry e : GateWalk.enabled(tree)) {
            GateNode gate = e.gate();
            for (SlideSample s : samples) {
                String absent = absentChannel(gate, s);
                if (absent != null) {
                    infos.add(new ReviewItem.Info(s.slideId(), s.name(), gate,
                            absent + " is not measured on this slide — its cells are unmeasured"));
                    continue;
                }

                TreeResolver.ResolvedTree r = resolved.get(s.slideId());
                TreeResolver.Applied applied = r.applied(gate);

                if (answered(gate, s.slideId(), applied.applied())) continue;

                boolean[] parent = GatingEngine.computeAncestorMask(r.tree(), r.resolvedOf(gate),
                        s.index(), s.stats(), s.clean());
                int parentCount = 0;
                for (boolean b : parent) if (b) parentCount++;

                Set<ReviewItem.Flag> flags = new LinkedHashSet<>();
                Set<String> reasons = new LinkedHashSet<>();
                boolean oneDimensionalCut = !(gate instanceof Region2DGate);

                for (int k = 0; k < GateAxis.axisCount(gate); k++) {
                    String column = applied.columns().get(k);
                    String channel = gate.getChannels().get(k);

                    Landmarks lm = model.landmarks(s.slideId(), column);
                    if (gate.isCorrectStaining() && !s.slideId().equals(model.referenceSlideId())
                            && lm != null && !lm.hasL1()) {
                        flags.add(ReviewItem.Flag.NO_LANDMARK);
                        reasons.add("No clear negative peak — not corrected");
                    }

                    String unusual = model.unusualStaining(s.slideId(), column);
                    if (unusual != null) {
                        flags.add(ReviewItem.Flag.UNUSUAL_STAINING);
                        reasons.add(unusual);
                    }

                    MeasuredColumn measuredColumn = s.index().column(channel, gate.compartmentAt(k), gate.statisticAt(k), s.stats());
                    double[] raw = measuredColumn.values();

                    if (parentCount < MIN_PARENT_CELLS) {
                        flags.add(ReviewItem.Flag.CANT_JUDGE);
                        reasons.add(String.format(Locale.US, "Only %d cells reach this gate", parentCount));
                        continue;
                    }

                    int measured = 0;
                    for (int i = 0; i < raw.length; i++) {
                        if (parent[i] && Double.isFinite(raw[i])) measured++;
                    }
                    if (measured < MIN_COVERAGE * parentCount) {
                        flags.add(ReviewItem.Flag.CANT_JUDGE);
                        reasons.add(String.format(Locale.US, "Only %d%% of cells reaching this gate are measured on %s",
                                (int) Math.floor(100.0 * measured / parentCount), channel));
                        continue;
                    }

                    if (oneDimensionalCut) {
                        double c = model.cofactor(column);
                        if (Double.isFinite(c)) {
                            Density density = Density.of(Landmarks.toAsinh(raw, parent, c));
                            double u = Landmarks.asinh(applied.applied().axis(k)[0], c);
                            Density.Peak peak = density.nearestPeak(u);
                            if (peak != null && density.at(u) >= ON_PEAK_FRACTION * peak.height()) {
                                flags.add(ReviewItem.Flag.ON_PEAK);
                                reasons.add("Threshold sits on a peak, not in a valley");
                            }
                        }
                    }
                }

                if (!flags.isEmpty()) {
                    items.add(new ReviewItem(new ReviewItem.Key(s.slideId(), e.rootIndex(), e.gatePath()), s.name(), gate,
                            List.copyOf(flags), List.copyOf(reasons), applied.applied()));
                }
            }
        }
        return new Result(List.copyOf(items), List.copyOf(infos));
    }

    /**
     * Whether {@code gate} on {@code slideId} is already answered — {@code Skip} or
     * {@code Manual} — or hidden — {@code Reviewed} whose recorded values still match
     * {@code applied} (a review is of a number: a moved value re-opens it). The one place
     * this rule is written, so a later caller (marker rules, Task 15) asks it rather than
     * copying the block.
     */
    public static boolean answered(GateNode gate, String slideId, GateValues applied) {
        SlideSetting setting = gate.slideSetting(slideId);
        if (setting instanceof SlideSetting.Skip || setting instanceof SlideSetting.Manual) return true;
        return setting instanceof SlideSetting.Reviewed rv && rv.appliedValues().matches(applied);
    }

    /** The first axis channel {@code s}'s sample does not carry, or null when every axis is measured. */
    private static String absentChannel(GateNode gate, SlideSample s) {
        List<String> channels = gate.getChannels();
        for (int k = 0; k < GateAxis.axisCount(gate); k++) {
            String ch = k < channels.size() ? channels.get(k) : null;
            if (ch == null || s.index().getMarkerIndex(ch) < 0) return String.valueOf(ch);
        }
        return null;
    }
}
