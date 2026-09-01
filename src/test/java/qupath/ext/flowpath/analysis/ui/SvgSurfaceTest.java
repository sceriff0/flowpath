package qupath.ext.flowpath.analysis.ui;

import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** No toolkit: SvgSurface writes text, and ApproxTextMeasurer measures without one. */
class SvgSurfaceTest {

    private static SvgSurface surface() {
        return new SvgSurface(200, 100, new ApproxTextMeasurer());
    }

    @Test
    void emitsAWellFormedRootWithTheRequestedSize() {
        String svg = surface().toSvg();
        assertTrue(svg.startsWith("<svg "), "starts with the root element");
        assertTrue(svg.contains("width=\"200\""));
        assertTrue(svg.contains("height=\"100\""));
        assertTrue(svg.contains("viewBox=\"0 0 200 100\""));
        assertTrue(svg.contains("xmlns=\"http://www.w3.org/2000/svg\""));
        assertTrue(svg.trim().endsWith("</svg>"));
    }

    @Test
    void fillRectCarriesItsColourAsHex() {
        SvgSurface s = surface();
        s.setFill(Color.web("#4C9AFF"));
        s.fillRect(10, 20, 30, 40);
        String svg = s.toSvg();
        assertTrue(svg.contains("<rect"), svg);
        assertTrue(svg.contains("x=\"10\""), svg);
        assertTrue(svg.contains("fill=\"#4c9aff\""), svg);
        assertFalse(svg.contains("fill-opacity"), "a fully opaque fill needs no opacity attribute");
    }

    @Test
    void translucentFillsCarryOpacitySeparately() {
        SvgSurface s = surface();
        s.setFill(Color.rgb(76, 154, 255, 0.85));
        s.fillRect(0, 0, 1, 1);
        String svg = s.toSvg();
        assertTrue(svg.contains("fill=\"#4c9aff\""), svg);
        assertTrue(svg.contains("fill-opacity=\"0.85\""), svg);
    }

    @Test
    void textIsEscapedRatherThanEmittedRaw() {
        // Gate branch names are user-editable: "CD3 > 0.5 & CD8-" is a legal name and
        // would otherwise produce an SVG no viewer can parse.
        SvgSurface s = surface();
        s.setFill(Color.BLACK);
        s.fillText("CD3 > 0.5 & \"CD8-\" <x>", 5, 5);
        String svg = s.toSvg();
        assertTrue(svg.contains("&gt;"), svg);
        assertTrue(svg.contains("&amp;"), svg);
        assertTrue(svg.contains("&lt;x&gt;"), svg);
        assertFalse(svg.contains("<x>"), "raw markup must not survive into the document");
    }

    @Test
    void rotatedTextCarriesATransformAnchoredAtItsOrigin() {
        SvgSurface s = surface();
        s.setFill(Color.BLACK);
        s.fillTextRotated("Count", 12, 50, -90);
        String svg = s.toSvg();
        assertTrue(svg.contains("rotate(-90"), svg);
        assertTrue(svg.contains("12"), svg);
        assertTrue(svg.contains("50"), svg);
    }

    @Test
    void decimalsUseUsLocaleRegardlessOfDefault() {
        java.util.Locale previous = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY); // decimal comma
            SvgSurface s = surface();
            s.setFill(Color.BLACK);
            s.fillRect(1.5, 2.5, 3.5, 4.5);
            String svg = s.toSvg();
            assertTrue(svg.contains("1.5"), svg);
            assertFalse(svg.contains("1,5"), "a decimal comma makes the SVG unparseable");
        } finally {
            java.util.Locale.setDefault(previous);
        }
    }

    @Test
    void approxMeasurerScalesWithLengthAndSizeAndIsWiderWhenBold() {
        TextMeasurer m = new ApproxTextMeasurer();
        assertTrue(m.width("XXXX", 10, false) > m.width("XX", 10, false));
        assertTrue(m.width("XX", 20, false) > m.width("XX", 10, false));
        assertTrue(m.width("XX", 10, true) > m.width("XX", 10, false));
        assertEquals(0, m.width("", 10, false), 1e-9);
        assertEquals(0, m.width(null, 10, false), 1e-9);
    }

    @Test
    void surfaceReportsTheWidthOfTextAtTheCurrentFont() {
        SvgSurface s = surface();
        s.setFont(8, false);
        double small = s.textWidth("CD45+");
        s.setFont(16, false);
        assertTrue(s.textWidth("CD45+") > small, "textWidth follows setFont");
        assertEquals(16, s.fontSize(), 1e-9);
    }
}
