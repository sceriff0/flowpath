package qupath.ext.flowpath.umap.ui;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import qupath.ext.flowpath.umap.ui.UiStateController.UiState;

import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link UiStateController} state-machine transitions.
 * <p>
 * Strategy: instantiate bare JavaFX controls (no scene), apply each state, and
 * assert the resulting {@code disabled}/{@code visible}/{@code managed} property
 * values. The JavaFX toolkit is initialized once in {@link #initToolkit()}
 * because {@link ColorPicker} touches CSS during construction.
 */
class UiStateControllerTest {

    private Button computeButton;
    private Button cancelButton;
    private ProgressIndicator progressIndicator;
    private ToggleButton drawButton;
    private Button clearButton;
    private TextField tagNameField;
    private ColorPicker tagColorPicker;
    private Button applyTagButton;
    private Button exportButton;

    private UiStateController controller;

    @BeforeAll
    static void initToolkit() throws InterruptedException {
        try {
            CountDownLatch latch = new CountDownLatch(1);
            Platform.startup(latch::countDown);
            latch.await();
        } catch (IllegalStateException alreadyStarted) {
            // Toolkit was already initialized (e.g. by a previous test class) — fine.
        }
    }

    @BeforeEach
    void setUp() {
        computeButton = new Button();
        cancelButton = new Button();
        progressIndicator = new ProgressIndicator();
        drawButton = new ToggleButton();
        clearButton = new Button();
        tagNameField = new TextField();
        tagColorPicker = new ColorPicker();
        applyTagButton = new Button();
        exportButton = new Button();

        controller = new UiStateController(
                computeButton, cancelButton, progressIndicator,
                drawButton, clearButton,
                tagNameField, tagColorPicker, applyTagButton,
                exportButton);
    }

    // ---------- per-state contracts ----------

    @Test
    @DisplayName("NO_IMAGE disables every actionable control and hides cancel/progress")
    void noImageState() {
        controller.setState(UiState.NO_IMAGE);

        assertTrue(computeButton.isDisabled(), "computeButton should be disabled");
        assertCancelHidden();
        assertFalse(progressIndicator.isVisible());
        assertTrue(drawButton.isDisabled());
        assertTrue(clearButton.isDisabled());
        assertTrue(tagNameField.isDisabled());
        assertTrue(tagColorPicker.isDisabled());
        assertTrue(applyTagButton.isDisabled());
        assertTrue(exportButton.isDisabled());
        assertFalse(drawButton.isSelected());
        assertEquals(UiState.NO_IMAGE, controller.currentState());
    }

    @Test
    @DisplayName("READY enables compute only; gating/tag/export remain disabled")
    void readyState() {
        controller.setState(UiState.READY);

        assertFalse(computeButton.isDisabled(), "computeButton should be enabled");
        assertCancelHidden();
        assertFalse(progressIndicator.isVisible());
        assertTrue(drawButton.isDisabled());
        assertTrue(clearButton.isDisabled());
        assertTrue(tagNameField.isDisabled());
        assertTrue(tagColorPicker.isDisabled());
        assertTrue(applyTagButton.isDisabled());
        assertTrue(exportButton.isDisabled());
        assertEquals(UiState.READY, controller.currentState());
    }

    @Test
    @DisplayName("COMPUTING disables compute, exposes cancel, shows progress")
    void computingState() {
        controller.setState(UiState.COMPUTING);

        assertTrue(computeButton.isDisabled());
        assertCancelVisible();
        assertFalse(cancelButton.isDisabled(), "cancelButton must be clickable while computing");
        assertTrue(progressIndicator.isVisible());

        // Gating / tag / export are all locked during a compute
        assertTrue(drawButton.isDisabled());
        assertTrue(clearButton.isDisabled());
        assertTrue(tagNameField.isDisabled());
        assertTrue(tagColorPicker.isDisabled());
        assertTrue(applyTagButton.isDisabled());
        assertTrue(exportButton.isDisabled());
        assertEquals(UiState.COMPUTING, controller.currentState());
    }

    @Test
    @DisplayName("COMPUTED enables gating + export; tag controls await polygon")
    void computedState() {
        controller.setState(UiState.COMPUTED);

        assertFalse(computeButton.isDisabled(), "compute remains enabled for re-computation");
        assertCancelHidden();
        assertFalse(progressIndicator.isVisible());
        assertFalse(drawButton.isDisabled());
        assertFalse(clearButton.isDisabled());
        // Tag controls only unlock once a polygon is closed (GATING)
        assertTrue(tagNameField.isDisabled());
        assertTrue(tagColorPicker.isDisabled());
        assertTrue(applyTagButton.isDisabled());
        assertFalse(exportButton.isDisabled());
        assertFalse(drawButton.isSelected(), "drawButton starts unselected after compute");
        assertEquals(UiState.COMPUTED, controller.currentState());
    }

    @Test
    @DisplayName("GATING unlocks tag fields while keeping compute/clear/draw available")
    void gatingState() {
        controller.setState(UiState.GATING);

        assertFalse(computeButton.isDisabled());
        assertCancelHidden();
        assertFalse(drawButton.isDisabled());
        assertFalse(clearButton.isDisabled());
        assertFalse(tagNameField.isDisabled(), "tag name field must be writable in GATING");
        assertFalse(tagColorPicker.isDisabled());
        assertFalse(applyTagButton.isDisabled());
        assertFalse(exportButton.isDisabled());
        assertEquals(UiState.GATING, controller.currentState());
    }

    @Test
    @DisplayName("TAGGED mirrors COMPUTED — tag controls re-locked after Apply Tag")
    void taggedState() {
        controller.setState(UiState.TAGGED);

        assertFalse(computeButton.isDisabled());
        assertCancelHidden();
        assertFalse(progressIndicator.isVisible());
        assertFalse(drawButton.isDisabled());
        assertFalse(clearButton.isDisabled());
        assertTrue(tagNameField.isDisabled(), "tag fields lock again after applying tag");
        assertTrue(tagColorPicker.isDisabled());
        assertTrue(applyTagButton.isDisabled());
        assertFalse(exportButton.isDisabled());
        assertFalse(drawButton.isSelected());
        assertEquals(UiState.TAGGED, controller.currentState());
    }

    // ---------- transition / invariant tests ----------

    @Test
    @DisplayName("Transitioning back to NO_IMAGE re-locks every control after COMPUTED")
    void teardownOnImageRemoval() {
        controller.setState(UiState.COMPUTED);
        controller.setState(UiState.NO_IMAGE);

        assertTrue(computeButton.isDisabled());
        assertTrue(drawButton.isDisabled());
        assertTrue(clearButton.isDisabled());
        assertTrue(tagNameField.isDisabled());
        assertTrue(tagColorPicker.isDisabled());
        assertTrue(applyTagButton.isDisabled());
        assertTrue(exportButton.isDisabled());
        assertFalse(drawButton.isSelected());
    }

    @Test
    @DisplayName("Round-trip GATING -> COMPUTED -> GATING re-enables tag fields each time")
    void gatingRoundTrip() {
        controller.setState(UiState.COMPUTED);
        assertTrue(applyTagButton.isDisabled());

        controller.setState(UiState.GATING);
        assertFalse(applyTagButton.isDisabled());

        controller.setState(UiState.COMPUTED);
        assertTrue(applyTagButton.isDisabled(), "re-clearing polygon must lock tag again");

        controller.setState(UiState.GATING);
        assertFalse(applyTagButton.isDisabled());
    }

    @Test
    @DisplayName("currentStateProperty fires when state changes")
    void propertyFiresOnTransition() {
        var seen = new java.util.concurrent.atomic.AtomicReference<UiState>();
        controller.currentStateProperty().addListener((obs, oldVal, newVal) -> seen.set(newVal));

        controller.setState(UiState.READY);
        assertEquals(UiState.READY, seen.get());

        controller.setState(UiState.COMPUTING);
        assertEquals(UiState.COMPUTING, seen.get());
    }

    @Test
    @DisplayName("setState rejects null")
    void setStateRejectsNull() {
        assertThrows(NullPointerException.class, () -> controller.setState(null));
    }

    /**
     * Critical invariant (universal): Compute-enabled and Cancel-visible must
     * NEVER both be true simultaneously. Allowing both would let the user click
     * two conflicting actions in the same frame — Compute would re-trigger a UMAP
     * run while Cancel is asking the prior run to abort.
     */
    @ParameterizedTest
    @EnumSource(UiState.class)
    @DisplayName("Compute-enabled and Cancel-visible are never both true")
    void computeAndCancelAreNeverBothTrue(UiState state) {
        controller.setState(state);

        boolean computeEnabled = !computeButton.isDisabled();
        boolean cancelVisible = cancelButton.isVisible();

        assertFalse(computeEnabled && cancelVisible,
                "State " + state + ": both compute (enabled=" + computeEnabled
                        + ") and cancel (visible=" + cancelVisible + ") cannot be active");
    }

    /**
     * Companion invariant (interactive states): when the panel is interactive
     * (anything other than NO_IMAGE), the user must always have a forward path —
     * either Compute is clickable (idle) or Cancel is visible (running). Both
     * cannot be inert at the same time, otherwise the panel deadlocks.
     */
    @ParameterizedTest
    @EnumSource(value = UiState.class, names = "NO_IMAGE", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("Compute or Cancel is always offered in interactive states")
    void computeOrCancelOfferedInInteractiveStates(UiState state) {
        controller.setState(state);

        boolean computeEnabled = !computeButton.isDisabled();
        boolean cancelVisible = cancelButton.isVisible();

        assertTrue(computeEnabled || cancelVisible,
                "State " + state + ": neither compute nor cancel is actionable");
    }

    /**
     * Companion invariant: cancelButton's {@code managed} flag must track its
     * {@code visible} flag, otherwise the layout reserves space for a hidden control.
     */
    @ParameterizedTest
    @EnumSource(UiState.class)
    @DisplayName("cancelButton.managed always tracks cancelButton.visible")
    void cancelManagedTracksVisible(UiState state) {
        controller.setState(state);
        assertEquals(cancelButton.isVisible(), cancelButton.isManaged(),
                "State " + state + ": cancel managed/visible drift");
    }

    // ---------- helpers ----------

    private void assertCancelHidden() {
        assertFalse(cancelButton.isVisible(), "cancelButton should be hidden");
        assertFalse(cancelButton.isManaged(), "cancelButton should not be managed when hidden");
    }

    private void assertCancelVisible() {
        assertTrue(cancelButton.isVisible(), "cancelButton should be visible");
        assertTrue(cancelButton.isManaged(), "cancelButton should be managed when visible");
    }
}
