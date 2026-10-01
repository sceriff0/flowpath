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
import qupath.ext.flowpath.model.cohort.LogScale;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Scores every slide x gate against the four flags of spec §6, top-down in tree order: no
 * landmark, unusual staining, on a peak, can't judge — then merges in the marker-rule findings
 * ({@link MarkerRules}, flag type 5) under the same answered/hidden rule. Pure — no JavaFX, no
 * mutation.
 */
public final class ReviewScorer {

    public static final int MIN_PARENT_CELLS = 300;
    public static final double MIN_COVERAGE = 0.90;
    public static final double ON_PEAK_FRACTION = 0.5;

    /**
     * {@code rules} carries every slide's marker-rule rates, so a reader ({@code qc_summary.csv}'s
     * {@code rule_violation_pct}) reports the numbers the review was built from instead of
     * evaluating the rules a second time.
     */
    public record Result(List<ReviewItem> items, List<ReviewItem.Info> infos, MarkerRules.Evaluation rules) {

        public Result(List<ReviewItem> items, List<ReviewItem.Info> infos) {
            this(items, infos, MarkerRules.Evaluation.NONE);
        }

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

                    // Not while correction is off for want of a reference sample: nothing is
                    // corrected then, so "not corrected" would single this slide out for nothing.
                    Landmarks lm = model.landmarks(s.slideId(), column);
                    if (gate.isCorrectStaining() && !s.slideId().equals(model.referenceSlideId())
                            && !model.referenceMissing() && lm != null && !lm.hasL1()) {
                        flags.add(ReviewItem.Flag.NO_LANDMARK);
                        reasons.add("No clear negative peak — not corrected");
                    }

                    String unusual = model.unusualStaining(s.slideId(), column);
                    if (unusual != null) {
                        flags.add(ReviewItem.Flag.UNUSUAL_STAINING);
                        reasons.add(unusual);
                    }

                    MeasuredColumn measuredColumn = s.index().column(gate, k, s.stats());
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
                        // Task 3 switches this to model.scale().
                        LogScale scale = LogScale.LN;
                        Density density = Density.of(Landmarks.toLog(raw, parent, scale));
                        double u = scale.toLog(applied.applied().axis(k)[0]);
                        if (!Double.isNaN(u)) {
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
        MarkerRules.Evaluation rules = MarkerRules.evaluate(tree, samples, resolved);
        mergeRuleFindings(rules, tree, samples, resolved, items);
        return new Result(List.copyOf(items), List.copyOf(infos), rules);
    }

    /**
     * Adds each marker-rule finding to its slide x gate item — a new item, or {@code MARKER_RULE}
     * and the reason appended to the one already there — then restores the top-down order (gate
     * in tree order, then slide in sample order). A slide lacking the gate's channel stays an
     * info; an {@link #answered} slide stays hidden.
     */
    private static void mergeRuleFindings(MarkerRules.Evaluation rules, GateTree tree, List<SlideSample> samples,
                                          Map<String, TreeResolver.ResolvedTree> resolved, List<ReviewItem> items) {
        if (rules.findings().isEmpty()) return;
        Map<String, SlideSample> sampleById = new HashMap<>();
        for (SlideSample s : samples) sampleById.put(s.slideId(), s);
        for (MarkerRules.Finding f : rules.findings()) {
            GateNode gate = f.gate();
            SlideSample s = sampleById.get(f.slideId());
            if (absentChannel(gate, s) != null) continue;
            TreeResolver.Applied applied = resolved.get(s.slideId()).applied(gate);
            if (answered(gate, s.slideId(), applied.applied())) continue;
            ReviewItem.Key key = new ReviewItem.Key(s.slideId(), f.gateRef().rootIndex(), f.gateRef().gatePath());
            // Matched by gate identity: the one fact a finding and an item both carry for certain.
            int at = -1;
            for (int i = 0; i < items.size() && at < 0; i++) {
                ReviewItem item = items.get(i);
                if (item.gate() == gate && item.key().slideId().equals(s.slideId())) at = i;
            }
            if (at < 0) {
                items.add(new ReviewItem(key, s.name(), gate, List.of(ReviewItem.Flag.MARKER_RULE),
                        List.of(f.reason()), applied.applied()));
            } else {
                ReviewItem old = items.get(at);
                Set<ReviewItem.Flag> flags = new LinkedHashSet<>(old.flags());
                flags.add(ReviewItem.Flag.MARKER_RULE);
                Set<String> reasons = new LinkedHashSet<>(old.reasons());
                reasons.add(f.reason());
                items.set(at, new ReviewItem(key, old.slideName(), old.gate(), List.copyOf(flags),
                        List.copyOf(reasons), old.applied()));
            }
        }
        Map<GateNode, Integer> gateOrder = new IdentityHashMap<>();
        List<GateWalk.Entry> walk = GateWalk.enabled(tree);
        for (int i = 0; i < walk.size(); i++) gateOrder.put(walk.get(i).gate(), i);
        Map<String, Integer> slideOrder = new HashMap<>();
        for (int i = 0; i < samples.size(); i++) slideOrder.put(samples.get(i).slideId(), i);
        items.sort(Comparator.<ReviewItem>comparingInt(i -> gateOrder.get(i.gate()))
                .thenComparingInt(i -> slideOrder.get(i.key().slideId())));
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
