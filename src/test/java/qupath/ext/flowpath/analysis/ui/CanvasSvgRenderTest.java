package qupath.ext.flowpath.analysis.ui;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.PopulationStats;
import qupath.ext.flowpath.testing.AnalysisFixtures;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Renders each canvas to SVG and asserts on the document. This is the first test in the
 * codebase that can see what a plot actually drew rather than only what it reduced.
 *
 * Two enabled roots on one channel throughout — the fixture the multi-root blind spot
 * documented in CLAUDE.md demands.
 */
class CanvasSvgRenderTest {

    private static List<PopulationStats.Row> twoRootRows() {
        return AnalysisFixtures.twoRootsSameChannelRows();
    }

    @Test
    void compositionDrawsOneRectPerLeafPlusTheAxisFrame() {
        CompositionCanvas canvas = new CompositionCanvas();
        canvas.setTheme(PlotTheme.DARK);
        canvas.setRows(twoRootRows());
        String svg = canvas.toSvg();
        long rects = svg.lines().filter(l -> l.contains("<rect")).count();
        assertTrue(rects >= canvas.barLabels().size(),
                "every bar reaches the document: " + canvas.barLabels());
        assertTrue(svg.contains("Population"), "the X axis is labelled");
        assertTrue(svg.contains("Count"), "the Y axis is labelled");
    }

    @Test
    void anEmptyCanvasSaysWhyRatherThanDrawingNothing() {
        CompositionCanvas canvas = new CompositionCanvas();
        canvas.setRows(List.of());
        String svg = canvas.toSvg();
        assertTrue(svg.contains("No gated populations yet"), svg);
        assertFalse(svg.contains("No data"), "the old placeholder text is gone");
    }

    @Test
    void markerPositivityDrawsUngatedInTheThemeAmberNotInAGreyNobodyCanSee() {
        MarkerPositivityCanvas canvas = new MarkerPositivityCanvas();
        canvas.setTheme(PlotTheme.DARK);
        canvas.setRows(twoRootRows());
        String svg = canvas.toSvg();
        assertFalse(svg.contains("#505059"), "the old invisible ungated grey is gone");
        assertFalse(svg.contains("#00c800"), "the off-palette green is gone");
        assertTrue(svg.contains(hex(PlotTheme.DARK.positive())), svg);
        assertTrue(svg.contains(hex(PlotTheme.DARK.negative())), svg);
        assertTrue(svg.contains("Ungated"), "the legend still names all three segments");
    }

    @Test
    void switchingThemeChangesEveryColourInTheDocument() {
        CompositionCanvas canvas = new CompositionCanvas();
        canvas.setRows(twoRootRows());
        canvas.setTheme(PlotTheme.DARK);
        String dark = canvas.toSvg();
        canvas.setTheme(PlotTheme.LIGHT);
        String light = canvas.toSvg();
        assertNotEquals(dark, light, "the theme reaches the rendered output");
        assertTrue(dark.contains(hex(PlotTheme.DARK.background())));
        assertTrue(light.contains(hex(PlotTheme.LIGHT.background())));
    }

    @Test
    void twoRootsOnOneChannelStillProduceTwoSeparableMarkerBars() {
        MarkerPositivityCanvas canvas = new MarkerPositivityCanvas();
        canvas.setRows(twoRootRows());
        List<String> markers = canvas.markers();
        assertEquals(2, markers.size(), "one bar per root, not one pooled bar: " + markers);
        assertTrue(markers.stream().allMatch(m -> m.contains("root")),
                "each bar names its root: " + markers);
    }

    private static String hex(javafx.scene.paint.Color c) {
        return String.format("#%02x%02x%02x",
                (int) Math.round(c.getRed() * 255),
                (int) Math.round(c.getGreen() * 255),
                (int) Math.round(c.getBlue() * 255));
    }
}
