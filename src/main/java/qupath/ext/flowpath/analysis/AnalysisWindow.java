package qupath.ext.flowpath.analysis;

import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.stage.Window;
import qupath.ext.flowpath.analysis.session.AnalysisSession;
import qupath.ext.flowpath.analysis.ui.AnalysisPane;
import qupath.lib.gui.QuPathGUI;

/**
 * Owns the single floating Analysis stage and its lifecycle.
 * <p>
 * Mirrors {@code UmapWindow} exactly — see that class. The gating pane holds one of these
 * for its whole life and calls {@link #open} when the user asks for the Analysis window,
 * then {@link #push} whenever the gating changes. Keeping the stage in one place means
 * "open Analysis" is idempotent — a second click focuses the window that is already open
 * rather than stacking a second copy of the same report.
 * <p>
 * All methods must be called on the JavaFX application thread.
 */
public final class AnalysisWindow {

    private final AnalysisSession session = new AnalysisSession();

    private Stage stage;
    private AnalysisPane pane;

    /**
     * Show the Analysis view for a gating pass, creating the window on first use and
     * focusing it thereafter. Re-opening with a newer pass refreshes the existing window
     * in place.
     *
     * @param qupath the QuPath GUI, used as the stage owner
     * @param input  the current gating pass; must not be {@code null}
     * @param owner  window to centre on, or {@code null} to centre on QuPath
     */
    public void open(QuPathGUI qupath, AnalysisSession.AnalysisInput input, Window owner) {
        if (stage != null && stage.isShowing()) {
            pane.accept(input);
            stage.toFront();
            stage.requestFocus();
            return;
        }
        // A stage that was closed leaves its reference behind; drop it rather than
        // trying to re-show it, because its scene graph has already been torn down.
        disposeStage();

        pane = new AnalysisPane(session);
        stage = new Stage();
        stage.setTitle("FlowPath — Analysis");
        stage.initOwner(owner != null ? owner : qupath.getStage());
        stage.setScene(new Scene(pane, 960, 640));
        stage.setMinWidth(720);
        stage.setMinHeight(480);
        stage.setOnCloseRequest(e -> disposeStage());

        pane.accept(input);
        stage.show();
    }

    /**
     * Push an updated gating pass into an already-open window; a no-op when closed.
     * <p>
     * Deliberately does not open the window. The gating pane recomputes on every gate
     * edit, and an Analysis window that sprang open on its own the first time a gate moved
     * would be an ambush rather than a feature.
     */
    public void push(AnalysisSession.AnalysisInput input) {
        if (stage != null && stage.isShowing() && pane != null) {
            pane.accept(input);
        }
    }

    /** {@code true} when the Analysis window is currently on screen. */
    public boolean isShowing() {
        return stage != null && stage.isShowing();
    }

    /** Close the window and release its pane. Safe to call when already closed. */
    public void close() {
        if (stage != null) {
            stage.close();
        }
        disposeStage();
    }

    private void disposeStage() {
        pane = null;
        stage = null;
    }
}
