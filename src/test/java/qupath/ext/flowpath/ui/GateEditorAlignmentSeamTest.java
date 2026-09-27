package qupath.ext.flowpath.ui;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.CompartmentCapability;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.QuadrantGate;
import qupath.ext.flowpath.model.GateValues;
import qupath.ext.flowpath.model.RectangleGate;
import qupath.ext.flowpath.model.SlideSetting;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.model.cohort.Alignment;
import qupath.ext.flowpath.model.cohort.Landmarks;
import qupath.ext.flowpath.testing.Cells;
import qupath.ext.flowpath.testing.FxTestSupport;
import qupath.ext.flowpath.ui.editor.EditorAlignment;
import qupath.ext.flowpath.ui.widgets.ScatterPlotCanvas;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Every editor type draws through the seam: the aligned window is the inverse of the raw one.
 * An editor shows one gate by nature, so the two-root rule (preflight C5) does not apply here;
 * {@code DisplayClassificationAgreementTest} pins the same-channel sibling case.
 */
class GateEditorAlignmentSeamTest {

    static final Alignment BRIGHTER = Alignment.between(new Landmarks(10.0, 0.0, 2.0), new Landmarks(10.0, 0.3, 2.9));

    enum Type {
        THRESHOLD(() -> { GateNode g = new GateNode("CD3", 10); g.setStatistic(Statistic.MEAN); return g; }),
        QUADRANT(() -> { QuadrantGate g = new QuadrantGate("CD3", "CD4", 10, 20);
            g.setStatisticX(Statistic.MEAN); g.setStatisticY(Statistic.MEAN); return g; }),
        RECTANGLE(() -> { RectangleGate g = new RectangleGate("CD3", "CD4", 5, 15, 10, 30);
            g.setStatisticX(Statistic.MEAN); g.setStatisticY(Statistic.MEAN); return g; });

        final Supplier<GateNode> create;
        Type(Supplier<GateNode> create) { this.create = create; }
    }

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

    /** The window the editor put on screen: slider ranges, else the scatter's axis window. */
    private static double[] window(Type type, EditorAlignment alignment) {
        CellIndex index = Cells.of(100).marker("CD3", i -> i).marker("CD4", i -> 2.0 * i).area(50.0).build();
        GateEditorPane pane = FxTestSupport.onFx(GateEditorPane::new);
        return FxTestSupport.onFx(() -> {
            pane.setChannelNames(List.of("CD3", "CD4"));
            pane.setCompartmentCapability(CompartmentCapability.scan(Arrays.asList(index.getObjects())));
            pane.setCellIndex(index);
            pane.setMarkerStats(MarkerStats.compute(index, Cells.allTrue(100)));
            pane.setEditorAlignment(alignment);
            pane.setGateNode(type.create.get());
            List<Slider> sliders = new ArrayList<>();
            collect(pane, Slider.class, sliders);
            if (type == Type.RECTANGLE) {
                List<ScatterPlotCanvas> scatters = new ArrayList<>();
                collect(pane, ScatterPlotCanvas.class, scatters);
                return scatters.get(0).axisWindow();
            }
            double[] out = new double[sliders.size() * 2];
            for (int i = 0; i < sliders.size(); i++) {
                out[2 * i] = sliders.get(i).getMin();
                out[2 * i + 1] = sliders.get(i).getMax();
            }
            return out;
        });
    }

    @ParameterizedTest
    @EnumSource(Type.class)
    void theEditorDrawsSlideValuesInReferenceUnits(Type type) {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        double[] raw = window(type, EditorAlignment.IDENTITY);
        double[] shown = window(type, new EditorAlignment() {
            @Override public Alignment forAxis(GateNode gate, int axis) { return BRIGHTER; }
            @Override public String referenceName() { return "slide_01"; }
        });
        assertEquals(raw.length, shown.length);
        assertTrue(raw.length > 0, "the editor put a window on screen");
        for (int k = 0; k < raw.length; k++) {
            assertEquals(BRIGHTER.inverse(raw[k]), shown[k], 1e-9 * Math.max(1, Math.abs(raw[k])), type + " end " + k);
        }
    }

    private static <T extends Node> T find(Parent root, Class<T> type, java.util.function.Predicate<T> which) {
        List<T> found = new ArrayList<>();
        collect(root, type, found);
        return found.stream().filter(which).findFirst().orElseThrow();
    }

