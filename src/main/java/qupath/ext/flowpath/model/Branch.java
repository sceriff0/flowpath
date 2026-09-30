package qupath.ext.flowpath.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A named output partition of a gate. Every gate type produces N branches,
 * each with a name, color, child gates, and a transient cell count.
 * <p>
 * ThresholdGate has 2 branches (positive/negative).
 * QuadrantGate has 4 branches (++, +-, -+, --).
 * Region gates (polygon/rectangle/ellipse) have 2 branches (inside/outside).
 */
public class Branch {

    private String name;
    private int color;
    private List<GateNode> children;
    private transient int count;
    // A gate's Unmeasured cells, by reason, filled by the same walk as count. Held on the gate's
    // FIRST branch (GateNode.getUnmeasuredCount reads it there), so the transfer that carries
    // counts from the walked copy onto the live tree carries these too, with no second pairing.
    private transient int unmeasured;
    private transient int unmeasuredRoundQc;
    private transient int unmeasuredSkipped;

    public Branch(String name, int color) {
        this.name = name;
        this.color = color;
        this.children = new ArrayList<>();
    }

    public Branch(String name, int color, List<GateNode> children) {
        this.name = name;
        this.color = color;
        this.children = children != null ? children : new ArrayList<>();
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public int getColor() { return color; }
    public void setColor(int color) { this.color = color; }

    public List<GateNode> getChildren() { return children; }
    public void setChildren(List<GateNode> children) { this.children = children != null ? children : new ArrayList<>(); }

    public int getCount() { return count; }
    public void setCount(int count) { this.count = count; }

    int getUnmeasured() { return unmeasured; }
    int getUnmeasuredRoundQc() { return unmeasuredRoundQc; }
    int getUnmeasuredSkipped() { return unmeasuredSkipped; }

    void recordUnmeasured(UnmeasuredReason reason) {
        unmeasured++;
        if (reason == UnmeasuredReason.ROUND_QC) unmeasuredRoundQc++;
        else if (reason == UnmeasuredReason.SKIPPED) unmeasuredSkipped++;
    }

    /** Zero this branch's count and, on a gate's first branch, its Unmeasured counts. */
    public void resetCounts() {
        count = 0;
        unmeasured = 0;
        unmeasuredRoundQc = 0;
        unmeasuredSkipped = 0;
    }

    public boolean isLeaf() { return children.isEmpty(); }

    /**
     * Deep-copy this branch and all its children recursively.
     */
    public Branch deepCopy() {
        List<GateNode> copiedChildren = new ArrayList<>();
        for (GateNode child : children) {
            copiedChildren.add(child.deepCopy());
        }
        return new Branch(name, color, copiedChildren);
    }

    /**
     * Copy transient count from another branch.
     */
    public void transferCountFrom(Branch source) {
        this.count = source.count;
        this.unmeasured = source.unmeasured;
        this.unmeasuredRoundQc = source.unmeasuredRoundQc;
        this.unmeasuredSkipped = source.unmeasuredSkipped;
    }
}
