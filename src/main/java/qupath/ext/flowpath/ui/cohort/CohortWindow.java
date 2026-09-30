package qupath.ext.flowpath.ui.cohort;

import javafx.scene.Scene;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * Owns the single floating Cohort stage, the way {@code analysis/AnalysisWindow} owns its own:
 * one field, {@code open}/{@code close}. The pane is the host's and outlives the stage, so its
 * state survives close and reopen; closing evicts it from the old scene by swapping in a
 * throwaway root (a {@link Scene} never releases its root on {@code Stage.close()}).
 */
public final class CohortWindow {

    private Stage stage;

    public void open(Window owner, CohortGridPane pane, String stylesheet) {
        if (stage != null) {
            stage.toFront();
            return;
        }
        Stage s = new Stage();
        s.initOwner(owner);
        s.setTitle("FlowPath — Cohort");
        Scene scene = new Scene(pane, 900, 600);
        if (stylesheet != null) scene.getStylesheets().add(stylesheet);
        s.setScene(scene);
        s.setOnHidden(e -> {
            scene.setRoot(new Pane());
            stage = null;
        });
        stage = s;
        s.show();
    }

    public boolean isOpen() { return stage != null; }

    public void close() {
        if (stage != null) stage.close();
    }
}
