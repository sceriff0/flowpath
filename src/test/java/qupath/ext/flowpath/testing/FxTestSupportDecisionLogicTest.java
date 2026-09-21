package qupath.ext.flowpath.testing;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the pure decision logic behind {@link FxTestSupport}'s timeout budget and its
 * loud-failure property, without needing an actually-broken or actually-slow JavaFX
 * toolkit (this machine always has a display, so the real timeout/retry path can only be
 * exercised end-to-end by a documented manual check — see the class javadoc and Task 7's
 * report). What is testable in-process, and must never regress:
 * <ul>
 *   <li>the timeout budget is read from one place, defaults sanely, and an override wins
 *       over the default without silently swallowing a typo into something worse than the
 *       default;</li>
 *   <li>an unavailable toolkit is fatal if and only if {@code FLOWPATH_FX_REQUIRED=true} was
 *       asked for — this is the property that keeps CI from going green on a silent skip;</li>
 *   <li>the failure message names the real cause it observed (a timeout vs. a graphics
 *       failure) and the budget that was in effect, rather than sending a future reader to
 *       xvfb when the machine was simply busy.</li>
 * </ul>
 */
class FxTestSupportDecisionLogicTest {

    @Test
    void defaultsWhenNothingIsConfigured() {
        assertEquals(30, FxTestSupport.parseTimeoutSeconds(null, null));
        assertEquals(30, FxTestSupport.parseTimeoutSeconds("", ""));
        assertEquals(30, FxTestSupport.parseTimeoutSeconds("   ", null));
    }

    @Test
    void systemPropertyOverridesTheDefault() {
        assertEquals(45, FxTestSupport.parseTimeoutSeconds("45", null));
    }

    @Test
    void envVarOverridesTheDefaultWhenNoPropertyIsSet() {
        assertEquals(60, FxTestSupport.parseTimeoutSeconds(null, "60"));
        assertEquals(60, FxTestSupport.parseTimeoutSeconds("", "60"));
    }

    @Test
    void systemPropertyWinsOverEnvVarWhenBothAreSet() {
        assertEquals(45, FxTestSupport.parseTimeoutSeconds("45", "60"));
    }

    @Test
    void unparsableOverrideFallsBackToTheDefaultRatherThanFailingTheSuite() {
        assertEquals(30, FxTestSupport.parseTimeoutSeconds("not-a-number", null));
        assertEquals(30, FxTestSupport.parseTimeoutSeconds(null, "not-a-number"));
    }

    @Test
    void nonPositiveOverrideFallsBackToTheDefault() {
        assertEquals(30, FxTestSupport.parseTimeoutSeconds("0", null));
        assertEquals(30, FxTestSupport.parseTimeoutSeconds("-5", null));
    }

    /**
     * The loud-failure property: an unavailable toolkit must fail hard exactly when the
     * caller demanded one with {@code FLOWPATH_FX_REQUIRED=true}, and must otherwise let the
     * caller {@code assumeTrue}-skip. This is the property the whole task exists to keep
     * intact while the timeouts get more generous.
     */
    @Test
    void unavailableToolkitFailsLoudlyOnlyWhenRequired() {
        assertTrue(FxTestSupport.shouldFailLoudly(false, true),
                "unavailable + required must be fatal -- this is what stops a broken xvfb "
                        + "from silently skipping every UI test in CI");
        assertFalse(FxTestSupport.shouldFailLoudly(false, false),
                "unavailable + not required must be a skip, not a failure");
        assertFalse(FxTestSupport.shouldFailLoudly(true, true),
                "an available toolkit is never a failure, required or not");
        assertFalse(FxTestSupport.shouldFailLoudly(true, false),
                "an available toolkit is never a failure, required or not");
    }

