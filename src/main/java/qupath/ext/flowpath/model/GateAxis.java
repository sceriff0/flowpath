package qupath.ext.flowpath.model;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/**
 * One measurement axis of one gate, and every decision that depends on it.
 * <p>
 * An axis is a <em>slot</em> on a gate — X or Y — not a position in
 * {@link GateNode#getChannels()}. It names the channel that slot reads, the
 * {@link Compartment} and {@link Statistic} it reads that channel in, which of those
 * the loaded export actually offers ({@link #choicesFrom}), which one it must be
 * pinned to ({@link #pinTo}), and what has to follow when the channel changes
 * ({@link #retarget}).
 *
 * <h2>Why this is one object rather than six lambdas</h2>
 * The gate editor lays out one axis for a threshold gate, two for a quadrant and two
 * for a region gate. Those are three <em>layouts</em>, but there is only ever one
 * <em>decision</em>: which column this axis resolves to. Each builder used to
 * hand-wire that decision from the per-type getters and setters
 * ({@code gate::getCompartmentX}, {@code region::setStatisticY}, …), so a fix landed
 * in one builder and not the other two. Four consecutive commits fixed the same class
 * of bug one builder at a time — a scatter plot reading whole-cell mean while the gate
 * was set to nuclear median, an axis range anchored on a column the axis did not use,
 * a plot not refreshed when the compartment changed, a quadrant's initial render
 * disagreeing with its own selection. Every one of them was a place where an axis was
 * spelled out again instead of asked.
 *
 * <h2>Slots, not list positions</h2>
 * {@link GateNode#getChannels()} and its parallel {@code getCompartments()} /
 * {@code getStatistics()} lists <em>omit</em> a null channel, so on a quadrant gate
 * whose X channel is unset, list index 0 is the Y axis. Reading through
 * {@code compartmentAt(0)} and writing through a {@code setCompartmentX} therefore
 * addressed two different axes. This class reads and writes the same slot by
 * construction, so that skew is not expressible.
 *
 * <p>Free of JavaFX: every decision here is testable without a toolkit.
 */
public final class GateAxis {

    private final GateNode gate;
    private final int slot;

    private GateAxis(GateNode gate, int slot) {
        this.gate = gate;
        this.slot = slot;
    }

    // ---- addressing ---------------------------------------------------------

    /** How many axes a gate of this type has: two for quadrant and region gates, one otherwise. */
    public static int axisCount(GateNode gate) {
        return (gate instanceof QuadrantGate || gate instanceof Region2DGate) ? 2 : 1;
    }

    /**
     * The {@code slot}-th axis of {@code gate} (0 = X, 1 = Y).
     *
     * @throws IllegalArgumentException if the gate type has no such axis — a threshold
     *         gate has no Y, and answering with a whole-cell-mean placeholder is how a
     *         2D reader used to be handed an axis that did not exist.
     */
    public static GateAxis of(GateNode gate, int slot) {
        Objects.requireNonNull(gate, "gate");
        int count = axisCount(gate);
        if (slot < 0 || slot >= count) {
            throw new IllegalArgumentException("a " + gate.getGateType() + " gate has "
                    + count + " axis/axes; asked for axis " + slot);
        }
        return new GateAxis(gate, slot);
    }

    /** Every axis of {@code gate}, X first. */
    public static List<GateAxis> axesOf(GateNode gate) {
        List<GateAxis> axes = new ArrayList<>(axisCount(gate));
        for (int k = 0; k < axisCount(gate); k++) axes.add(of(gate, k));
        return axes;
    }

    /** Which axis this is: 0 for X, 1 for Y. */
    public int slot() {
        return slot;
    }

    // ---- what this axis reads -----------------------------------------------

    /** The marker channel this axis reads, or null when the slot is unset. */
    public String channel() {
        if (gate instanceof Region2DGate region) {
            return slot == 0 ? region.getChannelX() : region.getChannelY();
        }
        if (gate instanceof QuadrantGate quad) {
            return slot == 0 ? quad.getChannelX() : quad.getChannelY();
        }
        return gate.getChannel();
    }

    /** The compartment this axis reads its channel in. */
    public Compartment compartment() {
        if (gate instanceof Region2DGate region) {
            return slot == 0 ? region.getCompartmentX() : region.getCompartmentY();
        }
        if (gate instanceof QuadrantGate quad) {
            return slot == 0 ? quad.getCompartmentX() : quad.getCompartmentY();
        }
        return gate.getCompartment();
    }

    /** The statistic this axis reads its channel with. */
    public Statistic statistic() {
        if (gate instanceof Region2DGate region) {
            return slot == 0 ? region.getStatisticX() : region.getStatisticY();
        }
        if (gate instanceof QuadrantGate quad) {
            return slot == 0 ? quad.getStatisticX() : quad.getStatisticY();
        }
        return gate.getStatistic();
    }

