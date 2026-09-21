package qupath.ext.flowpath.ui;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.TextField;
import javafx.scene.layout.Background;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.text.Text;
import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.testing.FxTestSupport;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Loads {@code flowpath.css} for real, applies it to a small scene under both a light and a
 * dark Modena base, and checks WCAG contrast for every semantic text class it defines.
 * <p>
 * {@code -fx-base} is what QuPath's own theme switch changes; setting it on the root
 * (inline, so it wins over the {@code .root} rule Modena itself installs) simulates the
 * light/dark toggle without needing a running QuPath instance. A class whose colour is a
 * looked-up token ({@code -fx-text-background-color}, {@code derive(...)}) re-resolves
 * against the new base the same way a real theme switch would; a fixed hex literal would not,
 * which is exactly the bug this task fixes.
 * <p>
 * {@code fp-mono-field} paints its own background ({@code -fx-control-inner-background})
 * rather than sitting on the panel's, so contrast is checked against whichever background the
 * class itself resolves to — its own, if it set one, else the panel's.
 */
class FlowpathStylesheetContrastTest {

    private static final String LIGHT_BASE = "#ececec";
    private static final String DARK_BASE = "#2b2b2b";

    /** Primary text: must read clearly on its own, so held to the full 4.5:1 floor. */
    private static final List<String> FULL_CONTRAST_CLASSES =
            List.of("fp-primary-text", "fp-section-header", "fp-mono-field");

    /**
     * Secondary/supplementary text — a count beside a name, a hover read-out beside the
     * histogram, an italic hint under a control that already has its own full-contrast label.
     * WCAG's own floor for this category is 3:1; nothing here is the only text on screen.
     */
    private static final List<String> MUTED_CONTRAST_CLASSES = List.of("fp-muted", "fp-hint");

    @Test
    void fullContrastClassesMeet4_5to1OnLightBase() {
        assertClassesMeetContrast(FULL_CONTRAST_CLASSES, LIGHT_BASE, 4.5);
    }

    @Test
    void fullContrastClassesMeet4_5to1OnDarkBase() {
        assertClassesMeetContrast(FULL_CONTRAST_CLASSES, DARK_BASE, 4.5);
    }

    @Test
    void mutedClassesMeet3to1FloorOnLightBase() {
        assertClassesMeetContrast(MUTED_CONTRAST_CLASSES, LIGHT_BASE, 3.0);
    }

    @Test
    void mutedClassesMeet3to1FloorOnDarkBase() {
        assertClassesMeetContrast(MUTED_CONTRAST_CLASSES, DARK_BASE, 3.0);
    }

    private void assertClassesMeetContrast(List<String> styleClasses, String base, double minRatio) {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        FxTestSupport.onFxRun(() -> {
            VBox root = new VBox(4);
            root.getStyleClass().add("fp-panel");
            root.setStyle("-fx-base: " + base + ";");

            Control[] controls = new Control[styleClasses.size()];
            for (int i = 0; i < styleClasses.size(); i++) {
                Control control = sampleControlFor(styleClasses.get(i));
                controls[i] = control;
                root.getChildren().add(control);
            }

            Scene scene = new Scene(root, 400, 300);
            scene.getStylesheets().add(stylesheetUrl());
            root.applyCss();
            root.layout();

            Color panelBg = resolvedBackground(root);
            assertStylesheetApplied(panelBg, base);

            for (int i = 0; i < controls.length; i++) {
                String styleClass = styleClasses.get(i);
                Control control = controls[i];
                Color fg = resolvedTextFill(control);
                assertTrue(fg != null, styleClass + ": resolved text fill is not a solid Color");
                Color bg = ownBackgroundOr(control, panelBg);
                double ratio = contrastRatio(fg, bg);
                assertTrue(ratio >= minRatio,
                        String.format("%s on base=%s: contrast %.2f < required %.2f (fg=%s, bg=%s)",
                                styleClass, base, ratio, minRatio, fg, bg));
            }
        });
    }

    /**
     * {@code fp-mono-field} is only ever applied to a real {@link TextField} in production
     * (see {@code QuadrantGateEditor}/{@code ThresholdGateEditor}); a {@code Label} does not
     * exercise the same skin, so it gets a real field here instead of the generic sample.
     */
    private static Control sampleControlFor(String styleClass) {
        Control control = "fp-mono-field".equals(styleClass) ? new TextField("123.4") : new Label("Sample text");
        control.getStyleClass().add(styleClass);
        return control;
    }

    /**
     * {@link Labeled#getTextFill()} answers directly for a {@code Label}; {@link TextField}
     * has no such property; its skin routes {@code -fx-text-fill} to the internal
     * {@code .text} node instead, so that node's resolved fill is read via lookup.
     */
    private static Color resolvedTextFill(Control control) {
        Paint fillPaint;
        if (control instanceof Labeled labeled) {
            fillPaint = labeled.getTextFill();
        } else {
            Node textNode = control.lookup(".text");
            fillPaint = textNode instanceof Text text ? text.getFill() : null;
        }
        return fillPaint instanceof Color color ? color : null;
    }

    /** Fails loudly rather than silently passing if the stylesheet did not apply at all. */
    private static void assertStylesheetApplied(Color panelBg, String base) {
        assertTrue(panelBg != null, "Panel background did not resolve at all for base " + base);
    }

    private static String stylesheetUrl() {
        var url = FlowpathStylesheetContrastTest.class.getResource("/qupath/ext/flowpath/ui/flowpath.css");
        assertTrue(url != null, "flowpath.css not found on the classpath");
        return url.toExternalForm();
    }

    /** The node's own resolved background fill, or {@code fallback} when it painted none. */
    private static Color ownBackgroundOr(Region region, Color fallback) {
        Color own = resolvedBackground(region);
        return own != null ? own : fallback;
    }

    private static Color resolvedBackground(Region region) {
        Background bg = region.getBackground();
        if (bg == null || bg.getFills().isEmpty()) return null;
        Paint fill = bg.getFills().get(0).getFill();
        return fill instanceof Color ? (Color) fill : null;
    }

    // ---- WCAG 2.x contrast ----

    private static double contrastRatio(Color a, Color b) {
        double l1 = relativeLuminance(a);
        double l2 = relativeLuminance(b);
        double lighter = Math.max(l1, l2);
        double darker = Math.min(l1, l2);
        return (lighter + 0.05) / (darker + 0.05);
    }

    private static double relativeLuminance(Color c) {
        return 0.2126 * channel(c.getRed()) + 0.7152 * channel(c.getGreen()) + 0.0722 * channel(c.getBlue());
    }

    private static double channel(double c) {
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
}
