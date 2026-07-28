package qupath.ext.flowpath.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The phenotype hierarchy + reserved buckets; structural sibling of {@link GateTree}. */
public final class PhenotypeTree {

    private final List<PhenotypeNode> roots = new ArrayList<>();
    private final Map<String, PhenotypeNode> byName = new LinkedHashMap<>();

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
}