    /** The (compartment, statistic) pair this axis currently reads with. */
    public Signal signal() {
        return new Signal(compartment(), statistic());
    }

    /** Point this axis at a different channel without touching anything else. */
    private void setChannel(String channel) {
        if (gate instanceof Region2DGate region) {
            if (slot == 0) region.setChannelX(channel); else region.setChannelY(channel);
        } else if (gate instanceof QuadrantGate quad) {
            if (slot == 0) quad.setChannelX(channel); else quad.setChannelY(channel);
        } else {
            gate.setChannel(channel);
        }
    }

    /**
     * Read this axis in {@code signal}'s compartment and statistic. The single place an
     * axis index is mapped onto the per-gate-type setters.
     */
    public void apply(Signal signal) {
        Objects.requireNonNull(signal, "signal");
        if (gate instanceof Region2DGate region) {
            if (slot == 0) {
                region.setCompartmentX(signal.compartment());
                region.setStatisticX(signal.statistic());
            } else {
                region.setCompartmentY(signal.compartment());
                region.setStatisticY(signal.statistic());
            }
        } else if (gate instanceof QuadrantGate quad) {
            if (slot == 0) {
                quad.setCompartmentX(signal.compartment());
                quad.setStatisticX(signal.statistic());
            } else {
                quad.setCompartmentY(signal.compartment());
                quad.setStatisticY(signal.statistic());
            }
        } else {
            gate.setCompartment(signal.compartment());
            gate.setStatistic(signal.statistic());
        }
    }

    // ---- the decision -------------------------------------------------------

    /**
     * What this export lets this axis be read as, and what it should be read as.
     * <p>
     * The whole per-axis decision, with no UI in it: {@link Choices#compartments()} and
     * {@link Choices#statistics()} are what a selector may offer, and
     * {@link Choices#signal()} is the one the axis must hold either way. A caller that
     * shows no selector still applies the signal — that is how a legacy export ends up
     * on the bare whole-cell-mean column instead of on a structured key that is not in
     * the file.
     */
    public Choices choicesFrom(CompartmentCapability capability) {
        String channel = channel();
        if (channel == null) {
            // Nothing to resolve against; leave the model's own default alone rather
            // than inventing a selection for an axis that reads nothing.
            return new Choices(List.of(), List.of(), signal());
        }
        if (capability == null || !capability.hasCompartments(channel)) {
            // Legacy / no per-compartment measurements: the bare marker column IS the
            // whole-cell mean, and no selector is offered.
            return new Choices(List.of(), List.of(), new Signal(Compartment.WHOLE_CELL, Statistic.MEAN));
        }
        List<Compartment> compartments = new ArrayList<>();
        EnumSet<Compartment> availableComps = EnumSet.noneOf(Compartment.class);
        availableComps.addAll(capability.compartmentsFor(channel));
        for (Compartment c : Compartment.values()) {
            if (availableComps.contains(c)) compartments.add(c);
        }
        List<Statistic> statistics = new ArrayList<>();
        EnumSet<Statistic> availableStats = EnumSet.noneOf(Statistic.class);
        availableStats.addAll(capability.statisticsFor(channel));
        for (Statistic s : Statistic.values()) {
            if (availableStats.contains(s)) statistics.add(s);
        }
        return new Choices(List.copyOf(compartments), List.copyOf(statistics),
                new Signal(capability.resolveCompartment(channel, compartment()),
                           capability.resolveStatistic(channel, statistic())));
    }

    /**
     * Pin this axis to a (compartment, statistic) the loaded export actually carries,
     * keeping the current selection whenever the export has it.
     */
    public void pinTo(CompartmentCapability capability) {
        apply(choicesFrom(capability).signal());
    }

    /**
     * The measurement column this axis resolves to, with its statistics registered — the
     * same {@link CellIndex#column} resolution {@code GatingEngine} classifies against,
     * so an editor reading an axis through here cannot display one column while the
     * engine gates on another.
     *
     * @return the column, or null when there is nothing to resolve yet (no channel, no
     *         index, no statistics)
     */
    public MeasuredColumn columnIn(CellIndex index, MarkerStats stats) {
        String channel = channel();
        if (channel == null || index == null || stats == null) return null;
        return index.column(channel, compartment(), statistic(), stats);
    }

