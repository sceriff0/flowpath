package qupath.ext.flowpath.analysis;

import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.stage.Window;
import qupath.ext.flowpath.analysis.session.AnalysisSession;
import qupath.ext.flowpath.analysis.ui.AnalysisPane;
import qupath.ext.flowpath.analysis.ui.PopulationRef;
import qupath.lib.gui.QuPathGUI;

import java.util.function.Consumer;

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
     * The host's callback for Task 14's forward direction — a population picked inside the
     * Analysis pane (a table row, or a plot bar click). Held here, not only handed to
     * {@link AnalysisPane}, because the pane itself is rebuilt by every {@link #open} call
     * after a close (see {@link #disposeStage}); a handler installed only on the pane that
     * happened to exist when {@code setPopulationSelectionListener} was called would silently
     * stop firing the moment the window was closed and reopened.
     */
    private Consumer<PopulationRef> populationSelectionListener;

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
            stage.setTitle(titleFor(input));
            stage.toFront();
            stage.requestFocus();
            return;
        }
        // A stage that was closed leaves its reference behind; drop it rather than
        // trying to re-show it, because its scene graph has already been torn down.
        disposeStage();

        pane = new AnalysisPane(session);
        pane.setOnPopulationSelected(populationSelectionListener);
        stage = new Stage();
        stage.setTitle(titleFor(input));
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
     * <p>
     * Also re-sets the title, not only the pane's content — a live-preview push can move a
     * report from one image to another (the gating pane holds one {@code AnalysisWindow} for
     * its whole life, across images), and a title set once at {@link #open} would keep
     * naming the image the window was first opened on.
     */
    public void push(AnalysisSession.AnalysisInput input) {
        if (stage != null && stage.isShowing() && pane != null) {
            pane.accept(input);
            stage.setTitle(titleFor(input));
        }
    }

    /**
     * {@code "FlowPath — Analysis — " + imageName} when the pass names an image, the plain
     * title otherwise — a QuPath image can genuinely have no name, so a blank/{@code null}
     * name falls back rather than appending an empty suffix.
     */
    private static String titleFor(AnalysisSession.AnalysisInput input) {
        String imageName = input == null ? null : input.imageName();
        return imageName == null || imageName.isBlank()
                ? "FlowPath — Analysis"
                : "FlowPath — Analysis — " + imageName;
    }

    /**
     * Install the host's callback for Task 14's forward direction: a population picked inside
     * the Analysis pane (a table row selection, or a plot bar click) is reported here. Wired
     * onto the pane immediately if one already exists (the window is open), and onto every
     * pane {@link #open} goes on to build afterward — see {@link #populationSelectionListener}'s
     * own javadoc for why the field, not just the pane, is what this method sets.
     *
     * @param handler called with the population's {@code (rootIndex, path)}; {@code
     *                 FlowPathPane} is the one production caller, resolving it against the
     *                 live gate tree via {@code GateTree.findBranch} and selecting the
     *                 matching tree item
     */
    public void setPopulationSelectionListener(Consumer<PopulationRef> handler) {
        this.populationSelectionListener = handler;
        if (pane != null) {
            pane.setOnPopulationSelected(handler);
        }
    }

    /**
     * Land the Analysis pane's own selection (table row plus both comparison plots) on {@code
     * ref} — Task 14's reverse direction, driven by the gate tree's own selection changing. A
     * no-op while the window is closed: there is no pane to apply it to, and re-opening later
     * does not replay a selection that arrived while nothing was open, the same way {@link
     * #push} does not open the window on its own.
     */
    public void selectPopulation(PopulationRef ref) {
        if (stage != null && stage.isShowing() && pane != null) {
            pane.selectPopulation(ref);
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
