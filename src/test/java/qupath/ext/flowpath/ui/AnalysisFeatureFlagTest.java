package qupath.ext.flowpath.ui;

import javafx.scene.Node;
import javafx.scene.control.Button;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.testing.FxTestSupport;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Analysis half of the extension is complete but withheld from this release, so the
 * toolbar button that opens it must be genuinely inert — not merely greyed out.
 * <p>
 * This is {@code UmapFeatureFlagTest} applied to the second withheld feature, and it pins
 * the same distinction: a button that is only {@code setDisable(true)} still holds a live
 * action handler, so anything that later re-enables it opens a window this release does
 * not ship. {@link FlowPathPane#createAnalysisControl} therefore removes the handler as
 * well.
 * <p>
 * They reach {@code createAnalysisControl} rather than {@code FlowPathPane} itself because
 * the pane's constructor needs a live {@code QuPathGUI} and cannot be built in the suite
 * — which is exactly how a regression here would otherwise go unnoticed.
 */
class AnalysisFeatureFlagTest {

    @BeforeAll
    static void toolkit() throws InterruptedException {
        FxTestSupport.startToolkit();
    }

    /**
     * The tripwire. Flipping the flag is a release decision, and it must not happen as a
     * side effect of an unrelated edit.
     * <p>
     * The failure message names the collateral deliberately: Analysis has one entry point
     * UMAP does not, the population-selection listener, which is wired from the pane's
     * constructor on a tree selection rather than on a button press.
     */
    @Test
    void analysisIsHeldBackInThisRelease() {
        assertFalse(FlowPathPane.ANALYSIS_ENABLED,
                "The Analysis window is withheld from this release. If this is a deliberate "
                + "re-enable, also restore: the population-selection listener wired in the "
                + "FlowPathPane constructor, the forward half of that link in "
                + "onTreeSelectionChanged(), the per-pass push in onPreviewUpdated(), and the "
                + "population-statistics sentence in FlowPathExtension.DESCRIPTION, "
                + "catalog.json and build.gradle.kts.");
    }

    @Test
    void theButtonIsDisabled() {
        Button button = FxTestSupport.onFx(
                () -> FlowPathPane.createAnalysisControl(() -> {}).button());
        assertTrue(button.isDisable(), "the Analysis button must not be pressable");
    }

    /**
     * The part a {@code setDisable(true)} alone would miss: with no handler attached,
     * re-enabling the button by any route still cannot open the window.
     */
    @Test
    void theButtonCarriesNoActionHandler() {
        AtomicInteger opened = new AtomicInteger();
        Button button = FxTestSupport.onFx(
                () -> FlowPathPane.createAnalysisControl(opened::incrementAndGet).button());

        assertNull(button.getOnAction(), "a withheld feature must not keep a live handler");

        // Belt and braces: even forced past the disabled state and fired directly, nothing
        // runs. fire() is a no-op on a disabled button, so the enable is what makes this
        // assertion meaningful rather than vacuous.
        FxTestSupport.onFxRun(() -> {
            button.setDisable(false);
            button.fire();
        });
        assertFalse(opened.get() > 0, "firing the Analysis button must not open the window");
    }

    /**
     * A disabled JavaFX node receives no mouse events, so a tooltip set on the button
     * itself would never appear and the user would face an unexplained dead control. The
     * explanation therefore lives on an enabled wrapper.
     */
    @Test
    void theExplanationIsReachableOnAnEnabledWrapper() {
        FlowPathPane.AnalysisControl control = FxTestSupport.onFx(
                () -> FlowPathPane.createAnalysisControl(() -> {}));

        assertNotSame(control.button(), control.slot(),
                "the button must be wrapped, or its tooltip is unreachable while disabled");
        Node slot = control.slot();
        assertFalse(slot.isDisable(), "the wrapper must stay enabled to receive hover events");
        assertTrue(slot.getProperties().values().stream()
                        .anyMatch(v -> v instanceof javafx.scene.control.Tooltip),
                "the wrapper must carry the tooltip explaining why the button is inert");
    }

    /** The label says so without needing a hover at all. */
    @Test
    void theLabelSaysTheFeatureIsComing() {
        String text = FxTestSupport.onFx(
                () -> FlowPathPane.createAnalysisControl(() -> {}).button().getText());
        assertTrue(text.toLowerCase().contains("coming"),
                "the label should explain the disabled state, but was: " + text);
    }

    /**
     * When the feature is switched back on the wrapper disappears and the toolbar holds
     * the button directly. Guards against the wrapper becoming permanent scaffolding.
     */
    @Test
    void theWrapperIsOnlyThereWhileTheFeatureIsOff() {
        FlowPathPane.AnalysisControl control = FxTestSupport.onFx(
                () -> FlowPathPane.createAnalysisControl(() -> {}));
        if (FlowPathPane.ANALYSIS_ENABLED) {
            assertSame(control.button(), control.slot());
        } else {
            assertNotSame(control.button(), control.slot());
        }
    }
}
