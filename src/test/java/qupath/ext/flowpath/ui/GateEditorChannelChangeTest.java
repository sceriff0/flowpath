package qupath.ext.flowpath.ui;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ComboBox;
import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.Branch;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.Compartment;
import qupath.ext.flowpath.model.CompartmentCapability;
import qupath.ext.flowpath.model.GateAxis;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.MeasuredColumn;
import qupath.ext.flowpath.model.QuadrantGate;
import qupath.ext.flowpath.model.RectangleGate;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.testing.Cells;
import qupath.ext.flowpath.testing.FxTestSupport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What must happen when a gate's <em>channel</em> is changed in the editor — asked of
 * all three builders, through the real controls.
 *
 * <p>{@code GateEditorSignalTest} already drives the compartment and statistic
 * selectors. The channel pickers were the uncovered half: one axis for
 * {@code buildThresholdEditor}, two for {@code buildQuadrantEditor}, two for
 * {@code build2DEditor}, each hand-wired with its own handler body. Every one of those
 * handlers has to do the same three things, and the ways they differed are the ways
 * they broke:
 * <ol>
 *   <li>point the axis at the new channel — and only that axis;</li>
 *   <li>re-pin the axis' compartment and statistic to something the <em>new</em>
 *       channel is actually quantified with, so the axis resolves to a column that is
 *       in the file rather than to one inherited from the channel it replaced;</li>
 *   <li>move the branch labels the user has not claimed onto the new channel, and
 *       leave the ones they have.</li>
 * </ol>
 *
 * <p>The pins below are stated as data — the resolved {@link MeasuredColumn} the engine
 * would gate on, read back through {@link GateAxis} — rather than as a settings triple,
 * because "resolves to a key that is not in the file" reads as a perfectly ordinary
 * (Nuclear, Median) selection right up until every cell reads NaN.
 */
class GateEditorChannelChangeTest {

    private static final int N = 12;

    /**
     * A default (non-expanded) MIRAGE export with a legacy channel in it: CD3 and CD4
     * carry per-compartment {@code Median} keys, CD8 only the bare column. Retargeting an
     * axis from CD3 to CD8 therefore <em>must</em> change the signal — CD8 has no nuclear
     * median — which is what makes the re-pin observable.
     */
    private static Cells cells() {
        return Cells.of(N)
                .mirageMedianMarker("CD3", i -> 10.0 + i)
                .mirageMedianMarker("CD4", i -> 20.0 + i)
                .marker("CD8", i -> 100.0 + i)
                .area(100.0);
    }

    private record Fixture(GateEditorPane pane, CellIndex index, MarkerStats stats) {}

