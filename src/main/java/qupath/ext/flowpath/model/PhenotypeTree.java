package qupath.ext.flowpath.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The phenotype hierarchy + reserved buckets; structural sibling of {@link GateTree}. */
public final class PhenotypeTree {

    private final List<PhenotypeNode> roots = new ArrayList<>();
    private final Map<String, PhenotypeNode> byName = new LinkedHashMap<>();
    private final Map<Integer, ConstraintEntry> constraintsById = new LinkedHashMap<>();
    private final Map<String, Integer> reservedColors = new LinkedHashMap<>();

    public void addRoot(PhenotypeNode node) { roots.add(node); }

    public void register(PhenotypeNode node) {
        if (node != null && node.getName() != null) byName.put(node.getName(), node);
    }

    public List<PhenotypeNode> getRoots() { return roots; }

    public PhenotypeNode findByName(String name) { return byName.get(name); }

    /** Parent chain for {@code name}, nearest ancestor first, excluding the node itself. */
    public List<String> ancestorsOf(String name) {
        List<String> out = new ArrayList<>();
        PhenotypeNode n = byName.get(name);
        String p = n != null ? n.getParent() : null;
        while (p != null) {
            out.add(p);
            PhenotypeNode pn = byName.get(p);
            p = pn != null ? pn.getParent() : null;
        }
        return out;
    }

    public void addConstraint(ConstraintEntry entry) {
        if (entry != null) constraintsById.put(entry.id(), entry);
    }

    public ConstraintEntry getConstraint(int id) { return constraintsById.get(id); }

    /** Human-readable label for a Conflict's violated constraint; "—" when unknown. */
    public String constraintLabel(int id) {
        ConstraintEntry e = constraintsById.get(id);
        if (e == null) return "—";
        List<String> m = e.markers();
        if ("requires".equals(e.kind()) && m.size() == 2) {
            return m.get(0) + " → " + m.get(1) + " (requires)";
        }
        return String.join(" ⊥ ", m) + " (" + e.kind() + ")";
    }

    public void setReservedColor(String name, int packed) { reservedColors.put(name, packed); }

    public int reservedColor(String name) { return reservedColors.getOrDefault(name, 0x808080); }
}
