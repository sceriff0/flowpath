package qupath.ext.flowpath.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * <b>One way of reading a gate's values</b>, and the whole of what the editor's "Values"
 * selector may offer for a given gate.
 *
 * <h2>Only what the file carries</h2>
 * Every entry corresponds to a measurement column that is <em>actually in the export</em>.
 * There is no mode for a number FlowPath would compute itself.
 * <p>
 * That is a deliberate narrowing. The control this replaces was a fixed
 * {@code Raw} / {@code Z-score} radio whose z-score FlowPath derived on the fly, by
 * standardising the column against the cells currently loaded <em>and filtered</em>. It
 * was offered whether or not anything in the data resembled it, and it moved: tighten a
 * quality filter or draw a different annotation ROI and the same slider position meant a
 * different cut, because the mean and standard deviation underneath it had changed. A gate
 * defined that way cannot be reproduced from the export alone, and a threshold quoted in a
 * paper's methods would not identify the same cells on a re-run.
 * <p>
 * So a gate compares against numbers that exist in the file. If a pipeline emits a
 * pre-standardised column -- a {@code " Z"} or {@code " RobustZ"} sibling -- that is a real
 * column and appears here as its own labelled option. If it does not, no standardised
 * option appears, because there is nothing to select.
 * <p>
 * <b>On MIRAGE {@code main} there are no such columns</b> ({@code STATISTICS} is
 * {@code ("Median", "Mean", "Sum")}, and {@code expanded_quantification} decides which of
 * those an export carries). A gate therefore has exactly one way to be read, this list has
 * one entry, and {@link #isAChoice} is false so the editor shows no selector at all.
 * Which compartment and which statistic to read stays where it belongs -- the dropdowns,
 * populated from {@link CompartmentCapability}.
 *
 * <h2>Identified by normalisation, not by statistic</h2>
 * A 2D gate's axes can sit on different base statistics (Median on X, Mean on Y), so a
 * mode cannot name one concrete {@link Statistic}. It names the <em>normalisation
 * suffix</em> instead, and {@link #applyTo} composes each axis's own sibling from its own
 * base. One gate-wide answer, whatever the axes read.
 *
 * <h2>Derived, never set</h2>
 * {@link #availableFor} is a pure function of the gate and the capability scanned at
 * ingest. Nothing outside decides which modes exist, matching the rule
 * {@code UmapSession.viewState()} already follows on the UMAP side, and it is
 * table-testable with no JavaFX toolkit.
 *
 * @param kind          which of the two this is
 * @param normalisation the suffix each axis's statistic must carry: {@code ""} for the
 *                      column as measured, otherwise {@code " Z"} / {@code " RobustZ"}
 * @param label         what the button says
 * @param tooltip       the longer explanation behind it
 */
public record ValueMode(Kind kind, String normalisation, String label, String tooltip) {

    /** Where the number the gate compares against came from. */
    public enum Kind {
        /** The column as measured. */
        RAW,
        /** A sibling column the pipeline standardised before export. */
        MIRAGE
    }

    public ValueMode {
        Objects.requireNonNull(kind, "kind");
        normalisation = normalisation == null ? "" : normalisation;
        if (kind == Kind.MIRAGE && normalisation.isEmpty()) {
            throw new IllegalArgumentException("a MIRAGE mode must name a normalisation");
        }
        if (kind == Kind.RAW && !normalisation.isEmpty()) {
            throw new IllegalArgumentException(
                    "RAW is the column as measured; it carries no normalisation");
        }
    }

    private static final ValueMode RAW = new ValueMode(Kind.RAW, "",
            "Raw",
            "Compare the column as measured, exactly as the export supplied it.");

    /** How a normalisation suffix is named in the selector. */
    private static String labelFor(String normalisation) {
        return switch (normalisation) {
            case " Z" -> "Z-score (MIRAGE)";
            case " RobustZ" -> "Robust Z (MIRAGE)";
            default -> normalisation.trim() + " (MIRAGE)";
        };
    }

    /**
     * Every mode this gate can offer: raw first, then any pre-standardised sibling the
     * file actually carries, in {@link Statistic#standardisingNormalisations()} order.
     * <p>
     * Raw is always present -- there is always a column to read as measured -- so the list
     * is never empty and a gate is never unreadable. On a typical export it is the
     * <em>only</em> entry; see {@link #isAChoice}.
     *
     * @param gate       the gate to describe; every axis must carry a sibling for that
     *                   sibling to appear, since one mode moves the whole gate
     * @param capability what the file carries, from the one ingest scan. When {@code null}
     *                   or not rich, only raw is offered: an absent capability means "not
     *                   scanned", which is not evidence a standardised column exists, and
     *                   offering one would pin an axis to a key that is not in the file
     *                   and read NaN for every cell.
     */
    public static List<ValueMode> availableFor(GateNode gate, CompartmentCapability capability) {
        List<ValueMode> modes = new ArrayList<>(3);
        if (gate == null) return modes;
        modes.add(RAW);

        List<GateAxis> axes = GateAxis.axesOf(gate);
        if (!axes.isEmpty() && capability != null && capability.isRich()) {
            for (String norm : Statistic.standardisingNormalisations()) {
                if (allAxesOffer(axes, capability, norm)) {
                    modes.add(new ValueMode(Kind.MIRAGE, norm, labelFor(norm),
                            "Read the pipeline's own " + labelFor(norm).replace(" (MIRAGE)", "")
                            + " column.\nStandardised before export, across every cell of "
                            + "the patient, and already in the file — FlowPath applies "
                            + "nothing on top."));
                }
            }
        }
        return modes;
    }

    /**
     * Whether there is anything to choose between.
     * <p>
     * One mode is not a choice, and a radio group with a single button is a control that
     * cannot do anything: it poses a question with one answer and implies the others were
     * taken away. The editor hides the row entirely instead.
     */
    public static boolean isAChoice(List<ValueMode> modes) {
        return modes != null && modes.size() > 1;
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
     * Put {@code gate} into this mode: point every axis at its own sibling column for this
     * normalisation, and clear the retired standardise-here flag.
     * <p>
     * <b>One writer.</b> Selecting a mode changes what each axis reads, and the flag that
     * used to mean "standardise it again on the way past" must come down with it. A caller
     * that set one without the other would leave the engine standardising a column the
     * editor is drawing as measured -- the display/classification split this repository
     * has shipped before. Doing it here means no caller can spell it half-way.
     * <p>
     * Note this does <em>not</em> convert the threshold. Moving between columns is a
     * change of scale, and only a caller holding the index can re-map it; see
     * {@code GateEditorPane.onModeSelected}.
     */
    public void applyTo(GateNode gate) {
        if (gate == null) return;
        for (GateAxis axis : GateAxis.axesOf(gate)) {
            Statistic statistic = axis.statistic();
            if (statistic == null) continue;
            axis.apply(new GateAxis.Signal(axis.compartment(),
                    statistic.withNormalisation(normalisation)));
        }
        gate.setThresholdIsZScore(false);
    }

    /**
     * The mode {@code gate} is currently in, as one of {@code modes}.
     * <p>
     * Falls back to raw when the gate's combination is not on offer -- a saved gate pinned
     * to a column this file does not carry, or one still carrying the retired
     * standardise-here flag. The caller must write that fallback back with
     * {@link #applyTo} <em>and convert the threshold with it</em>, or the gate keeps
     * comparing a standardised number against a column of raw intensities.
     */
    public static ValueMode selectedIn(List<ValueMode> modes, GateNode gate) {
        if (modes == null || modes.isEmpty()) return null;
        if (gate == null) return modes.get(0);
        // A gate still carrying the retired flag is in no offered mode: the number it
        // compares against is one FlowPath derived, which is what is no longer on the menu.
        if (gate.isThresholdIsZScore()) return modes.get(0);

        String norm = normalisationOf(gate);
        if (norm != null) {
            for (ValueMode mode : modes) {
                if (mode.normalisation().equals(norm)) return mode;
            }
        }
        return modes.get(0);
    }

    /**
     * The one normalisation every axis carries, or {@code null} if they disagree.
     * <p>
     * Axes that disagree have no gate-wide mode -- a real state, reachable by loading a
     * hand-edited file -- and answering {@code null} lets the caller fall back to raw and
     * write that back, rather than picking one axis's answer for both.
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
}
