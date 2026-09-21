package qupath.ext.flowpath.analysis;

import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
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
 * <b>Quarantined (round 2 of Task 7, review round 0.9.4) — see the {@link Disabled} reason.</b>
 * <p>
 * <b>What the quarantine leaves unguarded.</b> {@code CLAUDE.md}'s "The Analysis window has two
 * distinct persistence lifetimes" invariant requires the in-memory lifetime (an
 * {@link AnalysisPane} kept alive across a close and a reopen, carrying its tab, scope, filter,
 * table selection and every plot's {@link ScaleOptions}) and the {@code Preferences} lifetime to
 * stay <em>independently</em> tested — in its own words, "or a broken layer can hide behind the
 * working one". This class is the only test of the in-memory half.
 * {@code AnalysisWindowPrefsTest} covers the other half and stays enabled. So while this class
 * is disabled that invariant is <b>not</b> enforced: a regression in the in-memory path is
 * exactly the kind of break the still-green prefs test would hide.
 * <p>
 * <b>Why it is quarantined.</b> This class hangs non-deterministically on the shared JavaFX
 * Application Thread: Task 7's second evidence run saw
 * {@link #allFourTabsScaleOptionsSurviveACloseReopenEvenWithPreferencesWipedInBetween} time out
 * opening a real {@link Stage} (a single, contained failure that did not cascade that time — the
 * first run instead saw {@code AnalysisPaneFxTest} wedge and cascade into 172 failures across 33
 * classes).
 * <p>
 * <b>The mechanism is a hypothesis, not a diagnosis.</b> The one this class's javadoc used to
 * assert — repeatedly opening real {@code Stage}s across a long single-JVM suite slowing every
 * later FX operation in that JVM — would explain <em>this</em> class, but it cannot be the
 * shared cause of both quarantined classes: {@code AnalysisPaneFxTest}, the class run 1 wedged
 * on, opens no {@code Stage} at all. The actual root cause is unidentified.
 * <p>
 * The Analysis window is feature-flagged off
 * ({@code FlowPathPane.ANALYSIS_ENABLED == false}), so this gates nothing a user can reach
 * today — quarantining it, rather than fixing the hang, is what round 2 of Task 7 decided. Full
 * evidence is in {@code .superpowers/sdd/review-round-0.9.4-followups/task-7-report.md}. Must be
 * re-enabled and this hang fixed before the Analysis window ships.
 * <p>
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
@Disabled("Quarantined (Task 7 round 2, review 0.9.4). LEAVES UNGUARDED: CLAUDE.md's \"The "
        + "Analysis window has two distinct persistence lifetimes\" invariant, which requires "
        + "the in-memory lifetime (an AnalysisPane surviving a close/reopen with its tab, scope, "
        + "filter, table selection and every plot's ScaleOptions) and the Preferences lifetime "
        + "to stay INDEPENDENTLY tested, \"or a broken layer can hide behind the working one\". "
        + "This class is the only test of the in-memory half; AnalysisWindowPrefsTest covers the "
        + "Preferences half and stays enabled, so a break in the in-memory path is now invisible "
        + "behind a green prefs test. WHY: this class hangs the shared JavaFX Application Thread "
        + "non-deterministically once FxTestSupport's timeout is generous enough to stop masking "
        + "it as \"toolkit unavailable\" -- evidence run 2 saw "
        + "allFourTabsScaleOptionsSurviveACloseReopenEvenWithPreferencesWipedInBetween time out "
        + "opening a real Stage (a single, contained failure), while run 1 of identical code "
        + "instead saw AnalysisPaneFxTest wedge and cascade into 172 failures across 33 "
        + "unrelated classes. HYPOTHESIS, NOT A DIAGNOSIS: accumulated live Stages slowing every "
        + "later FX operation in a long single-JVM suite would explain this class, but cannot be "
        + "the cause shared with AnalysisPaneFxTest -- that class opens no Stage at all. The root "
        + "cause is unidentified. The Analysis window is feature-flagged off "
        + "(FlowPathPane.ANALYSIS_ENABLED == false), so this gates nothing reachable today. See "
        + ".superpowers/sdd/review-round-0.9.4-followups/task-7-report.md for the full evidence. "
        + "MUST be re-enabled and this hang fixed before the Analysis window ships.")
class AnalysisWindowFxTest {

    @BeforeAll
    static void toolkit() throws InterruptedException {
        FxTestSupport.startToolkit();
    }

    private static Preferences scratch() {
        return Preferences.userRoot().node("flowpath-test/" + UUID.randomUUID());
    }

    /**
     * A real, shown {@code Stage} to own the {@code AnalysisWindow}'s own {@code Stage}.
     * <p>
     * Every test here must close its owner in a {@code finally}, alongside the window it
     * owns: an owner left open leaked past the end of each test (three per full run of this
     * class, more once other real-Stage tests in the same JVM are counted), which is the
     * likely cause of this class's flaky Stage-disposal timeouts under
     * {@code FxTestSupport.onFx}'s 5-second budget -- more live windows for the platform to
     * track and repaint makes every subsequent open/close in the same JVM session slower,
     * and a full-suite run accumulates far more of them than this class running alone ever
     * would (which is also why the flakiness did not reproduce running this class by itself).
     */
    private static Stage newShownOwnerStage() {
        return FxTestSupport.onFx(() -> {
            Stage s = new Stage();
            s.show();
            return s;
        });
    }

    @Test
    void theSamePaneReparentsIntoAFreshSceneAcrossAClose() throws Exception {
        Preferences node = scratch();
        try {
            AnalysisWindow window = new AnalysisWindow(node);
            Stage owner = newShownOwnerStage();
            try {
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
                FxTestSupport.onFxRun(owner::close);
            }
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
            Stage owner = newShownOwnerStage();
            try {
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
                FxTestSupport.onFxRun(owner::close);
            }
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
            Stage owner = newShownOwnerStage();
            try {
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
                FxTestSupport.onFxRun(owner::close);
            }
        } finally {
            node.removeNode();
        }
    }
}
