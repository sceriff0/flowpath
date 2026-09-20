package qupath.ext.flowpath.ui.editor;

import javafx.geometry.Insets;
import javafx.scene.control.Label;

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
}
