package qupath.ext.flowpath.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Every enabled gate, top-down in tree order, with the {@code (rootIndex, gatePath)} value that
 * names it. {@code rootIndex} counts enabled roots only, as {@code PopulationStats} and
 * {@link GateTree#findBranch} do, so two same-channel roots stay apart; a disabled gate is a hard
 * stop for its subtree, as in the engine walk.
 */
public final class GateWalk {

    public record Entry(GateNode gate, int rootIndex, String gatePath, GateNode parentGate, Branch parentBranch) {}

    private GateWalk() {}

    public static List<Entry> enabled(GateTree tree) {
        List<Entry> out = new ArrayList<>();
        int rootIndex = 0;
        for (GateNode root : tree.getRoots()) {
            if (!root.isEnabled()) continue;
            visit(root, rootIndex, "", null, null, out);
            rootIndex++;
        }
        return out;
    }

    private static void visit(GateNode gate, int rootIndex, String branchPath, GateNode parent, Branch parentBranch,
                              List<Entry> out) {
        if (!gate.isEnabled()) return;
        out.add(new Entry(gate, rootIndex, branchPath.isEmpty() ? label(gate) : branchPath + "/" + label(gate),
                parent, parentBranch));
        for (Branch b : gate.getBranches()) {
            String path = branchPath.isEmpty() ? b.getName() : branchPath + "/" + b.getName();
            for (GateNode child : b.getChildren()) visit(child, rootIndex, path, gate, b, out);
        }
    }

    /** The channel for a 1-D gate, "X vs Y" for a 2-D gate. */
    public static String label(GateNode gate) {
        List<String> ch = gate.getChannels();
        return GateAxis.axisCount(gate) == 2 && ch.size() >= 2 ? ch.get(0) + " vs " + ch.get(1)
                : (ch.isEmpty() ? "" : String.valueOf(ch.get(0)));
    }
}
