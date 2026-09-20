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

    // ---- moving a gate within the tree ---------------------------------------------------

    /**
     * Why {@link #move(GateNode, Branch)} would, or would not, take a gate.
     * <p>
     * A value rather than a bare {@code boolean} because the tree view's hover cue and its
     * drop both ask the same question and must get the same answer: the hover path calls
     * {@link #checkMove}, the drop path calls {@link #move}, which re-checks. A drop that is
     * offered and then silently refused — or, worse, taken where the hover said no — is the
     * "two implementations of one rule" failure this codebase keeps catalogueing.
     */
    public enum MoveVerdict {
        /** The move would be taken. */
        ALLOWED,
        /** No such gate in this tree (or {@code null}). */
        NO_SUCH_GATE,
        /** No such branch in this tree — a non-{@code null} target that belongs elsewhere. */
        NO_SUCH_BRANCH,
        /** The gate already hangs off that branch (or already is a root): a no-op, not a move. */
        ALREADY_THERE,
        /** The target lies inside the gate's own subtree, so the move would orphan the tree. */
        WOULD_CYCLE;

        public boolean allowed() { return this == ALLOWED; }
    }

    /**
     * Whether {@link #move(GateNode, Branch)} would take this move, and why not when it would
     * not. Pure: asks nothing of the caller and changes nothing.
     *
     * @param gate   the gate to move, which must belong to this tree
     * @param target the branch to move it under, or {@code null} to promote it to a root —
     *               see {@link #move(GateNode, Branch)} for that contract
     */
    public MoveVerdict checkMove(GateNode gate, Branch target) {
        if (gate == null || !holdsGate(roots, gate)) return MoveVerdict.NO_SUCH_GATE;
        if (target == null) {
            return containsIdentical(roots, gate) ? MoveVerdict.ALREADY_THERE : MoveVerdict.ALLOWED;
        }
        if (!holdsBranch(roots, target)) return MoveVerdict.NO_SUCH_BRANCH;
        // Cycle before no-op: a gate's own branch can never already hold it, so the two can
        // not both apply, but checking the destructive case first keeps that obvious.
        if (subtreeHolds(gate, target)) return MoveVerdict.WOULD_CYCLE;
        if (containsIdentical(target.getChildren(), gate)) return MoveVerdict.ALREADY_THERE;
        return MoveVerdict.ALLOWED;
    }

    /**
     * Move {@code gate} — with its whole subtree — to hang off {@code target}, appended after
     * whatever already hangs there. The one implementation of "reorder a gate", shared by the
     * tree view's drag and drop and by anything else that ever needs to re-parent a gate.
     * <p>
     * <b>A {@code null} target means the root list.</b> Promoting a gate back to a root is the
     * same operation with the same rules — detach from wherever it is, append last — and
     * naming it {@code moveToRoot} would have been a second copy of the detach-and-append walk
     * with its own chance to disagree about what "already there" means. The tree view's
     * background rows (the empty space under the last gate) pass {@code null}.
     * <p>
     * <b>Atomic.</b> Every refusal is decided by {@link #checkMove} <em>before</em> anything is
     * detached, so a refused move leaves the tree exactly as it was rather than dropping the
     * gate on the floor between the two halves of the operation.
     * <p>
     * <b>Identity is preserved.</b> The very same {@link GateNode} and {@link Branch} objects
     * are re-parented, not copied, so a caller holding the moved gate still holds it. What a
     * move <em>does</em> change is the {@code (rootIndex, path)} that names the populations
     * underneath it — which is exactly why nothing in this codebase may remember a {@link
     * Branch} pointer across a pass, and why {@code PopulationRef}/{@code DenominatorRef} are
     * re-resolved through {@link #findBranch} after every gating pass.
     *
     * @param gate   the gate to move, which must belong to this tree
     * @param target the branch to move it under, or {@code null} to make it a root
     * @return {@code true} when the move was applied; {@code false} for every refusal in
     *         {@link MoveVerdict}, with the tree untouched
     */
    public boolean move(GateNode gate, Branch target) {
        if (!checkMove(gate, target).allowed()) return false;
        // checkMove already established the gate is somewhere in this tree, so detach should
        // always succeed here -- checked anyway so "never in two places" is a structural
        // guarantee of this method rather than resting on that invariant holding elsewhere.
        if (!detach(roots, gate)) return false;
        if (target == null) roots.add(gate);
        else target.getChildren().add(gate);
        return true;
    }

    /** Remove {@code gate} from wherever it hangs — the root list or some branch's children. */
    private static boolean detach(List<GateNode> roots, GateNode gate) {
        if (removeIdentical(roots, gate)) return true;
        for (GateNode node : roots) {
            for (Branch branch : node.getBranches()) {
                if (removeIdentical(branch.getChildren(), gate)) return true;
                if (detach(branch.getChildren(), gate)) return true;
            }
        }
        return false;
    }

    /** True when {@code target} is {@code gate}'s own branch or lies anywhere beneath it. */
    private static boolean subtreeHolds(GateNode gate, Branch target) {
        for (Branch branch : gate.getBranches()) {
            if (branch == target) return true;
            for (GateNode child : branch.getChildren()) {
                if (subtreeHolds(child, target)) return true;
            }
        }
        return false;
    }

    private static boolean holdsGate(List<GateNode> nodes, GateNode gate) {
        for (GateNode node : nodes) {
            if (node == gate) return true;
            for (Branch branch : node.getBranches()) {
                if (holdsGate(branch.getChildren(), gate)) return true;
            }
        }
        return false;
    }

    private static boolean holdsBranch(List<GateNode> nodes, Branch target) {
        for (GateNode node : nodes) {
            for (Branch branch : node.getBranches()) {
                if (branch == target) return true;
                if (holdsBranch(branch.getChildren(), target)) return true;
            }
        }
        return false;
    }

    /**
     * Identity membership. {@link GateNode} does not override {@code equals}, so {@code
     * List.contains} would agree today — but "this exact gate" is what every rule here means,
     * and spelling it out keeps that true if a value {@code equals} is ever added.
     */
    private static boolean containsIdentical(List<GateNode> nodes, GateNode gate) {
        for (GateNode node : nodes) {
            if (node == gate) return true;
        }
        return false;
    }

    private static boolean removeIdentical(List<GateNode> nodes, GateNode gate) {
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i) == gate) {
                nodes.remove(i);
                return true;
            }
        }
        return false;
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
     * <p>
     * <b>Backtracks.</b> Two sibling gates on the same channel (e.g. two {@code CD3} gates
     * hanging off one {@code CD45+} branch) both contribute a branch named {@code "CD3+"} at
     * the same depth, so a segment match is not unique the way a single gate's branches would
     * be — only the FULL remaining path tells the candidates apart. Trying the first match and
     * returning whatever its recursion finds (including {@code null}) used to make the first
     * same-named sibling win outright: if that sibling was a leaf and the path being resolved
     * continued past it (naming a gate that only the SECOND sibling has), the search dead-ended
     * instead of trying the second candidate. A recursion that returns {@code null} now falls
     * through to the next matching branch — across the rest of this node's own branches AND the
     * remaining sibling nodes — rather than returning {@code null} on the spot; only exhausting
     * every candidate at this depth is a genuine "no such path". A leaf match ({@code rest ==
     * null}) still returns on the first hit: every sibling contributing that same segment names
     * an equally valid branch for that exact path, so first-match there is a real answer, not a
     * missed backtrack (see {@code GateTreeTest.findBranchAndLocateAreExactInverses} for the
     * two-sibling fixture this backtracking exists for).
     */
    private static Branch findBranchAmong(List<GateNode> nodes, String remainingPath) {
        int slash = remainingPath.indexOf('/');
        String segment = slash < 0 ? remainingPath : remainingPath.substring(0, slash);
        String rest = slash < 0 ? null : remainingPath.substring(slash + 1);
        for (GateNode node : nodes) {
            if (!node.isEnabled()) continue;
            for (Branch branch : node.getBranches()) {
                if (!branch.getName().equals(segment)) continue;
                if (rest == null) return branch;
                Branch found = findBranchAmong(branch.getChildren(), rest);
                if (found != null) return found;
                // else: this candidate's subtree does not carry `rest` -- keep trying the
                // remaining same-named candidates instead of giving up.
            }
        }
        return null;
    }

    /**
     * A population's identity within a {@link GateTree} — the value {@link #findBranch} resolves
     * <em>from</em> and {@link #locate} resolves <em>to</em>, sharing one "enabled roots, joined
     * path" rule rather than two hand-kept implementations of it. The two are exact inverses of
     * each other for every {@code (rootIndex, path)} {@code PopulationStats} can actually emit —
     * which is the round trip every caller of this pair depends on, and what
     * {@code GateTreeTest.findBranchAndLocateAreExactInverses} pins.
     * <p>
     * <b>Not unconditionally exact, and that qualifier is load-bearing.</b> A {@code path} names
     * a branch by NAME, not by identity: when two sibling gates on the same channel hang off one
     * branch (e.g. two {@code CD3} gates under one {@code CD45+} branch), each contributes its
     * own branch named {@code "CD3+"} at the same depth, and {@code PopulationStats.collect}
     * happily emits a row for both — two distinct {@link Branch} objects sharing one path. {@link
     * #locate} on the SECOND sibling's own {@code "CD3+"} branch still returns that same path
     * (identity search, so it is exact), but {@link #findBranch} fed that path back resolves to
     * whichever sibling it tries first — the FIRST one, not necessarily the one {@link #locate}
     * was originally given. That is a property of path-based identity, shared with every other
     * path-keyed lookup in this codebase ({@code AnalysisSession.resolveDenominator} included),
     * not a defect in either method here.
     * <p>
     * Deliberately not {@code qupath.ext.flowpath.analysis.ui.PopulationRef}, even though the
     * two records carry identical fields: {@link #locate} is reached from
     * {@code FlowPathPane} (the gating pane, in {@code ui}), which must not import anything from
     * {@code analysis.ui} — the same {@code ui} ↔ {@code session} layering
     * {@code DenominatorRef}'s own javadoc already keeps apart for the Analysis window's half of
     * this codebase. {@code FlowPathPane} maps a {@code BranchLocation} to a {@code
     * PopulationRef} itself, in one line, once it is back on the {@code ui} side of that
     * boundary.
     *
     * @param rootIndex zero-based index among the tree's ENABLED roots only
     * @param path      the branch-name route from that root, e.g. {@code "CD45+/CD3+"}
     */
    public record BranchLocation(int rootIndex, String path) {}

    /**
     * The inverse of {@link #findBranch}, for every path {@code PopulationStats} can actually
     * emit — see {@link BranchLocation}'s own javadoc for the one case, two sibling gates on one
     * channel sharing a branch name, where feeding the returned path straight back into {@link
     * #findBranch} is not guaranteed to hand back this exact {@code target} object rather than
     * its same-named sibling. Given a live {@link Branch} that belongs to this tree, returns the
     * {@code (rootIndex, path)} that names it — or {@code null} when {@code target} is not
     * reachable through this tree's enabled roots at all, which covers both "not in this tree"
     * and "only reachable through a disabled gate" (a disabled node, root or nested, is a hard
     * stop for its whole subtree — see {@link #findBranch}'s own javadoc for why
     * {@code PopulationStats} agrees).
     * <p>
     * {@code rootIndex} is assigned by the identical enabled-roots-only rule {@link #findBranch}
     * indexes by, so a {@code BranchLocation} this method returns can always be fed straight
     * back into {@link #findBranch} to get SOME branch {@code PopulationStats} would show at
     * that exact path back — see {@code GateTreeTest.findBranchAndLocateAreExactInverses}, which
     * pins the round trip in both directions rather than trusting the two methods to merely
     * agree by inspection.
     *
     * @param target a branch belonging to this tree, or {@code null}
     */
    public BranchLocation locate(Branch target) {
        if (target == null) return null;
        int rootIndex = 0;
        for (GateNode root : roots) {
            if (!root.isEnabled()) continue;
            String path = pathTo(root, "", target);
            if (path != null) return new BranchLocation(rootIndex, path);
            rootIndex++;
        }
        return null;
    }

    /**
     * Depth-first search for {@code target} under {@code node}, building its path as it descends
     * — the reverse traversal of {@link #findBranchAmong}, walked by identity rather than by
     * name since the caller already holds the {@link Branch} object itself.
     */
    private static String pathTo(GateNode node, String prefix, Branch target) {
        if (!node.isEnabled()) return null;
        for (Branch branch : node.getBranches()) {
            String path = prefix.isEmpty() ? branch.getName() : prefix + "/" + branch.getName();
            if (branch == target) return path;
            for (GateNode child : branch.getChildren()) {
                String found = pathTo(child, path, target);
                if (found != null) return found;
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
     * Transfer {@code source}'s per-branch counts onto {@code destination} when the two
     * forests are structurally identical (same number of roots, same branches per gate, same
     * children per branch, at every depth), and leave {@code destination} untouched otherwise.
     * <p>
     * {@link GateNode#deepCopy()} does not carry {@link Branch#getCount()} — counts are
     * {@code transient} on purpose, filled only by a gating walk — and neither does loading a
     * tree from JSON. An undo, a redo or a load therefore used to hand the tree view a tree
     * that reads 0/0% everywhere until the next {@code LivePreviewService} pass lands, which
     * can be seconds on a large slide since that pass runs in the background. When the
     * outgoing tree still pairs with the incoming one branch-for-branch — an undo of a
     * threshold nudge, most loads of the tree just saved — its counts are a much better
     * stand-in than zero for that whole window; when it does not (a gate was added, removed
     * or reshaped), there is no correspondence to borrow and the destination is left at its
     * own default.
     * <p>
     * Uses {@link #pairBranches} defensively rather than {@link #transferCounts} directly:
     * {@code transferCounts} alone stops gracefully at the shorter of two diverged structures,
     * which could transfer a count onto an unrelated branch under a partial mismatch; checking
     * the pairing first (which throws on any mismatch, strict) means the transfer that follows
     * either covers the whole forest correctly or does not run at all.
     *
     * @return {@code true} when the transfer happened
     */
    public static boolean transferCountsIfStructureMatches(List<GateNode> destination, List<GateNode> source) {
        try {
            pairBranches(destination, source);
        } catch (IllegalArgumentException notPaired) {
            return false;
        }
        transferCounts(destination, source);
        return true;
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
