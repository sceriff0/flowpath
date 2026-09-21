package qupath.ext.flowpath.testing;

import org.junit.jupiter.api.Test;

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
}
