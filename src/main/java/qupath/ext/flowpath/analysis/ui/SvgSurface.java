package qupath.ext.flowpath.analysis.ui;

import javafx.scene.paint.Color;

import java.util.Locale;

/**
 * The exportable {@link PlotSurface} backend: accumulates a fixed-size SVG document instead of
 * painting pixels, so the same drawing calls {@link CanvasSurface} sends to a live {@code
 * Canvas} can also produce a vector file Task 12 hands to a caller. Hand-written rather than
 * built on a library — there is no SVG dependency in this project and none is being added for
 * one export path (see the task brief: "the SVG is hand-written; there is no SVG library here
 * and you are not to add one").
 * <p>
 * <b>Text measurement comes from whichever {@link TextMeasurer} this surface is constructed
 * with, never from a hardcoded approximation.</b> A caller with no toolkit (this class's own
 * unit test, most obviously) hands it {@link ApproxTextMeasurer}; Task 12's export hands it the
 * live canvas's {@link FxTextMeasurer} instead, so an exported figure's text lays out exactly
 * as it did on screen rather than merely approximately. If {@link #textWidth} ever called
 * {@code new ApproxTextMeasurer()} directly instead of delegating to the field, that seam would
 * be gone and the export could silently stop matching the screen — nothing here should do that.
 * <p>
 * <b>Formatting rules, all load-bearing for a document a browser or Illustrator can actually
 * parse:</b>
 * <ul>
 *   <li>Every number is written through {@link #num(double)} under {@link Locale#US} — a
 *       decimal comma (the default in, e.g., {@link Locale#GERMANY}) makes the attribute value
 *       unparseable, which is exactly what {@code decimalsUseUsLocaleRegardlessOfDefault} pins
 *       by flipping the JVM default locale mid-test.</li>
 *   <li>Colours are written as lowercase {@code #rrggbb}; a fill or stroke below full opacity
 *       adds its own {@code fill-opacity}/{@code stroke-opacity} attribute rather than folding
 *       alpha into the hex, because SVG's {@code #rrggbbaa} form is not universally supported
 *       while the two-attribute form is.</li>
 *   <li>Text content is escaped ({@code &} first, then {@code < > " '}) because gate and branch
 *       names are user-editable free text — {@code CD3 > 0.5 & "CD8-" <x>} is a legal branch
 *       name, and emitting it raw would produce a document no XML parser can open.</li>
 * </ul>
 */
public final class SvgSurface implements PlotSurface {

    private final double width;
    private final double height;
    private final TextMeasurer measurer;
    private final StringBuilder body = new StringBuilder();

    private Color fill = Color.BLACK;
    private Color stroke = Color.BLACK;
    private double lineWidth = 1;
    private double fontSize = 10;
    private boolean bold = false;

    public SvgSurface(double width, double height, TextMeasurer measurer) {
        this.width = width;
        this.height = height;
        this.measurer = measurer;
    }

    @Override public double width() { return width; }

    @Override public double height() { return height; }

    @Override public void setFill(Color color) { this.fill = color; }

    @Override public void setStroke(Color color) { this.stroke = color; }

    @Override public void setLineWidth(double width) { this.lineWidth = width; }

    @Override
    public void setFont(double sizePx, boolean isBold) {
        this.fontSize = sizePx;
        this.bold = isBold;
    }

    @Override
    public void fillRect(double x, double y, double w, double h) {
        body.append("<rect x=\"").append(num(x))
                .append("\" y=\"").append(num(y))
                .append("\" width=\"").append(num(w))
                .append("\" height=\"").append(num(h))
                .append("\" fill=\"").append(hex(fill)).append("\"");
        appendOpacity("fill-opacity", fill);
        body.append("/>\n");
    }

    @Override
    public void strokeRect(double x, double y, double w, double h) {
        body.append("<rect x=\"").append(num(x))
                .append("\" y=\"").append(num(y))
                .append("\" width=\"").append(num(w))
                .append("\" height=\"").append(num(h))
                .append("\" fill=\"none\" stroke=\"").append(hex(stroke))
                .append("\" stroke-width=\"").append(num(lineWidth)).append("\"");
        appendOpacity("stroke-opacity", stroke);
        body.append("/>\n");
    }

