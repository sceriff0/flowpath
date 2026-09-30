package qupath.ext.flowpath.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Which cells failed which imaging round under the current round-QC ranges — <b>the one accessor
 * every reader of a raw marker column asks</b>: the gate predicate, the statistics, the plots, the
 * cohort's landmarks and review, the CSV. A failed round makes that round's markers Unmeasured for
 * that cell, exactly as a NaN value does; the raw value itself is never overwritten.
 * <p>
 * Immutable, and equal to another mask when the same cells fail the same rounds — which is what a
 * cache of statistics keys on, since two different thresholds that fail the same cells describe the
 * same population. One {@link BitSet} per round with a failure: {@code n/8} bytes each.
 */
public final class RoundMask {

    public static final RoundMask NONE = new RoundMask(List.of(), Map.of(), new BitSet[0]);

    private final List<RoundQc.Round> rounds;
    private final Map<String, Integer> roundOfMarker;
    /** Per round, the cells that fail it; {@code null} where none do. */
    private final BitSet[] failed;

    RoundMask(List<RoundQc.Round> rounds, Map<String, Integer> roundOfMarker, BitSet[] failed) {
        this.rounds = List.copyOf(rounds);
        this.roundOfMarker = Map.copyOf(roundOfMarker);
        this.failed = failed;
    }

    /** True when no cell fails any round. */
    public boolean isEmpty() {
        for (BitSet b : failed) {
            if (b != null && !b.isEmpty()) return false;
        }
        return true;
    }

    /**
     * The cells whose round for {@code marker} failed, or {@code null} when none did (or the marker
     * is in no round). Resolve it once per column, then test cells against it.
     */
    public BitSet failedFor(String marker) {
        if (marker == null) return null;
        Integer r = roundOfMarker.get(marker);
        return r == null ? null : failed[r];
    }

    /** Whether {@code cell}'s round for {@code marker} failed. */
    public boolean fails(String marker, int cell) {
        BitSet b = failedFor(marker);
        return b != null && b.get(cell);
    }

    /** Cells failing round {@code round}. */
    public int failedCount(int round) {
        return round >= 0 && round < failed.length && failed[round] != null ? failed[round].cardinality() : 0;
    }

    /** The rounds {@code cell} failed, by label ({@code "[CD3, CD8]"}), in round order. */
    public List<String> failedRoundLabels(int cell) {
        List<String> out = new ArrayList<>(0);
        for (int r = 0; r < failed.length; r++) {
            if (failed[r] != null && failed[r].get(cell)) out.add(rounds.get(r).label());
        }
        return out;
    }

    /**
     * The marker a statistics column belongs to: the marker slot of a compartment key, else the
     * bare key itself. The link between {@link MarkerStats}' key-named columns and this mask.
     */
    public static String markerOf(String columnKey) {
        MeasurementKeys.Parsed parsed = MeasurementKeys.parse(columnKey);
        return parsed != null ? parsed.marker() : columnKey;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RoundMask other)) return false;
        if (isEmpty() && other.isEmpty()) return true;
        if (!roundOfMarker.equals(other.roundOfMarker) || failed.length != other.failed.length) return false;
        for (int r = 0; r < failed.length; r++) {
            BitSet a = failed[r] == null || failed[r].isEmpty() ? null : failed[r];
            BitSet b = other.failed[r] == null || other.failed[r].isEmpty() ? null : other.failed[r];
            if (!Objects.equals(a, b)) return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        if (isEmpty()) return 0;
        return 31 * roundOfMarker.hashCode() + Arrays.hashCode(failed);
    }
}
