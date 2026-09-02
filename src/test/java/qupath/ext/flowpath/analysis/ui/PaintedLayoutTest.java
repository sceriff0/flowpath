package qupath.ext.flowpath.analysis.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link PlotCanvas#paintedLayout()}: the geometry a paint actually used, read back rather
 * than recomputed.
 * <p>
 * Hit-testing (Task 13) has no {@code PlotSurface} to measure text with, so it cannot rebuild
 * a {@link PlotCanvas.LabelLayout} of its own — and if it built one from a throwaway surface it
 * would be free to disagree with what is on screen. These tests pin the two facts a hit-test
 * depends on: that a real paint publishes exactly the pair it drew with, and that a paint which
 * drew <em>no</em> plot publishes {@code null} instead of leaving the previous rectangle
 * standing. The second is the one that bites: a canvas that showed an empty state after
 * showing data would otherwise report a plot rectangle that is no longer on screen, and every
 * click inside it would resolve to a population that is not being displayed.
 */
class PaintedLayoutTest {

    /** Draws axes only when it has something to draw, exactly as the four real canvases do. */
    private static final class Probe extends PlotCanvas {
        boolean hasData = true;
        int legendRows = 2;
        LabelLayout drawnWith;

        Probe() { super(300, 200); }

        @Override
        protected void draw(PlotSurface s, PlotTheme theme) {
            if (!hasData) {
                drawEmptyState(s, theme, "Nothing to show");
                return;
            }
            drawnWith = layoutLabels(s, List.of("CD4", "CD8"));
            drawAxes(s, theme, drawnWith, legendRows, "Population", "Count");
            drawCategoryLabels(s, theme, drawnWith, legendRows);
        }

        PaintedLayout painted() { return paintedLayout(); }

        @Override public List<PlotDatum> plotData() { return List.of(); }
    }

    @Test
    void nothingIsRememberedBeforeTheFirstPaint() {
        assertNull(new Probe().painted(),
                "a click can arrive before the first paint -- a hit-test must be able to say "
                        + "'no hit' rather than throw");
    }

    @Test
    void aPaintPublishesTheExactPairItDrewWith() {
        Probe probe = new Probe();
        probe.toSvg();

        PlotCanvas.PaintedLayout painted = probe.painted();
        assertNotNull(painted);
        assertSame(probe.drawnWith, painted.labels(),
                "the remembered layout must be the object the plot drew with, not an equal one "
                        + "rebuilt afterwards -- an equal copy is exactly what could drift");
        assertEquals(2, painted.legendRows());
        // The whole plot rectangle follows from the pair, which is why holding the pair is
        // enough for a hit-test to invert the drawing.
        assertEquals(probe.plotTop(2) + probe.plotHeight(probe.drawnWith, 2),
                probe.plotTop(painted.legendRows())
                        + probe.plotHeight(painted.labels(), painted.legendRows()),
                1e-9);
    }

    @Test
    void anEmptyStatePaintClearsTheRectangleRatherThanLeavingAStaleOne() {
        Probe probe = new Probe();
        probe.toSvg();
        assertNotNull(probe.painted(), "precondition: the data pass published a rectangle");

        probe.hasData = false;
        probe.toSvg();
        assertNull(probe.painted(),
                "an empty state draws no axes and so has no plot rectangle -- reporting the "
                        + "previous one would put every click inside a plot that is not on screen");
    }
}
