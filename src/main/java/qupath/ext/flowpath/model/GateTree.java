package qupath.ext.flowpath.model;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

public class GateTree {

    private List<GateNode> roots = new ArrayList<>();
    private QualityFilter qualityFilter = new QualityFilter();
    private boolean roiFilterEnabled;

    public GateTree() {
    }

    public void addRoot(GateNode node) {
        roots.add(node);
    }

    public void removeRoot(GateNode node) {
        roots.remove(node);
    }

    public List<GateNode> getRoots() {
        return roots;
    }

    public void setRoots(List<GateNode> roots) {
        this.roots = roots;
    }

    public QualityFilter getQualityFilter() {
        return qualityFilter;
    }

    public void setQualityFilter(QualityFilter qualityFilter) {
        this.qualityFilter = qualityFilter;
    }

    public boolean isRoiFilterEnabled() {
        return roiFilterEnabled;
    }

    public void setRoiFilterEnabled(boolean roiFilterEnabled) {
        this.roiFilterEnabled = roiFilterEnabled;
    }

    /**
     * Create a deep copy of this tree (all nodes and the quality filter are cloned).
     */
    public GateTree deepCopy() {
        GateTree copy = new GateTree();
        copy.qualityFilter = this.qualityFilter.deepCopy();
        copy.roiFilterEnabled = this.roiFilterEnabled;
        copy.roots = new ArrayList<>();
        for (GateNode root : this.roots) {
            copy.roots.add(root.deepCopy());
        }
        return copy;
    }

    /**
     * Resolve a population's identity — {@code (rootIndex, path)} — to the live {@link Branch}
     * it names, walking this tree's <b>enabled</b> roots only, indexed exactly the way
     * {@code PopulationStats.collectFromRoots} numbers them (contiguous among enabled roots; a
     * disabled root contributes no index at all), then following {@code path}'s branch names
     * down from there.
     * <p>
     * <b>Value-keyed, deliberately, so this survives a {@link #deepCopy()}.</b> The Analysis
     * window's population table is built from a deep copy of the live tree
     * ({@code FlowPathPane.buildAnalysisInput()}), so a {@link Branch} minted while that report
     * was built is never the SAME object as anything in the tree the user goes on editing — an
     * identity-keyed lookup would need the caller to hold a copy that never goes stale, which is
     * exactly what live gate editing rules out. {@code (rootIndex, path)} names the same
     * population in either tree, which is what lets a click on a report built from a copy select
     * something in the live tree it was copied from.
     * <p>
     * <b>Absence, never an exception.</b> Returns {@code null} whenever the path does not
     * resolve — an out-of-range {@code rootIndex}, a segment naming no branch, or a segment that
     * would have to pass through a disabled gate (a disabled gate is a hard stop for its whole
     * subtree in {@code GatingEngine.walkNode}, so {@code PopulationStats} never emits a row for
     * anything beneath one, and this method must agree, or a stale ref would resolve to a branch
     * the table never showed). The user may simply have disabled, deleted or renamed the gate
     * since the report naming it was pushed; that is an ordinary consequence of live editing, not
     * an error for a caller to handle — see {@code AnalysisPane.selectPopulation} and
     * {@code FlowPathPane}'s own population-selection listener, both of which treat a {@code
     * null} return as "ignore silently".
     *
     * @param rootIndex zero-based index among this tree's ENABLED roots only — the same
     *                   indexing {@link PopulationStats.Row#rootIndex()} carries
     * @param path       the branch-name route from that root, e.g. {@code "CD45+/CD3+"} —
     *                   {@link PopulationStats.Row#path()}
     */
    public Branch findBranch(int rootIndex, String path) {
        if (path == null || path.isEmpty() || rootIndex < 0) return null;
        GateNode root = enabledRoot(rootIndex);
        if (root == null) return null;
        return findBranchAmong(List.of(root), path);
    }

    /** The {@code rootIndex}-th ENABLED root, in tree order, or {@code null} when out of range. */
    private GateNode enabledRoot(int rootIndex) {
        int index = 0;
        for (GateNode root : roots) {
            if (!root.isEnabled()) continue;
            if (index == rootIndex) return root;
            index++;
        }
        return null;
    }

    /**
     * Follow {@code remainingPath}'s leading segment against every branch of every node in
     * {@code nodes} — mirroring {@code PopulationStats.collect}'s own traversal, where a
     * branch's children are a LIST of sibling gates rather than a single one, so more than one
     * node can contribute a branch at the same path depth.
     */
    private static Branch findBranchAmong(List<GateNode> nodes, String remainingPath) {
        int slash = remainingPath.indexOf('/');
        String segment = slash < 0 ? remainingPath : remainingPath.substring(0, slash);
        String rest = slash < 0 ? null : remainingPath.substring(slash + 1);
        for (GateNode node : nodes) {
            if (!node.isEnabled()) continue;
            for (Branch branch : node.getBranches()) {
                if (!branch.getName().equals(segment)) continue;
                return rest == null ? branch : findBranchAmong(branch.getChildren(), rest);
            }
        }
        return null;
    }

