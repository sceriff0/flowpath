package qupath.ext.flowpath.umap.ui;

import javafx.scene.control.ToggleButton;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.testing.FxTestSupport;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * That the Draw toggle follows {@link PolygonSelector} rather than being told about it.
 * <p>
 * The toggle's {@code selected} flag used to be set by hand at eight sites — the Escape
 * handler, the snapshot teardown, the derived-state teardown, the compute teardown and four
 * states of the UI machine — and any path that deactivated the selector without remembering
 * the button left a pressed toggle over a selector that was no longer listening.
 * <p>
 * The binding is exercised through {@link UmapPane#drawToggleFor}, which is the production
 * code, not a copy of its idiom. An earlier version of this file built its own selector and
 * toggle and wired them inline under a comment claiming to be "exactly the wire UmapPane
 * installs" — which pinned the idiom and left the wiring free: deleting the line in
 * {@code UmapPane} kept the whole suite green. Deleting the factory's call site is now a
 * compile error ({@code drawButton} is final) and deleting its body fails
 * {@link #theToggleFollowsTheSelector()}.
 */
class PolygonSelectorFxTest {

    /** Selector plus the recorded active-flag transitions, built on the FX thread. */
    private record Rig(PolygonSelector selector, List<Boolean> seen, ToggleButton drawButton) {}

    private static Rig rig() {
        return FxTestSupport.onFx(() -> {
            var selector = new PolygonSelector(new UmapCanvas());
            var seen = new ArrayList<Boolean>();
            selector.setOnActiveChanged(seen::add);
            return new Rig(selector, seen, null);
        });
    }

    @Test
    @DisplayName("activate/deactivate report the flag, and only on a real change")
    void reportsEveryTransitionOnce() {
        assumeTrue(FxTestSupport.toolkitAvailable());
        var rig = rig();

        FxTestSupport.onFxRun(() -> {
            rig.selector().activate();
            rig.selector().activate();       // already active — nothing changed
            rig.selector().deactivate();
            rig.selector().deactivate();     // already inactive — nothing changed
        });

        assertEquals(List.of(true, false), rig.seen(),
                "a listener that fires on non-changes would fight the button's own handler");
    }

    @Test
    @DisplayName("The production binding keeps the toggle in step with the selector")
    void theToggleFollowsTheSelector() {
        assumeTrue(FxTestSupport.toolkitAvailable());

        FxTestSupport.onFxRun(() -> {
            var selector = new PolygonSelector(new UmapCanvas());
            // The production factory — the same call UmapPane's constructor makes.
            var drawButton = UmapPane.drawToggleFor(selector);

            // The user presses Draw. ToggleButton.fire() flips `selected` itself before
            // dispatching the action, which is how the real click arrives.
            drawButton.fire();
            assertTrue(selector.isActive());
            assertTrue(drawButton.isSelected());

            // Escape, a snapshot teardown or a fresh embedding deactivates the selector
            // without touching the button. This is the case every one of the eight old
            // hand-written sites existed to cover.
            selector.deactivate();
            assertFalse(selector.isActive());
            assertFalse(drawButton.isSelected(),
                    "the toggle must come up with the selector, or it lies about the mode");

            // And back again, from the selector's side.
            selector.activate();
            assertTrue(drawButton.isSelected());
        });
    }
}
