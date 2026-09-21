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
 * <p>
 * <b>A wedged action must not poison the JVM (critical invariant).</b> {@link #onFx} used to
 * give up on the *calling* thread after a timeout but leave the submitted {@link FutureTask}
 * sitting in the FX Application Thread's queue forever. If that action was genuinely stuck
 * (not merely slow), every later {@code onFx}/{@code onFxRun} call in the same JVM — from any
 * test class — queued up behind it and timed out identically: one stuck action anywhere in a
 * full-suite run could cascade into every FX test that ran after it (this is exactly what
 * happened to {@code AnalysisPaneFxTest} in Task 7's round-0.9.4 evidence run: 172 failures
 * across 33 classes from one wedge). {@code onFx} now calls {@code task.cancel(true)} on a
 * timeout — a best-effort interrupt that frees the thread when the stuck action is merely slow
 * or is blocked in something that honours interruption, though not when it is blocked in
 * native/non-interruptible code — and tracks whether an earlier, still-unresolved action might
 * still be occupying the thread via {@link #pending}. A later timeout while that marker is
 * still set is diagnosed as "the FX Application Thread is still busy with an earlier action",
 * naming that action and how long ago it was submitted, rather than presenting as a fresh,
 * unrelated timeout. The marker is cleared whenever any {@code onFx} call — successful or not —
 * actually completes, because the FX queue is strictly FIFO: if our action ran, everything
 * queued before it must already have finished or been abandoned.
 */
public final class FxTestSupport {

    private FxTestSupport() {}

    /** Fallback timeout budget (seconds) when no override is configured. Generous for CI. */
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    private static Boolean available;
    private static String unavailableReason;

    /**
     * Identity of the current earliest {@link #onFx} call whose completion this JVM has not
     * yet observed. {@code null} means no call is known to be unresolved — the last one either
     * finished (successfully or not) or none has run yet. Never overwritten while non-null
     * (see {@link #onFx}), so it names the *original* stuck action across a whole cascade of
     * later timeouts, not just the most recent caller left waiting behind it.
     */
    private static final AtomicReference<PendingAction> pending = new AtomicReference<>();

    /**
     * One {@link #onFx} call's identity: where it was called from, and when it was submitted.
     * Package-private (rather than {@code private}) solely so {@code buildTimeoutMessage}'s
     * decision logic is constructible from a test in this package without wedging a real
     * toolkit — see {@code FxTestSupportDecisionLogicTest}.
     */
    record PendingAction(String description, long submittedAtNanos) {}

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
                // Same reasoning as onFx: don't leave a probe FutureTask sitting in the FX
                // queue behind us forever if the thread eventually frees up.
                probe.cancel(true);
                return new Attempt(Outcome.TIMED_OUT,
                        "timed out after " + budget + "s waiting for the control probe");
            }
        } catch (Throwable headlessOrMissing) {
            return new Attempt(Outcome.FAILED,
                    "graphics initialisation failed: " + headlessOrMissing);
        }
    }

    /**
     * Run {@code action} on the FX application thread and block for its result.
     * <p>
     * On timeout, cancels the submitted task (best-effort interrupt — see the class javadoc)
     * so a stuck action cannot keep occupying the FX Application Thread's queue indefinitely
     * from this call's perspective, and fails only *this* call rather than leaving the caller
     * to guess. If an earlier, still-unresolved {@code onFx} call's identity is on record (see
     * {@link #pending}), the failure names it explicitly instead of presenting as a fresh,
     * unrelated timeout.
     */
    public static <T> T onFx(Supplier<T> action) {
        if (Platform.isFxApplicationThread()) {
            return action.get();
        }
        String description = describeCaller();
        int budget = timeoutSeconds();
        FutureTask<T> task = new FutureTask<>(action::get);

        // Only ever claim the marker while it is empty -- see the field javadoc for why an
        // already-set marker must survive this call rather than being overwritten by it.
        PendingAction mine = new PendingAction(description, System.nanoTime());
        boolean weAreTheOnlyUnresolvedCall = pending.compareAndSet(null, mine);
        PendingAction blamed = weAreTheOnlyUnresolvedCall ? null : pending.get();

        Platform.runLater(task);
        try {
            T result = task.get(budget, TimeUnit.SECONDS);
            // The FX queue is FIFO: if OUR action ran to completion, everything queued before
            // it (including whatever "blamed" named) is no longer occupying the thread.
            pending.set(null);
            return result;
        } catch (TimeoutException timedOut) {
            task.cancel(true);
            throw new RuntimeException(
                    buildTimeoutMessage(description, budget, blamed), timedOut);
        } catch (Exception e) {
            pending.set(null);
            throw new RuntimeException(e);
        }
    }

    /** The message an {@link #onFx} timeout fails with. Pure, so it is unit-testable. */
    static String buildTimeoutMessage(String description, int budgetSeconds, PendingAction blamed) {
        StringBuilder msg = new StringBuilder("JavaFX action [").append(description)
                .append("] did not complete within ").append(budgetSeconds)
                .append("s (override with FLOWPATH_FX_TIMEOUT_SECONDS)");
        if (blamed == null) {
            msg.append(" -- cancelling it so later tests are not blocked by it.");
        } else {
            double ageSeconds = (System.nanoTime() - blamed.submittedAtNanos()) / 1e9;
            msg.append(" -- the FX Application Thread is still busy with an earlier action (")
                    .append(blamed.description()).append(", submitted ")
                    .append(String.format(java.util.Locale.ROOT, "%.1f", ageSeconds))
                    .append("s ago) that a previous test's own budget could not wait out "
                            + "either. This is very likely the SAME stuck action still wedging "
                            + "the thread, not a fresh, unrelated timeout -- cancelling this "
                            + "call's task so it does not add a second one to the queue.");
        }
        return msg.toString();
    }

    /**
     * Names the first stack frame outside this class — the test method (or test helper) that
     * called {@link #onFx}/{@link #onFxRun} — so a timeout failure identifies *which* action
     * was waiting, not just that "an" action somewhere did.
     */
    private static String describeCaller() {
        StackWalker walker = StackWalker.getInstance();
        return walker.walk(frames -> frames
                        .filter(f -> !FxTestSupport.class.getName().equals(f.getClassName()))
                        .findFirst()
                        .map(FxTestSupport::describeFrame))
                .orElse("<unknown caller>");
    }

    private static String describeFrame(StackWalker.StackFrame frame) {
        return frame.getClassName() + "." + frame.getMethodName()
                + "(" + frame.getFileName() + ":" + frame.getLineNumber() + ")";
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