    private static Fixture editorFor(GateNode gate) {
        CellIndex index = cells().build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(index.size()));
        CompartmentCapability capability =
                CompartmentCapability.scan(Arrays.asList(index.getObjects()), 100);
        GateEditorPane pane = FxTestSupport.onFx(GateEditorPane::new);
        FxTestSupport.onFxRun(() -> {
            pane.setChannelNames(List.of("CD3", "CD4", "CD8"));
            pane.setCompartmentCapability(capability);
            pane.setCellIndex(index);
            pane.setMarkerStats(stats);
            pane.setGateNode(gate);
        });
        return new Fixture(pane, index, stats);
    }

    /** Let any {@code Platform.runLater} rebuild queued by the editor run. */
    private static void flushFx() {
        FxTestSupport.onFxRun(() -> { });
        FxTestSupport.onFxRun(() -> { });
    }

    private static <T extends Node> void collect(Parent root, Class<T> type, Predicate<T> keep, List<T> out) {
        for (Node child : root.getChildrenUnmodifiable()) {
            if (type.isInstance(child)) {
                T t = type.cast(child);
                if (keep.test(t)) out.add(t);
            }
            if (child instanceof Parent p) collect(p, type, keep, out);
        }
    }

    /** The {@code nth} channel picker in layout order — X first, then Y. */
    @SuppressWarnings("unchecked")
    private static ComboBox<String> channelCombo(GateEditorPane pane, int nth) {
        List<ComboBox> combos = new ArrayList<>();
        collect(pane, ComboBox.class,
                c -> !c.getItems().isEmpty() && c.getItems().get(0) instanceof String, combos);
        assertTrue(combos.size() > nth, "expected a channel picker at index " + nth
                + ", found " + combos.size());
        return (ComboBox<String>) combos.get(nth);
    }

    /** Pick a channel the way a user would, firing the combo's action handler. */
    private static void selectChannel(ComboBox<String> combo, String channel) {
        FxTestSupport.onFxRun(() -> {
            combo.setValue(channel);
            combo.fireEvent(new javafx.event.ActionEvent());
        });
        flushFx();
    }

    private static List<String> branchNames(GateNode gate) {
        return gate.getBranches().stream().map(Branch::getName).toList();
    }

    /**
     * The guarantee: this axis resolves to {@code expectedKey}, and every cell reads a
     * real number off it. A signal inherited from the previous channel resolves to a key
     * the export does not contain, and {@code CellIndex} answers that with NaN for every
     * cell — an empty plot, and a gate that sweeps the whole population into one branch.
     */
    private static void assertAxisReadsRealData(Fixture f, GateNode gate, int slot, String expectedKey) {
        MeasuredColumn column = GateAxis.of(gate, slot).columnIn(f.index(), f.stats());
        assertNotNull(column, "axis " + slot + " must resolve to a column");
        assertEquals(expectedKey, column.key(),
                "axis " + slot + " must be read in a compartment/statistic the new channel has");
        for (double v : column.values()) {
            assertFalse(Double.isNaN(v), "every cell must read a real value on axis " + slot);
        }
        assertTrue(column.hasSpread(), "a column of NaN or one repeated value has no spread");
    }

    // ---- one axis: buildThresholdEditor --------------------------------------

    @Test
    void thresholdChannelChangeRepinsTheAxisToTheNewChannelsColumn() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        GateNode gate = new GateNode("CD3");
        gate.setCompartment(Compartment.NUCLEAR);
        Fixture f = editorFor(gate);
        assertAxisReadsRealData(f, gate, 0, "CD3: Nucleus: Median");

        selectChannel(channelCombo(f.pane(), 0), "CD8");

        assertEquals("CD8", gate.getChannel());
        assertAxisReadsRealData(f, gate, 0, "CD8");
    }

    @Test
    void thresholdChannelChangeMovesTheDefaultBranchLabels() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        GateNode gate = new GateNode("CD3");
        Fixture f = editorFor(gate);
        assertEquals(List.of("CD3+", "CD3-"), branchNames(gate));

        selectChannel(channelCombo(f.pane(), 0), "CD4");

        assertEquals(List.of("CD4+", "CD4-"), branchNames(gate));
    }

    @Test
    void thresholdChannelChangeLeavesARenamedBranchAlone() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        GateNode gate = new GateNode("CD3");
        gate.getBranches().get(0).setName("T cells");
        Fixture f = editorFor(gate);

        selectChannel(channelCombo(f.pane(), 0), "CD4");

        assertEquals("T cells", branchNames(gate).get(0),
                "a label the user typed is their name for the population, not a caption");
        assertEquals("CD4-", branchNames(gate).get(1));
    }

    // ---- two axes: buildQuadrantEditor ---------------------------------------

    @Test
    void quadrantXChannelChangeRepinsOnlyTheXAxis() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        QuadrantGate gate = new QuadrantGate("CD3", "CD4");
        gate.setCompartmentX(Compartment.NUCLEAR);
        gate.setCompartmentY(Compartment.NUCLEAR);
        Fixture f = editorFor(gate);
        assertAxisReadsRealData(f, gate, 0, "CD3: Nucleus: Median");
        assertAxisReadsRealData(f, gate, 1, "CD4: Nucleus: Median");

        selectChannel(channelCombo(f.pane(), 0), "CD8");

        assertEquals("CD8", gate.getChannelX());
        assertEquals("CD4", gate.getChannelY(), "the Y axis was not asked about");
        assertAxisReadsRealData(f, gate, 0, "CD8");
        assertAxisReadsRealData(f, gate, 1, "CD4: Nucleus: Median");
    }

    @Test
    void quadrantYChannelChangeRepinsOnlyTheYAxis() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        QuadrantGate gate = new QuadrantGate("CD3", "CD4");
        gate.setCompartmentX(Compartment.NUCLEAR);
        gate.setCompartmentY(Compartment.NUCLEAR);
        Fixture f = editorFor(gate);

        selectChannel(channelCombo(f.pane(), 1), "CD8");

        assertEquals("CD3", gate.getChannelX(), "the X axis was not asked about");
        assertEquals("CD8", gate.getChannelY());
        assertAxisReadsRealData(f, gate, 0, "CD3: Nucleus: Median");
        assertAxisReadsRealData(f, gate, 1, "CD8");
    }

    @Test
    void quadrantChannelChangeMovesTheDefaultBranchLabelsOfThatAxisOnly() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        QuadrantGate gate = new QuadrantGate("CD3", "CD4");
        Fixture f = editorFor(gate);
        assertEquals(List.of("CD3+/CD4+", "CD3-/CD4+", "CD3+/CD4-", "CD3-/CD4-"), branchNames(gate));

        selectChannel(channelCombo(f.pane(), 0), "CD8");

        assertEquals(List.of("CD8+/CD4+", "CD8-/CD4+", "CD8+/CD4-", "CD8-/CD4-"), branchNames(gate));
    }

    @Test
    void quadrantChannelChangeLeavesARenamedBranchAlone() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        QuadrantGate gate = new QuadrantGate("CD3", "CD4");
        gate.getBranches().get(0).setName("double positive");
        Fixture f = editorFor(gate);

        selectChannel(channelCombo(f.pane(), 0), "CD8");

        assertEquals("double positive", branchNames(gate).get(0));
        assertEquals("CD8-/CD4+", branchNames(gate).get(1));
    }

    // ---- two axes: build2DEditor ---------------------------------------------

    @Test
    void regionXChannelChangeRepinsOnlyTheXAxis() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        RectangleGate gate = new RectangleGate("CD3", "CD4", -1, 1, -1, 1);
        gate.setCompartmentX(Compartment.NUCLEAR);
        gate.setCompartmentY(Compartment.NUCLEAR);
        Fixture f = editorFor(gate);
        assertAxisReadsRealData(f, gate, 0, "CD3: Nucleus: Median");
        assertAxisReadsRealData(f, gate, 1, "CD4: Nucleus: Median");

        selectChannel(channelCombo(f.pane(), 0), "CD8");

        assertEquals("CD8", gate.getChannelX());
        assertEquals("CD4", gate.getChannelY(), "the Y axis was not asked about");
        assertAxisReadsRealData(f, gate, 0, "CD8");
        assertAxisReadsRealData(f, gate, 1, "CD4: Nucleus: Median");
    }

    @Test
    void regionYChannelChangeRepinsOnlyTheYAxis() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        RectangleGate gate = new RectangleGate("CD3", "CD4", -1, 1, -1, 1);
        gate.setCompartmentX(Compartment.NUCLEAR);
        gate.setCompartmentY(Compartment.NUCLEAR);
        Fixture f = editorFor(gate);

        selectChannel(channelCombo(f.pane(), 1), "CD8");

        assertEquals("CD3", gate.getChannelX(), "the X axis was not asked about");
        assertEquals("CD8", gate.getChannelY());
        assertAxisReadsRealData(f, gate, 0, "CD3: Nucleus: Median");
        assertAxisReadsRealData(f, gate, 1, "CD8");
    }

    /**
     * The region editor never renamed its branches at all, so a gate drawn on CD3/CD4 and
     * then repointed kept a label naming a channel it no longer read. The rename is the
     * same decision for all three gate types, so it is now made in the same place.
     */
    @Test
    void regionChannelChangeMovesTheDefaultBranchLabels() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        RectangleGate gate = new RectangleGate("CD3", "CD4", -1, 1, -1, 1);
        Fixture f = editorFor(gate);
        assertEquals(List.of("CD3/CD4 (in)", "CD3/CD4 (out)"), branchNames(gate));

        selectChannel(channelCombo(f.pane(), 0), "CD8");

        assertEquals(List.of("CD8/CD4 (in)", "CD8/CD4 (out)"), branchNames(gate),
                "a region gate's labels name the plane it is drawn on, so they follow it");
    }

    /**
     * The quadrant editor rewrote its labels by substring: {@code name.replace("CD3+",
     * "CD8+")}. That is not a test of whether the label is still the default — it is a
     * blind edit of whatever the user had typed, and it mangled any name that happened to
     * contain the old channel.
     */
    @Test
    void quadrantChannelChangeDoesNotRewriteInsideAUserTypedLabel() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        QuadrantGate gate = new QuadrantGate("CD3", "CD4");
        gate.getBranches().get(0).setName("CD3+ blasts");
        Fixture f = editorFor(gate);

        selectChannel(channelCombo(f.pane(), 0), "CD8");

        assertEquals("CD3+ blasts", branchNames(gate).get(0),
                "the user named this population; it is not a caption to be find-and-replaced");
    }

    /**
     * A statistic the new channel does not carry must not survive the change. CD3 is
     * quantified with {@code Median} only here, so an axis switched onto it from a
     * whole-cell {@code Mean} legacy channel has to land on {@code "CD3: Cell: Median"}.
     */
    @Test
    void regionChannelChangeOntoACompartmentChannelPicksAStatisticItCarries() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        RectangleGate gate = new RectangleGate("CD8", "CD4", -1, 1, -1, 1);
        Fixture f = editorFor(gate);
        assertEquals(Statistic.MEAN, gate.getStatisticX(), "CD8 is a bare legacy column");

        selectChannel(channelCombo(f.pane(), 0), "CD3");

        assertEquals("CD3", gate.getChannelX());
        assertAxisReadsRealData(f, gate, 0, "CD3: Cell: Median");
    }
}
