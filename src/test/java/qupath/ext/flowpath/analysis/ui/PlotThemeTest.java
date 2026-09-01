package qupath.ext.flowpath.analysis.ui;

import javafx.geometry.Insets;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Rectangle;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * No JavaFX toolkit: PlotTheme is a value type over Color, which needs no Stage.
 * <p>
 * The luminance-walk tests below build real {@link Pane}, {@link Group} and {@link Scene}
 * objects and wire them into a parent chain — that is safe without {@code Platform.startup()}
 * for {@link Pane}/{@link Group}: a {@code Region}'s {@link Background} and attaching a node
 * to a parent are plain object-graph fields with no windowing toolkit, CSS pass or GPU
 * involved. Constructing a {@link Scene} is the one exception — its constructor reaches into
 * the render toolkit to schedule a layout pulse and throws without one running, even though
 * the scene it built is otherwise perfectly usable; see {@link #sceneWithFill} for exactly
 * what that means and how the one test that needs a {@code Scene} works around it without
 * starting the toolkit. Do not reach for {@code FxTestSupport} here — that bootstraps the
 * toolkit for tests that actually need a live {@code Stage} (layout, CSS, rendering), which
 * none of these do, including the {@code Scene} one.
 */
class PlotThemeTest {

    @Test
    void seriesWrapsRatherThanThrowing() {
        PlotTheme theme = PlotTheme.DARK;
        assertEquals(8, theme.series().size(), "the palette is eight colours");
        assertEquals(theme.series(0), theme.series(8), "index wraps");
        assertEquals(theme.series(1), theme.series(-7), "negative index wraps too");
    }

    @Test
    void ungatedIsDistinguishableFromNegativeAndFromTheBackground() {
        for (PlotTheme theme : new PlotTheme[] { PlotTheme.DARK, PlotTheme.LIGHT }) {
            // The whole point of the segment: "nobody asked this question of these cells"
            // must not read as "these cells were negative", nor vanish into the canvas.
            assertTrue(hueDistance(theme.ungated(), theme.negative()) > 0.15,
                    "ungated must not read as a shade of negative");
            double ungatedRatio = contrastRatio(theme.ungated(), theme.background());
            assertTrue(ungatedRatio >= 3.0,
                    "ungated must stand off the background at WCAG 3:1, was " + ungatedRatio);
            double positiveRatio = contrastRatio(theme.positive(), theme.background());
            assertTrue(positiveRatio >= 3.0,
                    "positive must stand off the background at WCAG 3:1, was " + positiveRatio);
            // negative is a data-bearing colour too — "measured, and below threshold" — not
            // decoration, so it is held to the same 3:1 floor as positive and ungated.
            double negativeRatio = contrastRatio(theme.negative(), theme.background());
            assertTrue(negativeRatio >= 3.0,
                    "negative must stand off the background at WCAG 3:1, was " + negativeRatio);
        }
    }

    @Test
    void everySeriesColourStandsOffItsOwnBackground() {
        for (PlotTheme theme : new PlotTheme[] { PlotTheme.DARK, PlotTheme.LIGHT }) {
            for (int i = 0; i < theme.series().size(); i++) {
                Color c = theme.series(i);
                double ratio = contrastRatio(c, theme.background());
                assertTrue(ratio >= 3.0,
                        "series " + i + " (" + c + ") is too close to the background: ratio " + ratio);
            }
        }
    }

    @Test
    void isDarkReadsLuminanceNotIdentity() {
        assertTrue(PlotTheme.isDark(Color.rgb(30, 30, 30)));
        assertTrue(PlotTheme.isDark(Color.rgb(60, 60, 70)));
        assertFalse(PlotTheme.isDark(Color.WHITE));
        assertFalse(PlotTheme.isDark(Color.rgb(240, 240, 240)));
        assertFalse(PlotTheme.isDark(null), "undetectable is treated as light, never dark");
    }

    @Test
    void detectFallsBackToLightWithoutAScene() {
        // A node with no scene and no styled parent cannot be sampled. LIGHT is the
        // documented fallback, chosen because a light plot on a dark theme is merely
        // ugly whereas a dark plot on a light theme was the bug being fixed.
        assertSame(PlotTheme.LIGHT, PlotTheme.detect(null));
    }

    @Test
    void detectReadsADarkImmediateParentBackground() {
        Pane parent = solidBackgroundPane(Color.rgb(20, 20, 20));
        Rectangle child = new Rectangle();
        parent.getChildren().add(child);
        assertSame(PlotTheme.DARK, PlotTheme.detect(child));
    }

    @Test
    void detectReadsALightImmediateParentBackground() {
        Pane parent = solidBackgroundPane(Color.rgb(240, 240, 240));
        Rectangle child = new Rectangle();
        parent.getChildren().add(child);
        assertSame(PlotTheme.LIGHT, PlotTheme.detect(child));
    }

    @Test
    void detectWalksPastAnUnstyledParentToAStyledAncestor() {
        // The bug this pins: an implementation that checked only node.getParent() rather
        // than walking the whole chain would miss the background here and fall through to
        // LIGHT regardless of the tree's real theme.
        Pane styledAncestor = solidBackgroundPane(Color.rgb(20, 20, 20));
        Pane unstyledParent = new Pane(); // no background of its own
        Rectangle leaf = new Rectangle();
        unstyledParent.getChildren().add(leaf);
        styledAncestor.getChildren().add(unstyledParent);
        assertSame(PlotTheme.DARK, PlotTheme.detect(leaf));
    }

    @Test
    void detectSkipsANonColorBackgroundFillAndKeepsWalking() {
        // A Region CAN have a background whose fill isn't a solid Color (a gradient, an
        // image). sampleBackground must not throw on that fill and must not treat it as "no
        // background" either — it skips past it to keep looking, exactly as it would skip
        // an ancestor with no background at all.
        Pane styledAncestor = solidBackgroundPane(Color.rgb(20, 20, 20));
        Pane gradientParent = new Pane();
        gradientParent.setBackground(new Background(new BackgroundFill(
                new LinearGradient(0, 0, 1, 1, true, CycleMethod.NO_CYCLE,
                        new Stop(0, Color.WHITE), new Stop(1, Color.BLACK)),
                CornerRadii.EMPTY, Insets.EMPTY)));
        Rectangle leaf = new Rectangle();
        gradientParent.getChildren().add(leaf);
        styledAncestor.getChildren().add(gradientParent);
        assertSame(PlotTheme.DARK, PlotTheme.detect(leaf));
    }

    @Test
    void detectFallsBackToADarkSceneFillWithNoStyledAncestor() {
        Group root = new Group();
        Rectangle leaf = new Rectangle();
        root.getChildren().add(leaf);
        sceneWithFill(root, Color.rgb(15, 15, 20));
        assertSame(PlotTheme.DARK, PlotTheme.detect(leaf));
    }

    private static Pane solidBackgroundPane(Color fill) {
        Pane pane = new Pane();
        pane.setBackground(new Background(new BackgroundFill(fill, CornerRadii.EMPTY, Insets.EMPTY)));
        return pane;
    }

    /**
     * Attaches {@code root} to a {@link Scene} with the given fill, without a running
     * toolkit. {@code new Scene(root, ...)} calls {@code Scene.setRoot}, which — after
     * already recording itself as {@code root}'s scene — asks the render toolkit to
     * schedule a layout pulse and throws {@code NullPointerException} because no toolkit is
     * running (verified empirically: {@code QuantumToolkit.resumeTimer}'s {@code
     * this.pulseTimer} is null). That NPE is a side effect of the constructor, not a failure
     * of the object graph it built: by the time it is thrown, {@code root}'s scene reference
     * is already set, so the caller can still reach the (real, usable) {@link Scene} through
     * {@code root.getScene()} and set its fill there. Swallowing the NPE and recovering the
     * scene this way is the only public-API route to a scene-backed node tree under the "no
     * toolkit" constraint this test class is held to — see the class javadoc.
     */
    private static void sceneWithFill(Group root, Color fill) {
        try {
            new Scene(root, 100, 100);
        } catch (NullPointerException expectedWithoutALiveToolkit) {
            // See method javadoc: the scene exists on root despite the constructor throwing.
        }
        root.getScene().setFill(fill);
    }

    private static double hueDistance(Color a, Color b) {
        double sat = Math.min(a.getSaturation(), b.getSaturation());
        if (sat < 0.15) return 1.0; // one of them is neutral grey; that IS the distinction
        double d = Math.abs(a.getHue() - b.getHue());
        return Math.min(d, 360 - d) / 360.0;
    }

    /**
     * WCAG relative-luminance contrast ratio, 1:1 to 21:1.
     * <p>
     * Not {@code Color.getBrightness()}, which is HSB brightness — {@code max(r,g,b)} — and
     * reports 0.92 for a strong blue like {@code #2563EB} purely because its blue channel is
     * near maximum, though it reads perfectly against white. WCAG 1.4.11 sets 3:1 as the floor
     * for non-text graphics, which is exactly what a chart series is.
     */
    private static double contrastRatio(Color a, Color b) {
        double la = relativeLuminance(a), lb = relativeLuminance(b);
        double hi = Math.max(la, lb), lo = Math.min(la, lb);
        return (hi + 0.05) / (lo + 0.05);
    }

    private static double relativeLuminance(Color c) {
        return 0.2126 * linear(c.getRed()) + 0.7152 * linear(c.getGreen()) + 0.0722 * linear(c.getBlue());
    }

    private static double linear(double channel) {
        return channel <= 0.03928 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
    }
}
