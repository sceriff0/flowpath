package qupath.ext.flowpath.analysis;

import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.analysis.session.AnalysisSession;
import qupath.ext.flowpath.analysis.ui.AnalysisPane;
import qupath.ext.flowpath.analysis.ui.ScaleOptions;
import qupath.ext.flowpath.testing.AnalysisFixtures;
import qupath.ext.flowpath.testing.FxTestSupport;

import java.util.List;
import java.util.UUID;
import java.util.prefs.Preferences;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises a real {@code AnalysisWindow} — a real {@link Stage}, a real close and a real
 * re-open — to check the two claims Task 15's brief said needed verifying rather than
 * assuming:
 * <ol>
 *   <li>a JavaFX {@code Parent}, once the {@code Stage} that showed it has been closed, really
 *       can be re-parented into a brand-new {@code Scene} on a brand-new {@code Stage} without
 *       throwing — see {@link #theSamePaneReparentsIntoAFreshSceneAcrossAClose};
 *   <li>a close/reopen keeps the pane's own state because the SAME {@link AnalysisPane}
 *       instance stays alive in memory, not because {@code AnalysisWindow} re-reads it from
 *       {@code AnalysisWindowPrefs} on the way back in — see
 *       {@link #allFourTabsScaleOptionsSurviveACloseReopenEvenWithPreferencesWipedInBetween},
 *       which proves the negative directly by wiping the scratch preferences node between the
 *       close and the reopen and asserting nothing regresses — for all four tabs, not only
 *       whichever one happened to be selected.
 * </ol>
 * Both use the package-private {@link AnalysisWindow#AnalysisWindow(Preferences)} constructor
 * pointed at a scratch node, so this class's real open()/close() geometry-persistence code
 * never touches whatever this machine has genuinely saved — the same rule
 * {@code AnalysisWindowPrefsTest} follows for the record's own tests.
 */
class AnalysisWindowFxTest {

    @BeforeAll
    static void toolkit() throws InterruptedException {
        FxTestSupport.startToolkit();
    }

    private static Preferences scratch() {
        return Preferences.userRoot().node("flowpath-test/" + UUID.randomUUID());
    }

    @Test
    void theSamePaneReparentsIntoAFreshSceneAcrossAClose() throws Exception {
        Preferences node = scratch();
        try {
            AnalysisWindow window = new AnalysisWindow(node);
            Stage owner = FxTestSupport.onFx(() -> {
                Stage s = new Stage();
                s.show();
                return s;
            });
            AnalysisSession.AnalysisInput input = AnalysisFixtures.simpleInput();

            FxTestSupport.onFxRun(() -> window.open(null, input, owner));
            assertTrue(FxTestSupport.onFx(window::isShowing));
            AnalysisPane firstPane = FxTestSupport.onFx(window::paneForTest);
            assertNotNull(firstPane);

            FxTestSupport.onFxRun(window::close);
            assertFalse(FxTestSupport.onFx(window::isShowing));

            // THE CHECK: a second real Stage/Scene, built while the first Stage's Scene has
            // already been torn down (window.close() -> Stage.close()). If a Parent could not
            // actually be re-parented once its old Scene was closed, this throws
            // IllegalArgumentException from Scene's own root-assignment code.
            assertDoesNotThrow(() -> FxTestSupport.onFxRun(() -> window.open(null, input, owner)));
            assertTrue(FxTestSupport.onFx(window::isShowing));

            AnalysisPane secondPane = FxTestSupport.onFx(window::paneForTest);
            assertSame(firstPane, secondPane,
                    "disposeStage() must not discard the pane -- a fresh AnalysisPane here "
                            + "would mean the re-parenting claim was never actually tested");

            FxTestSupport.onFxRun(window::close);
        } finally {
            node.removeNode();
        }
    }

    /**
     * Proves the in-memory survival is real, not merely reading the same values back from
     * preferences by coincidence: seeds the scratch node with a non-default tab and FOUR
     * DISTINCT tabs' scale options (log on for tab 0 AND tab 3, off for the other two -- exactly
     * the "two different tabs configured differently" case a single remembered triple could not
     * represent) so the FIRST open's freshly-built pane visibly differs from {@code
     * AnalysisWindowPrefs.defaults()} on every tab, then WIPES the node before the second open.
     * If {@code AnalysisWindow} depended on preferences to restore pane state on every open (the
     * bug this task's brief warns against reintroducing), the second open would read only
     * defaults from the now-empty node and this test would fail.
     */
    @Test
    void allFourTabsScaleOptionsSurviveACloseReopenEvenWithPreferencesWipedInBetween() throws Exception {
        Preferences node = scratch();
        try {
            List<ScaleOptions> seeded = List.of(
                    new ScaleOptions(true, true, 80),
                    new ScaleOptions(false, false, 95),
                    new ScaleOptions(false, false, 95),
                    new ScaleOptions(true, false, 65));
            new AnalysisWindowPrefs(Double.NaN, Double.NaN, 960, 640, 2, "WHOLE_SLIDE", seeded)
                    .save(node);

            AnalysisWindow window = new AnalysisWindow(node);
            Stage owner = FxTestSupport.onFx(() -> {
                Stage s = new Stage();
                s.show();
                return s;
            });
            AnalysisSession.AnalysisInput input = AnalysisFixtures.simpleInput();

            FxTestSupport.onFxRun(() -> window.open(null, input, owner));
            AnalysisPane pane = FxTestSupport.onFx(window::paneForTest);
            assertEquals(2, FxTestSupport.onFx(pane::selectedTabIndex),
                    "the FIRST open should have seeded the tab from the pre-populated prefs");
            assertEquals(seeded, FxTestSupport.onFx(pane::scaleOptionsByTab),
                    "all four tabs, not only whichever one is selected, seeded from prefs");

            FxTestSupport.onFxRun(window::close);
            // Simulate preferences being unavailable or corrupted between the close and the
            // reopen -- every key this class ever wrote is gone.
            node.clear();

            FxTestSupport.onFxRun(() -> window.open(null, input, owner));
            AnalysisPane paneAfterReopen = FxTestSupport.onFx(window::paneForTest);

            assertSame(pane, paneAfterReopen, "still the same in-memory pane");
            assertEquals(2, FxTestSupport.onFx(paneAfterReopen::selectedTabIndex),
                    "tab 2 survived with an EMPTY preferences node -- it came from the live "
                            + "pane, not from AnalysisWindowPrefs.load()");
            assertEquals(seeded, FxTestSupport.onFx(paneAfterReopen::scaleOptionsByTab),
                    "all four tabs' scale options survived with an EMPTY preferences node, for "
                            + "the same reason -- not just whichever tab was selected");

            FxTestSupport.onFxRun(window::close);
        } finally {
            node.removeNode();
        }
    }

    /**
     * {@code AnalysisWindow.dispose()} is the genuine teardown path — unlike {@link
     * AnalysisWindow#close()}, it must actually release the pane, or the whole point of adding
     * it separately from {@code disposeStage()} (see that method's own javadoc) is defeated.
     */
    @Test
    void disposeReleasesThePaneUnlikeClose() throws Exception {
        Preferences node = scratch();
        try {
            AnalysisWindow window = new AnalysisWindow(node);
            Stage owner = FxTestSupport.onFx(() -> {
                Stage s = new Stage();
                s.show();
                return s;
            });
            AnalysisSession.AnalysisInput input = AnalysisFixtures.simpleInput();

            FxTestSupport.onFxRun(() -> window.open(null, input, owner));
            assertNotNull(FxTestSupport.onFx(window::paneForTest));

            FxTestSupport.onFxRun(window::close);
            assertNotNull(FxTestSupport.onFx(window::paneForTest),
                    "close() alone must still keep the pane alive");

            FxTestSupport.onFxRun(window::dispose);
            assertNull(FxTestSupport.onFx(window::paneForTest),
                    "dispose() is the real teardown -- it must release the pane, or it is not "
                            + "distinguishable from close() at all");
        } finally {
            node.removeNode();
        }
    }
}