    @Override
    public void strokeLine(double x1, double y1, double x2, double y2) {
        body.append("<line x1=\"").append(num(x1))
                .append("\" y1=\"").append(num(y1))
                .append("\" x2=\"").append(num(x2))
                .append("\" y2=\"").append(num(y2))
                .append("\" stroke=\"").append(hex(stroke))
                .append("\" stroke-width=\"").append(num(lineWidth)).append("\"");
        appendOpacity("stroke-opacity", stroke);
        body.append("/>\n");
    }

    @Override
    public void fillText(String text, double x, double y) {
        openText(x, y);
        body.append(">").append(escape(text)).append("</text>\n");
    }

    /**
     * The same {@code transform="rotate(deg x y)"} SVG offers natively for exactly this
     * rotate-about-a-point case — no {@code save}/{@code translate}/{@code rotate}/{@code
     * restore} sequence to replay, because that sequence is a retained-graphics-context idiom
     * that means nothing in a declarative document.
     */
    @Override
    public void fillTextRotated(String text, double x, double y, double degrees) {
        openText(x, y);
        body.append(" transform=\"rotate(").append(num(degrees)).append(" ").append(num(x))
                .append(" ").append(num(y)).append(")\"");
        body.append(">").append(escape(text)).append("</text>\n");
    }

    private void openText(double x, double y) {
        body.append("<text x=\"").append(num(x)).append("\" y=\"").append(num(y))
                .append("\" fill=\"").append(hex(fill))
                .append("\" font-size=\"").append(num(fontSize))
                .append("\" font-family=\"sans-serif\"");
        if (bold) {
            body.append(" font-weight=\"bold\"");
        }
    }

    @Override public double textWidth(String text) { return measurer.width(text, fontSize, bold); }

    @Override public double fontSize() { return fontSize; }

    /** The finished document: the accumulated body wrapped in a sized, namespaced root. */
    public String toSvg() {
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"" + num(width)
                + "\" height=\"" + num(height)
                + "\" viewBox=\"0 0 " + num(width) + " " + num(height) + "\">\n"
                + body
                + "</svg>";
    }

    private void appendOpacity(String attr, Color color) {
        if (color.getOpacity() < 1) {
            body.append(" ").append(attr).append("=\"").append(num(color.getOpacity())).append("\"");
        }
    }

    /**
     * {@code value} under {@link Locale#US}, with a trailing {@code .0} trimmed so an integral
     * coordinate reads {@code 10} rather than {@code 10.0} — cosmetic, but it keeps the
     * document's numbers readable to a human diffing two exports. Negative zero is normalised
     * to {@code "0"} rather than {@code "-0"} — {@code String.format("%.0f", -0.0)} and a
     * negative value that rounds away to nothing at 4dp (e.g. {@code -0.00001}) both produce a
     * leading minus sign with no non-zero digit behind it, which is valid SVG but reads as a
     * formatting bug to anyone diffing the document.
     */
    private static String num(double value) {
        String s;
        if (value == Math.rint(value) && !Double.isInfinite(value)) {
            s = String.format(Locale.US, "%.0f", value);
        } else {
            s = String.format(Locale.US, "%.4f", value);
            int end = s.length();
            while (s.charAt(end - 1) == '0') {
                end--;
            }
            if (s.charAt(end - 1) == '.') {
                end--;
            }
            s = s.substring(0, end);
        }
        return "-0".equals(s) ? "0" : s;
    }

    /** Lowercase {@code #rrggbb} — alpha is carried separately, see {@link #appendOpacity}. */
    private static String hex(Color color) {
        int r = (int) Math.round(color.getRed() * 255);
        int g = (int) Math.round(color.getGreen() * 255);
        int b = (int) Math.round(color.getBlue() * 255);
        return String.format(Locale.US, "#%02x%02x%02x", r, g, b);
    }

    /** {@code &} first, then {@code < > " '} — reordering would double-escape an {@code &amp;}. */
    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&apos;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
