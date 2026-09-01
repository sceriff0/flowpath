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
 * The UMAP half of the extension is complete but withheld from this release, so the
 * toolbar button that opens it must be genuinely inert — not merely greyed out.
 * <p>
 * The distinction matters. A button that is only {@code setDisable(true)} still holds a
 * live action handler, so anything that later re-enables it (a new call site in
 * {@code onPreviewUpdated}, a stylesheet, a test harness) opens a window this release
 * does not ship. {@link FlowPathPane#createUmapControl} therefore removes the handler as
 * well, and these tests pin both halves.
 * <p>
 * They reach {@code createUmapControl} rather than {@code FlowPathPane} itself because
 * the pane's constructor needs a live {@code QuPathGUI} and cannot be built in the suite
 * — which is exactly how a regression here would otherwise go unnoticed.
 */
class UmapFeatureFlagTest {

    @BeforeAll
    static void toolkit() throws InterruptedException {
        FxTestSupport.startToolkit();
    }

    /**
     * The tripwire. Flipping the flag is a release decision, and it must not happen as a
     * side effect of an unrelated edit: this test fails the moment it does, which is the
     * prompt to re-check the other things the flag governs — the {@code Ctrl+U}
     * accelerator, the snapshot push in {@code onPreviewUpdated()}, and the extension
     * description QuPath shows in its extension manager, which no longer advertises UMAP.
     */
    @Test
    void umapIsHeldBackInThisRelease() {
        assertFalse(FlowPathPane.UMAP_ENABLED,
                "UMAP is withheld from this release. If this is a deliberate re-enable, "
                + "also restore the Ctrl+U accelerator's effect and put UMAP back into "
                + "FlowPathExtension.DESCRIPTION.");
    }

    @Test
    void theButtonIsDisabled() {
        Button button = FxTestSupport.onFx(
                () -> FlowPathPane.createUmapControl(() -> {}).button());
        assertTrue(button.isDisable(), "the UMAP button must not be pressable");
    }

    /**
     * The part a {@code setDisable(true)} alone would miss: with no handler attached,
     * re-enabling the button by any route still cannot open the window.
     */
    @Test
    void theButtonCarriesNoActionHandler() {
        AtomicInteger opened = new AtomicInteger();
        Button button = FxTestSupport.onFx(
                () -> FlowPathPane.createUmapControl(opened::incrementAndGet).button());

        assertNull(button.getOnAction(), "a withheld feature must not keep a live handler");

        // Belt and braces: even forced past the disabled state and fired directly, nothing
        // runs. fire() is a no-op on a disabled button, so the enable is what makes this
        // assertion meaningful rather than vacuous.
        FxTestSupport.onFxRun(() -> {
            button.setDisable(false);
            button.fire();
        });
        assertFalse(opened.get() > 0, "firing the UMAP button must not open the window");
    }

    /**
     * A disabled JavaFX node receives no mouse events, so a tooltip set on the button
     * itself would never appear and the user would face an unexplained dead control. The
     * explanation therefore lives on an enabled wrapper.
     */
    @Test
    void theExplanationIsReachableOnAnEnabledWrapper() {
        FlowPathPane.UmapControl control = FxTestSupport.onFx(
                () -> FlowPathPane.createUmapControl(() -> {}));

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
                () -> FlowPathPane.createUmapControl(() -> {}).button().getText());
        assertTrue(text.toLowerCase().contains("coming"),
                "the label should explain the disabled state, but was: " + text);
    }

    /**
     * When the feature is switched back on the wrapper disappears and the toolbar holds
     * the button directly. Guards against the wrapper becoming permanent scaffolding.
     */
    @Test
    void theWrapperIsOnlyThereWhileTheFeatureIsOff() {
        FlowPathPane.UmapControl control = FxTestSupport.onFx(
                () -> FlowPathPane.createUmapControl(() -> {}));
        if (FlowPathPane.UMAP_ENABLED) {
            assertSame(control.button(), control.slot());
        } else {
            assertNotSame(control.button(), control.slot());
        }
    }
}
