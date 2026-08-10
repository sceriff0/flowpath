package qupath.ext.flowpath.umap;

import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.stage.Window;
import qupath.ext.flowpath.umap.ui.UmapPane;
import qupath.lib.gui.QuPathGUI;

/**
 * Owns the single floating UMAP stage and its lifecycle.
 * <p>
 * The gating pane holds one of these for its whole life and calls {@link #open} when the
 * user asks for a UMAP, then {@link #push} whenever the phenotyping changes. Keeping the
 * stage in one place means "open UMAP" is idempotent — a second click focuses the window
 * that is already open rather than stacking a second copy of a view that can hold an
 * entire slide's embedding in memory.
 * <p>
 * All methods must be called on the JavaFX application thread.
 */
public final class UmapWindow {

    private Stage stage;
    private UmapPane pane;

    /**
     * Show the UMAP view for a phenotype snapshot, creating the window on first use and
     * focusing it thereafter. Re-opening with a newer snapshot refreshes the existing
     * window in place.
     *
     * @param qupath   the QuPath GUI, used as the stage owner and for viewer linking
     * @param snapshot the current phenotyping; must not be {@code null}
     * @param owner    window to centre on, or {@code null} to centre on QuPath
     */
    public void open(QuPathGUI qupath, PhenotypeSnapshot snapshot, Window owner) {
        if (stage != null && stage.isShowing()) {
            pane.applySnapshot(snapshot);
            stage.toFront();
            stage.requestFocus();
            return;
        }
        // A stage that was closed leaves its reference behind; drop it rather than
        // trying to re-show it, because its scene graph has already been torn down.
        disposeStage();

        pane = new UmapPane(qupath);
        stage = new Stage();
        stage.setTitle("FlowPath — UMAP");
        stage.initOwner(owner != null ? owner : qupath.getStage());
        stage.setScene(new Scene(pane, 1180, 760));
        stage.setMinWidth(880);
        stage.setMinHeight(620);
        stage.setOnCloseRequest(e -> disposeStage());

        pane.applySnapshot(snapshot);
        stage.show();
    }

    /**
     * Push an updated snapshot into an already-open window; a no-op when closed.
     * <p>
     * Deliberately does not open the window. The gating pane recomputes on every slider
     * drag, and a UMAP window that sprang open on its own the first time a gate moved
     * would be an ambush rather than a feature.
     */
    public void push(PhenotypeSnapshot snapshot) {
        if (stage != null && stage.isShowing() && pane != null) {
            pane.applySnapshot(snapshot);
        }
    }

    /** {@code true} when the UMAP window is currently on screen. */
    public boolean isShowing() {
        return stage != null && stage.isShowing();
    }

    /** Close the window and release its data. Safe to call when already closed. */
    public void close() {
        if (stage != null) {
            stage.close();
        }
        disposeStage();
    }

    private void disposeStage() {
        if (pane != null) {
            try {
                pane.shutdown();
            } finally {
                pane = null;
            }
        }
        stage = null;
    }
}
