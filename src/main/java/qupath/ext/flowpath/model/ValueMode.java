package qupath.ext.flowpath.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * <b>One way of reading a gate's values</b>, and the whole of what the editor's "Values"
 * selector may offer for a given gate.
 * <p>
 * The selector used to be a fixed two-way radio, {@code Raw} / {@code Z-score}. The
 * trouble with a fixed pair is that <b>standardisation is not a display mode FlowPath
 * applies on top of a column</b> -- it is a property of the column itself. If a pipeline
 * emits {@code "CD3: Cell: Median"} and {@code "CD3: Cell: Median Z"}, those are two
 * <em>different measurement columns</em>, and reaching the second means changing the
 * <em>Statistic</em>, not the mode. One user intent -- "show me standardised values" --
 * would then be split across two controls that did not know about each other, with the
 * dropdown silently disabling the radio and nothing to say the two were about the same
 * thing.
 * <p>
 * <b>As of MIRAGE {@code main} that second column does not exist:</b>
 * {@code STATISTICS} is {@code ("Median", "Mean", "Sum")} and nothing is pre-standardised,
 * so this list resolves to {@link Kind#RAW} plus {@link Kind#COMPUTED} and the
 * {@link Kind#MIRAGE} branch stays dormant. It is here because the vocabulary is read from
 * the file rather than hard-coded, so the day a standardised column does arrive it becomes
 * a labelled option instead of a wrong answer -- which is the same reason {@link Statistic}
 * stopped being an enum.
 *
 * <h2>Three kinds, and who computed the number</h2>
 * <ul>
 *   <li>{@link Kind#RAW} -- the column as measured.</li>
 *   <li>{@link Kind#MIRAGE} -- a sibling column MIRAGE already standardised
 *       ({@code " Z"} / {@code " RobustZ"}). Offered only when that column is in the file
 *       for <em>every</em> axis of the gate. Selecting it changes each axis's statistic;
 *       FlowPath applies nothing on top.</li>
 *   <li>{@link Kind#COMPUTED} -- FlowPath standardises the base column itself, over the
 *       cells currently loaded and filtered. Always offered, but declined with a reason
 *       when the column is constant or MIRAGE already standardised it.</li>
 * </ul>
 * The last two are <b>not the same number</b>, even in principle, which is why the labels
 * name who computed them: MIRAGE standardises across every cell of a patient, FlowPath
 * across whatever survives the current quality filter and annotation ROI. A user who
 * cannot tell them apart cannot reproduce either.
 *
 * <h2>Identified by normalisation, not by statistic</h2>
 * A 2D gate's axes can sit on different base statistics (Median on X, Mean on Y), so a
 * mode cannot name one concrete {@link Statistic}. It names the <em>normalisation suffix</em>
 * instead, and {@link #applyTo} composes each axis's own sibling from its own base. One
 * gate-wide answer, whatever the axes read.
 *
 * <h2>Derived, never set</h2>
 * {@link #availableFor} is a pure function of facts -- the gate, the capability scanned
 * from the file, and (when there is one) the index and its statistics. Nothing outside
 * decides which modes exist, matching the rule {@code UmapSession.viewState()} already
 * follows on the UMAP side, and it is table-testable with no JavaFX toolkit.
 *
 * <h2>Declined, not hidden</h2>
 * A mode the gate cannot use right now is still <em>offered</em>, carrying an
 * {@link #unavailableReason}. FlowPath's own standardisation is meaningless over a
 * constant column, and wrong over one MIRAGE already standardised — but a user who simply
 * finds the option missing learns nothing, and is left to guess whether it was ever there.
 * Only modes whose column is genuinely absent from the file are omitted, because there is
 * nothing to explain about a measurement that was never exported.
 *
 * @param kind              which of the three this is
 * @param normalisation     the suffix each axis's statistic must carry: {@code ""},
 *                          {@code " Z"} or {@code " RobustZ"}
 * @param computed          whether the gate's {@code thresholdIsZScore} flag must be set
 * @param label             what the button says, naming who computed the number
 * @param tooltip           the longer explanation behind it
 * @param unavailableReason why this mode cannot be chosen, or {@code null} when it can
 */
public record ValueMode(Kind kind, String normalisation, boolean computed,
                        String label, String tooltip, String unavailableReason) {

    /** Who produced the number the gate compares against. */
    public enum Kind { RAW, MIRAGE, COMPUTED }

    public ValueMode {
        Objects.requireNonNull(kind, "kind");
        normalisation = normalisation == null ? "" : normalisation;
        if (kind == Kind.COMPUTED && !computed) {
            throw new IllegalArgumentException("a COMPUTED mode must set the computed flag");
        }
        if (kind != Kind.COMPUTED && computed) {
            throw new IllegalArgumentException(
                    "only FlowPath's own standardisation sets the computed flag; "
                    + kind + " reads a column that is already what it claims to be");
        }
        if (kind == Kind.MIRAGE && normalisation.isEmpty()) {
            throw new IllegalArgumentException("a MIRAGE mode must name a normalisation");
        }
    }

    private static final ValueMode RAW = new ValueMode(Kind.RAW, "", false,
            "Raw",
            "Compare the column as measured, with no standardisation.", null);

    private static final String COMPUTED_LABEL = "Z-score (computed here)";
    private static final String COMPUTED_TOOLTIP =
            "Standardise against this column's own distribution.\n"
            + "Computed by FlowPath over the cells currently loaded and filtered — not "
            + "a value read from the export, and not the same number as MIRAGE's, which "
            + "spans every cell of the patient.";

    /** FlowPath's own standardisation, offered but declined, with the reason shown. */
    private static ValueMode computedButUnavailable(String reason) {
        return new ValueMode(Kind.COMPUTED, "", true, COMPUTED_LABEL, reason, reason);
    }

    private static final ValueMode COMPUTED = new ValueMode(Kind.COMPUTED, "", true,
            COMPUTED_LABEL, COMPUTED_TOOLTIP, null);

    /** How a normalisation suffix is named in the selector. */
    private static String labelFor(String normalisation) {
        return switch (normalisation) {
            case " Z" -> "Z-score (MIRAGE)";
            case " RobustZ" -> "Robust Z (MIRAGE)";
            default -> normalisation.trim() + " (MIRAGE)";
        };
    }

    /**
     * Every mode this gate can offer, in display order: raw first, then MIRAGE's own
     * standardisations in {@link Statistic#standardisingNormalisations()} order, then
     * FlowPath's computed one last.
     * <p>
     * Raw is always present -- there is always a column to read unstandardised, and a
     * selector with nothing in it would leave the gate unreadable.
     *
     * @param gate       the gate to describe; every axis must agree for a mode to appear
     * @param capability what the file carries, from the one ingest scan. When {@code null}
     *                   or not rich, no MIRAGE mode is offered: an absent capability means
     *                   "not scanned", which is not evidence a standardised column exists,
     *                   and offering one would pin an axis to a key that is not in the file
     *                   and read NaN for every cell.
     * @param index      the loaded cells, or {@code null} if none yet
     * @param stats      statistics for {@code index}, or {@code null}
     */
    public static List<ValueMode> availableFor(GateNode gate, CompartmentCapability capability,
                                               CellIndex index, MarkerStats stats) {
        List<ValueMode> modes = new ArrayList<>(4);
        if (gate == null) return modes;
        modes.add(RAW);

        List<GateAxis> axes = GateAxis.axesOf(gate);
        if (!axes.isEmpty() && capability != null && capability.isRich()) {
            for (String norm : Statistic.standardisingNormalisations()) {
                if (allAxesOffer(axes, capability, norm)) {
                    modes.add(new ValueMode(Kind.MIRAGE, norm, false,
                            labelFor(norm),
                            "Read MIRAGE's own " + labelFor(norm).replace(" (MIRAGE)", "")
                            + " column.\nStandardised by MIRAGE across every cell of this "
                            + "patient, and already in the file — FlowPath applies "
                            + "nothing on top.", null));
                }
            }
        }

        if (!axes.isEmpty()) {
            String reason = whyNotStandardisedHere(axes, index, stats);
            modes.add(reason == null ? COMPUTED : computedButUnavailable(reason));
        }
        return modes;
    }

    /** Every axis has the {@code norm} sibling of its own base statistic in the file. */
    private static boolean allAxesOffer(List<GateAxis> axes, CompartmentCapability capability,
                                        String norm) {
        for (GateAxis axis : axes) {
            String marker = axis.channel();
            Statistic statistic = axis.statistic();
            if (marker == null || statistic == null) return false;
            if (!capability.offers(marker, axis.compartment(),
                    statistic.withNormalisation(norm))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether FlowPath's own standardisation is meaningful for every axis.
     * <p>
     * Undecidable counts as available. "This column is flat" and "no cells are loaded yet"
     * are different answers that a single boolean would merge, and merging them disables
     * the mode on an editor that has simply not seen data -- discarding a saved gate's
     * preference before it could ever be honoured.
     * <p>
     * It is also withdrawn over a column MIRAGE already standardised: a second pass over
     * already-centred data would not throw and would look almost right, while actually
     * rescaling the axis by a factor that varies with the current filter.
     */
    private static String whyNotStandardisedHere(List<GateAxis> axes, CellIndex index,
                                                 MarkerStats stats) {
        for (GateAxis axis : axes) {
            Statistic statistic = axis.statistic();
            if (statistic != null && statistic.isStandardised()) {
                return "This statistic is already standardised by MIRAGE, across every "
                        + "cell of the patient.\nStandardising it again would rescale the "
                        + "axis by whatever is currently filtered.";
            }
            if (index == null || stats == null) continue;
            String channel = axis.channel();
            if (channel == null || index.getMarkerIndex(channel) < 0) continue;
            MeasuredColumn column = index.column(channel, axis.compartment(), statistic, stats);
            if (column == null || !column.hasSpread()) {
                return "This channel's values are constant, so there is no spread to "
                        + "standardise against.";
            }
        }
        return null;
    }

    /**
     * Put {@code gate} into this mode: point every axis at its own sibling column for this
     * normalisation, and set the computed flag to match.
     * <p>
     * <b>One writer.</b> Selecting a mode changes two things that must move together -- the
     * statistic each axis reads and whether FlowPath standardises it -- and a caller that
     * set one without the other would leave the engine z-scoring a column the editor is
     * drawing raw. That display/classification split is a defect this repository has
     * shipped before; doing it here means no caller can spell it half-way.
     */
    public void applyTo(GateNode gate) {
        if (gate == null) return;
        for (GateAxis axis : GateAxis.axesOf(gate)) {
            Statistic statistic = axis.statistic();
            if (statistic == null) continue;
            axis.apply(new GateAxis.Signal(axis.compartment(),
                    statistic.withNormalisation(normalisation)));
        }
        gate.setThresholdIsZScore(computed);
    }

    /**
     * The mode {@code gate} is currently in, as one of {@code modes}.
     * <p>
     * Falls back to the first entry -- raw -- when the gate's combination is not on offer,
     * which is what happens when a file's capability no longer carries a column a saved
     * gate was pinned to. Returning raw rather than null means the selector always has a
     * selection; the caller must write that fallback back onto the gate with
     * {@link #applyTo}, so the editor and the engine do not disagree about it.
     */
    public static ValueMode selectedIn(List<ValueMode> modes, GateNode gate) {
        if (modes == null || modes.isEmpty()) return null;
        if (gate == null) return modes.get(0);

        boolean computed = gate.isThresholdIsZScore();
        String norm = normalisationOf(gate);
        if (norm != null) {
            for (ValueMode mode : modes) {
                // An unavailable mode is never the answer, even when the gate's own flags
                // spell it: that is exactly the saved-gate-meets-flat-column case, and the
                // caller writes the raw fallback back onto the gate.
                if (mode.available() && mode.computed() == computed
                        && mode.normalisation().equals(norm)) {
                    return mode;
                }
            }
        }
        return modes.get(0);
    }

    /**
     * The one normalisation every axis carries, or {@code null} if they disagree.
     * <p>
     * Axes that disagree have no gate-wide mode -- which is a real state, reachable by
     * loading a hand-edited file -- and answering {@code null} lets the caller fall back to
     * raw and write that back, rather than picking one axis's answer for both.
     */
    private static String normalisationOf(GateNode gate) {
        String seen = null;
        for (GateAxis axis : GateAxis.axesOf(gate)) {
            Statistic statistic = axis.statistic();
            if (statistic == null) continue;
            String norm = statistic.normalisation();
            if (seen == null) seen = norm;
            else if (!seen.equals(norm)) return null;
        }
        return seen == null ? "" : seen;
    }

    /** True when this mode can actually be chosen right now. */
    public boolean available() {
        return unavailableReason == null;
    }

    /** True when this mode reads a number FlowPath did not compute. */
    public boolean readsExportedValues() {
        return kind != Kind.COMPUTED;
    }
}
