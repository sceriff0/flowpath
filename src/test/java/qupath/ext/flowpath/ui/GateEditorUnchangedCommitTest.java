package qupath.ext.flowpath.ui;

import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.engine.GatingEngine;
import qupath.ext.flowpath.model.Branch;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.CompartmentCapability;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.QuadrantGate;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.testing.Cells;
import qupath.ext.flowpath.testing.FxTestSupport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Committing a text field that holds what the gate already has is not an edit.
 * <p>
 * The threshold field and the branch-name fields commit on Enter and on focus loss, and both
 * reported {@code gateChanged} unconditionally. Undo, reselect the gate, click into the
 * threshold field and out again: the report recorded an undo step identical to the tree, and
 * recording cleared the redo stack. The threshold field also rewrote the gate with its own
 * four-decimal rendering, so a dragged threshold was silently rounded.
 * <p>
 * Driven through each field's Enter handler: focus loss runs the same commit, and whether a
 * test JVM's window can take OS focus is not something a test can rely on. Wired to a real
 * {@link GatingSession} the way {@code FlowPathPane} wires it, with two roots on one channel,
 * across the passes before and after the redo.
 */
class GateEditorUnchangedCommitTest {

    private static final int N = 20;
    /** Not representable in the field's four decimals, so a rounding rewrite would show. */
    private static final double DRAGGED = 5.123456;

    @BeforeAll
    static void initJfx() throws InterruptedException {
        FxTestSupport.startToolkit();
    }

    private static <T extends Node> void collect(Parent root, Class<T> type, List<T> out) {
        for (Node child : root.getChildrenUnmodifiable()) {
            if (type.isInstance(child)) out.add(type.cast(child));
            if (child instanceof Parent p) collect(p, type, out);
        }
    }

    private static TextField fieldShowing(GateEditorPane pane, String text) {
        return FxTestSupport.onFx(() -> {
            List<TextField> fields = new ArrayList<>();
            collect(pane, TextField.class, fields);
            return fields.stream().filter(f -> text.equals(f.getText())).findFirst()
                    .orElseThrow(() -> new AssertionError("no field shows " + text));
        });
    }

    private static void commit(TextField field) {
        FxTestSupport.onFxRun(() -> field.fireEvent(new ActionEvent()));
    }

    private static GateTree twoRootsOnCd3() {
        GateTree tree = new GateTree();
        GateNode a = new GateNode("CD3", DRAGGED);
        a.setStatistic(Statistic.MEAN);
        GateNode b = new GateNode("CD3", 12.5);
        b.setStatistic(Statistic.MEAN);
        tree.addRoot(a);
        tree.addRoot(b);
        return tree;
    }

    private record Fixture(GatingSession session, GateEditorPane pane, AtomicInteger reports,
                           AtomicReference<GatingEngine.AssignmentResult> lastPass, AtomicLong clock) {}

    /** A session whose last step (a threshold edit to 8) has just been undone, and the pane showing root 0. */
    private static Fixture undoneEditShown() {
        CellIndex index = Cells.of(N).marker("CD3", i -> i + 1.0).area(50.0).build();
        AtomicReference<GatingEngine.AssignmentResult> lastPass = new AtomicReference<>();
        AtomicLong clock = new AtomicLong(10_000);
        GatingSession session = new GatingSession(clock::get, input -> lastPass.set(input.index() == null ? null
                : GatingEngine.assignAll(input.tree(), input.index(), input.stats(), input.roiMask())));
        session.replaceTree(twoRootsOnCd3());
        session.adoptIndex(index);
        session.resync(List::of);

        clock.addAndGet(5_000);
        session.tree().getRoots().get(0).setThreshold(8.0);
        session.recordAppliedEdit(GatingSession.EditSource.GATE);
        session.resync(List::of);
        assertTrue(session.undo());
        session.resync(List::of);
        assertEquals(DRAGGED, session.tree().getRoots().get(0).getThreshold());

        AtomicInteger reports = new AtomicInteger();
        CompartmentCapability capability = CompartmentCapability.scan(Arrays.asList(index.getObjects()));
        GateEditorPane pane = FxTestSupport.onFx(GateEditorPane::new);
        FxTestSupport.onFxRun(() -> {
            // As FlowPathPane.onGateNodeChanged / onGateNodeNormalised.
            pane.setOnNodeChanged(node -> {
                reports.incrementAndGet();
                session.recordAppliedEdit(GatingSession.EditSource.GATE);
                session.settle();
            });
            pane.setOnNodeNormalised(node -> session.settle());
            pane.setChannelNames(List.of("CD3"));
            pane.setCompartmentCapability(capability);
            pane.setCellIndex(index);
            pane.setMarkerStats(session.stats());
            pane.setGateNode(session.tree().getRoots().get(0));
        });
        clock.addAndGet(5_000);
        return new Fixture(session, pane, reports, lastPass, clock);
    }

    private static int[] counts(GatingEngine.AssignmentResult result, GateNode root) {
        Branch pos = root.getBranches().get(0);
        Branch neg = root.getBranches().get(1);
        assertEquals(pos.getCount(), result.getTally().clean(pos));
        return new int[]{pos.getCount(), neg.getCount()};
    }

