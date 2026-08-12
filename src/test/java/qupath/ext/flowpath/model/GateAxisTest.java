package qupath.ext.flowpath.model;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.testing.Cells;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-axis decision, driven directly and without a JavaFX toolkit.
 * <p>
 * {@link GateAxis} is what the three gate-editor builders used to spell out by hand,
 * once each. The bugs that produced were all the same shape — an axis read in a
 * compartment or statistic it was not set to, or not re-resolved when its channel
 * changed — and each was fixed in one builder at a time (commits {@code 6b66868},
 * {@code d9c1de9}, {@code 99b6e6d}, {@code 21904ad}). The cases below are those four,
 * asked of the decision itself rather than of a layout.
 */
class GateAxisTest {

    private static final int N = 8;

    /**
     * A default (non-expanded) MIRAGE export: CD3 quantified per compartment with
     * {@code Median} only, CD8 present as a bare marker column with no compartment keys
     * at all — the legacy shape. Two channels that must be read differently, in one file.
     */
    private static Cells mixedExport() {
        return Cells.of(N)
                .mirageMedianMarker("CD3", i -> 10.0 + i)
                .marker("CD8", i -> 100.0 + i)
                .area(100.0);
    }

    private static CompartmentCapability capabilityOf(CellIndex index) {
        return CompartmentCapability.scan(Arrays.asList(index.getObjects()), 100);
    }

    /** An export carrying every compartment and every statistic for CD3. */
    private static CompartmentCapability expandedCapability() {
        return CompartmentCapability.fromKeys(List.of(
                "CD3: Cell: Mean", "CD3: Cell: Median", "CD3: Cell: Sum",
                "CD3: Nucleus: Mean", "CD3: Nucleus: Median"));
    }

    // ---- slots, not list positions ------------------------------------------

    @Test
    void eachAxisReadsAndWritesItsOwnSlot() {
        QuadrantGate quad = new QuadrantGate("CD3", "CD8");

        GateAxis.of(quad, 1).apply(new GateAxis.Signal(Compartment.NUCLEAR, Statistic.SUM));

        assertEquals(Compartment.NUCLEAR, quad.getCompartmentY());
        assertEquals(Statistic.SUM, quad.getStatisticY());
        assertEquals(Compartment.WHOLE_CELL, quad.getCompartmentX(), "X must be untouched");
        assertEquals(Statistic.MEDIAN, quad.getStatisticX());
        assertEquals(new GateAxis.Signal(Compartment.NUCLEAR, Statistic.SUM),
                GateAxis.of(quad, 1).signal(), "what was written is what is read back");
    }

    /**
     * The bug in {@code 6b66868}: a region gate's axes were read as whole-cell mean
     * regardless of what the gate was set to, so the scatter plot showed one column
     * while the engine gated on another.
     */
    @Test
    void regionAxesReportTheirOwnCompartmentAndStatistic() {
        RectangleGate rect = new RectangleGate("CD3", "CD8", 0, 1, 0, 1);
        rect.setCompartmentX(Compartment.NUCLEAR);
        rect.setStatisticX(Statistic.MEDIAN);
        rect.setCompartmentY(Compartment.CYTOPLASMIC);
        rect.setStatisticY(Statistic.SUM);

        assertEquals(new GateAxis.Signal(Compartment.NUCLEAR, Statistic.MEDIAN),
                GateAxis.of(rect, 0).signal());
        assertEquals(new GateAxis.Signal(Compartment.CYTOPLASMIC, Statistic.SUM),
                GateAxis.of(rect, 1).signal());
    }

    /**
     * With the X channel unset, {@code getChannels()} omits it and list index 0 becomes
     * the Y axis — so a reader indexing the list and a writer calling {@code setXxxX}
     * addressed two different axes. Slot addressing cannot express that.
     */
    @Test
    void anUnsetXChannelDoesNotShiftTheYAxisOntoSlotZero() {
        QuadrantGate quad = new QuadrantGate();
        quad.setChannelY("CD8");

        GateAxis yAxis = GateAxis.of(quad, 1);
        assertEquals("CD8", yAxis.channel());
        yAxis.apply(new GateAxis.Signal(Compartment.NUCLEAR, Statistic.MEDIAN));

        assertEquals(Compartment.NUCLEAR, quad.getCompartmentY());
        assertEquals(Compartment.WHOLE_CELL, quad.getCompartmentX(),
                "the Y selection must not land on the X slot");
        // The gate's own compacted lists still describe the axis it does read.
        assertEquals(List.of("CD8"), quad.getChannels());
        assertEquals(List.of(Compartment.NUCLEAR), quad.getCompartments());
        assertNull(GateAxis.of(quad, 0).channel());
    }

