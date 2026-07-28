package qupath.ext.flowpath.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One node of the phenotype hierarchy parsed from the panel_model.json sidecar. */
public final class PhenotypeNode {

    private final String name;
    private final String parent;               // null for a root
    private final Map<String, Integer> signature;
    private final int color;                   // packed RGB (R<<16)|(G<<8)|B
    private final boolean leaf;
    private final List<PhenotypeNode> children = new ArrayList<>();
    private int count;                          // transient live count

    public PhenotypeNode(String name, String parent, Map<String, Integer> signature,
                         int color, boolean leaf) {
        this.name = name;
        this.parent = parent;
        this.signature = signature != null ? new LinkedHashMap<>(signature) : new LinkedHashMap<>();
        this.color = color;
        this.leaf = leaf;
    }

    public String getName() { return name; }
    public String getParent() { return parent; }
    public Map<String, Integer> getSignature() { return signature; }
    public int getColor() { return color; }
    public boolean isLeaf() { return leaf; }
    public List<PhenotypeNode> getChildren() { return children; }
    public void addChild(PhenotypeNode child) { children.add(child); }
    public int getCount() { return count; }
    public void setCount(int count) { this.count = count; }
}
