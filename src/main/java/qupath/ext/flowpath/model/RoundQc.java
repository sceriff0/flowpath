package qupath.ext.flowpath.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The round-level QC an export carries: MIRAGE's {@code QC: <Metric>: [<markers>]} keys, one
 * per imaging round and metric. A cell failing round {@code r} is not removed — it becomes
 * Unmeasured for gates on round {@code r}'s markers only, so a CD3/CD8 gate keeps a cell whose
 * nucleus was lost in the CD68 round. The reference round and a round with only nuclear markers
 * emit no keys, so a marker in no round is never masked.
 * <p>
 * The metrics are global: one range per metric (a {@code qcround/<metric>} slug in the
 * {@link QualityFilter}) applies to every round. Values are held as {@code float}: QC precision
 * is ample there, and three metrics over a dozen rounds on a multi-million-cell slide would
 * otherwise double the memory for numbers nobody reads past two decimals.
 */
public final class RoundQc {

    /** One imaging round, named by its markers exactly as the key lists them. */
    public record Round(int index, List<String> markers) {
        public Round {
            markers = List.copyOf(markers);
        }

        /** {@code "[CD3, CD8]"}: the round as the key spells it. */
        public String label() {
            return "[" + String.join(", ", markers) + "]";
        }
    }

    /** One QC metric, applied to every round. */
    public record Metric(String slug, String label, String keyName) {}

    public static final RoundQc NONE = new RoundQc(List.of(), List.of(), new float[0][0][]);

    private final List<Round> rounds;
    private final List<Metric> metrics;
    /** [metric][round] -> one value per cell, or {@code null} when that pair was not exported. */
    private final float[][][] values;
    private final Map<String, Integer> roundOfMarker;

    private RoundQc(List<Round> rounds, List<Metric> metrics, float[][][] values) {
        this.rounds = List.copyOf(rounds);
        this.metrics = List.copyOf(metrics);
        this.values = values;
        Map<String, Integer> byMarker = new LinkedHashMap<>();
        for (Round r : rounds) {
            for (String m : r.markers()) byMarker.putIfAbsent(m, r.index());
        }
        this.roundOfMarker = Map.copyOf(byMarker);
    }

    /** Discover the rounds from {@code sampleKeys} and read their values off {@code index}. */
    static RoundQc discover(Set<String> sampleKeys, CellIndex index) {
        Map<List<String>, Integer> roundIndex = new LinkedHashMap<>();
        Map<String, Integer> metricIndex = new LinkedHashMap<>();
        List<Metric> metrics = new ArrayList<>();
        List<MeasurementName> names = new ArrayList<>();
        for (String key : sampleKeys) {
            if (key == null) continue;
            MeasurementName name = MeasurementName.classify(key);
            if (name.kind() != MeasurementName.Kind.QC_ROUND) continue;
            names.add(name);
            roundIndex.putIfAbsent(name.roundMarkers(), roundIndex.size());
            if (!metricIndex.containsKey(name.slug())) {
                metricIndex.put(name.slug(), metrics.size());
                metrics.add(new Metric(name.slug(), name.label(), name.metric()));
            }
        }
        if (names.isEmpty()) return NONE;
        List<Round> rounds = new ArrayList<>();
        roundIndex.forEach((markers, i) -> rounds.add(new Round(i, markers)));

        double[][] columns = index.readColumns(names.stream().map(MeasurementName::key).toList());
        float[][][] values = new float[metrics.size()][rounds.size()][];
        for (int c = 0; c < names.size(); c++) {
            MeasurementName name = names.get(c);
            float[] col = new float[columns[c].length];
            for (int i = 0; i < col.length; i++) col[i] = (float) columns[c][i];
            values[metricIndex.get(name.slug())][roundIndex.get(name.roundMarkers())] = col;
        }
        return new RoundQc(rounds, metrics, values);
    }

    public boolean isEmpty() {
        return rounds.isEmpty();
    }

    public List<Round> rounds() {
        return rounds;
    }

    public List<Metric> metrics() {
        return metrics;
    }

    /** The round {@code marker} was imaged in, or {@code -1} when no round key lists it. */
    public int roundOf(String marker) {
        return marker == null ? -1 : roundOfMarker.getOrDefault(marker, -1);
    }

    /** Metric {@code metric}'s value in round {@code round} for {@code cell}; NaN when not exported. */
    public double value(int metric, int round, int cell) {
        float[] col = values[metric][round];
        return col == null ? Double.NaN : col[cell];
    }

    /** Whether metric {@code metric} was exported for round {@code round} at all. */
    public boolean has(int metric, int round) {
        return values[metric][round] != null;
    }

    /**
     * Which cells fail which round under {@code filter}'s {@code qcround/*} ranges: a cell fails a
     * round when any metric with a range reads outside it there. A missing value is no evidence
     * and passes. {@link RoundMask#NONE} when no round range is set or nothing fails.
     */
    public RoundMask mask(QualityFilter filter) {
        if (isEmpty() || filter == null) return RoundMask.NONE;
        List<Integer> constrained = new ArrayList<>();
        List<QualityFilter.Range> ranges = new ArrayList<>();
        for (int m = 0; m < metrics.size(); m++) {
            QualityFilter.Range r = filter.range(metrics.get(m).slug());
            if (!r.isOpen()) {
                constrained.add(m);
                ranges.add(r);
            }
        }
        if (constrained.isEmpty()) return RoundMask.NONE;
        BitSet[] failed = new BitSet[rounds.size()];
        boolean any = false;
        for (Round round : rounds) {
            BitSet bits = new BitSet();
            for (int k = 0; k < constrained.size(); k++) {
                float[] col = values[constrained.get(k)][round.index()];
                if (col == null) continue;
                QualityFilter.Range r = ranges.get(k);
                for (int i = 0; i < col.length; i++) {
                    if (!r.accepts(col[i])) bits.set(i);
                }
            }
            if (!bits.isEmpty()) {
                failed[round.index()] = bits;
                any = true;
            }
        }
        return any ? new RoundMask(rounds, roundOfMarker, failed) : RoundMask.NONE;
    }

    @Override
    public String toString() {
        return "RoundQc" + rounds.stream().map(Round::label).toList() + " x "
                + Arrays.toString(metrics.stream().map(Metric::slug).toArray());
    }
}
