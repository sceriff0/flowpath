package qupath.ext.flowpath.ui;

import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Slider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.CompartmentCapability;
import qupath.ext.flowpath.model.GateAxis;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.QuadrantGate;
import qupath.ext.flowpath.model.RectangleGate;
import qupath.ext.flowpath.model.Region2DGate;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.testing.Cells;
import qupath.ext.flowpath.testing.FxTestSupport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Switching the editor from gate A to gate B leaves no control wired to A.
 * <p>
 * The pane used to keep every gate type's widgets in {@code current*} fields that each builder
 * reset by hand, and some handlers wrote to the gate they were built for without asking whether
 * it was still the one on screen — a quadrant slider kept writing to its quadrant after another
 * gate had been opened. Each type editor is now disposed on a switch. Pinned for every ordered
 * pair of editor types, including a type to itself, and across a switch back, so both
 * directions of one pair are exercised against a live and a stale set of controls.
 */
class GateEditorSwitchTest {

    private static final int N = 30;

    @BeforeAll
    static void initJfx() throws InterruptedException {
        FxTestSupport.startToolkit();
    }

    enum Type {
        THRESHOLD(() -> {
            GateNode g = new GateNode("CD3", 10);
            g.setStatistic(Statistic.MEAN);
            return g;
        }),
        QUADRANT(() -> {
            QuadrantGate g = new QuadrantGate("CD3", "CD4", 10, 20);
            g.setStatisticX(Statistic.MEAN);
            g.setStatisticY(Statistic.MEAN);
            return g;
        }),
        REGION(() -> {
            RectangleGate g = new RectangleGate("CD3", "CD4", 5, 15, 10, 30);
            g.setStatisticX(Statistic.MEAN);
            g.setStatisticY(Statistic.MEAN);
            return g;
        });

        final Supplier<GateNode> create;

        Type(Supplier<GateNode> create) {
            this.create = create;
        }
    }

    static Stream<Arguments> pairs() {
        List<Arguments> out = new ArrayList<>();
        for (Type a : Type.values()) for (Type b : Type.values()) out.add(Arguments.of(a, b));
        return out.stream();
    }

    /** The controls a user edits, captured while they are on screen. */
    private record Controls(ComboBox<String> channel, Slider slider) {}

    private static <T extends Node> void collect(Parent root, Class<T> type, List<T> out) {
        for (Node child : root.getChildrenUnmodifiable()) {
            if (type.isInstance(child)) out.add(type.cast(child));
            if (child instanceof Parent p) collect(p, type, out);
        }
    }

    @SuppressWarnings("unchecked")
    private static Controls controlsOn(GateEditorPane pane) {
        return FxTestSupport.onFx(() -> {
            List<ComboBox> combos = new ArrayList<>();
            collect(pane, ComboBox.class, combos);
            ComboBox<String> channel = null;
            for (ComboBox c : combos) {
                if (!c.getItems().isEmpty() && c.getItems().get(0) instanceof String) {
                    channel = (ComboBox<String>) c;
                    break;
                }
            }
            assertNotNull(channel, "every gate type lays out a channel picker");
            List<Slider> sliders = new ArrayList<>();
            collect(pane, Slider.class, sliders);
            return new Controls(channel, sliders.isEmpty() ? null : sliders.get(0));
        });
    }

    /** Everything a control on any editor could write, as one comparable value. */
    private static String fingerprint(GateNode gate) {
        StringBuilder sb = new StringBuilder();
        for (GateAxis axis : GateAxis.axesOf(gate)) {
            sb.append(axis.channel()).append('/').append(axis.signal()).append(';');
        }
        sb.append(gate.getThreshold()).append(';').append(gate.getClipPercentileLow());
        if (gate instanceof QuadrantGate q) sb.append(';').append(q.getThresholdX()).append(',').append(q.getThresholdY());
        if (gate instanceof RectangleGate r) {
            sb.append(';').append(r.getMinX()).append(',').append(r.getMaxX())
                    .append(',').append(r.getMinY()).append(',').append(r.getMaxY());
        }
        if (gate instanceof Region2DGate) sb.append(";region");
        return sb.toString();
    }

    /** Edit {@code controls} the way a user would: pick another channel, drag the slider. */
    private static void edit(Controls controls) {
        FxTestSupport.onFxRun(() -> {
            if (controls.slider() != null) {
                Slider s = controls.slider();
                s.setValue(s.getMin() + 0.37 * (s.getMax() - s.getMin()));
            }
            controls.channel().setValue("CD8");
            controls.channel().fireEvent(new ActionEvent());
        });
        // Let a queued rebuild run.
        FxTestSupport.onFxRun(() -> { });
        FxTestSupport.onFxRun(() -> { });
    }

    @ParameterizedTest(name = "{0} then {1}")
    @MethodSource("pairs")
    void afterASwitchOnlyTheShownGateIsWritten(Type typeA, Type typeB) {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        CellIndex index = Cells.of(N)
                .marker("CD3", i -> i)
                .marker("CD4", i -> 2.0 * i)
                .marker("CD8", i -> 100.0 - i)
                .area(50.0)
                .build();
        CompartmentCapability capability =
                CompartmentCapability.scan(Arrays.asList(index.getObjects()));
        GateNode a = typeA.create.get();
        GateNode b = typeB.create.get();

        GateEditorPane pane = FxTestSupport.onFx(GateEditorPane::new);
        List<GateNode> reported = new ArrayList<>();
        FxTestSupport.onFxRun(() -> {
            pane.setChannelNames(List.of("CD3", "CD4", "CD8"));
            pane.setCompartmentCapability(capability);
            pane.setCellIndex(index);
            pane.setMarkerStats(MarkerStats.compute(index, Cells.allTrue(N)));
            pane.setOnNodeChanged(reported::add);
            pane.setGateNode(a);
        });
        Controls onA = controlsOn(pane);

        // Switch A -> B, then edit what is on screen, then the controls A left behind.
        FxTestSupport.onFxRun(() -> pane.setGateNode(b));
        Controls onB = controlsOn(pane);
        String aBefore = fingerprint(a);
        String bBefore = fingerprint(b);
        edit(onB);
        assertNotEquals(bBefore, fingerprint(b), "fixture check: the edit wrote to B");
        assertEquals(aBefore, fingerprint(a), "editing B's controls must not write to A");
        assertTrue(reported.stream().allMatch(g -> g == b), "every edit reported is B's");

        String bAfterEdit = fingerprint(b);
        reported.clear();
        edit(onA);
        assertEquals(aBefore, fingerprint(a), "A's disposed controls write nothing to A");
        assertEquals(bAfterEdit, fingerprint(b), "nor to B");
        assertEquals(List.of(), reported, "and report nothing");

        // Second switch, back to A: the same holds the other way round.
        FxTestSupport.onFxRun(() -> pane.setGateNode(a));
        Controls onAAgain = controlsOn(pane);
        reported.clear();
        edit(onAAgain);
        assertNotEquals(aBefore, fingerprint(a), "fixture check: the edit wrote to A");
        assertEquals(bAfterEdit, fingerprint(b), "editing A's controls must not write to B");
        assertTrue(reported.stream().allMatch(g -> g == a), "every edit reported is A's");

        String aAfterEdit = fingerprint(a);
        reported.clear();
        edit(onB);
        assertEquals(bAfterEdit, fingerprint(b), "B's disposed controls write nothing to B");
        assertEquals(aAfterEdit, fingerprint(a), "nor to A");
        assertEquals(List.of(), reported, "and report nothing");
    }
}
