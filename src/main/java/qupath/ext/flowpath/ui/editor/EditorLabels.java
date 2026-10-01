package qupath.ext.flowpath.ui.editor;

import javafx.geometry.Insets;
import javafx.scene.control.Label;
import qupath.ext.flowpath.model.cohort.Alignment;

import java.util.Locale;

/**
 * The two small label builders every gate-type editor used to keep its own copy of: a
 * section header and a styled plain label. {@code GateEditorPane} (in {@code ui}, which
 * already depends one-way on {@code ui.editor} -- see
 * {@code UiPackageDependencyDirectionTest}) shares these too, rather than keeping the
 * byte-for-byte duplicates {@code createSectionHeader}/{@code primaryLabel} used to be.
 */
public final class EditorLabels {

    private EditorLabels() {}

    public static Label sectionHeader(String text) {
        Label header = new Label(text);
        header.getStyleClass().add("fp-section-header");
        header.setStyle("-fx-font-size: 10;");
        header.setPadding(new Insets(4, 0, 0, 0));
        return header;
    }

    public static Label styledLabel(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }

    /**
     * The gating panel's reference line: which slide is the reference and what that means on the
     * open slide. {@code axis0} is the shown gate's first-axis alignment on the open slide (the
     * factor its reference thresholds are multiplied by here), or null when no gate is shown;
     * {@code slideName} is the open slide's name, or null when none resolves.
     */
    public static String referenceLine(String referenceName, String slideName, boolean onReference, Alignment axis0) {
        if (referenceName == null) return "No reference slide — thresholds are not corrected between slides";
        String head = "★ Reference: " + referenceName;
        if (onReference) return head + " — you are on it; edits move every slide";
        if (slideName == null || axis0 == null) return head;
        return switch (axis0.kind()) {
            case IDENTITY -> head + " · this slide not corrected";
            case AUTO -> head + " · this slide ×" + factor(axis0.factor()) + " (automatic)";
            case LANDMARK -> head + " · this slide ×" + factor(axis0.factor()) + " (picked peak)";
        };
    }

    /** Two decimals; two significant digits below 0.1, so a strong correction never reads ×0.00. */
    private static String factor(double f) {
        return Math.abs(f) >= 0.1 || f == 0 ? String.format(Locale.US, "%.2f", f) : String.format(Locale.US, "%.2g", f);
    }
}