    @Test
    void aThresholdGateHasNoSecondAxis() {
        GateNode gate = new GateNode("CD3");
        assertEquals(1, GateAxis.axisCount(gate));
        assertEquals(2, GateAxis.axisCount(new QuadrantGate("CD3", "CD8")));
        assertEquals(2, GateAxis.axisCount(new PolygonGate("CD3", "CD8")));
        assertThrows(IllegalArgumentException.class, () -> GateAxis.of(gate, 1),
                "a threshold gate's Y axis must not be answerable at all");
    }

    // ---- which combinations the export offers -------------------------------

    @Test
    void aLegacyChannelOffersNothingAndIsReadAsTheBareWholeCellMean() {
        CellIndex index = mixedExport().build();
        GateNode gate = new GateNode("CD8");
        gate.setCompartment(Compartment.NUCLEAR);
        gate.setStatistic(Statistic.MEDIAN);

        GateAxis.Choices choices = GateAxis.of(gate, 0).choicesFrom(capabilityOf(index));

        assertFalse(choices.offersCompartment(), "no compartment keys, so no selector");
        assertFalse(choices.offersStatistic());
        assertEquals(new GateAxis.Signal(Compartment.WHOLE_CELL, Statistic.MEAN), choices.signal(),
                "the bare marker column is the whole-cell mean");
    }

    /**
     * A default MIRAGE run emits {@code Median} only. Offering — or silently pinning —
     * {@code Mean} resolves the axis to a key that is not in the file, and every cell
     * reads NaN.
     */
    @Test
    void aDefaultMirageChannelOffersMedianOnly() {
        CellIndex index = mixedExport().build();
        GateNode gate = new GateNode("CD3");
        gate.setStatistic(Statistic.MEAN);            // what a caller might hardcode

        GateAxis.Choices choices = GateAxis.of(gate, 0).choicesFrom(capabilityOf(index));

        assertEquals(List.of(Statistic.MEDIAN), choices.statistics());
        assertFalse(choices.offersStatistic(), "one statistic is not a choice");
        assertTrue(choices.offersCompartment());
        assertEquals(List.of(Compartment.NUCLEAR, Compartment.CYTOPLASMIC, Compartment.WHOLE_CELL),
                choices.compartments(), "compartments are offered in enum order");
        assertEquals(Statistic.MEDIAN, choices.signal().statistic(),
                "the axis must be pinned to the statistic the export has, not the one it was set to");
    }

    @Test
    void anExpandedChannelOffersEveryStatisticItCarries() {
        GateNode gate = new GateNode("CD3");

        GateAxis.Choices choices = GateAxis.of(gate, 0).choicesFrom(expandedCapability());

        assertEquals(List.of(Statistic.MEAN, Statistic.MEDIAN, Statistic.SUM), choices.statistics());
        assertTrue(choices.offersStatistic());
        assertEquals(List.of(Compartment.NUCLEAR, Compartment.WHOLE_CELL), choices.compartments());
        assertEquals(new GateAxis.Signal(Compartment.WHOLE_CELL, Statistic.MEDIAN), choices.signal(),
                "a selection the export carries is kept");
    }

    @Test
    void anAxisWithNoChannelIsLeftAlone() {
        GateNode gate = new GateNode();
        GateAxis.Signal before = GateAxis.of(gate, 0).signal();

        GateAxis.Choices choices = GateAxis.of(gate, 0).choicesFrom(expandedCapability());
        GateAxis.pinAll(gate, expandedCapability());

        assertEquals(before, choices.signal(), "there is nothing to resolve against");
        assertEquals(before, GateAxis.of(gate, 0).signal());
    }

    // ---- pinning ------------------------------------------------------------

    @Test
    void pinToKeepsASelectionTheExportCarries() {
        GateNode gate = new GateNode("CD3");
        gate.setCompartment(Compartment.NUCLEAR);
        gate.setStatistic(Statistic.MEAN);

        GateAxis.of(gate, 0).pinTo(expandedCapability());

        assertEquals(Compartment.NUCLEAR, gate.getCompartment());
        assertEquals(Statistic.MEAN, gate.getStatistic());
    }

