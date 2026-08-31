package qupath.ext.flowpath.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the "Values" selector's contents to what the export actually carries.
 * <p>
 * The behaviour under test is the fix for a split intent: standardised values used to be
 * reachable two unrelated ways -- pick MIRAGE's {@code Median Z} column from the Statistic
 * dropdown, or leave the column at {@code Median} and have FlowPath standardise it with
 * the Mode radio -- and choosing the first silently disabled the second, with nothing to
 * say they were about the same thing. {@link ValueMode} is the single ordered answer.
 * <p>
 * No JavaFX toolkit: {@link ValueMode#availableFor} is a pure function of the gate, the
 * capability and (optionally) the data, which is the whole reason it lives in
 * {@code model} rather than in the editor.
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

    /**
     * MIRAGE's default export is Median only. There is no standardised sibling in the
     * file, so offering one would pin the axis to a key that is not there and read NaN for
     * every cell -- the exact failure CompartmentCapability exists to prevent.
     */
    @Test
    void defaultMirageExportOffersRawAndFlowPathsOwnZScoreOnly() {
        var capability = CompartmentCapability.fromKeys(Set.of(
                "CD3: Cell: Median", "CD3: Nucleus: Median"));
        var gate = thresholdOn("CD3", Compartment.WHOLE_CELL, Statistic.MEDIAN);

        var modes = ValueMode.availableFor(gate, capability, null, null);

        assertEquals(List.of("Raw", "Z-score (computed here)"), labels(modes));
        assertTrue(modes.stream().noneMatch(m -> m.kind() == ValueMode.Kind.MIRAGE));
    }

    /**
     * <b>What a real MIRAGE run actually produces.</b> On {@code main},
     * {@code quantify_compartments} defaults to true and {@code expanded_quantification}
     * to false, so the export carries three compartments and exactly one statistic,
     * {@code Median}. Nothing is pre-standardised, so the selector is Raw plus FlowPath's
     * own z-score — and the compartment/statistic choice belongs to the dropdowns, not
     * here.
     */
    @Test
    void aDefaultMirageRunOffersRawAndTheComputedZScoreOnly() {
        var capability = CompartmentCapability.fromKeys(Set.of(
                "CD3: Nucleus: Median", "CD3: Cytoplasm: Median", "CD3: Cell: Median"));
        var gate = thresholdOn("CD3", Compartment.WHOLE_CELL, Statistic.MEDIAN);

        assertEquals(List.of("Raw", "Z-score (computed here)"),
                labels(ValueMode.availableFor(gate, capability, null, null)));
        // All three compartments are on offer, with Median the only statistic in any.
        assertEquals(3, capability.compartmentsFor("CD3").size());
        assertEquals(Set.of(Statistic.MEDIAN),
                capability.statisticsFor("CD3", Compartment.WHOLE_CELL));
    }

    /**
     * The same run with {@code --expanded_quantification}: Mean and Sum join Median per
     * compartment. Still no standardised column, so the Values row is unchanged — the
     * extra statistics belong to the Statistic dropdown.
     */
    @Test
    void expandedQuantificationAddsStatisticsButNotValueModes() {
        var capability = CompartmentCapability.fromKeys(Set.of(
                "CD3: Cell: Median", "CD3: Cell: Mean", "CD3: Cell: Sum",
                "CD3: Nucleus: Median", "CD3: Nucleus: Mean", "CD3: Nucleus: Sum"));
        var gate = thresholdOn("CD3", Compartment.WHOLE_CELL, Statistic.MEDIAN);

        assertEquals(List.of("Raw", "Z-score (computed here)"),
                labels(ValueMode.availableFor(gate, capability, null, null)));
        assertEquals(Set.of(Statistic.MEDIAN, Statistic.MEAN, Statistic.SUM),
                capability.statisticsFor("CD3", Compartment.WHOLE_CELL));
    }

    /** With the standardised columns present, both of MIRAGE's appear, in suffix order. */
    @Test
    void standardisedColumnsInTheFileBecomeSelectableModes() {
        var capability = CompartmentCapability.fromKeys(Set.of(
                "CD3: Cell: Median",
                "CD3: Cell: Median Z",
                "CD3: Cell: Median RobustZ"));
        var gate = thresholdOn("CD3", Compartment.WHOLE_CELL, Statistic.MEDIAN);

        assertEquals(List.of("Raw", "Z-score (MIRAGE)", "Robust Z (MIRAGE)",
                        "Z-score (computed here)"),
                labels(ValueMode.availableFor(gate, capability, null, null)));
    }

    /**
     * REDSEA is whole-cell only -- a membrane correction has no nuclear decomposition --
     * so a nuclear gate must not be offered a standardised REDSEA sibling the file cannot
     * contain.
     */
    @Test
    void aCompartmentWithoutTheStandardisedSiblingIsNotOfferedIt() {
        var capability = CompartmentCapability.fromKeys(Set.of(
                "CD3: Cell: REDSEA", "CD3: Cell: REDSEA Z",
                "CD3: Nucleus: REDSEA"));
        var nuclear = thresholdOn("CD3", Compartment.NUCLEAR, Statistic.of("REDSEA"));
        var wholeCell = thresholdOn("CD3", Compartment.WHOLE_CELL, Statistic.of("REDSEA"));

        assertTrue(ValueMode.availableFor(nuclear, capability, null, null).stream()
                        .noneMatch(m -> m.kind() == ValueMode.Kind.MIRAGE),
                "Nucleus x REDSEA Z is not in the file");
        assertTrue(ValueMode.availableFor(wholeCell, capability, null, null).stream()
                        .anyMatch(m -> m.kind() == ValueMode.Kind.MIRAGE),
                "Cell x REDSEA Z is");
    }

    /**
     * A gate already on a standardised column still offers Raw and does not compose a
     * "Median Z Z". FlowPath's own z-score is still <em>listed</em> -- so the user can see
     * it exists -- but declined, with the reason attached: standardising a z-score again
     * would rescale the axis by whatever is currently filtered.
     */
    @Test
    void anAlreadyStandardisedGateDeclinesASecondStandardisationAndSaysWhy() {
        var capability = CompartmentCapability.fromKeys(Set.of(
                "CD3: Cell: Median", "CD3: Cell: Median Z"));
        var gate = thresholdOn("CD3", Compartment.WHOLE_CELL, Statistic.of("Median Z"));

        var modes = ValueMode.availableFor(gate, capability, null, null);

        assertEquals(List.of("Raw", "Z-score (MIRAGE)", "Z-score (computed here)"),
                labels(modes));
        assertTrue(labels(modes).stream().noneMatch(l -> l.contains("Z Z")));

        var computed = modes.stream()
                .filter(m -> m.kind() == ValueMode.Kind.COMPUTED).findFirst().orElseThrow();
        assertFalse(computed.available());
        assertTrue(computed.unavailableReason().contains("already standardised"),
                "the reason must be legible to a user: " + computed.unavailableReason());
    }

    /** A mode applies to every axis, composing each one's sibling from its own base. */
    @Test
    void applyingAModeMovesBothAxesFromTheirOwnBases() {
        var gate = quadrantOn("CD3", Statistic.MEDIAN, "CD8", Statistic.MEAN);
        var mirageZ = ValueMode.availableFor(
                thresholdOn("CD3", Compartment.WHOLE_CELL, Statistic.MEDIAN),
                CompartmentCapability.fromKeys(Set.of(
                        "CD3: Cell: Median", "CD3: Cell: Median Z")),
                null, null).stream()
                .filter(m -> m.kind() == ValueMode.Kind.MIRAGE).findFirst().orElseThrow();

        mirageZ.applyTo(gate);

        assertEquals(Statistic.of("Median Z"), gate.getStatisticX());
        assertEquals(Statistic.of("Mean Z"), gate.getStatisticY());
        assertFalse(gate.isThresholdIsZScore(),
                "MIRAGE's column is already standardised; FlowPath must not do it again");
    }

    /** Returning to Raw strips the suffix from every axis and clears the flag. */
    @Test
    void applyingRawReturnsEveryAxisToItsBaseColumn() {
        var gate = quadrantOn("CD3", Statistic.of("Median RobustZ"), "CD8", Statistic.of("Mean Z"));
        gate.setThresholdIsZScore(true);

        ValueMode.availableFor(gate, null, null, null).get(0).applyTo(gate);

        assertEquals(Statistic.MEDIAN, gate.getStatisticX());
        assertEquals(Statistic.MEAN, gate.getStatisticY());
        assertFalse(gate.isThresholdIsZScore());
    }

    /** A 2D gate offers a MIRAGE mode only when BOTH axes have that sibling. */
    @Test
    void aTwoAxisGateNeedsBothSiblingsPresent() {
        var capability = CompartmentCapability.fromKeys(Set.of(
                "CD3: Cell: Median", "CD3: Cell: Median Z",
                "CD8: Cell: Median"));   // CD8 has no Median Z
        var gate = quadrantOn("CD3", Statistic.MEDIAN, "CD8", Statistic.MEDIAN);

        assertEquals(List.of("Raw", "Z-score (computed here)"),
                labels(ValueMode.availableFor(gate, capability, null, null)));
    }

    /** Only FlowPath's own standardisation may set the computed flag. */
    @Test
    void onlyTheComputedModeSetsTheComputedFlag() {
        var capability = CompartmentCapability.fromKeys(Set.of(
                "CD3: Cell: Median", "CD3: Cell: Median Z", "CD3: Cell: Median RobustZ"));
        var gate = thresholdOn("CD3", Compartment.WHOLE_CELL, Statistic.MEDIAN);

        for (ValueMode mode : ValueMode.availableFor(gate, capability, null, null)) {
            assertEquals(mode.kind() == ValueMode.Kind.COMPUTED, mode.computed(),
                    mode.label() + " sets computed only when FlowPath is the one computing");
            assertEquals(mode.kind() != ValueMode.Kind.COMPUTED, mode.readsExportedValues());
        }
        // And the invariant is enforced by construction, not only by the factory.
        assertThrows(IllegalArgumentException.class,
                () -> new ValueMode(ValueMode.Kind.MIRAGE, " Z", true, "x", "y", null));
        assertThrows(IllegalArgumentException.class,
                () -> new ValueMode(ValueMode.Kind.MIRAGE, "", false, "x", "y", null));
    }

    /** A capability that was never scanned is not evidence a standardised column exists. */
    @Test
    void anAbsentCapabilityOffersNoMirageModes() {
        var gate = thresholdOn("CD3", Compartment.WHOLE_CELL, Statistic.MEDIAN);

        assertEquals(List.of("Raw", "Z-score (computed here)"),
                labels(ValueMode.availableFor(gate, null, null, null)));
        assertEquals(List.of("Raw", "Z-score (computed here)"),
                labels(ValueMode.availableFor(gate, CompartmentCapability.empty(), null, null)));
    }

    /** The current combination selects itself; an unavailable one falls back to raw. */
    @Test
    void selectionResolvesToTheGateCombinationOrFallsBackToRaw() {
        var capability = CompartmentCapability.fromKeys(Set.of(
                "CD3: Cell: Median", "CD3: Cell: Median Z"));
        var gate = thresholdOn("CD3", Compartment.WHOLE_CELL, Statistic.of("Median Z"));
        var modes = ValueMode.availableFor(gate, capability, null, null);

        assertEquals(ValueMode.Kind.MIRAGE, ValueMode.selectedIn(modes, gate).kind());

        // Same column, but the gate also claims FlowPath standardised it -- a combination
        // no mode offers, so raw is the answer the caller must write back.
        gate.setThresholdIsZScore(true);
        assertEquals(ValueMode.Kind.RAW, ValueMode.selectedIn(modes, gate).kind());
    }

    /** Axes that disagree about normalisation have no gate-wide mode; raw is the answer. */
    @Test
    void axesThatDisagreeFallBackToRaw() {
        var capability = CompartmentCapability.fromKeys(Set.of(
                "CD3: Cell: Median", "CD3: Cell: Median Z",
                "CD8: Cell: Median", "CD8: Cell: Median Z"));
        var gate = quadrantOn("CD3", Statistic.of("Median Z"), "CD8", Statistic.MEDIAN);
        var modes = ValueMode.availableFor(gate, capability, null, null);

        assertEquals(ValueMode.Kind.RAW, ValueMode.selectedIn(modes, gate).kind());
    }

    /** Composition, not enumeration: a statistic FlowPath has never seen still works. */
    @Test
    void anUnknownBaseStatisticStillComposesItsSiblings() {
        var capability = CompartmentCapability.fromKeys(Set.of(
                "CD3: Cell: Trimmed", "CD3: Cell: Trimmed Z"));
        var gate = thresholdOn("CD3", Compartment.WHOLE_CELL, Statistic.of("Trimmed"));

        assertEquals(List.of("Raw", "Z-score (MIRAGE)", "Z-score (computed here)"),
                labels(ValueMode.availableFor(gate, capability, null, null)));
    }
}
