package qupath.ext.flowpath.model;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/** Pure viewer-filter predicates for the Phenotype Tree pane (spec §7.2). */
public final class PhenotypeFilter {

    private PhenotypeFilter() {}

    /** Committed cells whose class is {@code phenotypeName} or a descendant of it. */
    public static Predicate<CellPhenotype> forNode(String phenotypeName, PhenotypeTree tree) {
        Set<String> subtree = new HashSet<>();
        subtree.add(phenotypeName);
        PhenotypeNode node = tree.findByName(phenotypeName);
        if (node != null) collect(node, subtree);
        return c -> c.getOutcome() == PhenotypeOutcome.PHENOTYPE && subtree.contains(c.getCommitted());
    }

    private static void collect(PhenotypeNode node, Set<String> out) {
        for (PhenotypeNode child : node.getChildren()) {
            out.add(child.getName());
            collect(child, out);
        }
    }

    /** Reserved bucket filter; "Uncertain" = Ambiguous ∪ Conflict. */
    public static Predicate<CellPhenotype> forReserved(String reservedName) {
        if ("Uncertain".equals(reservedName)) {
            return c -> c.getOutcome().isUncertain();
        }
        return c -> reservedName.equals(c.getOutcome().reservedName());
    }
}