    @Test
    void failureMessageNamesATimeoutRatherThanSendingAReaderToXvfb() {
        String message = FxTestSupport.buildRequiredFailureMessage(
                "timed out after 30s waiting for Platform.startup", 30);
        assertTrue(message.contains("timed out after 30s"),
                "the real observed cause (a timeout) must be named verbatim");
        assertTrue(message.contains("30s budget"),
                "the budget in effect must be visible so a reader knows to raise it");
        assertTrue(message.contains("FLOWPATH_FX_TIMEOUT_SECONDS"),
                "the override knob must be named so a reader is not sent straight to xvfb");
    }

    @Test
    void failureMessageNamesAGraphicsFailureDistinctlyFromATimeout() {
        String message = FxTestSupport.buildRequiredFailureMessage(
                "graphics initialisation failed: java.lang.ExceptionInInitializerError", 30);
        assertTrue(message.contains("graphics initialisation failed"),
                "a real headless failure must be distinguishable from a timeout in the message");
    }

    // ---- onFx timeout diagnosis: a wedged action must not poison the JVM (round-2 fix) ----

    /**
     * A fresh timeout -- nothing else was left unresolved -- must name the action that timed
     * out and the budget, and must say the task was cancelled, but must NOT claim the thread
     * is busy with something else: there is nothing else on record to blame.
     */
    @Test
    void freshTimeoutNamesTheActionAndTheBudgetWithoutBlamingAnythingElse() {
        String message = FxTestSupport.buildTimeoutMessage(
                "qupath.ext.flowpath.SomeTest.someMethod(SomeTest.java:42)", 30, null);
        assertTrue(message.contains("SomeTest.someMethod(SomeTest.java:42)"),
                "must name which action -- the caller's own stack frame -- timed out");
        assertTrue(message.contains("30s"), "the budget in effect must be visible");
        assertTrue(message.toLowerCase().contains("cancel"),
                "must say the stuck task was cancelled rather than left to poison the FX thread");
        assertFalse(message.contains("still busy"),
                "a fresh timeout with nothing else on record must not fabricate a earlier culprit");
    }

    /**
     * The property this whole fix-round exists for: when an earlier action's identity is still
     * on record as unresolved, a later timeout must diagnose that explicitly -- naming the
     * earlier action and roughly how long ago it was submitted -- rather than presenting as a
     * fresh, unrelated timeout. This is exactly what turned one AnalysisPaneFxTest wedge into
     * 172 failures across 33 classes in Task 7's first evidence run: every later timeout looked
     * unrelated because nothing recorded that the SAME action was still stuck.
     */
    @Test
    void timeoutWhileAnEarlierActionIsStillUnresolvedBlamesThatEarlierAction() {
        long submittedFiveSecondsAgo = System.nanoTime() - TimeUnit.SECONDS.toNanos(5);
        FxTestSupport.PendingAction blamed = new FxTestSupport.PendingAction(
                "qupath.ext.flowpath.AnalysisPaneFxTest.firstTest(AnalysisPaneFxTest.java:134)",
                submittedFiveSecondsAgo);

        String message = FxTestSupport.buildTimeoutMessage(
                "qupath.ext.flowpath.AnalysisPaneFxTest.secondTest(AnalysisPaneFxTest.java:200)",
                30, blamed);

        assertTrue(message.contains("secondTest(AnalysisPaneFxTest.java:200)"),
                "must still name THIS call's own action");
        assertTrue(message.contains("firstTest(AnalysisPaneFxTest.java:134)"),
                "must name the earlier, still-unresolved action believed to be occupying the "
                        + "FX thread");
        assertTrue(message.toLowerCase().contains("still busy"),
                "must diagnose the FX thread as still busy with the earlier action, not present "
                        + "this as a fresh unrelated timeout");

        Matcher m = Pattern.compile("submitted (\\d+\\.\\d)s ago").matcher(message);
        assertTrue(m.find(), "must report roughly how long ago the earlier action was submitted");
        double reportedAge = Double.parseDouble(m.group(1));
        assertTrue(reportedAge >= 5.0 && reportedAge < 6.0,
                "the reported age must be close to the real elapsed time (~5s), got " + reportedAge);
    }
}