    @Test
    void pinAllResolvesEachAxisAgainstItsOwnChannel() {
        CellIndex index = mixedExport().build();
        QuadrantGate quad = new QuadrantGate("CD3", "CD8");
        quad.setCompartmentX(Compartment.NUCLEAR);
        quad.setCompartmentY(Compartment.NUCLEAR);

        GateAxis.pinAll(quad, capabilityOf(index));

        assertEquals(Compartment.NUCLEAR, quad.getCompartmentX(), "CD3 has a nuclear median");
        assertEquals(Statistic.MEDIAN, quad.getStatisticX());
        assertEquals(Compartment.WHOLE_CELL, quad.getCompartmentY(), "CD8 is quantified whole-cell only");
        assertEquals(Statistic.MEAN, quad.getStatisticY());
    }

    // ---- retargeting a channel ----------------------------------------------

    /**
     * The guarantee, stated as data rather than as a setting: after a channel change the
     * axis resolves to a column that is <em>in the file</em>. Keeping the old channel's
     * compartment would resolve {@code "CD8: Nucleus: Median"}, which this export does not
     * contain, and every cell would read NaN — an empty plot and a gate classifying
     * everything into one branch.
     */
    @Test
    void retargetingRepinsTheSignalToAColumnTheNewChannelHas() {
        Cells cells = mixedExport();
        CellIndex index = cells.build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(index.size()));
        CompartmentCapability capability = capabilityOf(index);

        GateNode gate = new GateNode("CD3");
        GateAxis axis = GateAxis.of(gate, 0);
        axis.pinTo(capability);
        axis.apply(new GateAxis.Signal(Compartment.NUCLEAR, Statistic.MEDIAN));
        assertEquals("CD3: Nucleus: Median", axis.columnIn(index, stats).key());

        assertTrue(axis.retarget("CD8", capability), "the channel changed");