    /**
     * Transfer transient counts from a copy's nodes back to the originals.
     * Walks both trees in parallel; stops gracefully if structures differ.
     */
    public static void transferCounts(List<GateNode> originals, List<GateNode> copies) {
        walkBranchPairs(originals, copies, Branch::transferCountFrom, false);
    }

    /**
     * Pair every branch of a tree the gating walk classified against with the corresponding
     * branch of the live tree it was copied from.
     * <p>
     * <b>Why this exists.</b> {@code LivePreviewService} deep-copies the tree before walking
     * it, and {@link GateNode#deepCopy()} constructs fresh {@link Branch} objects. A
     * {@link BranchTally} is deliberately identity-keyed, so the tally the walk fills is
     * keyed on the <em>copy's</em> branches while every consumer — the Analysis window
     * above all — holds the <em>live</em> tree's. {@link #transferCounts} reconciles the raw
     * {@code int} count and nothing else, which left every per-branch lookup missing and
     * {@link BranchTally} answering 0 by design. {@link BranchTally#rebindTo} closes that
     * with this pairing; the traversal is shared with {@link #transferCounts} rather than
     * written twice, because the two must agree on what "the corresponding branch" means.
     * <p>
     * <b>Strict.</b> Unlike {@link #transferCounts}, which stops gracefully at the shorter
     * of two diverged structures, this throws: re-keying a tally onto a tree that is not the
     * one it was filled from would silently attribute one branch's cells to another, or drop
     * them, which is the class of failure {@code PhenotypeSnapshot.rebindTo} also refuses to
     * migrate half-way through.
     *
     * @param live   the tree the caller holds and will look counts up in
     * @param walked the tree the gating walk actually classified against
     * @return an identity map from each {@code walked} branch to its {@code live} counterpart
     * @throws IllegalArgumentException when the two forests are not structurally identical
     */
    static Map<Branch, Branch> pairBranches(List<GateNode> live, List<GateNode> walked) {
        Map<Branch, Branch> walkedToLive = new IdentityHashMap<>();
        walkBranchPairs(live, walked, (l, w) -> walkedToLive.put(w, l), true);
        return walkedToLive;
    }

    /**
     * Walk two gate forests in parallel, handing each pair of corresponding branches to
     * {@code action} as {@code (fromA, fromB)}.
     *
     * @param strict when {@code true}, any difference in the number of roots, branches or
     *               children throws instead of stopping at the shorter of the two
     */
    private static void walkBranchPairs(List<GateNode> a, List<GateNode> b,
                                        BiConsumer<Branch, Branch> action, boolean strict) {
        if (a == null || b == null) {
            if (strict && a != b) {
                throw new IllegalArgumentException("one gate forest is null and the other is not");
            }
            return;
        }
        if (strict && a.size() != b.size()) {
            throw new IllegalArgumentException(
                    "gate forests differ in size: " + a.size() + " vs " + b.size() + " node(s)");
        }
        for (int i = 0; i < a.size() && i < b.size(); i++) {
            List<Branch> aBranches = a.get(i).getBranches();
            List<Branch> bBranches = b.get(i).getBranches();
            if (strict && aBranches.size() != bBranches.size()) {
                throw new IllegalArgumentException(
                        "gate '" + a.get(i).getChannel() + "' has " + aBranches.size()
                                + " branch(es) here and " + bBranches.size() + " in the other tree");
            }
            for (int k = 0; k < aBranches.size() && k < bBranches.size(); k++) {
                action.accept(aBranches.get(k), bBranches.get(k));
                walkBranchPairs(aBranches.get(k).getChildren(), bBranches.get(k).getChildren(),
                        action, strict);
            }
        }
    }

    public List<String> collectLeafNames() {
        List<String> names = new ArrayList<>();
        for (GateNode root : roots) {
            root.collectLeafNames(names);
        }
        return names;
    }

    /**
     * Find leaf branch names that appear in more than one root gate.
     *
     * @return map from duplicate leaf name to the list of root indices where it appears;
     *         empty if no duplicates exist
     */
    public Map<String, List<Integer>> findDuplicateLeafNames() {
        Map<String, List<Integer>> nameToRoots = new LinkedHashMap<>();
        for (int i = 0; i < roots.size(); i++) {
            GateNode root = roots.get(i);
            if (!root.isEnabled()) continue;
            List<String> leafNames = new ArrayList<>();
            root.collectLeafNames(leafNames);
            // De-duplicate within the root first: a name used twice inside one root
            // is a naming choice there, not the cross-root collision reported here.
            // Recording the root index twice made it look like one.
            for (String name : new LinkedHashSet<>(leafNames)) {
                nameToRoots.computeIfAbsent(name, k -> new ArrayList<>()).add(i);
            }
        }
        nameToRoots.entrySet().removeIf(e -> e.getValue().size() < 2);
        return nameToRoots;
    }
}
