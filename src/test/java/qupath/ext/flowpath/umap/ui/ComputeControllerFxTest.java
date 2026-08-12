package qupath.ext.flowpath.umap.ui;

import javafx.scene.control.Button;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.MarkerSelection;
import qupath.ext.flowpath.testing.Cells;
import qupath.ext.flowpath.testing.FxTestSupport;
import qupath.ext.flowpath.umap.engine.EmbeddingFeatures;
import qupath.ext.flowpath.umap.engine.UmapComputeService;
import qupath.ext.flowpath.umap.model.ScalingMode;
import qupath.ext.flowpath.umap.model.UmapParameters;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

        @Override
        public void compute(EmbeddingFeatures features, UmapParameters params, int maxCells,
                            ScalingMode scalingMode) {
            handedOver.set(features);
        }
    }

    private static CellIndex threeMarkers() {
        return Cells.of(40)
                .marker("CD3", i -> i)
                .marker("CD8", i -> i * 2.0)
                .marker("FoxP3", i -> i * 3.0)
                .build();
    }

    /** Build the controller, attach the state machine it needs, and click Run. */
    private static EmbeddingFeatures runWith(CellIndex index, MarkerSelection selection) {
        var service = new RecordingService();
        try {
            FxTestSupport.onFxRun(() -> {
                var controller = new ComputeController(
                        service,
                        () -> index,
                        () -> selection,
                        () -> UiStateController.UiState.READY,
                        result -> { },
                        new UmapPane.StatusReporter() {
                            @Override public void report(String text, UmapPane.StatusLevel level) { }
                            @Override public void detail(String text) { }
                        },
                        size -> { });
                controller.attachUiState(new UiStateController(
                        controller.getComputeButton(), controller.getCancelButton(),
                        new ProgressIndicator(), new ToggleButton(), new Button(),
                        new TextField(), new ColorPicker(), new Button(), new Button()));
                controller.runUmap();
            });
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
}
