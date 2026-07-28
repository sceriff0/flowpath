package qupath.ext.flowpath.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.BiConsumer;

/** Prioritised worklist + bulk pre-clear rules for reconciliation (spec §7.3). */
public final class ReconciliationQueue {

    private ReconciliationQueue() {}

    private static int rank(PhenotypeOutcome o) {
        return switch (o) {
            case CONFLICT -> 0;
            case AMBIGUOUS -> 1;
            case UNCLASSIFIED -> 2;
            default -> 3; // excluded
        };
    }

    /** Uncertain first (Conflict → Ambiguous), then optionally Unclassified; Artefact excluded. */
    public static List<CellPhenotype> buildWorklist(Collection<CellPhenotype> cells, boolean includeUnclassified) {
        List<CellPhenotype> kept = new ArrayList<>();
        for (CellPhenotype c : cells) {
            PhenotypeOutcome o = c.getOutcome();
            if (o == PhenotypeOutcome.CONFLICT || o == PhenotypeOutcome.AMBIGUOUS
                    || (includeUnclassified && o == PhenotypeOutcome.UNCLASSIFIED)) {
                kept.add(c);
            }
        }
        // Stable sort by rank keeps input order within each group.
        kept.sort((a, b) -> Integer.compare(rank(a.getOutcome()), rank(b.getOutcome())));
        return kept;
    }

    /** Commit the top candidate when its score ≥ minScore; return the cells left for manual triage. */
    public static List<CellPhenotype> residueAfterPreClear(
            List<CellPhenotype> worklist, double minScore,
            BiConsumer<CellPhenotype, String> commit) {
        List<CellPhenotype> residue = new ArrayList<>();
        for (CellPhenotype c : worklist) {
            List<Candidate> cands = c.candidates();
            if (!cands.isEmpty() && cands.get(0).score() >= minScore) {
                commit.accept(c, cands.get(0).name());
            } else {
                residue.add(c);
            }
        }
        return residue;
    }

    /**
     * The nearest phenotype that is an ancestor of every candidate (a candidate that is itself
     * an ancestor of the others counts). Returns {@code null} when there are fewer than two
     * candidates or they share no common ancestor.
     */
    public static String propagateUp(CellPhenotype cell, PhenotypeTree tree) {
        List<String> names = cell.candidateNames();
        if (names.size() < 2) return null;

        // Ancestor-inclusive chain (self first, then ancestors) for the first candidate.
        List<String> chain = new ArrayList<>();
        chain.add(names.get(0));
        chain.addAll(tree.ancestorsOf(names.get(0)));

        for (String anc : chain) {
            boolean commonToAll = true;
            for (String name : names) {
                boolean covered = name.equals(anc) || tree.ancestorsOf(name).contains(anc);
                if (!covered) { commonToAll = false; break; }
            }
            if (commonToAll) return anc;
        }
        return null;
    }
}
