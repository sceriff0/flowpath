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
