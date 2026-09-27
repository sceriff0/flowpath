package qupath.ext.flowpath.cohort;

import qupath.ext.flowpath.engine.AlignmentLookup;
import qupath.ext.flowpath.engine.GateReadout;
import qupath.ext.flowpath.engine.TreeResolver;
import qupath.ext.flowpath.model.Branch;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.Compartment;
import qupath.ext.flowpath.model.GateAxis;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.GateWalk;
import qupath.ext.flowpath.model.MeasuredColumn;
import qupath.ext.flowpath.model.cohort.CohortStats;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The lineage the tree already states, as a number per rule per slide (spec §6 "Marker rules",
 * flag type 5).
 * <ul>
 *   <li><b>Implies</b>, automatic: every enabled gate's positive (branch 0) implies each ancestor
 *   gate's branch it sits under — {@code "CD8+ => CD3+"}.</li>
 *   <li><b>Exclusive</b>: every pair of enabled threshold gates ticked
 *   {@link GateNode#isLineageMarker() Lineage marker}, except a pair reading the same column —
 *   {@code "CD3+ & CD20+"}, the forbidden combination.</li>
 * </ul>
 * <b>Rules reuse the readout.</b> Every sampled clean cell is judged by every gate regardless of
 * where the gate sits in the tree, and only through {@link GateReadout#branchIgnoringClip} on the
 * slide's {@link TreeResolver resolved} tree — the readout the CSV {@code _sign} column uses — so
 * there is no second gate predicate. A cell either gate reads {@link GateReadout#UNMEASURED} is
 * left out of that rule, never counted as a violation (CLAUDE.md "unmeasured is not negative").
 * <p>
 * Rules only point: nothing here tunes a threshold. Rules and findings name their gates by
 * {@link GateRef} — {@code (rootIndex, gatePath)} — because two roots on one channel produce
 * byte-identical labels. Pure — no JavaFX, no mutation of the tree.
 */
public final class MarkerRules {

    /** A slide's rate must exceed the cohort median by this many MADs to be flagged. */
    public static final double MAD_LIMIT = 3.0;
    /** ... and this absolute rate ... */
    public static final double MIN_RATE = 0.02;
    /** ... and be backed by at least this many violating cells. */
    public static final int MIN_CELLS = 20;

    public enum Kind { IMPLIES, EXCLUSIVE }

    /** A gate by value: the {@link GateWalk} key that {@link ReviewItem.Key} and the manifest use. */
    public record GateRef(int rootIndex, String gatePath) {}

    /**
     * IMPLIES: cells {@code a} judges positive must land in {@code b}'s branch {@code bBranch}
     * ({@code b} is the ancestor). EXCLUSIVE: {@code a} and {@code b} are not both positive
     * ({@code bBranch} is 0). The gates are nodes of the tree {@link #rulesOf} was given;
     * {@code aRef}/{@code bRef} name them by value.
     */
    public record Rule(Kind kind, GateNode a, GateNode b, int bBranch, String label, GateRef aRef, GateRef bRef) {}

    /**
     * One rule on one slide: {@code violations} of {@code judged} cells, {@code rate} their ratio
     * (0 when nothing was judged). This is what {@code qc_summary.csv}'s {@code rule_violation_pct}
     * reports; read it from {@link ReviewScorer.Result#rules()} rather than evaluating again.
     */
    public record RuleRate(String slideId, Rule rule, double rate, int violations, int judged) {}

    /** A flagged rule on one slide, attached to {@code gate} (named by value as {@code gateRef}). */
    public record Finding(String slideId, GateNode gate, GateRef gateRef, String reason) {}

    public record Evaluation(List<RuleRate> rates, List<Finding> findings) {
        public static final Evaluation NONE = new Evaluation(List.of(), List.of());
    }

    private MarkerRules() {}

    /** Every rule {@code tree} states, implies rules first (tree order), then exclusive pairs. */
    public static List<Rule> rulesOf(GateTree tree) {
        List<GateWalk.Entry> walk = GateWalk.enabled(tree);
        Map<GateNode, GateWalk.Entry> byGate = new IdentityHashMap<>();
        for (GateWalk.Entry e : walk) byGate.put(e.gate(), e);
        List<Rule> rules = new ArrayList<>();
        for (GateWalk.Entry e : walk) {
            String positive = positiveName(e.gate());
            for (GateWalk.Entry up = e; up.parentGate() != null; up = byGate.get(up.parentGate())) {
                GateNode ancestor = up.parentGate();
                Branch under = up.parentBranch();
                rules.add(new Rule(Kind.IMPLIES, e.gate(), ancestor, ancestor.getBranches().indexOf(under),
                        positive + " => " + under.getName(), ref(e), ref(byGate.get(ancestor))));
            }
        }
        List<GateWalk.Entry> lineage = walk.stream()
                .filter(e -> e.gate().isLineageMarker() && "threshold".equals(e.gate().getGateType())
                        && e.gate().getChannel() != null && !e.gate().getChannel().isEmpty())
                .toList();
        for (int i = 0; i < lineage.size(); i++) {
            for (int j = i + 1; j < lineage.size(); j++) {
                GateWalk.Entry a = lineage.get(i), b = lineage.get(j);
                // Two gates on one column would be a rule against itself.
                if (columnKey(a.gate()).equals(columnKey(b.gate()))) continue;
                rules.add(new Rule(Kind.EXCLUSIVE, a.gate(), b.gate(), 0,
                        positiveName(a.gate()) + " & " + positiveName(b.gate()), ref(a), ref(b)));
            }
        }
        return rules;
    }

    /** Evaluate every rule of {@code tree} on every sample, each slide gated through its own resolved tree. */
    public static Evaluation evaluate(GateTree tree, List<SlideSample> samples, AlignmentLookup lookup) {
        Map<String, TreeResolver.ResolvedTree> resolved = new HashMap<>();
        for (SlideSample s : samples) resolved.put(s.slideId(), TreeResolver.resolve(tree, s.slideId(), lookup));
        return evaluate(tree, samples, resolved);
    }

    /** As {@link #evaluate(GateTree, List, AlignmentLookup)}, reusing trees already resolved per slide. */
    static Evaluation evaluate(GateTree tree, List<SlideSample> samples, Map<String, TreeResolver.ResolvedTree> resolved) {
        List<Rule> rules = rulesOf(tree);
        if (rules.isEmpty() || samples.isEmpty()) return Evaluation.NONE;

        List<RuleRate> rates = new ArrayList<>();
        Map<String, SlideSample> sampleById = new HashMap<>();
        for (SlideSample s : samples) {
            sampleById.put(s.slideId(), s);
            TreeResolver.ResolvedTree r = resolved.get(s.slideId());
            GateReadout readout = GateReadout.compile(r.tree(), s.index(), s.stats());
            for (Rule rule : rules) {
                rates.add(rate(s.slideId(), rule, r.resolvedOf(rule.a()), r.resolvedOf(rule.b()), readout, s.clean()));
            }
        }

        List<Finding> findings = new ArrayList<>();
        for (Rule rule : rules) {
            List<RuleRate> forRule = rates.stream().filter(r -> r.rule() == rule && r.judged() > 0).toList();
            if (forRule.isEmpty()) continue;
            double[] values = forRule.stream().mapToDouble(RuleRate::rate).toArray();
            double median = CohortStats.median(values);
            double mad = CohortStats.mad(values, median);
            for (RuleRate r : forRule) {
                if (!(r.rate() > median + MAD_LIMIT * mad && r.rate() > MIN_RATE && r.violations() >= MIN_CELLS)) continue;
                long pct = Math.round(100 * r.rate());
                long cohort = Math.round(100 * median);
                if (rule.kind() == Kind.IMPLIES) {
                    findings.add(new Finding(r.slideId(), rule.b(), rule.bRef(), String.format(Locale.US,
                            "%d%% of %s cells are %s here (cohort %d%%) — %s", pct, positiveName(rule.a()),
                            otherSide(rule.b(), rule.bBranch()), cohort, direction(rule.b(), rule.bBranch()))));
                } else {
                    double nucleus = nucleusRate(rule, resolved.get(r.slideId()), sampleById.get(r.slideId()));
                    String reason = String.format(Locale.US,
                            "%s%s %d%% (cohort %d%%) — a threshold may be too low, or signal spills from neighbouring cells",
                            positiveName(rule.a()), positiveName(rule.b()), pct, cohort)
                            + (nucleus >= 0 && nucleus < r.rate() / 2 ? " — try Nucleus" : "");
                    findings.add(new Finding(r.slideId(), rule.a(), rule.aRef(), reason));
                    findings.add(new Finding(r.slideId(), rule.b(), rule.bRef(), reason));
                }
            }
        }
        return new Evaluation(List.copyOf(rates), List.copyOf(findings));
    }

    /** {@code a} and {@code b} are the resolved copies of the rule's gates; {@code readout} was compiled over their tree. */
    private static RuleRate rate(String slideId, Rule rule, GateNode a, GateNode b, GateReadout readout, boolean[] clean) {
        int judged = 0, violations = 0;
        for (int i = 0; i < clean.length; i++) {
            if (!clean[i]) continue;
            int ra = readout.branchIgnoringClip(a, i);
            if (ra == GateReadout.UNMEASURED) continue;
            int rb = readout.branchIgnoringClip(b, i);
            if (rb == GateReadout.UNMEASURED) continue;
            if (rule.kind() == Kind.IMPLIES) {
                if (ra != 0) continue;
                judged++;
                if (rb != rule.bBranch()) violations++;
            } else if (ra == 0 || rb == 0) {
                judged++;
                if (ra == 0 && rb == 0) violations++;
            }
        }
        return new RuleRate(slideId, rule, judged == 0 ? 0 : (double) violations / judged, violations, judged);
    }

    /**
     * The exclusive rate with both gates reading their marker's Nucleus column at the same applied
     * thresholds, or -1 when the sample does not carry both Nucleus columns. Computed on a copy of
     * the slide's resolved tree ({@code TreeResolver.resolve(t, null, NONE)} copies exactly and maps
     * every node), so the slide's own resolved tree is never touched.
     */
    private static double nucleusRate(Rule rule, TreeResolver.ResolvedTree resolved, SlideSample s) {
        TreeResolver.ResolvedTree copy = TreeResolver.resolve(resolved.tree(), null, AlignmentLookup.NONE);
        GateNode a = copy.resolvedOf(resolved.resolvedOf(rule.a()));
        GateNode b = copy.resolvedOf(resolved.resolvedOf(rule.b()));
        for (GateNode g : List.of(a, b)) {
            g.setCompartment(Compartment.NUCLEAR);
            MeasuredColumn column = s.index().column(g, 0, s.stats());
            if (column == null || !anyFinite(column.values())) return -1;
        }
        GateReadout readout = GateReadout.compile(copy.tree(), s.index(), s.stats());
        return rate(s.slideId(), rule, a, b, readout, s.clean()).rate();
    }

    private static boolean anyFinite(double[] values) {
        for (double v : values) if (Double.isFinite(v)) return true;
        return false;
    }

    /** The name of the branch of {@code ancestor} a violating cell falls on instead of {@code branch}. */
    private static String otherSide(GateNode ancestor, int branch) {
        List<Branch> branches = ancestor.getBranches();
        if (GateAxis.axisCount(ancestor) == 1) return branches.get(1 - branch).getName();
        return "outside " + branches.get(branch).getName();
    }

    /** Which way to move the ancestor: a child under its positive branch loses cells to a cut set too high. */
    private static String direction(GateNode ancestor, int branch) {
        if (GateAxis.axisCount(ancestor) != 1) return "check the " + GateWalk.label(ancestor) + " gate";
        return ancestor.getChannel() + " threshold may be too " + (branch == 0 ? "high" : "low");
    }

    private static String positiveName(GateNode gate) {
        return gate.getBranches().get(0).getName();
    }

    /** The column a threshold gate reads, as {@code TreeResolver} names it. */
    private static String columnKey(GateNode gate) {
        return CellIndex.keyFor(gate.getChannel(), gate.compartmentAt(0), gate.statisticAt(0));
    }

    private static GateRef ref(GateWalk.Entry e) {
        return new GateRef(e.rootIndex(), e.gatePath());
    }
}
