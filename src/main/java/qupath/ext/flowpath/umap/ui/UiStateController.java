package qupath.ext.flowpath.umap.ui;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.control.Button;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;

import java.util.Objects;

/**
 * Central state machine for {@link UmapPane} control enable/disable/visibility.
 * <p>
 * Eliminates the duplicated {@code setDisable}/{@code setVisible}/{@code setManaged}
 * blocks that were previously scattered across {@code initializeFromImage},
 * {@code runUmap}, {@code cancelUmap}, {@code onUmapComplete}, {@code onUmapError},
 * {@code onPolygonComplete}, {@code clearPolygon} and {@code applyPopulationTag}.
 * <p>
 * State transitions are explicit and testable. All controls referenced by this
 * class are passed in at construction time and never re-bound.
 *
 * <h2>State transition table</h2>
 * <pre>
 * State       compute  cancel(vis)  progress  draw   clear  tagFields  apply  export
 * NO_IMAGE     OFF      hidden       hidden   OFF    OFF    OFF        OFF    OFF
 * READY        ON       hidden       hidden   OFF    OFF    OFF        OFF    OFF
 * COMPUTING    OFF      VISIBLE+ON   visible  OFF    OFF    OFF        OFF    OFF
 * COMPUTED     ON       hidden       hidden   ON     ON     OFF        OFF    ON
 * GATING       ON       hidden       hidden   ON     ON     ON         ON     ON
 * TAGGED       ON       hidden       hidden   ON     ON     OFF        OFF    ON
 * </pre>
 *
 * <p>Note: {@code compute} and {@code cancel} are mutually exclusive — in every
 * state, exactly one is offered to the user (compute enabled OR cancel visible,
 * never both, never neither). This invariant is asserted in the test suite.
 */
public final class UiStateController {

    /**
     * High-level UI states for the qUMAP panel.
     */
    public enum UiState {
        /** No QuPath image loaded — almost everything disabled. */
        NO_IMAGE,
        /** Image loaded, cells indexed, ready to compute UMAP. */
        READY,
        /** UMAP running — only Cancel is enabled. */
        COMPUTING,
        /** UMAP done — gating, marker selection, export enabled; tag controls await polygon. */
        COMPUTED,
        /** User has closed a polygon — tag fields are unlocked, awaiting Apply Tag. */
        GATING,
        /** Polygon has been tagged — same controls as COMPUTED, but a tag is active in the legend. */
        TAGGED
    }

    private final Button computeButton;
    private final Button cancelButton;
    private final ProgressIndicator progressIndicator;
    private final ToggleButton drawButton;
    private final Button clearButton;
    private final TextField tagNameField;
    private final ColorPicker tagColorPicker;
    private final Button applyTagButton;
    private final Button exportButton;

    private final ObjectProperty<UiState> currentState =
            new SimpleObjectProperty<>(this, "currentState", UiState.NO_IMAGE);

    public UiStateController(Button computeButton,
                             Button cancelButton,
                             ProgressIndicator progressIndicator,
                             ToggleButton drawButton,
                             Button clearButton,
                             TextField tagNameField,
                             ColorPicker tagColorPicker,
                             Button applyTagButton,
                             Button exportButton) {
        this.computeButton = Objects.requireNonNull(computeButton, "computeButton");
        this.cancelButton = Objects.requireNonNull(cancelButton, "cancelButton");
        this.progressIndicator = Objects.requireNonNull(progressIndicator, "progressIndicator");
        this.drawButton = Objects.requireNonNull(drawButton, "drawButton");
        this.clearButton = Objects.requireNonNull(clearButton, "clearButton");
        this.tagNameField = Objects.requireNonNull(tagNameField, "tagNameField");
        this.tagColorPicker = Objects.requireNonNull(tagColorPicker, "tagColorPicker");
        this.applyTagButton = Objects.requireNonNull(applyTagButton, "applyTagButton");
        this.exportButton = Objects.requireNonNull(exportButton, "exportButton");
    }

    /**
     * Apply the control configuration for the given state.
     * <p>
     * This is the ONE place where Compute/Cancel/Draw/Clear/Tag/Export
     * enable/disable/visibility is decided. All call sites in {@link UmapPane}
     * funnel through here.
     */
    public void setState(UiState newState) {
        Objects.requireNonNull(newState, "newState");
        switch (newState) {
            case NO_IMAGE -> {
                computeButton.setDisable(true);
                hideCancel();
                progressIndicator.setVisible(false);
                disableGatingControls();
                disableTagControls();
                exportButton.setDisable(true);
                drawButton.setSelected(false);
            }
            case READY -> {
                computeButton.setDisable(false);
                hideCancel();
                progressIndicator.setVisible(false);
                disableGatingControls();
                disableTagControls();
                exportButton.setDisable(true);
                drawButton.setSelected(false);
            }
            case COMPUTING -> {
                computeButton.setDisable(true);
                showCancel();
                progressIndicator.setVisible(true);
                disableGatingControls();
                disableTagControls();
                exportButton.setDisable(true);
            }
            case COMPUTED -> {
                computeButton.setDisable(false);
                hideCancel();
                progressIndicator.setVisible(false);
                enableGatingControls();
                disableTagControls();
                exportButton.setDisable(false);
                drawButton.setSelected(false);
            }
            case GATING -> {
                computeButton.setDisable(false);
                hideCancel();
                progressIndicator.setVisible(false);
                enableGatingControls();
                enableTagControls();
                exportButton.setDisable(false);
            }
            case TAGGED -> {
                computeButton.setDisable(false);
                hideCancel();
                progressIndicator.setVisible(false);
                enableGatingControls();
                disableTagControls();
                exportButton.setDisable(false);
                drawButton.setSelected(false);
            }
        }
        currentState.set(newState);
    }

    /**
     * @return the most recently applied state.
     */
    public UiState currentState() {
        return currentState.get();
    }

    /**
     * @return an observable property tracking {@link #currentState()}, for callers
     * that wish to bind UI elements to state changes.
     */
    public ObjectProperty<UiState> currentStateProperty() {
        return currentState;
    }

    // --- private helpers ---

    private void hideCancel() {
        cancelButton.setVisible(false);
        cancelButton.setManaged(false);
    }

    private void showCancel() {
        cancelButton.setVisible(true);
        cancelButton.setManaged(true);
    }

    private void enableGatingControls() {
        drawButton.setDisable(false);
        clearButton.setDisable(false);
    }

    private void disableGatingControls() {
        drawButton.setDisable(true);
        clearButton.setDisable(true);
    }

    private void enableTagControls() {
        tagNameField.setDisable(false);
        tagColorPicker.setDisable(false);
        applyTagButton.setDisable(false);
    }

    private void disableTagControls() {
        tagNameField.setDisable(true);
        tagColorPicker.setDisable(true);
        applyTagButton.setDisable(true);
    }
}
