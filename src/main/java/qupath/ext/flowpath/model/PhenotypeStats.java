package qupath.ext.flowpath.model;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;

/** Aggregate counts/rates + reconciliation progress for the stats panel (spec §7.4). */
public final class PhenotypeStats {

    private final int total;
    private final Map<PhenotypeOutcome, Integer> counts;
    private final int uncertainTotal;
    private final int uncertainResolved;

    private PhenotypeStats(int total, Map<PhenotypeOutcome, Integer> counts,
                           int uncertainTotal, int uncertainResolved) {
        this.total = total;
        this.counts = counts;
        this.uncertainTotal = uncertainTotal;
        this.uncertainResolved = uncertainResolved;
    }

    public static PhenotypeStats compute(Collection<CellPhenotype> cells) {
        Map<PhenotypeOutcome, Integer> counts = new EnumMap<>(PhenotypeOutcome.class);
        int total = 0, uncertain = 0, resolved = 0;
        for (CellPhenotype c : cells) {
            total++;
            counts.merge(c.getOutcome(), 1, Integer::sum);
            if (c.getOutcome().isUncertain()) {
                uncertain++;
                if (c.getProvenance() == Provenance.MANUAL) resolved++;
            }
        }
        return new PhenotypeStats(total, counts, uncertain, resolved);
    }

    public int total() { return total; }
    public int count(PhenotypeOutcome o) { return counts.getOrDefault(o, 0); }
    public double rate(PhenotypeOutcome o) { return total == 0 ? 0.0 : (double) count(o) / total; }
    public int uncertainTotal() { return uncertainTotal; }
    public int uncertainResolved() { return uncertainResolved; }
    public double reconciliationProgress() { return uncertainTotal == 0 ? 1.0 : (double) uncertainResolved / uncertainTotal; }
}
