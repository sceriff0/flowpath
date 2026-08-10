package qupath.ext.flowpath.umap.ui;

import javafx.application.Platform;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Bootstraps the JavaFX toolkit once per JVM for integration tests that exercise
 * real controls. On a headless machine with no display (typical CI), the toolkit
 * fails to start; {@link #toolkitAvailable()} then returns {@code false} so callers
 * can {@code assumeTrue(...)} and skip rather than fail. This keeps pure-logic
 * coverage running everywhere while real-control tests run wherever a display exists.
 */
final class FxTestSupport {

    private FxTestSupport() {}

    private static Boolean available;

    /**
     * Start the FX toolkit if it isn't already; cache whether it's usable.
     *
     * <p>When {@code FLOWPATH_FX_REQUIRED=true} (set by CI, which provides a
     * virtual display via xvfb), an unavailable toolkit is a hard error rather
     * than a skip — otherwise a broken display setup would silently skip every
     * UI test and leave CI falsely green.
     */
    static synchronized boolean toolkitAvailable() {
        boolean ready = computeAvailable();
        if (!ready && fxRequired()) {
            throw new IllegalStateException(
                "JavaFX toolkit unavailable but FLOWPATH_FX_REQUIRED=true — the headless "
                + "display (xvfb) is not working, so UI tests would silently skip. Failing loudly.");
        }
        return ready;
    }

    private static boolean fxRequired() {
        return "true".equalsIgnoreCase(System.getenv("FLOWPATH_FX_REQUIRED"))
                || Boolean.getBoolean("flowpath.fx.required");
    }

    private static boolean computeAvailable() {
        if (available != null) return available;
        try {
            CountDownLatch latch = new CountDownLatch(1);
            try {
                Platform.startup(latch::countDown);
            } catch (IllegalStateException alreadyStarted) {
                latch.countDown(); // another class already started it
            }
            if (!latch.await(5, TimeUnit.SECONDS)) {
                available = false;
                return available;
            }
            // Probe: actually build a control. A headless JVM without Monocle
            // fails graphics initialization here (ExceptionInInitializerError),
            // which we treat as "unavailable" so tests skip rather than error.
            FutureTask<Boolean> probe = new FutureTask<>(() -> {
                new javafx.scene.control.Slider();
                return Boolean.TRUE;
            });
            Platform.runLater(probe);
            available = Boolean.TRUE.equals(probe.get(5, TimeUnit.SECONDS));
        } catch (Throwable headlessOrMissing) {
            available = false;
        }
        return available;
    }

    /** Run {@code action} on the FX application thread and block for its result. */
    static <T> T onFx(Supplier<T> action) {
        if (Platform.isFxApplicationThread()) {
            return action.get();
        }
        FutureTask<T> task = new FutureTask<>(action::get);
        Platform.runLater(task);
        try {
            return task.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Run {@code action} on the FX application thread and block until it finishes. */
    static void onFxRun(Runnable action) {
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