    @Test
    void committingUnchangedFieldsLeavesRedoAvailable() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        Fixture f = undoneEditShown();
        GateNode shown = f.session().tree().getRoots().get(0);

        commit(fieldShowing(f.pane(), "5.1235"));
        for (Branch branch : shown.getBranches()) commit(fieldShowing(f.pane(), branch.getName()));

        assertEquals(0, f.reports().get(), "nothing changed, so nothing is reported");
        assertEquals(DRAGGED, shown.getThreshold(), "the gate keeps its unrounded threshold");

        assertTrue(f.session().redo(), "the undone edit can still be redone");
        f.session().resync(List::of);
        assertEquals(8.0, f.session().tree().getRoots().get(0).getThreshold());
        assertArrayEquals(new int[]{13, 7}, counts(f.lastPass().get(), f.session().tree().getRoots().get(0)),
                "CD3 8..20 at or above 8");
        assertArrayEquals(new int[]{8, 12}, counts(f.lastPass().get(), f.session().tree().getRoots().get(1)),
                "CD3 13..20 at or above 12.5");
    }

    /** The quadrant fields render three decimals; an unchanged commit must not round a dragged threshold. */
    @Test
    void committingUnchangedQuadrantFieldsReportsNothing() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        CellIndex index = Cells.of(N).marker("CD3", i -> i + 1.0).marker("CD4", i -> 20.0 - i).area(50.0).build();
        QuadrantGate gate = new QuadrantGate("CD3", "CD4", DRAGGED, 7.654321);
        gate.setStatisticX(Statistic.MEAN);
        gate.setStatisticY(Statistic.MEAN);
        AtomicInteger reports = new AtomicInteger();
        GateEditorPane pane = FxTestSupport.onFx(GateEditorPane::new);
        FxTestSupport.onFxRun(() -> {
            pane.setOnNodeChanged(node -> reports.incrementAndGet());
            pane.setChannelNames(List.of("CD3", "CD4"));
            pane.setCompartmentCapability(CompartmentCapability.scan(Arrays.asList(index.getObjects())));
            pane.setCellIndex(index);
            pane.setMarkerStats(MarkerStats.compute(index, null));
            pane.setGateNode(gate);
        });

        commit(fieldShowing(pane, "5.123"));
        commit(fieldShowing(pane, "7.654"));

        assertEquals(0, reports.get());
        assertEquals(DRAGGED, gate.getThresholdX());
        assertEquals(7.654321, gate.getThresholdY());
    }

    @Test
    void aNonFiniteThresholdIsRejectedAndARealChangeIsOneStep() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        Fixture f = undoneEditShown();
        GateNode shown = f.session().tree().getRoots().get(0);
        TextField threshold = fieldShowing(f.pane(), "5.1235");

        for (String bad : List.of("Infinity", "-Infinity", "NaN")) {
            FxTestSupport.onFxRun(() -> threshold.setText(bad));
            commit(threshold);
            assertEquals(DRAGGED, shown.getThreshold(), bad + " is not a threshold");
            assertEquals("5.1235", FxTestSupport.onFx(threshold::getText), bad + ": the field is reset");
        }
        assertEquals(0, f.reports().get());
        assertTrue(f.session().redo(), "rejected input records nothing");
        assertTrue(f.session().undo());
        f.session().resync(List::of);

        FxTestSupport.onFxRun(() -> f.pane().setGateNode(f.session().tree().getRoots().get(0)));
        GateNode reshown = f.session().tree().getRoots().get(0);
        TextField field = fieldShowing(f.pane(), "5.1235");
        FxTestSupport.onFxRun(() -> field.setText("10.5"));
        commit(field);
        assertEquals(1, f.reports().get(), "a real change is reported once");
        assertEquals(10.5, reshown.getThreshold());
        commit(field);
        assertEquals(1, f.reports().get(), "committing it again is not a second change");

        f.session().resync(List::of);
        assertArrayEquals(new int[]{10, 10}, counts(f.lastPass().get(), reshown));
        assertTrue(f.session().undo());
        f.session().resync(List::of);
        assertEquals(DRAGGED, f.session().tree().getRoots().get(0).getThreshold(), "one undo returns the value");
        assertArrayEquals(new int[]{15, 5}, counts(f.lastPass().get(), f.session().tree().getRoots().get(0)));
        assertArrayEquals(new int[]{8, 12}, counts(f.lastPass().get(), f.session().tree().getRoots().get(1)));
    }

    @Test
    void aRenamedBranchIsReportedOnceAndUndoneInOneStep() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        Fixture f = undoneEditShown();
        GateNode shown = f.session().tree().getRoots().get(0);
        String original = shown.getBranches().get(0).getName();
        TextField name = fieldShowing(f.pane(), original);

        FxTestSupport.onFxRun(() -> name.setText("T cells"));
        commit(name);
        commit(name);
        assertEquals(1, f.reports().get(), "the rename is one report; committing it again is none");
        assertEquals("T cells", shown.getBranches().get(0).getName());

        assertTrue(f.session().undo());
        f.session().resync(List::of);
        assertEquals(original, f.session().tree().getRoots().get(0).getBranches().get(0).getName());
        assertEquals(DRAGGED, f.session().tree().getRoots().get(0).getThreshold(), "only the rename is undone");
    }
}