    @Test
    void correctStainingIsOfferedOnlyInACohortAndWritesTheGateBeforeReportingIt() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        GateNode gate = Type.THRESHOLD.create.get();
        AtomicInteger reports = new AtomicInteger();
        boolean[] flagWhenReported = new boolean[1];
        AtomicInteger coalesced = new AtomicInteger();
        FxTestSupport.onFxRun(() -> {
            GateEditorPane pane = new GateEditorPane();
            pane.setChannelNames(List.of("CD3"));
            pane.setOnDiscreteEdit(n -> { reports.incrementAndGet(); flagWhenReported[0] = n.isCorrectStaining(); });
            pane.setOnNodeChanged(n -> coalesced.incrementAndGet());
            pane.setGateNode(gate);
            CheckBox box = find(pane, CheckBox.class, b -> "Correct staining".equals(b.getText()));
            assertTrue(box.isSelected(), "shows the gate's flag");
            assertFalse(box.isVisible(), "hidden outside a cohort");
            pane.setCohortAvailable(true);
            assertTrue(box.isVisible());
            assertEquals(0, reports.get(), "showing a gate is not an edit");
            box.setSelected(false);
        });
        assertFalse(gate.isCorrectStaining());
        assertEquals(1, reports.get());
        assertEquals(0, coalesced.get(), "a discrete edit, never reported as a coalescing gate edit");
        assertFalse(flagWhenReported[0], "written before it is reported");
    }

    @Test
    void aSlideSettingIsABannerWithAWayBackToTheCohortValue() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        AtomicInteger cleared = new AtomicInteger();
        String[] texts = FxTestSupport.onFx(() -> {
            GateEditorPane pane = new GateEditorPane();
            pane.setOnClearSlideSetting(cleared::incrementAndGet);
            pane.setGateNode(Type.THRESHOLD.create.get());
            Label banner = find(pane, Label.class, l -> l.getStyleClass().contains("fp-hint")
                    && l.getParent() != null && l.getParent().getChildrenUnmodifiable().stream()
                    .anyMatch(n -> n instanceof Button b && "Use the cohort value".equals(b.getText())));
            String[] out = new String[4];
            pane.setSlideSetting(new SlideSetting.Manual(GateValues.of(new double[]{12.5})));
            out[0] = banner.getParent().isVisible() ? banner.getText() : null;
            pane.setSlideSetting(new SlideSetting.Skip());
            out[1] = banner.getParent().isVisible() ? banner.getText() : null;
            pane.setSlideSetting(new SlideSetting.Reviewed(GateValues.of(new double[]{12.5})));
            out[2] = banner.getParent().isVisible() ? banner.getText() : null;
            pane.setSlideSetting(new SlideSetting.Skip());
            find(pane, Button.class, b -> "Use the cohort value".equals(b.getText())).fire();
            pane.setSlideSetting(null);
            out[3] = banner.getParent().isVisible() ? banner.getText() : null;
            return out;
        });
        assertEquals("Adjusted on this slide: 12.5000 (raw)", texts[0]);
        assertEquals("Skipped on this slide — its cells are unmeasured", texts[1]);
        assertNull(texts[2], "a review is not drawn as a banner");
        assertNull(texts[3]);
        assertEquals(1, cleared.get());
    }

    /** The raw-threshold tooltip, as it reads when it is about to show. */
    private static String rawThresholdTooltip(SlideSetting setting) {
        CellIndex index = Cells.of(100).marker("CD3", i -> i).area(50.0).build();
        return FxTestSupport.onFx(() -> {
            GateEditorPane pane = new GateEditorPane();
            pane.setChannelNames(List.of("CD3"));
            pane.setCompartmentCapability(CompartmentCapability.scan(Arrays.asList(index.getObjects())));
            pane.setCellIndex(index);
            pane.setMarkerStats(MarkerStats.compute(index, Cells.allTrue(100)));
            pane.setEditorAlignment(new EditorAlignment() {
                @Override public Alignment forAxis(GateNode gate, int axis) { return BRIGHTER; }
                @Override public String referenceName() { return "slide_01"; }
            });
            pane.setGateNode(Type.THRESHOLD.create.get());
            pane.setSlideSetting(setting);
            Label caption = find(pane, Label.class, l -> l.getTooltip() != null && l.isVisible()
                    && l.getText() != null && l.getText().contains("aligned to slide_01"));
            javafx.scene.control.Tooltip tip = caption.getTooltip();
            tip.getOnShowing().handle(new javafx.stage.WindowEvent(tip, javafx.stage.WindowEvent.WINDOW_SHOWING));
            return tip.getText();
        });
    }

    /**
     * Task 12 carry: with a Manual on the open slide, the tooltip gives the Manual number the pass
     * applies — the same number the banner shows — never f_s(reference).
     */
    @Test
    void theRawThresholdTooltipGivesTheManualValueWhileOneStands() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        assertEquals(String.format(java.util.Locale.US, "On this slide the threshold is %.4f (raw)", BRIGHTER.apply(10)),
                rawThresholdTooltip(null));
        assertEquals("On this slide the threshold is 12.5000 (raw, adjusted on this slide)",
                rawThresholdTooltip(new SlideSetting.Manual(GateValues.of(new double[]{12.5}))));
        assertEquals("This gate is skipped on this slide — its cells are unmeasured",
                rawThresholdTooltip(new SlideSetting.Skip()));
    }

    private static GateEditorPane thresholdPane(GateNode gate) {
        CellIndex index = Cells.of(100).marker("CD3", i -> i).area(50.0).build();
        GateEditorPane pane = new GateEditorPane();
        pane.setChannelNames(List.of("CD3"));
        pane.setCompartmentCapability(CompartmentCapability.scan(Arrays.asList(index.getObjects())));
        pane.setCellIndex(index);
        pane.setMarkerStats(MarkerStats.compute(index, Cells.allTrue(100)));
        pane.setEditorAlignment(new EditorAlignment() {
            @Override public Alignment forAxis(GateNode g, int axis) { return BRIGHTER; }
            @Override public String referenceName() { return "slide_01"; }
        });
        pane.setGateNode(gate);
        return pane;
    }

    /** A locked cut: slider, field and histogram drag off, and a hint says what to do instead. */
    @Test
    void aLockedCutDisablesEveryWayToMoveItAndSaysWhy() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        boolean[][] states = FxTestSupport.onFx(() -> {
            GateEditorPane pane = thresholdPane(Type.THRESHOLD.create.get());
            pane.setSlideSetting(new SlideSetting.Manual(GateValues.of(new double[]{12.5})));
            Slider slider = find(pane, Slider.class, x -> true);
            javafx.scene.control.TextField field = find(pane, javafx.scene.control.TextField.class,
                    f -> f.getStyleClass().contains("fp-mono-field"));
            qupath.ext.flowpath.ui.widgets.HistogramCanvas h =
                    find(pane, qupath.ext.flowpath.ui.widgets.HistogramCanvas.class, x -> true);
            Label hint = find(pane, Label.class, l -> GateEditorPane.CUT_LOCKED_HINT.equals(l.getText()));
            boolean[] editable = {slider.isDisable(), field.isDisable(), h.isDraggable(), hint.isVisible()};
            pane.setCutEditable(false);
            boolean[] locked = {slider.isDisable(), field.isDisable(), h.isDraggable(), hint.isVisible()};
            pane.setCutEditable(true);
            boolean[] again = {slider.isDisable(), field.isDisable(), h.isDraggable(), hint.isVisible()};
            return new boolean[][]{editable, locked, again};
        });
        assertArrayEquals(new boolean[]{false, false, true, false}, states[0]);
        assertArrayEquals(new boolean[]{true, true, false, true}, states[1]);
        assertArrayEquals(new boolean[]{false, false, true, false}, states[2]);
    }

    /**
     * The same gate rebuilt (a channel or column switch shows it again) keeps its setting on this
     * slide: the banner stays and the cut stays the Manual's, as the engine applies it.
     */
    @Test
    void rebuildingTheSameGateKeepsItsSlideSetting() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        Object[] out = FxTestSupport.onFx(() -> {
            GateNode gate = Type.THRESHOLD.create.get();
            GateEditorPane pane = thresholdPane(gate);
            pane.setSlideSetting(new SlideSetting.Manual(GateValues.of(new double[]{12.5})));
            pane.setCutEditable(false);
            pane.setGateNode(gate);
            Label banner = find(pane, Label.class, l -> l.getText() != null && l.getText().startsWith("Adjusted on this slide"));
            Slider slider = find(pane, Slider.class, x -> true);
            boolean kept = banner.getParent().isVisible();
            double thumb = slider.getValue();
            boolean locked = !pane.isCutEditable();
            pane.setGateNode(Type.THRESHOLD.create.get());
            return new Object[]{kept, thumb, locked, banner.getParent().isVisible(), pane.isCutEditable()};
        });
        assertEquals(true, out[0], "the banner stays");
        assertEquals(BRIGHTER.inverse(12.5), (double) out[1], 1e-9, "the thumb is the Manual's cut");
        assertEquals(true, out[2]);
        assertEquals(false, out[3], "another gate starts without the last one's setting");
        assertEquals(true, out[4]);
    }
}