        MeasuredColumn column = axis.columnIn(index, stats);
        assertEquals("CD8", column.key(),
                "a legacy channel must resolve to its bare column, not to a compartment key "
                        + "inherited from the channel it replaced");
        for (double v : column.values()) {
            assertFalse(Double.isNaN(v), "every cell must read a real value on the new axis");
        }
        assertTrue(column.hasSpread(), "a column of NaN or of one repeated value has no spread");
    }

    @Test
    void retargetingToTheSameChannelChangesNothing() {
        GateNode gate = new GateNode("CD3");
        gate.setCompartment(Compartment.NUCLEAR);
        gate.setStatistic(Statistic.SUM);

        assertFalse(GateAxis.of(gate, 0).retarget("CD3", expandedCapability()));

        assertEquals(Compartment.NUCLEAR, gate.getCompartment(), "an unchanged channel re-resolves nothing");
        assertEquals(Statistic.SUM, gate.getStatistic());
    }

    @Test
    void retargetingTheYAxisLeavesTheXAxisAlone() {
        CellIndex index = mixedExport().build();
        QuadrantGate quad = new QuadrantGate("CD3", "CD3");
        GateAxis.pinAll(quad, capabilityOf(index));
        quad.setCompartmentX(Compartment.NUCLEAR);
        quad.setCompartmentY(Compartment.NUCLEAR);

        GateAxis.of(quad, 1).retarget("CD8", capabilityOf(index));

        assertEquals("CD3", quad.getChannelX());
        assertEquals(Compartment.NUCLEAR, quad.getCompartmentX(), "the X axis was not asked about");
        assertEquals(Statistic.MEDIAN, quad.getStatisticX());
        assertEquals("CD8", quad.getChannelY());
        assertEquals(Compartment.WHOLE_CELL, quad.getCompartmentY());
        assertEquals(Statistic.MEAN, quad.getStatisticY());
    }

    // ---- branch labels follow the channel, unless the user owns them --------

    @Test
    void defaultThresholdLabelsFollowTheChannel() {
        GateNode gate = new GateNode("CD3");
        assertEquals(List.of("CD3+", "CD3-"),
                gate.getBranches().stream().map(Branch::getName).toList());

        GateAxis.of(gate, 0).retarget("CD8", null);

        assertEquals(List.of("CD8+", "CD8-"),
                gate.getBranches().stream().map(Branch::getName).toList());
    }

    @Test
    void defaultQuadrantLabelsFollowTheAxisThatChanged() {
        QuadrantGate quad = new QuadrantGate("CD3", "CD4");

        GateAxis.of(quad, 0).retarget("CD8", null);

        assertEquals(List.of("CD8+/CD4+", "CD8-/CD4+", "CD8+/CD4-", "CD8-/CD4-"),
                quad.getBranches().stream().map(Branch::getName).toList());
    }

    @Test
    void defaultRegionLabelsFollowTheChannel() {
        PolygonGate poly = new PolygonGate("CD3", "CD4");
        assertEquals(List.of("CD3/CD4 (in)", "CD3/CD4 (out)"),
                poly.getBranches().stream().map(Branch::getName).toList());

        GateAxis.of(poly, 1).retarget("CD8", null);

        assertEquals(List.of("CD3/CD8 (in)", "CD3/CD8 (out)"),
                poly.getBranches().stream().map(Branch::getName).toList(),
                "a region gate labelled with its plane must not keep naming a channel it dropped");
    }

    @Test
    void aRenamedBranchIsNeverRewritten() {
        QuadrantGate quad = new QuadrantGate("CD3", "CD4");
        quad.getBranches().get(0).setName("Double positive");

        GateAxis.of(quad, 0).retarget("CD8", null);

        assertEquals("Double positive", quad.getBranches().get(0).getName(),
                "the user's name for a population is not a caption to be regenerated");
        assertEquals("CD8-/CD4+", quad.getBranches().get(1).getName(),
                "the labels still at their default do follow the channel");
    }

    @Test
    void deserializedRegionLabelsAreLeftAlone() {
        RectangleGate rect = new RectangleGate();       // no-arg: "Inside" / "Outside"
        rect.setChannelX("CD3");
        rect.setChannelY("CD4");

        GateAxis.of(rect, 0).retarget("CD8", null);

        assertEquals(List.of("Inside", "Outside"),
                rect.getBranches().stream().map(Branch::getName).toList());
    }

    // ---- carrying axes across a gate-type change ----------------------------

    @Test
    void copySignalsCopiesOnlyTheAxesTheSourceReads() {
        GateNode threshold = new GateNode("CD3");
        threshold.setCompartment(Compartment.NUCLEAR);
        threshold.setStatistic(Statistic.MEDIAN);
        QuadrantGate quad = new QuadrantGate("CD3", "CD8");

        GateAxis.copySignals(threshold, quad);

        assertEquals(Compartment.NUCLEAR, quad.getCompartmentX());
        assertEquals(Statistic.MEDIAN, quad.getStatisticX());
        assertEquals(Compartment.WHOLE_CELL, quad.getCompartmentY(),
                "there was no source Y axis, so the target keeps its own default");
        assertEquals(Statistic.MEDIAN, quad.getStatisticY(),
                "and must not be stamped with the out-of-range whole-cell Mean fallback");
    }

    @Test
    void copySignalsCarriesBothAxesBetweenTwoDimensionalGates() {
        QuadrantGate quad = new QuadrantGate("CD3", "CD8");
        quad.setCompartmentX(Compartment.NUCLEAR);
        quad.setStatisticX(Statistic.MEDIAN);
        quad.setCompartmentY(Compartment.CYTOPLASMIC);
        quad.setStatisticY(Statistic.SUM);
        PolygonGate poly = new PolygonGate("CD3", "CD8");

        GateAxis.copySignals(quad, poly);

        assertEquals(new GateAxis.Signal(Compartment.NUCLEAR, Statistic.MEDIAN),
                GateAxis.of(poly, 0).signal());
        assertEquals(new GateAxis.Signal(Compartment.CYTOPLASMIC, Statistic.SUM),
                GateAxis.of(poly, 1).signal());
    }

    // ---- the axis names the same column the engine gates on -----------------

    @Test
    void anAxisResolvesTheColumnTheEngineWouldGateOn() {
        Cells cells = mixedExport();
        CellIndex index = cells.build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(index.size()));

        QuadrantGate quad = new QuadrantGate("CD3", "CD8");
        GateAxis.pinAll(quad, capabilityOf(index));
        GateAxis.of(quad, 0).apply(new GateAxis.Signal(Compartment.NUCLEAR, Statistic.MEDIAN));

        assertEquals(index.column(quad, 0, stats).key(), GateAxis.of(quad, 0).columnIn(index, stats).key(),
                "the axis must resolve exactly what CellIndex.column(gate, axis) resolves");
        assertEquals(index.column(quad, 1, stats).key(), GateAxis.of(quad, 1).columnIn(index, stats).key());
    }
}
