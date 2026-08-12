package qupath.ext.flowpath.umap.ui;

import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import qupath.ext.flowpath.umap.session.UmapSession;
import qupath.ext.flowpath.umap.session.ViewState;

import java.util.List;
import java.util.Objects;

/**
 * Applies {@link UmapSession#viewState()} to the panel's widgets. It decides nothing.
 *
 * <h2>Why {@code setState} is gone</h2>
 * This class used to expose {@code setState(UiState)} and claim in its own javadoc to be
 * "the ONE place where Compute/Cancel/Draw/Clear/Tag/Export enable/disable/visibility is
 * decided". That was false inside its own scope — Export, the empty state's second Run
 * button, the Draw toggle and the progress indicator were all set from
 * {@code UmapPane} — and false in the more important sense too: taking the state as an
 * <em>argument</em> made every caller a decision-maker. {@code UmapPane} answered
 * "which state?" in {@code computeRestingState()}, again in {@code clearPolygon()} with
 * the gate-mask branch missing, and a third time in the export path.
 * <p>
 * {@link #sync()} takes no argument. There is no longer any way for a caller to express a
 * state at all, so a caller's state cannot contradict the session's — the only expressible
 * mistake is failing to call {@code sync()}, which leaves the widgets stale rather than
 * lying about a different session. The rule itself lives in
 * {@link UmapSession#viewState()}, is a pure function, and is table-tested without a
 * toolkit.
 *
 * <h2>What each control is bound to</h2>
 * <pre>
 * Run UMAP (toolbar and empty state)  canCompute
 * Cancel (visible + managed)          canCancel
 * Progress indicator / bar / stage    canCancel
 * Draw, Clear Shape                   canGate
 * Tag name / colour / Tag Selection   canTag
 * Export Data                         canExport
 * Features…, marker + scale combos,   canEditInputs
 *   quality preset, k, epochs,
 *   subsample, max cells, scaling
 * Empty-state overlay                 showEmptyState
 * Annotation filter (visible)         standalone
 * </pre>
 *
 * <p>The Draw toggle's <em>selected</em> flag is deliberately absent: it mirrors
 * {@code PolygonSelector}'s active flag, which the selector now reports directly. Five
 * places used to set it by hand.
 */
public final class UiStateController {

    /**
     * Every widget whose enablement or visibility follows the session.
     * <p>
     * A record rather than a long constructor so that adding a control to the machine is a
     * compile error at the one call site that builds it, rather than a control quietly left
     * out — which is how {@code featuresButton}, {@code markerDropdown}, the spinners and
     * the scaling combo came to be ignored entirely, and with them the mid-run-edit hole.
     *
     * @param inputs         everything that changes what the <em>next</em> run reads: the
     *                       feature picker and every embedding parameter. Locked while a
     *                       run is in flight
     * @param dismissPopups  closes any popup hosting one of {@code inputs}. Disabling the
     *                       button that opens the feature picker does nothing for a picker
     *                       that is already open over the plot
     */
    public record Controls(Button compute,
                           Button cancel,
                           ProgressIndicator progressIndicator,
                           ProgressBar computeProgress,
                           Label computeStage,
                           ToggleButton draw,
                           Button clear,
                           TextField tagName,
                           ColorPicker tagColor,
                           Button applyTag,
                           Button export,
                           Node emptyState,
                           Button emptyAction,
                           Node roiFilter,
                           List<Node> inputs,
                           Runnable dismissPopups) {

        public Controls {
            Objects.requireNonNull(compute, "compute");
            Objects.requireNonNull(cancel, "cancel");
            Objects.requireNonNull(progressIndicator, "progressIndicator");
            Objects.requireNonNull(computeProgress, "computeProgress");
            Objects.requireNonNull(computeStage, "computeStage");
            Objects.requireNonNull(draw, "draw");
            Objects.requireNonNull(clear, "clear");
            Objects.requireNonNull(tagName, "tagName");
            Objects.requireNonNull(tagColor, "tagColor");
            Objects.requireNonNull(applyTag, "applyTag");
            Objects.requireNonNull(export, "export");
            Objects.requireNonNull(emptyState, "emptyState");
            Objects.requireNonNull(emptyAction, "emptyAction");
            Objects.requireNonNull(roiFilter, "roiFilter");
            inputs = List.copyOf(inputs);
            Objects.requireNonNull(dismissPopups, "dismissPopups");
        }
    }

