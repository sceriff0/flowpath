package qupath.ext.flowpath.analysis.ui;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.testing.AnalysisFixtures;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where rotated category labels actually land, read back out of the rendered document.
 * <p>
 * This exists because the first version of {@code drawCategoryLabels} anchored a rotated
 * label's <b>start</b> at the slot centre, which is the obvious reading of "origin at the slot
 * centre" and is wrong: at −45° the text then runs up and to the <em>right</em>, so the final
 * slot's label ran up to {@code 84 × cos45° ≈ 59px} past the right edge of a 380px canvas and
 * was clipped. Every layout test in {@code PlotCanvasLayoutTest} passed with that defect
 * present, because they all assert on {@code LabelLayout} — what the plot decided — and none
 * of them could see where the ink went. Asserting on positions in the document is the only
 * assertion that could have caught it, which is why this file is positional and the others
 * are not.
 * <p>
 * Verified by falsification rather than assumed: reverting {@code drawCategoryLabels} to the
 * start-anchored line makes both tests here fail, while every assertion in {@code
 * PlotCanvasLayoutTest} still passes. That is the evidence this file earns its place — it
 * catches something the layout assertions structurally cannot see.
 * <p>
 * Every bound below is asserted with <b>no tolerance at all</b>, vertical as well as
 * horizontal. An earlier 6px anchor gap left a label elided to the full 84px cap reaching
 * 1.4px past the canvas edge, which passed on the fixtures and would have surfaced as a
 * clipped label on a panel with long marker names.
 */
class RotatedLabelRenderTest {

    /** A text element as the document records it: where it starts, and what it says. */
    private record TextElement(double x, double y, boolean rotated, String content) {}

    private static final Pattern TEXT = Pattern.compile(
            "<text x=\"([-0-9.]+)\" y=\"([-0-9.]+)\"[^>]*?>(.*?)</text>");

    /** Eight phenotype paths: long enough to rotate, long enough to be elided at the cap. */
    private static final List<String> LONG_PATHS = List.of(
            "CD45+/CD3+/CD8+/PD1+", "CD45+/CD3+/CD8+/PD1-", "CD45+/CD3+/CD4+/FOXP3+",
            "CD45+/CD3+/CD4+/FOXP3-", "CD45+/CD3-/CD19+", "CD45+/CD3-/CD19-",
            "CD45-/PanCK+", "CD45-/PanCK-");

    /** A canvas that draws nothing but the axis furniture, so only label geometry is on trial. */
    private static final class LabelProbe extends PlotCanvas {
        private final List<String> labels;
        LabelProbe(double width, double height, List<String> labels) {
            super(width, height);
            this.labels = labels;
        }
        @Override protected void draw(PlotSurface s, PlotTheme theme) {
            LabelLayout layout = layoutLabels(s, labels);
            drawValueTicks(s, theme, 0, 1000, 4, layout, 0);
            drawAxes(s, theme, layout, 0, "Population", "Count");
            drawCategoryLabels(s, theme, layout, 0);
        }
    }

    @Test
    void rotatedLabelsAreDrawnEntirelyInsideTheCanvas() {
        LabelProbe probe = new LabelProbe(380, 220, LONG_PATHS);
        assertInsideTheCanvas(probe.toSvg(), 380, 220);
    }

    /**
     * The same check on a real canvas rather than a probe: a narrow Analysis panel is exactly
     * when population paths stop fitting side by side, so this is the shape a user meets.
     */
    @Test
    void aNarrowCompositionCanvasKeepsItsRotatedLabelsOnTheCanvasToo() {
        CompositionCanvas canvas = new CompositionCanvas();
        canvas.setRows(AnalysisFixtures.twoLevelRows());
        canvas.resize(180, 200);
        assertInsideTheCanvas(canvas.toSvg(), 180, 200);
    }

    private static void assertInsideTheCanvas(String svg, double width, double height) {
        List<TextElement> texts = parse(svg);
        assertTrue(texts.stream().anyMatch(TextElement::rotated),
                "nothing rotated, so this run pins nothing about rotated labels:\n" + svg);

        // Measured with the same measurer a live canvas uses, so the far end computed here is
        // the far end the toolkit actually advanced the text to.
        SvgSurface ruler = new SvgSurface(width, height, new FxTextMeasurer());
        ruler.setFont(8, false);
        double diagonal = Math.cos(Math.toRadians(45));

        // For a rotated layout the bottom padding IS PlotCanvas.PADDING_BOTTOM_ROTATED, so the
        // axis sits exactly that far above the canvas bottom, and the band below it is the
        // room the labels have to fit inside.
        double axisBottom = height - PlotCanvas.PADDING_BOTTOM_ROTATED;

        for (TextElement text : texts) {
            assertTrue(text.x() >= 0 && text.x() <= width,
                    "a text origin starts off the canvas: " + text);
            assertTrue(text.y() >= 0 && text.y() <= height,
                    "a text origin starts off the canvas vertically: " + text);
            if (!text.rotated()) {
                continue;
            }
            // No tolerance, deliberately. The deepest a label may reach is the anchor gap plus
            // the elision cap's vertical component (4 + 84 * sin45 = 63.4), and the band is 64:
            // it fits by arithmetic, not by luck. Widening the gap, raising the cap or
            // narrowing the band must fail here rather than clip a label on somebody's panel
            // with long marker names -- which is what an earlier 6px gap did, by 1.4px, on
            // exactly the labels that reach the cap.
            assertTrue(text.y() - axisBottom <= PlotCanvas.PADDING_BOTTOM_ROTATED,
                    "a rotated label reaches " + (text.y() - axisBottom) + "px below the axis, "
                            + "past the " + PlotCanvas.PADDING_BOTTOM_ROTATED
                            + "px band reserved for it: " + text);
            // A −45° label advances up and to the right from its origin; its far end is the
            // character nearest the bar it names, and must be on the canvas as well, since a
            // label whose name is clipped away names nothing.
            double advance = ruler.textWidth(text.content());
            double endX = text.x() + diagonal * advance;
            double endY = text.y() - diagonal * advance;
            assertTrue(endX >= 0 && endX <= width,
                    "a rotated label runs off the side of the canvas, ending at x=" + endX
                            + ": " + text);
            assertTrue(endY >= 0 && endY <= height,
                    "a rotated label ends off the canvas vertically at y=" + endY + ": " + text);
        }
    }

    private static List<TextElement> parse(String svg) {
        List<TextElement> out = new ArrayList<>();
        Matcher matcher = TEXT.matcher(svg);
        while (matcher.find()) {
            String element = matcher.group();
            out.add(new TextElement(Double.parseDouble(matcher.group(1)),
                    Double.parseDouble(matcher.group(2)),
                    element.contains("rotate(-45"),
                    matcher.group(3)));
        }
        return out;
    }
}
