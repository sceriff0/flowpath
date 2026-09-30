package qupath.ext.flowpath.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.Objects;

/** The side panel's cohort status: one line and two buttons; the review itself is in the Cohort window. */
final class CohortCard extends VBox {

    final Label status = new Label();
    final Button open = new Button("Open cohort…");
    final Button runAll = new Button("Run on all slides…");

    CohortCard() {
        super(4);
        getStyleClass().add("fp-panel");
        status.setMnemonicParsing(false);
        open.setMnemonicParsing(false);
        status.getStyleClass().add("fp-primary-text");
        status.setWrapText(true);
        HBox buttons = new HBox(4, open, runAll);
        buttons.setAlignment(Pos.CENTER_LEFT);
        getChildren().addAll(status, buttons);
    }

    void render(String statusLine, String openText, boolean visible) {
        status.setText(statusLine);
        open.setText(openText);
        setVisible(visible);
        setManaged(visible);
    }

    void setOnOpen(Runnable r) { Objects.requireNonNull(r); open.setOnAction(e -> r.run()); }

    Button runAllButton() { return runAll; }
}