    private final UmapSession session;
    private final Controls controls;

    /**
     * Read-only to the outside. Exposing the mutable property — even typed as
     * {@link ReadOnlyObjectProperty} — let a caller cast it back and set a fabricated state
     * that {@link #current()} would then report to {@code UmapPane.refreshOverview}, which
     * is the one thing this class exists to make impossible.
     */
    private final ReadOnlyObjectWrapper<ViewState> state;

    public UiStateController(UmapSession session, Controls controls) {
        this.session = Objects.requireNonNull(session, "session");
        this.controls = Objects.requireNonNull(controls, "controls");
        this.state = new ReadOnlyObjectWrapper<>(this, "state", session.viewState());
        apply(state.get(), null);
    }

    /**
     * Re-derive the panel's state from the session and apply it. Idempotent, and cheap
     * enough to call after anything at all — which is the point: a caller that is unsure
     * whether something changed should call it rather than guess a state.
     */
    public void sync() {
        ViewState previous = state.get();
        ViewState now = session.viewState();
        apply(now, previous);
        state.set(now);
    }

    /** The state most recently applied. */
    public ViewState current() {
        return state.get();
    }

    /** Observable {@link #current()}, for callers that repaint on every transition. */
    public ReadOnlyObjectProperty<ViewState> stateProperty() {
        return state.getReadOnlyProperty();
    }

    /**
     * Interim guard while {@link #sync()} still has to be <em>called</em>: assert that the
     * widgets agree with the session at the end of a handler that touched it.
     * <p>
     * The remaining expressible mistake in this design is forgetting to sync, which leaves
     * the panel stale. Until the session pushes its own state (the observer design deferred
     * to the next task), this turns that omission from a wrong button into a stack trace at
     * the handler that caused it.
     *
     * @throws IllegalStateException when the applied state is not the one the session
     *                               currently derives
     */
    public void assertSynced() {
        ViewState derived = session.viewState();
        if (!derived.equals(state.get())) {
            throw new IllegalStateException(
                    "UI state is stale: the session derives " + derived
                            + " but the panel is showing " + state.get()
                            + ". A handler mutated the session without calling sync().");
        }
    }

    // ------------------------------------------------------------------

    private void apply(ViewState s, ViewState previous) {
        controls.compute().setDisable(!s.canCompute());
        controls.emptyAction().setDisable(!s.canCompute());

        controls.cancel().setVisible(s.canCancel());
        controls.cancel().setManaged(s.canCancel());
        controls.progressIndicator().setVisible(s.canCancel());

        // The bar and its stage line sit under Run UMAP rather than in a floating dialog,
        // so they are shown and hidden with the run itself. Indeterminate on purpose:
        // SMILE reports phase transitions, not a fraction, and a bar that invented a
        // percentage would be lying about the longest wait in the application.
        controls.computeProgress().setVisible(s.canCancel());
        controls.computeProgress().setManaged(s.canCancel());
        controls.computeStage().setVisible(s.canCancel());
        controls.computeStage().setManaged(s.canCancel());
        if (s.canCancel()) {
            controls.computeProgress().setProgress(ProgressBar.INDETERMINATE_PROGRESS);
            if (previous == null || !previous.canCancel()) {
                controls.computeStage().setText("Starting…");
            }
        } else {
            controls.computeStage().setText("");
        }

        controls.draw().setDisable(!s.canGate());
        controls.clear().setDisable(!s.canGate());

        controls.tagName().setDisable(!s.canTag());
        controls.tagColor().setDisable(!s.canTag());
        controls.applyTag().setDisable(!s.canTag());

        controls.export().setDisable(!s.canExport());

        for (Node input : controls.inputs()) {
            input.setDisable(!s.canEditInputs());
        }
        if (!s.canEditInputs()) {
            controls.dismissPopups().run();
        }

        controls.emptyState().setVisible(s.showEmptyState());
        controls.emptyState().setManaged(s.showEmptyState());
        controls.emptyAction().setVisible(s.offerFirstRun());
        controls.emptyAction().setManaged(s.offerFirstRun());

        // The annotation filter belongs to the gating pane in snapshot mode — it has
        // already been applied there, and a second checkbox offering the same choice would
        // either do nothing or silently disagree with the gating window.
        controls.roiFilter().setVisible(s.standalone());
        controls.roiFilter().setManaged(s.standalone());
    }
}
