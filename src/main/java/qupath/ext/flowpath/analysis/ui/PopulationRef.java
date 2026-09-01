package qupath.ext.flowpath.analysis.ui;

import qupath.ext.flowpath.model.PopulationStats;

import java.util.Objects;

/**
 * Which population a plot is showing: the root gate it descends from, and its gating route
 * under that root.
 * <p>
 * <b>Why the root index is part of the identity.</b> Two un-renamed root gates on the
 * identical channel emit rows with byte-identical {@link PopulationStats.Row#path()} values —
 * {@code GateNode} names its branches {@code channel + "+"} / {@code channel + "-"} purely
 * from the channel, so nothing in the path or in {@link PopulationStats.Row#gateChannel()}
 * can tell the two apart. A canvas that selected a population by path alone therefore summed
 * both roots into one bar (reading 2x the true count), labelled both roots' regions
 * identically, and offered only the first root's populations for selection at all. That is
 * the same defect {@code CompositionCanvas} was fixed for, and
 * {@link PopulationStats.Row#rootIndex()} is the one field that resolves it — assigned once,
 * from the tree structure itself, by {@code PopulationStats.collectFromRoots}.
 *
 * @param rootIndex {@link PopulationStats.Row#rootIndex()} — the enabled root this population
 *                  descends from
 * @param path      {@link PopulationStats.Row#path()} — the gating route under that root
 */
public record PopulationRef(int rootIndex, String path) {

    public PopulationRef {
        Objects.requireNonNull(path, "path");
    }

    /** The reference to the population one row describes. */
    public static PopulationRef of(PopulationStats.Row row) {
        return new PopulationRef(row.rootIndex(), row.path());
    }

    /** {@code true} when {@code row} is a row for exactly this population. */
    public boolean matches(PopulationStats.Row row) {
        return row.rootIndex() == rootIndex && path.equals(row.path());
    }

    /**
     * How to name this population to a user.
     *
     * @param disambiguateRoot append {@code " (root N)"}, one-based — needed exactly when the
     *                         report holds more than one enabled root, since the paths alone
     *                         may then be ambiguous
     */
    public String label(boolean disambiguateRoot) {
        return disambiguateRoot ? path + " (root " + (rootIndex + 1) + ")" : path;
    }
}