    /**
     * Point this axis at {@code newChannel} and bring everything that depended on the
     * old one with it: branch labels that still spell the old channel, and the signal,
     * which is re-resolved because the new channel need not be quantified the same way.
     * <p>
     * That last step is the one that kept being missed. A gate reading
     * {@code "CD3: Nucleus: Median"} retargeted to a channel quantified whole-cell only
     * would keep {@code Nucleus}, resolve to a key that is not in the file, read NaN for
     * every cell, and show an empty plot with no explanation. The editor used to catch
     * it only by rebuilding itself afterwards, so anything that read the axis <em>before</em>
     * the rebuild — a scatter refresh, an axis range — read the stale one.
     *
     * @return true if the channel actually changed
     */
    public boolean retarget(String newChannel, CompartmentCapability capability) {
        String oldChannel = channel();
        if (Objects.equals(oldChannel, newChannel)) return false;
        List<String> before = channels();
        setChannel(newChannel);
        renameDefaultBranches(before, channels());
        pinTo(capability);
        return true;
    }

    /** The gate's channels, one per slot, nulls included. */
    private List<String> channels() {
        List<String> out = new ArrayList<>(axisCount(gate));
        for (GateAxis axis : axesOf(gate)) out.add(axis.channel());
        return out;
    }

    /**
     * Move the branch labels that were still the gate's defaults onto the new defaults.
     * <p>
     * A label the user has edited is never touched: it is their name for the population,
     * not a caption. The three builders each had their own rule for this — the threshold
     * editor compared against the default and renamed only on an exact match, the
     * quadrant editor blind-substituted {@code "CD3+"} for {@code "CD8+"} inside whatever
     * the user had typed, and the region editor did nothing at all, leaving a gate
     * labelled with a channel it no longer read.
     */
    private void renameDefaultBranches(List<String> before, List<String> after) {
        if (before.contains(null) || after.contains(null)) return;
        List<String> was = gate.defaultBranchNames(before);
        List<String> now = gate.defaultBranchNames(after);
        List<Branch> branches = gate.getBranches();
        int n = Math.min(branches.size(), Math.min(was.size(), now.size()));
        for (int i = 0; i < n; i++) {
            if (was.get(i).equals(branches.get(i).getName())) branches.get(i).setName(now.get(i));
        }
    }

    // ---- whole-gate operations ----------------------------------------------

    /**
     * Pin every axis of {@code gate} to a signal the loaded export carries, before the
     * gate is ever shown.
     * <p>
     * The gate model defaults to whole-cell Median because that is what MIRAGE writes by
     * default, but a legacy or mean-only export has no Median column. Without this the
     * gate was created on Median, rendered its {@code W·med} badge in the tree, and then
     * had the statistic corrected the moment the editor opened — the badge appearing and
     * vanishing on every new gate.
     */
    public static void pinAll(GateNode gate, CompartmentCapability capability) {
        if (gate == null) return;
        for (GateAxis axis : axesOf(gate)) axis.pinTo(capability);
    }

    /**
     * Carry each axis' signal across a gate-type change, slot by slot.
     * <p>
     * Only slots the source actually reads are copied. A threshold gate has no Y axis, so
     * converting one into a 2D gate must leave the new Y at the target's own default
     * rather than stamping it with a whole-cell <em>Mean</em> — a column a default
     * (Median-only) MIRAGE export does not contain.
     */
    public static void copySignals(GateNode from, GateNode to) {
        if (from == null || to == null) return;
        int slots = Math.min(axisCount(from), axisCount(to));
        for (int k = 0; k < slots; k++) {
            GateAxis source = of(from, k);
            if (source.channel() == null) continue;
            of(to, k).apply(source.signal());
        }
    }

    // ---- value types --------------------------------------------------------

    /**
     * How a channel is read: in which compartment, summarised by which statistic.
     * Together with the channel this is what {@code CellIndex.column} resolves to a
     * measurement key.
     */
    public record Signal(Compartment compartment, Statistic statistic) {
        public Signal {
            compartment = compartment != null ? compartment : Compartment.WHOLE_CELL;
            statistic = statistic != null ? statistic : Statistic.MEAN;
        }
    }

    /**
     * What an axis may be read as, and what it is read as. {@code compartments} and
     * {@code statistics} are empty when the export offers no choice at all.
     */
    public record Choices(List<Compartment> compartments, List<Statistic> statistics, Signal signal) {

        public Choices {
            compartments = List.copyOf(compartments);
            statistics = List.copyOf(statistics);
        }

        /** True when the export carries per-compartment measurements worth offering. */
        public boolean offersCompartment() {
            return !compartments.isEmpty();
        }

        /**
         * True only when the export carries more than one statistic. With one there is
         * nothing to choose — but {@link #signal()} still has to be applied, because the
         * one it carries may not be the one the gate defaulted to.
         */
        public boolean offersStatistic() {
            return statistics.size() > 1;
        }
    }
}
