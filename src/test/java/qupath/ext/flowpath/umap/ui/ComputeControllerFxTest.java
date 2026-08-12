package qupath.ext.flowpath.umap.ui;

import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.CompartmentCapability;
import qupath.ext.flowpath.model.MarkerSelection;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.testing.Cells;
import qupath.ext.flowpath.testing.FxTestSupport;
import qupath.ext.flowpath.umap.engine.EmbeddingFeatures;
import qupath.ext.flowpath.umap.engine.UmapComputeService;
import qupath.ext.flowpath.umap.engine.UmapOutcome;
import qupath.ext.flowpath.umap.model.ScalingMode;
import qupath.ext.flowpath.umap.model.UmapParameters;
import qupath.ext.flowpath.umap.session.UmapSession;
import qupath.ext.flowpath.umap.session.ViewState;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The one line that makes the include flag live.
 * <p>
 * Everything else about exclusion is tested against an {@code EmbeddingFeatures} some
 * test constructed. This tests the wire the <em>user</em> is on:
 * {@code ComputeController.runUmap} asking the session for its selection and narrowing the
 * index with it. Swap {@code selectionSupplier.get()} for {@code new MarkerSelection()}
 * there and every other test in the suite still passes while the flag is inert again —
 * the exact defect this work exists to close, resurfacing one level up.
 * <p>
 * The service is subclassed rather than stubbed through its package-private
 * {@code EmbeddingWork} seam, which lives in another package. Recording the argument is
 * also the sharper assertion: what is on trial is what the controller <em>hands over</em>,
 * not what the engine then does with it, which the engine's own tests cover.
 */
class ComputeControllerFxTest {

    /** Captures the feature set the controller hands the service, and runs nothing. */
    private static final class RecordingService extends UmapComputeService {
        final AtomicReference<EmbeddingFeatures> handedOver = new AtomicReference<>();
        /** When set, the next submit throws instead of recording — see the ordering test. */
        Error throwOnce;

        @Override
        public void compute(EmbeddingFeatures features, UmapParameters params, int maxCells,
                            ScalingMode scalingMode) {
            if (throwOnce != null) {
                Error thrown = throwOnce;
                throwOnce = null;
                throw thrown;
            }
            handedOver.set(features);
        }
    }

    /** A reporter that swallows everything, including the modal. */
    private static final class SilentReporter implements UmapPane.StatusReporter {
        final AtomicReference<String> alerted = new AtomicReference<>();

        @Override public void report(String text, UmapPane.StatusLevel level) { }
        @Override public void detail(String text) { }
        @Override public void alert(String message) { alerted.set(message); }
    }

    private static CellIndex threeMarkers() {
        return Cells.of(40)
                .marker("CD3", i -> i)
                .marker("CD8", i -> i * 2.0)
                .marker("FoxP3", i -> i * 3.0)
                .build();
    }

    private static final List<String> PANEL = List.of("CD3", "CD8", "FoxP3");

    /** A standalone session already holding {@code index} and the picker's {@code selection}. */
    private static UmapSession sessionOver(CellIndex index, MarkerSelection selection) {
        var session = new UmapSession();
        session.installIndex(index, MarkerStats.compute(index), PANEL,
                CompartmentCapability.empty(), selection);
        return session;
    }

    /** Wire a controller and its state machine over {@code session}, on the FX thread. */
    private static ComputeController controllerOver(UmapComputeService service, UmapSession session) {
        return controllerOver(service, session, new SilentReporter());
    }

    private static ComputeController controllerOver(UmapComputeService service, UmapSession session,
                                                    UmapPane.StatusReporter reporter) {
        var controller = new ComputeController(
                service,
                session,
                result -> { },
                reporter,
                size -> { });
        controller.attachUiState(new UiStateController(session, new UiStateController.Controls(
                controller.getComputeButton(), controller.getCancelButton(),
                new ProgressIndicator(), new ProgressBar(), new Label(),
                new ToggleButton(), new Button(),
                new TextField(), new ColorPicker(), new Button(),
                new Button(), new StackPane(), new Button(), new CheckBox(),
                List.of(), () -> { })));
        return controller;
    }

    /** Build the controller, attach the state machine it needs, and click Run. */
    private static EmbeddingFeatures runWith(CellIndex index, MarkerSelection selection) {
        var service = new RecordingService();
        try {
            FxTestSupport.onFxRun(() -> controllerOver(service, sessionOver(index, selection)).runUmap());
            return service.handedOver.get();
        } finally {
            service.shutdown();
        }
    }

    @Test
    void theRunIsNarrowedToTheMarkersThePickerLeftTicked() {
        assumeTrue(FxTestSupport.toolkitAvailable());

        var selection = new MarkerSelection();
        selection.put("CD8", MarkerSelection.defaultEntry().withIncluded(false));

        var handedOver = runWith(threeMarkers(), selection);

        var selected = assertInstanceOf(EmbeddingFeatures.Selected.class, handedOver,
                "two markers are still ticked, so the run must go ahead");
        assertArrayEquals(new String[]{"CD3", "FoxP3"}, selected.featureNames(),
                "the picker's state must reach the service, not the whole panel");
        assertEquals(40, selected.cellCount(), "unticking a marker unticks no cells");
    }

