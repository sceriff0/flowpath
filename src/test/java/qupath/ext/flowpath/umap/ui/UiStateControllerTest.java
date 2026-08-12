package qupath.ext.flowpath.umap.ui;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.CompartmentCapability;
import qupath.ext.flowpath.model.MarkerSelection;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.testing.Cells;
import qupath.ext.flowpath.testing.FxTestSupport;
import qupath.ext.flowpath.umap.engine.EmbeddingReport;
import qupath.ext.flowpath.umap.engine.UmapOutcome;
import qupath.ext.flowpath.umap.PhenotypeSnapshot;
import qupath.ext.flowpath.umap.model.PopulationTag;
import qupath.ext.flowpath.umap.model.UmapParameters;
import qupath.ext.flowpath.umap.model.UmapResult;
import qupath.ext.flowpath.umap.session.UmapSession;
import qupath.ext.flowpath.umap.session.ViewState;
import qupath.ext.flowpath.umap.testing.Embeddings;
import qupath.lib.objects.PathObject;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * That {@link UiStateController} applies {@link UmapSession#viewState()} faithfully — and
 * nothing else.
 * <p>
 * The rule itself is not tested here; it is a pure function and lives in
 * {@code ViewStateDerivationTest}, which needs no toolkit. What is on trial here is the
 * wiring: that every control the panel owns is actually attached to the machine. The
 * controls that were <em>not</em> attached are what this whole task is about — the feature
 * picker, the marker and scale combos and every embedding parameter were ignored entirely,
 * which is why editing features mid-run could reinstall the session's {@code CellIndex}
 * underneath a compute thread still reading it.
 * <p>
 * Bare controls, no scene. The toolkit is started because {@link ColorPicker} touches CSS
 * during construction.
 */
class UiStateControllerTest {

    private static final List<String> PANEL = List.of("CD3", "CD8", "FoxP3");

    private Button computeButton;
    private Button cancelButton;
    private ProgressIndicator progressIndicator;
    private ProgressBar computeProgress;
    private Label computeStage;
    private Label failureBanner;
    private ToggleButton drawButton;
    private Button clearButton;
    private TextField tagNameField;
    private ColorPicker tagColorPicker;
    private Button applyTagButton;
    private Button exportButton;
    private StackPane emptyState;
    private Button emptyAction;
    private CheckBox roiFilter;

    private Button featuresButton;
    private ComboBox<String> markerDropdown;
    private ComboBox<String> colorScaleDropdown;
    private Spinner<Integer> kSpinner;
    private Spinner<Integer> epochsSpinner;
    private Spinner<Integer> maxCellsSpinner;
    private ComboBox<String> subsampleMode;
    private ComboBox<String> scalingMode;
    private List<Node> inputs;

    private AtomicInteger popupsDismissed;

    private UmapSession session;
    private UiStateController controller;

    @BeforeAll
    static void initToolkit() throws InterruptedException {
        FxTestSupport.startToolkit();
    }

    @BeforeEach
    void setUp() {
        computeButton = new Button();
        cancelButton = new Button();
        progressIndicator = new ProgressIndicator();
        computeProgress = new ProgressBar();
        computeStage = new Label();
        failureBanner = new Label();
        drawButton = new ToggleButton();
        clearButton = new Button();
        tagNameField = new TextField();
        tagColorPicker = new ColorPicker();
        applyTagButton = new Button();
        exportButton = new Button();
        emptyState = new StackPane();
        emptyAction = new Button();
        roiFilter = new CheckBox();

        featuresButton = new Button();
        markerDropdown = new ComboBox<>();
        colorScaleDropdown = new ComboBox<>();
        kSpinner = new Spinner<>(5, 50, 10);
        epochsSpinner = new Spinner<>(50, 1000, 50);
        maxCellsSpinner = new Spinner<>(1000, 200000, 50000);
        subsampleMode = new ComboBox<>();
        scalingMode = new ComboBox<>();
        inputs = List.of(featuresButton, markerDropdown, colorScaleDropdown, kSpinner,
                epochsSpinner, maxCellsSpinner, subsampleMode, scalingMode);

        popupsDismissed = new AtomicInteger();
        session = new UmapSession();
        controller = new UiStateController(session, new UiStateController.Controls(
                computeButton, cancelButton, progressIndicator, computeProgress, computeStage,
                failureBanner, drawButton, clearButton,
                tagNameField, tagColorPicker, applyTagButton,
                exportButton, emptyState, emptyAction, roiFilter,
                inputs, popupsDismissed::incrementAndGet));
    }

    // ---------- fixtures ----------

    private static CellIndex index(int cells) {
        return Cells.of(cells).atGrid(1, 1)
                .marker("CD3", i -> i)
                .marker("CD8", i -> i * 2.0)
                .marker("FoxP3", i -> i * 3.0)
                .build();
    }

    private void installCells(int cells) {
        var idx = index(cells);
        session.installIndex(idx, MarkerStats.compute(idx), PANEL,
                CompartmentCapability.empty(), new MarkerSelection());
    }

    private void embed() {
        CellIndex idx = session.index();
        PathObject[] objects = idx.getObjects();
        var result = new UmapResult(new double[idx.size()], new double[idx.size()], objects,
                PANEL.toArray(new String[0]), UmapParameters.defaults());
        session.beginRun();
        session.record(UmapOutcome.succeeded(result,
                EmbeddingReport.training(Embeddings.of(idx), null)
                        .completedWith(EmbeddingReport.Steering.none(),
                                EmbeddingReport.Projection.none())));
    }

    // ---------- per-stage contracts ----------

    @Test
    @DisplayName("A fresh session locks everything actionable and hides cancel/progress")
    void noImage() {
        assertEquals(ViewState.Stage.NO_IMAGE, controller.current().stage());
        assertTrue(computeButton.isDisabled());
        assertCancelHidden();
        assertFalse(progressIndicator.isVisible());
        assertTrue(drawButton.isDisabled());
        assertTrue(clearButton.isDisabled());
        assertTrue(tagNameField.isDisabled());
        assertTrue(tagColorPicker.isDisabled());
        assertTrue(applyTagButton.isDisabled());
        assertTrue(exportButton.isDisabled());
        assertTrue(emptyState.isVisible(), "there is no embedding to show");
        assertFalse(emptyAction.isVisible(), "and nothing to run over");
    }

    @Test
    @DisplayName("Indexed cells enable Run UMAP in both places it appears")
    void ready() {
        installCells(8);
        assertEquals(ViewState.Stage.READY, controller.current().stage());
        assertFalse(computeButton.isDisabled());
        assertTrue(emptyAction.isVisible());
        assertFalse(emptyAction.isDisabled(),
                "the empty state's Run button used to ask the toolbar button how it felt");
        assertTrue(exportButton.isDisabled());
    }

    @Test
    @DisplayName("Fewer ticked markers than UMAP needs disables both Run affordances")
    void notEnoughFeatures() {
        installCells(8);
        // Through the session, the way the feature picker now writes. Putting straight
        // into session.selection() changed whether a run was possible at all without the
        // session hearing it, and the panel went on offering the click.
        session.editSelection("CD8", MarkerSelection.defaultEntry().withIncluded(false));
        session.editSelection("FoxP3", MarkerSelection.defaultEntry().withIncluded(false));

        assertTrue(computeButton.isDisabled(),
                "the toolbar button must not invite a click whose only ending is a failure");
        assertTrue(emptyAction.isDisabled());
        assertTrue(emptyAction.isVisible(), "still offered, so the disabled state is legible");
    }

    @Test
    @DisplayName("A run in flight exposes Cancel, shows progress and locks every input")
    void computing() {
        installCells(8);
        session.beginRun();

        assertEquals(ViewState.Stage.COMPUTING, controller.current().stage());
        assertTrue(computeButton.isDisabled());
        assertCancelVisible();
        assertFalse(cancelButton.isDisabled());
        assertTrue(progressIndicator.isVisible());
        assertTrue(computeProgress.isVisible());
        assertTrue(computeProgress.isManaged());
        assertEquals(ProgressBar.INDETERMINATE_PROGRESS, computeProgress.getProgress());
        assertEquals("Starting…", computeStage.getText());

        // The mid-run-edit hole. onFeatureSelectionChanged has no computeService.cancel(),
        // so it would call session.installRebuiltIndex(...) while the compute thread still
        // holds the old CellIndex. None of these was attached to the machine before.
        for (Node input : inputs) {
            assertTrue(input.isDisabled(),
                    input.getClass().getSimpleName() + " must be locked while a run is in flight");
        }
        assertEquals(1, popupsDismissed.get(),
                "disabling the Features… button does nothing for a picker already open");
    }

    @Test
    @DisplayName("Leaving COMPUTING re-enables every input and clears the stage line")
    void computingReleasesTheInputs() {
        installCells(8);
        session.beginRun();
        session.record(UmapOutcome.cancelled());

        for (Node input : inputs) {
            assertFalse(input.isDisabled(), input.getClass().getSimpleName());
        }
        assertCancelHidden();
        assertFalse(computeProgress.isVisible());
        assertEquals("", computeStage.getText());
    }

    @Test
    @DisplayName("A finished embedding enables gating and export; tag fields await a polygon")
    void computed() {
        installCells(8);
        embed();

        assertEquals(ViewState.Stage.COMPUTED, controller.current().stage());
        assertFalse(computeButton.isDisabled(), "compute stays available for a re-run");
        assertFalse(drawButton.isDisabled());
        assertFalse(clearButton.isDisabled());
        assertTrue(applyTagButton.isDisabled());
        assertFalse(exportButton.isDisabled());
        assertFalse(emptyState.isVisible(), "the overlay comes down when there is a plot");
    }

    @Test
    @DisplayName("A closed polygon unlocks the tag fields, and clearing it locks them again")
    void gatingRoundTrip() {
        installCells(8);
        embed();

        session.setGateMask(new boolean[8]);
        assertEquals(ViewState.Stage.GATING, controller.current().stage());
        assertFalse(tagNameField.isDisabled());
        assertFalse(tagColorPicker.isDisabled());
        assertFalse(applyTagButton.isDisabled());

        session.retireGate();
        assertTrue(applyTagButton.isDisabled());
        assertEquals(ViewState.Stage.COMPUTED, controller.current().stage());
    }

    @Test
    @DisplayName("An applied tag keeps gating and export available")
    void tagged() {
        installCells(8);
        embed();
        session.addTag(new PopulationTag("CD4", 0xFF0000, new boolean[8]));

        assertEquals(ViewState.Stage.TAGGED, controller.current().stage());
        assertFalse(drawButton.isDisabled());
        assertFalse(exportButton.isDisabled());
        assertTrue(applyTagButton.isDisabled(), "tag fields re-lock once applied");
    }

    @Test
    @DisplayName("A cancel under an open polygon leaves Tag Selection unlocked")
    void cancelUnderAnOpenGate() {
        installCells(8);
        embed();
        session.setGateMask(new boolean[8]);
        session.beginRun();
        assertTrue(applyTagButton.isDisabled(), "locked while the re-run is in flight");

        session.cancelRun();

        // clearPolygon()'s copy of the resting rule dropped the gate-mask branch, so this
        // combination used to disable Tag Selection under a user with a polygon closed.
        assertFalse(applyTagButton.isDisabled());
    }

    @Test
    @DisplayName("A failed run says so on the overlay and stays clickable")
    void failed() {
        installCells(8);
        session.beginRun();
        session.record(UmapOutcome.failed("heap exhausted"));

        assertEquals(ViewState.Stage.FAILED, controller.current().stage());
        assertEquals("heap exhausted", controller.current().failure());
        assertTrue(emptyState.isVisible());
        assertTrue(emptyAction.isVisible());
        assertFalse(emptyAction.isDisabled(), "the user may try again");
        assertCancelHidden();
        assertFalse(progressIndicator.isVisible());
    }

    @Test
    @DisplayName("An export in flight withdraws only the Export button")
    void exportInFlight() {
        installCells(8);
        embed();
        session.beginExport();

        assertTrue(exportButton.isDisabled());
        assertFalse(drawButton.isDisabled());
        assertFalse(computeButton.isDisabled());

        session.endExport();
        assertFalse(exportButton.isDisabled());
    }

    @Test
    @DisplayName("The annotation filter is shown while the panel owns its own cell set")
    void annotationFilterVisibility() {
        installCells(8);
        assertTrue(roiFilter.isVisible());
        assertTrue(roiFilter.isManaged());
    }

    @Test
    @DisplayName("An image change under a gating snapshot locks the panel over the stale cells")
    void detachedSnapshotLocksThePanel() {
        var idx = index(8);
        int n = idx.size();
        String[] labels = new String[n];
        for (int i = 0; i < n; i++) labels[i] = "T cell";
        session.adopt(new PhenotypeSnapshot(idx, MarkerStats.compute(idx), PANEL,
                CompartmentCapability.empty(), labels, new int[n], new boolean[n],
                List.of("CD3", "CD8"), new MarkerSelection(), 2, "img"));
        assertFalse(computeButton.isDisabled());

        // What UmapPane does when the active image changes while the gating tree owns the
        // cells. This used to be an imperative setState(NO_IMAGE); the derivation had no
        // fact for it and landed on READY over the PREVIOUS image's PathObjects.
        session.detachSnapshot();

        assertEquals(ViewState.Stage.NO_IMAGE, controller.current().stage());
        assertTrue(computeButton.isDisabled(),
                "Run here would embed the previous image's cells");
        assertTrue(emptyAction.isDisabled());
        assertFalse(emptyAction.isVisible());
        assertFalse(roiFilter.isVisible(),
                "and the annotation filter would re-index the new image behind the "
                        + "gating pane's back");
    }

    @Test
    @DisplayName("A pending feature rebuild disables Run in both places")
    void rebuildPendingLocksRun() {
        installCells(8);
        assertFalse(computeButton.isDisabled());

        session.beginRebuild();
        assertTrue(controller.current().indexRebuilding());
        assertTrue(computeButton.isDisabled(),
                "clicking Run here installs a rebuilt index under the run it starts");
        assertTrue(emptyAction.isDisabled());
        // Editing stays possible — only running is withheld, mirroring a run withholding edits.
        for (Node input : inputs) {
            assertFalse(input.isDisabled());
        }

        session.endRebuild();
        assertFalse(computeButton.isDisabled());
    }

    @Test
    @DisplayName("stateProperty cannot be cast back and written")
    void stateIsReadOnlyToTheOutside() {
        installCells(8);
        var exposed = controller.stateProperty();
        assertFalse(exposed instanceof javafx.beans.property.Property,
                "a writable property lets a caller install a state current() would report");
    }

    /**
     * The reason nothing in this file calls {@code sync()} any more.
     * <p>
     * {@code sync()} took no argument, so a caller could not name a <em>wrong</em> state —
     * but it could still be forgotten, and {@code UmapPane.onUmapResultReady} was the
     * standing proof, retiring the gate and the stale tags after {@code ComputeController}
     * had already synced. The controller now subscribes to the session, so a mutation
     * <em>is</em> the update. Every other test here mutates the session and asserts on the
     * widgets without a sync between them; if the subscription were deleted they would all
     * fail, which is the property this one states out loud.
     */
    @Test
    @DisplayName("A mutation nobody follows up on still reaches the widgets")
    void theSessionPushesItsOwnState() {
        installCells(8);
        assertFalse(computeButton.isDisabled());

        session.beginRun();   // and nothing else
        assertEquals(ViewState.Stage.COMPUTING, controller.current().stage());
        assertTrue(computeButton.isDisabled());
        assertCancelVisible();

        session.cancelRun();
        assertEquals(ViewState.Stage.READY, controller.current().stage());
        assertFalse(computeButton.isDisabled());
    }

    /**
     * Observers see settled states only.
     * <p>
     * {@code adopt} reaches {@code retireCellSet()} at the one instant at which the
     * session's own index/snapshot invariant is false — the snapshot names the incoming
     * cells while the index still holds the outgoing ones. A notification from inside that
     * window would make the invariant observable, and {@code UmapPane}'s summary would be
     * composed from a snapshot and an index describing different slides.
     */
    @Test
    @DisplayName("A nested mutation notifies once, after everything has settled")
    void observersNeverSeeAHalfAppliedChange() {
        var fresh = new UmapSession();
        var seen = new AtomicInteger();
        fresh.observe(state -> {
            seen.incrementAndGet();
            fresh.assertIndexInvariant();
        });
        assertEquals(1, seen.get(), "subscribing delivers the current state");

        var idx = index(8);
        String[] labels = new String[idx.size()];
        java.util.Arrays.fill(labels, "T cell");
        fresh.adopt(new PhenotypeSnapshot(idx, MarkerStats.compute(idx), PANEL,
                CompartmentCapability.empty(), labels, new int[idx.size()],
                new boolean[idx.size()], List.of("CD3", "CD8"), new MarkerSelection(), 2, "img"));
        assertEquals(2, seen.get(),
                "adopt() retires the old cell set on the way through — one change, one notice");

        // detachSnapshot -> clearDerivedState -> retireCellSet is three deep.
        fresh.detachSnapshot();
        assertEquals(3, seen.get());
    }

    @Test
    @DisplayName("A failure over a surviving embedding has somewhere durable to live")
    void aFailedRerunKeepsSayingSoBesideThePlot() {
        installCells(8);
        embed();
        assertFalse(failureBanner.isVisible(), "nothing has failed");
        assertFalse(emptyState.isVisible(), "and the overlay is down over a real plot");

        session.beginRun();
        session.record(UmapOutcome.failed("heap exhausted on the re-run"));

        // The stage stays COMPUTED: the plot still shows something true. Before this, the
        // only trace of the failure was a modal alert and a status line that wiped itself
        // after five seconds, and the empty-state overlay — the other place the reason is
        // written — never comes up while there is an embedding.
        assertEquals(ViewState.Stage.COMPUTED, controller.current().stage());
        assertTrue(failureBanner.isVisible());
        assertTrue(failureBanner.isManaged());
        assertEquals("heap exhausted on the re-run", failureBanner.getText());

        session.beginRun();
        assertFalse(failureBanner.isVisible(), "a new attempt is not the old failure");
    }

    // ---------- invariants across every reachable state ----------

    @Test
    @DisplayName("Compute and Cancel are never both offered, and never both inert with cells")
    void computeXorCancel() {
        installCells(8);
        forEachReachableState(() -> {
            boolean computeEnabled = !computeButton.isDisabled();
            boolean cancelVisible = cancelButton.isVisible();
            assertFalse(computeEnabled && cancelVisible,
                    controller.current().stage() + ": both offered");
            assertTrue(computeEnabled || cancelVisible,
                    controller.current().stage() + ": neither is actionable");
        });
    }

    @Test
    @DisplayName("cancelButton.managed always tracks cancelButton.visible")
    void cancelManagedTracksVisible() {
        installCells(8);
        forEachReachableState(() -> assertEquals(cancelButton.isVisible(), cancelButton.isManaged(),
                controller.current().stage() + ": cancel managed/visible drift"));
    }

    @Test
    @DisplayName("The empty overlay is up exactly when there is no embedding")
    void overlayTracksTheEmbedding() {
        installCells(8);
        forEachReachableState(() -> assertEquals(session.embedding() == null, emptyState.isVisible(),
                controller.current().stage() + ": overlay disagrees with the data"));
    }

    /**
     * Walk the session through every stage reachable from an indexed image. NO_IMAGE is
     * deliberately absent — it is the one stage in which neither Compute nor Cancel is
     * actionable, and {@link #noImage()} pins it on its own.
     */
    private void forEachReachableState(Runnable assertion) {
        assertion.run();                                  // READY

        session.beginRun();
        assertion.run();                                  // COMPUTING

        session.record(UmapOutcome.failed("boom"));
        assertion.run();                                  // FAILED

        embed();
        assertion.run();                                  // COMPUTED

        session.setGateMask(new boolean[8]);
        assertion.run();                                  // GATING

        session.retireGate();
        session.addTag(new PopulationTag("CD4", 0xFF0000, new boolean[8]));
        assertion.run();                                  // TAGGED

    }

    // ---------- construction ----------

    @Test
    void constructionRejectsMissingCollaborators() {
        assertThrows(NullPointerException.class, () -> new UiStateController(null, null));
    }

    // ---------- helpers ----------

    private void assertCancelHidden() {
        assertFalse(cancelButton.isVisible(), "cancelButton should be hidden");
        assertFalse(cancelButton.isManaged(), "cancelButton should not be managed when hidden");
    }

    private void assertCancelVisible() {
        assertTrue(cancelButton.isVisible(), "cancelButton should be visible");
        assertTrue(cancelButton.isManaged(), "cancelButton should be managed when visible");
    }
}
