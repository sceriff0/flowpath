package qupath.ext.flowpath.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

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
        for (int i = 0; i < originals.size() && i < copies.size(); i++) {
            originals.get(i).transferCountsFrom(copies.get(i));
            List<Branch> origBranches = originals.get(i).getBranches();
            List<Branch> copyBranches = copies.get(i).getBranches();
            for (int b = 0; b < origBranches.size() && b < copyBranches.size(); b++) {
                transferCounts(origBranches.get(b).getChildren(), copyBranches.get(b).getChildren());
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