    @Test
    void anUntouchedPickerStillRunsOverTheWholePanel() {
        assumeTrue(FxTestSupport.toolkitAvailable());

        var handedOver = runWith(threeMarkers(), new MarkerSelection());

        var selected = assertInstanceOf(EmbeddingFeatures.Selected.class, handedOver);
        assertArrayEquals(new String[]{"CD3", "CD8", "FoxP3"}, selected.featureNames());
    }

    @Test
    void untickingEverythingReachesTheServiceAsARefusalRatherThanBeingSwallowedHere() {
        assumeTrue(FxTestSupport.toolkitAvailable());

        // The controller deliberately does not pre-empt the refusal: it must travel to the
        // service so the ONE terminal channel reports it and clears the busy state the
        // caller has already shown. A second opinion here would be a second place deciding
        // what "enough features" means.
        var selection = new MarkerSelection();
        for (String marker : new String[]{"CD3", "CD8", "FoxP3"}) {
            selection.put(marker, MarkerSelection.defaultEntry().withIncluded(false));
        }

        var handedOver = runWith(threeMarkers(), selection);

        assertNotNull(handedOver, "the run must still be submitted, not silently dropped");
        assertInstanceOf(EmbeddingFeatures.Refused.class, handedOver);
    }

    /**
     * The wire behind {@code ViewStateDerivationTest.supersededOutcome}: the branch that
     * must do nothing, exercised through the controller that used to be the one deciding
     * it did nothing.
     * <p>
     * This mapping was untested when it was written, and {@code Superseded} is the branch
     * where a silent regression hurts most: a newer run owns the COMPUTING state, so any
     * edit that made this branch set a state would pull the panel out of a busy state that
     * is still true, leaving a live run with no progress bar and no Cancel.
     */
    @Test
    void asupersededEndingLeavesTheNewerRunsBusyStateAlone() {
        assumeTrue(FxTestSupport.toolkitAvailable());

        var service = new RecordingService();
        try {
            FxTestSupport.onFxRun(() -> {
                var session = sessionOver(threeMarkers(), new MarkerSelection());
                var controller = controllerOver(service, session);
                controller.runUmap();
                assertEquals(ViewState.Stage.COMPUTING, session.viewState().stage());

                controller.onUmapOutcome(UmapOutcome.superseded());

                assertEquals(ViewState.Stage.COMPUTING, session.viewState().stage(),
                        "the newer run still owns the panel");
                assertTrue(session.isRunning());
            });
        } finally {
            service.shutdown();
        }
    }

    /**
     * The counterweight: an ending that <em>is</em> for this run does clear the busy state.
     * Without this, {@link #asupersededEndingLeavesTheNewerRunsBusyStateAlone} would still
     * pass if the controller had simply stopped reacting to outcomes altogether.
     * <p>
     * Cancelled rather than Failed, because {@code onUmapError} opens a modal alert and
     * {@code showAndWait} on the FX thread would deadlock a headless test. The Failed
     * mapping is pinned in {@code ViewStateDerivationTest}, where no toolkit is involved.
     */
    @Test
    void anEndingThatBelongsToThisRunDoesClearTheBusyState() {
        assumeTrue(FxTestSupport.toolkitAvailable());

        var service = new RecordingService();
        try {
            FxTestSupport.onFxRun(() -> {
                var session = sessionOver(threeMarkers(), new MarkerSelection());
                var controller = controllerOver(service, session);

                controller.runUmap();
                controller.onUmapOutcome(UmapOutcome.cancelled());

                assertEquals(ViewState.Stage.READY, session.viewState().stage());
                assertFalse(session.isRunning());
            });
        } finally {
            service.shutdown();
        }
    }

    /**
     * The submit ordering, pinned rather than merely correct.
     * <p>
     * {@code runUmap} enters the running phase BEFORE submitting. The order used to be
     * reversed, which was safe only because production delivers outcomes through
     * {@code Platform::runLater} — with a synchronous delivery executor a refusal reaches
     * the consumer from inside {@code compute()}, and {@code beginRun()} afterwards would
     * re-enter a COMPUTING nothing was ever going to leave. Entering first makes the phase
     * true whatever the executor does, and leaves the submit itself as the thing that must
     * not strand it.
     * <p>
     * An {@link Error}, not a {@link RuntimeException}: the catch was narrowed to the latter
     * for one round, which is the same shape as the gap Task 1 was written to close.
     */
    @Test
    void aSubmitThatThrowsEndsTheRunInsteadOfStrandingIt() {
        assumeTrue(FxTestSupport.toolkitAvailable());

        var service = new RecordingService();
        try {
            FxTestSupport.onFxRun(() -> {
                var session = sessionOver(threeMarkers(), new MarkerSelection());
                var reporter = new SilentReporter();
                var controller = controllerOver(service, session, reporter);
                service.throwOnce = new LinkageError("the classpath moved under us");

                controller.runUmap();

                assertFalse(session.isRunning(),
                        "a throw from the submit must not leave the panel busy forever");
                assertEquals(ViewState.Stage.FAILED, session.viewState().stage());
                assertNotNull(session.viewState().failure());
                assertTrue(session.viewState().failure().contains("LinkageError"),
                        "and it must say what actually happened");
                assertNotNull(reporter.alerted.get(), "the user is told, without a modal here");
            });
        } finally {
            service.shutdown();
        }
    }
}
