package qupath.ext.flowpath.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the "Values" selector's contents to what the export actually carries.
 * <p>
 * The rule under test is that <b>every offered mode names a column that is in the file</b>.
 * FlowPath used to also offer a z-score it derived itself, over the cells currently loaded
 * <em>and filtered</em> -- a number that moved when a quality filter or an annotation ROI
 * changed, so a gate defined against it could not be reproduced from the export alone.
 * That option is gone; what remains is raw, plus any pre-standardised sibling the pipeline
 * actually wrote.
 * <p>
 * No JavaFX toolkit: {@link ValueMode#availableFor} is a pure function of the gate and the
 * capability, which is the whole reason it lives in {@code model} rather than the editor.
 */
class ValueModeTest {

    private static GateNode thresholdOn(String marker, Compartment compartment, Statistic statistic) {
        GateNode gate = new GateNode();
        gate.setChannel(marker);
        gate.setCompartment(compartment);
        gate.setStatistic(statistic);
        gate.setThresholdIsZScore(false);
        return gate;
    }

    private static QuadrantGate quadrantOn(String markerX, Statistic statX,
                                           String markerY, Statistic statY) {
        QuadrantGate gate = new QuadrantGate();
        gate.setChannelX(markerX);
        gate.setCompartmentX(Compartment.WHOLE_CELL);
        gate.setStatisticX(statX);
        gate.setChannelY(markerY);
        gate.setCompartmentY(Compartment.WHOLE_CELL);
        gate.setStatisticY(statY);
        gate.setThresholdIsZScore(false);
        return gate;
    }

    private static List<String> labels(List<ValueMode> modes) {
        return modes.stream().map(ValueMode::label).toList();
    }

    // ---- what a real MIRAGE run produces ------------------------------------------

    /**
     * <b>The default MIRAGE run.</b> {@code quantify_compartments} defaults to true and
     * {@code expanded_quantification} to false, so the export carries three compartments
     * and exactly one statistic, {@code Median}. Nothing is pre-standardised, so there is
     * exactly one way to read the gate -- and therefore no selector at all.
     */
    @Test
    void aDefaultMirageRunOffersOnlyRawAndSoIsNotAChoice() {
        var capability = CompartmentCapability.fromKeys(Set.of(
                "CD3: Nucleus: Median", "CD3: Cytoplasm: Median", "CD3: Cell: Median"));
        var gate = thresholdOn("CD3", Compartment.WHOLE_CELL, Statistic.MEDIAN);

        var modes = ValueMode.availableFor(gate, capability);

        assertEquals(List.of("Raw"), labels(modes));
        assertFalse(ValueMode.isAChoice(modes),
                "one mode is not a choice; the editor must hide the row");

        // The compartment and statistic choice lives in the dropdowns, not here.
        assertEquals(3, capability.compartmentsFor("CD3").size());
        assertEquals(Set.of(Statistic.MEDIAN),
                capability.statisticsFor("CD3", Compartment.WHOLE_CELL));
    }

    /**
     * The same run with {@code --expanded_quantification}: Mean and Sum join Median per
     * compartment. Still nothing pre-standardised, so the Values row stays absent -- the
     * extra statistics belong to the Statistic dropdown.
     */
    @Test
    void expandedQuantificationAddsStatisticsButStillNoValueChoice() {
        var capability = CompartmentCapability.fromKeys(Set.of(
                "CD3: Cell: Median", "CD3: Cell: Mean", "CD3: Cell: Sum",
                "CD3: Nucleus: Median", "CD3: Nucleus: Mean", "CD3: Nucleus: Sum"));
        var gate = thresholdOn("CD3", Compartment.WHOLE_CELL, Statistic.MEDIAN);

        assertEquals(List.of("Raw"), labels(ValueMode.availableFor(gate, capability)));
        assertEquals(Set.of(Statistic.MEDIAN, Statistic.MEAN, Statistic.SUM),
                capability.statisticsFor("CD3", Compartment.WHOLE_CELL));
    }

    /** No mode FlowPath would have to compute is offered, under any capability. */
    @Test
    void noModeIsEverSomethingFlowPathWouldCompute() {
        for (var capability : List.of(
                CompartmentCapability.fromKeys(Set.of("CD3: Cell: Median")),
                CompartmentCapability.fromKeys(Set.of("CD3: Cell: Median", "CD3: Cell: Median Z")),
                CompartmentCapability.empty())) {
            var gate = thresholdOn("CD3", Compartment.WHOLE_CELL, Statistic.MEDIAN);
            for (ValueMode mode : ValueMode.availableFor(gate, capability)) {
                assertNotEquals("Z-score (computed here)", mode.label());
                assertTrue(mode.kind() == ValueMode.Kind.RAW
                                || mode.kind() == ValueMode.Kind.MIRAGE,
                        "every mode names a column in the file");
            }
        }
    }

    // ---- when the pipeline does emit standardised columns --------------------------

    /** With standardised columns present, both appear, in suffix order, and it is a choice. */
    @Test
    void standardisedColumnsInTheFileBecomeSelectableModes() {
        var capability = CompartmentCapability.fromKeys(Set.of(
                "CD3: Cell: Median",
                "CD3: Cell: Median Z",
                "CD3: Cell: Median RobustZ"));
        var gate = thresholdOn("CD3", Compartment.WHOLE_CELL, Statistic.MEDIAN);

        var modes = ValueMode.availableFor(gate, capability);
        assertEquals(List.of("Raw", "Z-score (MIRAGE)", "Robust Z (MIRAGE)"), labels(modes));
        assertTrue(ValueMode.isAChoice(modes));
    }

    /**
     * A statistic that is whole-cell-only -- a membrane correction has no nuclear
     * decomposition -- must not offer a nuclear standardised sibling the file cannot hold.
     */
    @Test
    void aCompartmentWithoutTheStandardisedSiblingIsNotOfferedIt() {
        var capability = CompartmentCapability.fromKeys(Set.of(
                "CD3: Cell: REDSEA", "CD3: Cell: REDSEA Z",
                "CD3: Nucleus: REDSEA"));

        assertEquals(List.of("Raw"), labels(ValueMode.availableFor(
                thresholdOn("CD3", Compartment.NUCLEAR, Statistic.of("REDSEA")), capability)));
        assertEquals(List.of("Raw", "Z-score (MIRAGE)"), labels(ValueMode.availableFor(
                thresholdOn("CD3", Compartment.WHOLE_CELL, Statistic.of("REDSEA")), capability)));
    }

    /** A gate already on a standardised column still offers Raw, and no "Median Z Z". */
    @Test
    void anAlreadyStandardisedGateStillOffersTheColumnAsMeasured() {
        var capability = CompartmentCapability.fromKeys(Set.of(
                "CD3: Cell: Median", "CD3: Cell: Median Z"));
        var gate = thresholdOn("CD3", Compartment.WHOLE_CELL, Statistic.of("Median Z"));

        var modes = ValueMode.availableFor(gate, capability);
        assertEquals(List.of("Raw", "Z-score (MIRAGE)"), labels(modes));
        assertTrue(labels(modes).stream().noneMatch(l -> l.contains("Z Z")));
        assertEquals(ValueMode.Kind.MIRAGE, ValueMode.selectedIn(modes, gate).kind());
    }

    /** A 2D gate offers a mode only when BOTH axes have that sibling. */
    @Test
    void aTwoAxisGateNeedsBothSiblingsPresent() {
        var capability = CompartmentCapability.fromKeys(Set.of(
                "CD3: Cell: Median", "CD3: Cell: Median Z",
                "CD8: Cell: Median"));   // CD8 has no Median Z
        var gate = quadrantOn("CD3", Statistic.MEDIAN, "CD8", Statistic.MEDIAN);

        assertEquals(List.of("Raw"), labels(ValueMode.availableFor(gate, capability)));
    }

    /** A capability that was never scanned is not evidence a standardised column exists. */
    @Test
    void anAbsentCapabilityOffersOnlyRaw() {
        var gate = thresholdOn("CD3", Compartment.WHOLE_CELL, Statistic.MEDIAN);

        assertEquals(List.of("Raw"), labels(ValueMode.availableFor(gate, null)));
        assertEquals(List.of("Raw"),
                labels(ValueMode.availableFor(gate, CompartmentCapability.empty())));
    }

    // ---- applying, and the migration off the retired flag ---------------------------

    /** A mode applies to every axis, composing each one's sibling from its own base. */
    @Test
    void applyingAModeMovesBothAxesFromTheirOwnBases() {
        var capability = CompartmentCapability.fromKeys(Set.of(
                "CD3: Cell: Median", "CD3: Cell: Median Z",
                "CD8: Cell: Mean", "CD8: Cell: Mean Z"));
        var gate = quadrantOn("CD3", Statistic.MEDIAN, "CD8", Statistic.MEAN);

        var mirageZ = ValueMode.availableFor(gate, capability).stream()
                .filter(m -> m.kind() == ValueMode.Kind.MIRAGE).findFirst().orElseThrow();
        mirageZ.applyTo(gate);

        assertEquals(Statistic.of("Median Z"), gate.getStatisticX());
        assertEquals(Statistic.of("Mean Z"), gate.getStatisticY());
        assertFalse(gate.isThresholdIsZScore());
    }

    /** Returning to Raw strips the suffix from every axis. */
    @Test
    void applyingRawReturnsEveryAxisToItsBaseColumn() {
        var gate = quadrantOn("CD3", Statistic.of("Median RobustZ"), "CD8", Statistic.of("Mean Z"));

        ValueMode.availableFor(gate, null).get(0).applyTo(gate);

        assertEquals(Statistic.MEDIAN, gate.getStatisticX());
        assertEquals(Statistic.MEAN, gate.getStatisticY());
        assertFalse(gate.isThresholdIsZScore());
    }

    /**
     * <b>A gate saved with the retired flag is in no offered mode.</b> Its threshold is
     * expressed in standard deviations FlowPath derived, and nothing on the menu means
     * that any more -- so the selection falls back to raw, which is the caller's cue to
     * convert the threshold before clearing the flag.
     */
    @Test
    void aGateCarryingTheRetiredFlagFallsBackToRaw() {
        var capability = CompartmentCapability.fromKeys(Set.of(
                "CD3: Cell: Median", "CD3: Cell: Median Z"));
        var gate = thresholdOn("CD3", Compartment.WHOLE_CELL, Statistic.of("Median Z"));
        gate.setThresholdIsZScore(true);

        var modes = ValueMode.availableFor(gate, capability);
        assertEquals(ValueMode.Kind.RAW, ValueMode.selectedIn(modes, gate).kind(),
                "the flag means the number came from FlowPath, which is no longer a mode");
    }

    /** Axes that disagree about normalisation have no gate-wide mode; raw is the answer. */
    @Test
    void axesThatDisagreeFallBackToRaw() {
        var capability = CompartmentCapability.fromKeys(Set.of(
                "CD3: Cell: Median", "CD3: Cell: Median Z",
                "CD8: Cell: Median", "CD8: Cell: Median Z"));
        var gate = quadrantOn("CD3", Statistic.of("Median Z"), "CD8", Statistic.MEDIAN);

        assertEquals(ValueMode.Kind.RAW,
                ValueMode.selectedIn(ValueMode.availableFor(gate, capability), gate).kind());
    }

    /** Composition, not enumeration: a statistic FlowPath has never seen still works. */
    @Test
    void anUnknownBaseStatisticStillComposesItsSiblings() {
        var capability = CompartmentCapability.fromKeys(Set.of(
                "CD3: Cell: Trimmed", "CD3: Cell: Trimmed Z"));
        var gate = thresholdOn("CD3", Compartment.WHOLE_CELL, Statistic.of("Trimmed"));

        assertEquals(List.of("Raw", "Z-score (MIRAGE)"),
                labels(ValueMode.availableFor(gate, capability)));
    }

    /** The record refuses combinations that would misdescribe where a number came from. */
    @Test
    void theRecordEnforcesItsOwnInvariants() {
        assertThrows(IllegalArgumentException.class,
                () -> new ValueMode(ValueMode.Kind.MIRAGE, "", "x", "y"),
                "a MIRAGE mode must name the normalisation it reads");
        assertThrows(IllegalArgumentException.class,
                () -> new ValueMode(ValueMode.Kind.RAW, " Z", "x", "y"),
                "RAW is the column as measured; it carries no normalisation");
    }
}
