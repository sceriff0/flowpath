package qupath.ext.flowpath.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Every enabled gate, top-down in tree order, with the {@code (rootIndex, gatePath)} value that
 * names it. {@code rootIndex} counts enabled roots only, as {@code PopulationStats} and
 * {@link GateTree#findBranch} do, so two same-channel roots stay apart; a disabled gate is a hard
 * stop for its subtree, as in the engine walk.
 * <p>
 * A path is the branch names down to the gate, then the gate's label ({@link #label}). Two
 * enabled sibling gates under one branch can carry the same label — Duplicate makes exactly that
 * — so a label repeated among its enabled siblings carries its ordinal: the first keeps the plain
 * label, the second is {@code CD3+/CD8#2}, the third {@code #3}. A repeated branch name among
 * those siblings' branches is numbered the same way ({@code CD3+/CD8+#2/CD4} under the second
 * CD8), so every descendant's path is unique too. Every consumer — review items and groups, marker
 * rules, the manifest's {@code gate_path}, {@code qc_summary.csv} subjects — takes its path from
 * here, so they all agree.
 */
public final class GateWalk {

    /**
     * One enabled gate. {@code branchSegments} holds, per branch of the gate in order, the segment
     * that branch contributes to its children's paths — its name, numbered when repeated among the
     * sibling gates' branches ({@code CD8+#2}) — for a reader that names a branch uniquely.
     */
    public record Entry(GateNode gate, int rootIndex, String gatePath, GateNode parentGate, Branch parentBranch,
                        List<String> branchSegments) {
        public Entry {
            branchSegments = List.copyOf(branchSegments);
        }
    }

    private GateWalk() {}

    public static List<Entry> enabled(GateTree tree) {
        List<Entry> out = new ArrayList<>();
        int rootIndex = 0;
        for (GateNode root : tree.getRoots()) {
            if (!root.isEnabled()) continue;
            siblings(List.of(root), rootIndex, "", null, null, out);
            rootIndex++;
        }
        return out;
    }

    /** One set of sibling gates (a branch's children, or one enabled root), each then its subtree. */
    private static void siblings(List<GateNode> gates, int rootIndex, String branchPath, GateNode parent,
                                 Branch parentBranch, List<Entry> out) {
        Map<String, Integer> labels = new HashMap<>();
        Map<String, Integer> branchNames = new HashMap<>();
        for (GateNode gate : gates) {
            if (!gate.isEnabled()) continue;
            String gatePath = join(branchPath, numbered(label(gate), labels));
            List<String> segments = new ArrayList<>();
            for (Branch b : gate.getBranches()) segments.add(numbered(b.getName(), branchNames));
            out.add(new Entry(gate, rootIndex, gatePath, parent, parentBranch, segments));
            List<Branch> branches = gate.getBranches();
            for (int i = 0; i < branches.size(); i++) {
                siblings(branches.get(i).getChildren(), rootIndex, join(branchPath, segments.get(i)), gate,
                        branches.get(i), out);
            }
        }
    }

    /** {@code name} the first time it is seen among these siblings, {@code name#k} the k-th time. */
    private static String numbered(String name, Map<String, Integer> seen) {
        int k = seen.merge(name, 1, Integer::sum);
        return k == 1 ? name : name + "#" + k;
    }

    private static String join(String path, String segment) {
        return path.isEmpty() ? segment : path + "/" + segment;
    }

    /** The channel for a 1-D gate, "X vs Y" for a 2-D gate. */
    public static String label(GateNode gate) {
        List<String> ch = gate.getChannels();
        return GateAxis.axisCount(gate) == 2 && ch.size() >= 2 ? ch.get(0) + " vs " + ch.get(1)
                : (ch.isEmpty() ? "" : String.valueOf(ch.get(0)));
    }
}
