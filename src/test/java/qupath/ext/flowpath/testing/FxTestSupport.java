package qupath.ext.flowpath.testing;

import javafx.application.Platform;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Bootstraps the JavaFX toolkit once per JVM for integration tests that exercise
 * real controls. On a headless machine with no display (typical CI), the toolkit
 * fails to start; {@link #toolkitAvailable()} then returns {@code false} so callers
 * can {@code assumeTrue(...)} and skip rather than fail. This keeps pure-logic
 * coverage running everywhere while real-control tests run wherever a display exists.
 * <p>
 * One copy, shared by both halves of the suite. It previously existed twice —
 * byte-for-byte identical but for the package line — once under {@code ui} and once
 * under {@code umap.ui}.
 * <p>
 * <b>Timeout budget (critical invariant).</b> Every wait in this class — startup,
 * the control probe, and each {@link #onFx}/{@link #onFxRun} action — reads a single
 * configurable budget from {@link #timeoutSeconds()} rather than its own literal. A
 * full-suite run at load average 5-9 once produced 140 failures (98
 * {@code IllegalStateException: toolkit unavailable} plus 27 {@code TimeoutException})
 * that vanished when the same classes ran alone: a fixed 5s was not "the toolkit is
 * broken", it was "the FX Application Thread had not been scheduled yet". The default
 * (30s) is generous for a busy CI box; override with the {@code FLOWPATH_FX_TIMEOUT_SECONDS}
 * environment variable or the {@code flowpath.fx.timeout.seconds} system property when an
 * even slower machine needs more. A negative verdict caused by hitting this budget is
 * retried once at full budget before {@link #toolkitAvailable()} caches it — see
 * {@link #computeAvailable()} — so the cached answer reflects "no display", not "busy
 * right now". A genuinely headless JVM fails the very first attempt with a graphics
 * exception, not a timeout, so it is still detected on the first try and, with
 * {@code FLOWPATH_FX_REQUIRED=true}, still fails loudly (never silently skips).
 */
public final class FxTestSupport {

    private FxTestSupport() {}

    /** Fallback timeout budget (seconds) when no override is configured. Generous for CI. */
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    private static Boolean available;
    private static String unavailableReason;

    /**
     * The shared timeout budget, in seconds, for JavaFX startup, the control probe, and
     * every {@link #onFx}/{@link #onFxRun} action. Override with the
     * {@code FLOWPATH_FX_TIMEOUT_SECONDS} environment variable or the
     * {@code flowpath.fx.timeout.seconds} system property (property wins if both are set);
     * an unparsable or non-positive override falls back to the default of {@value
     * #DEFAULT_TIMEOUT_SECONDS}s. This is the one place the number lives — no other file in
     * {@code src/test} should hardcode its own FX wait.
     */
    public static int timeoutSeconds() {
        return parseTimeoutSeconds(
                System.getProperty("flowpath.fx.timeout.seconds"),
                System.getenv("FLOWPATH_FX_TIMEOUT_SECONDS"));
    }

    /** Pure decision logic behind {@link #timeoutSeconds()}, kept testable without env/props. */
    static int parseTimeoutSeconds(String systemProperty, String envVar) {
        String override = (systemProperty != null && !systemProperty.isBlank())
                ? systemProperty
                : envVar;
        if (override != null && !override.isBlank()) {
            try {
                int parsed = Integer.parseInt(override.trim());
                if (parsed > 0) return parsed;
            } catch (NumberFormatException ignored) {
                // Fall through to the default rather than fail a whole suite over a typo.
            }
        }
        return DEFAULT_TIMEOUT_SECONDS;
    }

    /**
     * Start the FX toolkit if it isn't already; cache whether it's usable.
     *
     * <p>When {@code FLOWPATH_FX_REQUIRED=true} (set by CI, which provides a
     * virtual display via xvfb), an unavailable toolkit is a hard error rather
     * than a skip — otherwise a broken display setup would silently skip every
     * UI test and leave CI falsely green.
     */
    public static synchronized boolean toolkitAvailable() {
        boolean ready = computeAvailable();
        if (shouldFailLoudly(ready, fxRequired())) {
            throw new IllegalStateException(
                    buildRequiredFailureMessage(unavailableReason, timeoutSeconds()));
        }
        return ready;
    }

    /**
     * Pure predicate behind {@link #toolkitAvailable()}'s hard failure: an unavailable
     * toolkit is fatal only when the caller demanded one. Extracted so the loud-failure
     * property can be pinned by a unit test without needing an actually-broken toolkit.
     */
    static boolean shouldFailLoudly(boolean ready, boolean required) {
        return !ready && required;
    }

    /** The message {@link #toolkitAvailable()} throws with — names the real cause observed. */
    static String buildRequiredFailureMessage(String reason, int budgetSeconds) {
        return "JavaFX toolkit unavailable but FLOWPATH_FX_REQUIRED=true (" + reason
                + ") -- the headless display (xvfb) is not working, or this machine is too "
                + "slow for the current " + budgetSeconds + "s budget (override with "
                + "FLOWPATH_FX_TIMEOUT_SECONDS). UI tests would otherwise silently skip. "
                + "Failing loudly.";
    }

    /**
     * Start the toolkit and block until it is up, tolerating one that another test class
     * already started.
     * <p>
     * Deliberately <em>not</em> {@link #toolkitAvailable()}: this makes no judgement about
     * whether graphics work and hands the caller no way to skip. Tests that only need the
     * FX thread — building bare controls with no scene, where a {@code ColorPicker} touches
     * CSS during construction — use this from {@code @BeforeAll} and must keep running
     * everywhere rather than gaining a silent skip.
     */
    public static void startToolkit() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        try {
            Platform.startup(latch::countDown);
        } catch (IllegalStateException alreadyStarted) {
            // Toolkit was already initialized (e.g. by a previous test class) — fine.
            latch.countDown();
        }
        int budget = timeoutSeconds();
        if (!latch.await(budget, TimeUnit.SECONDS)) {
            throw new IllegalStateException(
                    "Platform.startup did not complete within " + budget
                    + "s (override with FLOWPATH_FX_TIMEOUT_SECONDS) -- either the toolkit "
                    + "genuinely cannot start here, or this machine is too busy for the "
                    + "current budget.");
        }
    }

    private static boolean fxRequired() {
        return "true".equalsIgnoreCase(System.getenv("FLOWPATH_FX_REQUIRED"))
                || Boolean.getBoolean("flowpath.fx.required");
    }

    /** Outcome of one attempt to start the toolkit and probe it with a real control. */
    private enum Outcome { AVAILABLE, TIMED_OUT, FAILED }

    private record Attempt(Outcome outcome, String reason) {}

    private static boolean computeAvailable() {
        if (available != null) return available;

        Attempt attempt = attemptStartupAndProbe();
        if (attempt.outcome() == Outcome.TIMED_OUT) {
            // A timeout is ambiguous -- it is exactly what a busy-but-working machine looks
            // like -- so it must not be cached as a permanent verdict on one try. Retry once
            // at the full budget before deciding. A genuinely headless JVM fails the *first*
            // attempt with a graphics exception (Outcome.FAILED), not a timeout, so this
            // retry never masks a real "no display" case -- it only gives a loaded machine a
            // second, equally generous chance.
            attempt = attemptStartupAndProbe();
        }

        available = attempt.outcome() == Outcome.AVAILABLE;
        unavailableReason = attempt.reason();
        return available;
    }

    private static Attempt attemptStartupAndProbe() {
        int budget = timeoutSeconds();
        try {
            CountDownLatch latch = new CountDownLatch(1);
            try {
                Platform.startup(latch::countDown);
            } catch (IllegalStateException alreadyStarted) {
                latch.countDown(); // another class already started it
            }
            if (!latch.await(budget, TimeUnit.SECONDS)) {
                return new Attempt(Outcome.TIMED_OUT,
                        "timed out after " + budget + "s waiting for Platform.startup");
            }
            // Probe: actually build a control. A headless JVM without Monocle
            // fails graphics initialization here (ExceptionInInitializerError),
            // which we treat as "unavailable" so tests skip rather than error.
            FutureTask<Boolean> probe = new FutureTask<>(() -> {
                new javafx.scene.control.Slider();
                return Boolean.TRUE;
            });
            Platform.runLater(probe);
            try {
                boolean ok = Boolean.TRUE.equals(probe.get(budget, TimeUnit.SECONDS));
                return ok
                        ? new Attempt(Outcome.AVAILABLE, null)
                        : new Attempt(Outcome.FAILED, "control probe returned false");
            } catch (TimeoutException timedOut) {
                return new Attempt(Outcome.TIMED_OUT,
                        "timed out after " + budget + "s waiting for the control probe");
            }
        } catch (Throwable headlessOrMissing) {
            return new Attempt(Outcome.FAILED,
                    "graphics initialisation failed: " + headlessOrMissing);
        }
    }

    /** Run {@code action} on the FX application thread and block for its result. */
    public static <T> T onFx(Supplier<T> action) {
        if (Platform.isFxApplicationThread()) {
            return action.get();
        }
        FutureTask<T> task = new FutureTask<>(action::get);
        Platform.runLater(task);
        int budget = timeoutSeconds();
        try {
            return task.get(budget, TimeUnit.SECONDS);
        } catch (TimeoutException timedOut) {
            throw new RuntimeException(
                    "JavaFX action did not complete within " + budget + "s (override with "
                    + "FLOWPATH_FX_TIMEOUT_SECONDS) -- the FX Application Thread may simply "
                    + "be behind under load, or the action itself may be stuck", timedOut);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Run {@code action} on the FX application thread and block until it finishes. */
    public static void onFxRun(Runnable action) {
        AtomicReference<RuntimeException> err = new AtomicReference<>();
        onFx(() -> {
            try {
                action.run();
            } catch (RuntimeException e) {
                err.set(e);
            }
            return null;
        });
        if (err.get() != null) throw err.get();
    }
}
