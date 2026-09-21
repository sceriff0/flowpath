package qupath.ext.flowpath.ui;

import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.CompartmentCapability;
import qupath.ext.flowpath.model.EllipseGate;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.RectangleGate;
import qupath.ext.flowpath.testing.Cells;
import qupath.ext.flowpath.testing.FxTestSupport;
import qupath.ext.flowpath.ui.widgets.ScatterPlotCanvas;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code GateEditorPane}'s branch-name commit baseline ({@code committed[0]}) across a gate
 * replacement.
 * <p>
 * {@code Region2DGateEditor#onShapeDrawn} converts a gate to a different region shape by
 * calling {@code EditorContext#replaceGate}, which swaps {@code GateEditorPane#currentNode}
 * onto a fresh {@code GateNode} (with fresh {@code Branch} objects, names copied over from the
 * old ones by {@code copySharedSettings}). {@code EditorContext#showLater} — used for the rest
 * of the editor — defers its full rebuild to the next pulse so a drag in progress on the
 * scatter plot is not interrupted, which would leave the branch-name row's {@code TextField}
 * bound to the branch of a gate that is no longer shown, and its commit closure's baseline
 * captured against that gate, until that later pulse ran. {@code replaceGate} instead rebuilds
 * the branch-name rows and the action buttons (which hold no gesture of their own, unlike the
 * scatter canvas) immediately, so there is no such window at all.
 * <p>
 * Driven directly through the real {@code ScatterPlotCanvas} callback {@code Region2DGateEditor}
 * wires (reflectively read, since the callback consumer has no public getter — a live drag
 * gesture cannot be simulated, per {@code GateReorderFxTest}'s own note on
 * {@code startDragAndDrop}). Everything up to the rename commit runs inside ONE
 * {@code Platform.runLater} turn, before the deferred full rebuild ({@code showLater}) gets a
 * chance to run — proving the fix is the immediate rebuild in {@code replaceGate} itself, not
 * something the later full rebuild happens to paper over.
 */
class GateEditorBranchRenameAcrossReplacementTest {

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

    private static ScatterPlotCanvas scatterIn(GateEditorPane pane) {
        List<ScatterPlotCanvas> found = new ArrayList<>();
        collect(pane, ScatterPlotCanvas.class, found);
        assertFalse(found.isEmpty(), "the editor must have laid out a scatter plot");
        return found.get(0);
    }

    @SuppressWarnings("unchecked")
    private static Consumer<double[]> onEllipseDrawn(ScatterPlotCanvas scatter) {
        try {
            Field f = ScatterPlotCanvas.class.getDeclaredField("onEllipseDrawn");
            f.setAccessible(true);
            return (Consumer<double[]>) f.get(scatter);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void aReplacementRebuildsTheBranchNameRowAtOnce() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        CellIndex index = Cells.of(10).marker("CD3", i -> i + 1.0).marker("CD4", i -> 10.0 - i)
                .area(50.0).build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(10));
        CompartmentCapability capability = CompartmentCapability.scan(Arrays.asList(index.getObjects()));
        RectangleGate gate = new RectangleGate("CD3", "CD4", -1, 1, -1, 1);

        AtomicInteger reports = new AtomicInteger();
        GateEditorPane pane = FxTestSupport.onFx(GateEditorPane::new);
        FxTestSupport.onFxRun(() -> {
            pane.setOnNodeChanged(node -> reports.incrementAndGet());
            pane.setChannelNames(List.of("CD3", "CD4"));
            pane.setCompartmentCapability(capability);
            pane.setCellIndex(index);
            pane.setMarkerStats(stats);
            pane.setGateNode(gate);
        });
        String initialName = gate.getBranches().get(0).getName();
        assertEquals("CD3/CD4 (in)", initialName, "fixture check");
        TextField fieldBeforeReplacement = FxTestSupport.onFx(() -> {
            List<TextField> fields = new ArrayList<>();
            collect(pane, TextField.class, fields);
            return fields.stream().filter(f -> initialName.equals(f.getText())).findFirst().orElseThrow();
        });

        ScatterPlotCanvas scatter = scatterIn(pane);

        // Everything below runs in ONE Platform.runLater turn: the replacement's own
        // Platform.runLater(showLater) rebuild is only QUEUED here, not run, until some later
        // turn drains it -- which nothing below triggers. Whatever fixes this has to be
        // synchronous with replaceGate itself.
        FxTestSupport.onFxRun(() -> {
            onEllipseDrawn(scatter).accept(new double[]{0, 0, 1, 1});
            assertTrue(pane.getGateNode() instanceof EllipseGate, "the gate was replaced");

            List<TextField> fields = new ArrayList<>();
            collect(pane, TextField.class, fields);
            TextField name = fields.stream().filter(f -> initialName.equals(f.getText())).findFirst()
                    .orElseThrow(() -> new AssertionError("the row still shows the pre-replacement name"));
            assertNotSame(fieldBeforeReplacement, name,
                    "the row must have been rebuilt for the replacement, not the same TextField reused");

            name.setText("Live cells");
            name.fireEvent(new ActionEvent());
        });

        EllipseGate replacement = (EllipseGate) pane.getGateNode();
        assertEquals("Live cells", replacement.getBranches().get(0).getName());
        assertEquals(2, reports.get(),
                "one report for the replacement itself, one for the rename committed right after it");

        // Committing the same (now current) name again is not a further change.
        FxTestSupport.onFxRun(() -> {
            List<TextField> fields = new ArrayList<>();
            collect(pane, TextField.class, fields);
            TextField name = fields.stream().filter(f -> "Live cells".equals(f.getText())).findFirst()
                    .orElseThrow();
            name.fireEvent(new ActionEvent());
        });
        assertEquals(2, reports.get(), "an unchanged re-commit reports nothing");

        // Let the deferred rebuild run so it does not leak a queued Platform.runLater into
        // whichever test's pulse follows this one on the shared FX application thread.
        FxTestSupport.onFxRun(() -> { });
        FxTestSupport.onFxRun(() -> { });
    }
}
