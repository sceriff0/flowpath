package qupath.ext.flowpath.umap.ui;

import javafx.collections.FXCollections;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Spinner;
import javafx.scene.control.Tooltip;
import qupath.ext.flowpath.umap.engine.EmbeddingReport;
import qupath.ext.flowpath.umap.engine.UmapComputeService;
import qupath.ext.flowpath.umap.engine.UmapOutcome;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.umap.engine.EmbeddingFeatures;
import qupath.ext.flowpath.umap.session.UmapSession;
import qupath.ext.flowpath.umap.model.ScalingMode;
import qupath.ext.flowpath.umap.model.UmapParameters;
import qupath.ext.flowpath.umap.model.UmapResult;

import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Owns the UMAP-computation UI controls and the compute lifecycle.
 * <p>
 * Extracted from {@link UmapPane} in Phase B2 of the v0.10.0 UI refactor.
 * Responsibilities:
 * <ul>
 *   <li>Build and own the quality-preset combo, k/epochs/dotSize/maxCells spinners,
 *       subsample-mode combo, compute and cancel buttons.</li>
 *   <li>Track the {@code applyingPreset} guard, current {@code negativeSamples}
 *       (set by the active preset) and {@code computeStartTime}.</li>
 *   <li>Wire {@code computeService}'s terminal {@code onOutcome} channel and its
 *       separate {@code onStatusUpdate} progress channel.</li>
 *   <li>Fold each run's terminal {@link UmapOutcome} into {@link UmapSession} and ask
 *       {@link UiStateController} to re-derive the panel from it.</li>
 * </ul>
 *
 * <p>Data and rendering surfaces (canvas, legend, markerOverlay) remain in
 * {@link UmapPane}; the controller hands a finished {@link UmapResult} back via
 * the {@code Consumer<UmapResult>} supplied at construction time, AFTER the outcome has
 * been folded into the session and the panel re-derived from it (so the consumer observes
 * coherent UI state).
 *
 * <h2>Design choices</h2>
 * <ul>
 *   <li><b>No resting-state supplier:</b> this controller used to be handed a
 *       {@code Supplier<UiState>} resolving to COMPUTED/READY/NO_IMAGE, because
 *       {@code UmapPane} owned the embedding. It no longer decides anything about UI
 *       state: it records the outcome on the session and calls
 *       {@link UiStateController#sync()}, which re-derives the whole panel. The
 *       {@code Superseded} branch is safe by construction rather than by comment —
 *       {@link UmapSession#record} leaves the running phase alone for it, so the sync
 *       re-applies the COMPUTING the newer run still owns.</li>
 *   <li><b>computeService ownership:</b> stays in UmapPane; this controller
 *       receives it via constructor and wires its callbacks. UmapPane's
 *       {@code initializeFromImage} keeps calling {@code computeService.cancel()}
 *       directly for tear-down.</li>
 *   <li><b>Status reporting:</b> uses a {@code BiConsumer<String, StatusLevel>}
 *       supplied by UmapPane so colored/auto-clear status semantics stay
 *       centralized in {@code UmapPane#setStatus}.</li>
 * </ul>
 */
final class ComputeController {

    // --- Owned controls ---
    private final ComboBox<String> qualityPreset;
    private final Spinner<Integer> kSpinner;
    private final Spinner<Integer> epochsSpinner;
    private final Spinner<Double> dotSizeSpinner;
    private final Spinner<Integer> maxCellsSpinner;
    private final ComboBox<String> subsampleMode;
    private final ComboBox<String> scalingMode;
    private final Button computeButton;
    private final Button cancelButton;

    // --- Compute lifecycle state ---
    private boolean applyingPreset = false;
    private int negativeSamples = 3;          // Fast preset default; updated by applyPreset
    private long computeStartTime;

    // --- Collaborators ---
    private final UmapComputeService computeService;
    /**
     * The shared UI state machine. Assigned via {@link #attachUiState} after
     * construction because {@link UiStateController} itself takes this
     * controller's compute/cancel buttons as dependencies — there is a
     * one-shot circular reference at construction. After {@code attachUiState}
     * runs (which UmapPane does immediately before driving any state), the
     * reference is effectively final for the lifetime of the controller.
     */
    private UiStateController uiState;
    private final UmapSession session;
    private final Consumer<UmapResult> resultConsumer;
    private final UmapPane.StatusReporter statusReporter;

    /**
     * Construct the compute controller. Builds its own JavaFX controls; expose
     * them via the getters for assembly into the host pane's toolbar.
     *
     * @param computeService        the long-lived background service that runs UMAP
     * @param session               everything this run needs to know and everything its
     *                              ending changes: the index, the feature picker's state
     *                              (read at run time and narrowed into an
     *                              {@link EmbeddingFeatures} here, so an embedding cannot
     *                              be started over a marker the user unticked) and the
     *                              run phase the UI state is derived from
     * @param resultConsumer        invoked with a successful {@link UmapResult}
     *                              AFTER the panel has been re-synced to it
     * @param statusReporter        accepts ({@code text}, {@link UmapPane.StatusLevel})
     *                              pairs so UmapPane can color/auto-clear consistently
     * @param dotSizeListener       called when the user adjusts the Dot Size spinner;
     *                              UmapPane forwards this to its canvases. Kept as a
     *                              callback so the controller owns no rendering surface.
     */
    ComputeController(UmapComputeService computeService,
                      UmapSession session,
                      Consumer<UmapResult> resultConsumer,
                      UmapPane.StatusReporter statusReporter,
                      Consumer<Double> dotSizeListener) {
        this.computeService = Objects.requireNonNull(computeService, "computeService");
        this.session = Objects.requireNonNull(session, "session");
        this.resultConsumer = Objects.requireNonNull(resultConsumer, "resultConsumer");
        this.statusReporter = Objects.requireNonNull(statusReporter, "statusReporter");
        Objects.requireNonNull(dotSizeListener, "dotSizeListener");

        // --- Build controls ---
        qualityPreset = new ComboBox<>(FXCollections.observableArrayList(
                "Fast", "Balanced", "Quality", "Custom"));
        qualityPreset.setValue("Fast");
        qualityPreset.setPrefWidth(90);
        qualityPreset.setTooltip(new Tooltip(
                "Fast: quick preview, good for exploration\n" +
                "Balanced: good quality for most analyses\n" +
                "Quality: publication-ready, slower\n" +
                "Custom: manually tune all parameters"));
        qualityPreset.setOnAction(e -> applyPreset(qualityPreset.getValue()));

        kSpinner = new Spinner<>(5, 50, 10, 5);
        kSpinner.setPrefWidth(70);
        kSpinner.setEditable(true);
        kSpinner.setTooltip(new Tooltip(
                "Neighbors per cell (k) — how much neighborhood UMAP considers.\n" +
                "Low (5–10): teases apart small/rare cell clusters (local detail).\n" +
                "High (30–50): emphasizes broad population structure (global).\n" +
                "Most analyses: 15."));
        kSpinner.valueProperty().addListener((obs, o, n) -> {
            if (!applyingPreset) qualityPreset.setValue("Custom");
        });

        epochsSpinner = new Spinner<>(50, 1000, 50, 50);
        epochsSpinner.setPrefWidth(80);
        epochsSpinner.setEditable(true);
        epochsSpinner.setTooltip(new Tooltip(
                "Optimization passes — how long UMAP refines the layout.\n" +
                "More = cleaner, more stable separation, but slower.\n" +
                "50–100 is plenty for exploration; raise for a final figure."));
        epochsSpinner.valueProperty().addListener((obs, o, n) -> {
            if (!applyingPreset) qualityPreset.setValue("Custom");
        });

        dotSizeSpinner = new Spinner<>(1.0, 5.0, 2.0, 0.5);
        dotSizeSpinner.setPrefWidth(65);
        dotSizeSpinner.setEditable(true);
        dotSizeSpinner.setTooltip(new Tooltip("Size of each cell dot in the plot."));
        dotSizeSpinner.valueProperty().addListener((obs, o, n) -> dotSizeListener.accept(n));

        maxCellsSpinner = new Spinner<>(10000, 200000, 50000, 10000);
        maxCellsSpinner.setPrefWidth(90);
        maxCellsSpinner.setEditable(true);
        maxCellsSpinner.setTooltip(new Tooltip(
                "Maximum cells before subsampling kicks in.\n" +
                "Lower = faster but less complete."));

        subsampleMode = new ComboBox<>(FXCollections.observableArrayList("Auto", "Off", "Fixed"));
        subsampleMode.setValue("Auto");
        subsampleMode.setPrefWidth(70);
        subsampleMode.setTooltip(new Tooltip(
                "Train UMAP on a representative sample for speed/memory, then\n" +
                "project the rest onto the result (phenotype proportions preserved).\n" +
                "Auto: pick a safe sample size from available memory (recommended).\n" +
                "Off: use every cell — slowest, can run out of memory on big slides.\n" +
                "Fixed: train on exactly the Max value below."));

        // Feature scaling — affects the embedding's correctness, not just speed,
        // so it lives in the always-visible toolbar rather than the advanced row.
        scalingMode = new ComboBox<>(FXCollections.observableArrayList(
                ScalingMode.NONE.label(), ScalingMode.ZSCORE.label(), ScalingMode.ARCSINH.label()));
        scalingMode.setValue(ScalingMode.ZSCORE.label());
        scalingMode.setPrefWidth(85);
        scalingMode.setTooltip(new Tooltip(
                "How marker intensities are put on a common scale before UMAP.\n" +
                "UMAP measures cell similarity by distance, so without scaling a few\n" +
                "bright markers (e.g. DAPI, CD45) dominate and rarer biology is lost.\n" +
                "Z-score: standardize each marker (recommended for multiplexed imaging).\n" +
                "Arcsinh: tame bright outliers while keeping low signal.\n" +
                "None: raw values — only if your data is already normalized."));

        computeButton = new Button("Compute UMAP");
        computeButton.setTooltip(new Tooltip("Run UMAP dimensionality reduction on cell data."));
        computeButton.setOnAction(e -> runUmap());

        cancelButton = new Button("Cancel");
        cancelButton.setOnAction(e -> cancelUmap());

        // --- Wire compute service callbacks ---
        computeService.setOnOutcome(this::onUmapOutcome);
        // Phase messages ("Building neighbour graph…", "Projecting remaining N cells…")
        // go to the status reporter, which mirrors them into the rail beside the inline
        // progress bar. They used to also drive a floating progress dialog; that dialog
        // opened over the plot, had to be dragged aside, and duplicated a Cancel button
        // that the rail already shows. Progress now lives under the button that started
        // it, so there is one place to look and nothing covering the data.
        computeService.setOnStatusUpdate(s ->
                statusReporter.report(s, UmapPane.StatusLevel.INFO));
    }

    /**
     * Inject the {@link UiStateController} after construction. Required because
     * {@code UiStateController}'s constructor takes this controller's compute /
     * cancel buttons; UmapPane builds this controller first to obtain those
     * buttons, then constructs the state controller, then wires it back here.
     * Must be called before any lifecycle method ({@link #runUmap()},
     * {@link #cancelUmap()}, {@link #onUmapComplete}, {@link #onUmapError})
     * runs — UmapPane's constructor enforces this.
     */
    void attachUiState(UiStateController uiState) {
        this.uiState = Objects.requireNonNull(uiState, "uiState");
    }

    // --- Control getters (toolbar assembly happens in UmapPane) ---

    ComboBox<String> getQualityPreset() { return qualityPreset; }
    Spinner<Integer> getKSpinner() { return kSpinner; }
    Spinner<Integer> getEpochsSpinner() { return epochsSpinner; }
    Spinner<Double> getDotSizeSpinner() { return dotSizeSpinner; }
    Spinner<Integer> getMaxCellsSpinner() { return maxCellsSpinner; }
    ComboBox<String> getSubsampleMode() { return subsampleMode; }
    ComboBox<String> getScalingMode() { return scalingMode; }
    Button getComputeButton() { return computeButton; }
    Button getCancelButton() { return cancelButton; }

    // --- Preset / spinner helpers ---

    /**
     * Apply a quality preset by mutating the spinner values and the controller's
     * {@code negativeSamples}. The {@code applyingPreset} guard prevents the
     * spinner change listeners from flipping the preset back to "Custom" while
     * the preset is mid-application.
     */
    void applyPreset(String preset) {
        if (applyingPreset || "Custom".equals(preset)) return;
        applyingPreset = true;
        try {
            switch (preset) {
                case "Fast" -> {
                    kSpinner.getValueFactory().setValue(10);
                    epochsSpinner.getValueFactory().setValue(50);
                    negativeSamples = 3;
                }
                case "Balanced" -> {
                    kSpinner.getValueFactory().setValue(15);
                    epochsSpinner.getValueFactory().setValue(100);
                    negativeSamples = 5;
                }
                case "Quality" -> {
                    kSpinner.getValueFactory().setValue(15);
                    epochsSpinner.getValueFactory().setValue(200);
                    negativeSamples = 5;
                }
            }
        } finally {
            qualityPreset.setValue(preset);
            applyingPreset = false;
        }
    }

    /**
     * Force-commit an editable spinner's text into its value factory. JavaFX only
     * commits on Enter/focus-loss, so user-edited values that haven't yet been
     * committed would otherwise be silently lost when {@link #runUmap()} reads
     * the spinner values. On parse failure, the editor text is reset to the
     * current value and a WARN-level status message is emitted.
     */
    <T> void commitSpinner(Spinner<T> spinner) {
        if (spinner.isEditable()) {
            try {
                String text = spinner.getEditor().getText();
                spinner.getValueFactory().setValue(
                        spinner.getValueFactory().getConverter().fromString(text));
            } catch (Exception e) {
                spinner.getEditor().setText(
                        spinner.getValueFactory().getConverter().toString(spinner.getValue()));
                statusReporter.report("Invalid value — reverted to " + spinner.getValue(),
                        UmapPane.StatusLevel.WARN);
            }
        }
    }

    // --- Compute lifecycle ---

    /**
     * Kick off a UMAP run. No-ops (with a status update) when no cell index is
     * available. Commits all editable spinner values, builds {@link UmapParameters},
     * shows the progress dialog, transitions {@code uiState} to COMPUTING, and
     * submits the work to {@link UmapComputeService}.
     */
    void runUmap() {
        CellIndex cellIndex = session.index();
        if (cellIndex == null) {
            statusReporter.report("No cell data available", UmapPane.StatusLevel.WARN);
            return;
        }

        // Force-commit editable spinner values (JavaFX only commits on Enter/focus-loss)
        commitSpinner(kSpinner);
        commitSpinner(epochsSpinner);
        commitSpinner(maxCellsSpinner);
        commitSpinner(dotSizeSpinner);

        UmapParameters params = new UmapParameters(
                kSpinner.getValue(),
                0.1,
                1.0,
                epochsSpinner.getValue(),
                negativeSamples
        );

        int maxCells = switch (subsampleMode.getValue()) {
            case "Off" -> 0;
            case "Fixed" -> maxCellsSpinner.getValue();
            default -> -1; // Auto: let compute service decide based on available memory
        };

        ScalingMode scaling = ScalingMode.fromLabel(scalingMode.getValue());

        // Check if warning needed
        int n = cellIndex.size();
        if (n > 100000 && "Off".equals(subsampleMode.getValue())) {
            var result = new Alert(Alert.AlertType.WARNING,
                    String.format("You have %,d cells. UMAP without subsampling may be slow or run out of memory.\n\n" +
                            "Recommended: Enable subsampling.", n),
                    ButtonType.OK, ButtonType.CANCEL).showAndWait();
            if (result.isEmpty() || result.get() == ButtonType.CANCEL) return;
        }

        // Submit BEFORE showing the busy state. The two used to be the other way
        // round, so anything the submit threw synchronously — a compute() after the
        // service was shut down raises RejectedExecutionException — left the UI in
        // COMPUTING with no run behind it and no callback able to clear it. The
        // service now converts that rejection into a failed outcome rather than
        // throwing, and this ordering means even a throw we have not thought of
        // cannot strand the pane. Both calls happen on the FX thread, so the
        // outcome callback queues behind the rest of this method either way.
        computeStartTime = System.currentTimeMillis();
        // The tooltip describes the run whose result is on screen. A new run invalidates
        // it immediately, rather than leaving the previous run's provenance hanging behind
        // a status line that is now about something else.
        statusReporter.detail(null);
        // The feature picker's veto is applied here and only here. A selection with fewer
        // than two markers ticked comes back Refused and the service turns it into a
        // Failed outcome, which is why this does not check anything itself: the alternative
        // is a second place that decides what "enough features" means.
        computeService.compute(EmbeddingFeatures.of(cellIndex, session.selection()),
                params, maxCells, scaling);

        session.beginRun();
        uiState.sync();
        statusReporter.report("Computing UMAP...", UmapPane.StatusLevel.INFO);
    }

    /**
     * Cancel an in-flight UMAP run. Leaves the running phase immediately so the click feels
     * responsive; the run's own {@code Cancelled} outcome arrives later and is idempotent
     * with this.
     */
    void cancelUmap() {
        computeService.cancel();
        session.cancelRun();
        uiState.sync();
        statusReporter.report("UMAP cancelled", UmapPane.StatusLevel.WARN);
    }

    /**
     * The single terminal callback from {@link UmapComputeService}. Exactly one of
     * these arrives per {@code compute(...)} call, which is what lets the UI leave
     * COMPUTING on every path rather than only the two that used to report.
     * <p>
     * The cancelled/superseded split matters here. A <em>superseded</em> run must be
     * ignored: a newer run is in flight and owns the COMPUTING state, so reacting
     * would drive the pane out of a state that is still true. A <em>cancelled</em> run
     * must be reacted to, because by construction nothing newer is coming.
     */
    void onUmapOutcome(UmapOutcome outcome) {
        // Fold first, then apply. Which state the panel shows is the session's answer, not
        // this method's, so the Superseded case needs no special handling here: the session
        // leaves the running phase set for it and the sync re-applies the COMPUTING the
        // newer run still owns. Reacting to a superseded ending is therefore no longer
        // something a future edit to this switch could accidentally start doing.
        session.record(outcome);
        uiState.sync();
        switch (outcome) {
            case UmapOutcome.Succeeded succeeded ->
                    onUmapComplete(succeeded.result(), succeeded.report());
            case UmapOutcome.Failed failed -> onUmapError(failed.describe());
            case UmapOutcome.Cancelled ignored -> onUmapCancelled();
            case UmapOutcome.Superseded ignored -> { /* the newer run owns the UI */ }
        }
    }

    /**
     * Compute-success callback. {@link #onUmapOutcome} has already installed the result on
     * the session and re-synced the panel, so UmapPane sees coherent UI state when it
     * pushes the new data into the canvases/legend.
     * <p>
     * The status line carries the run's {@link EmbeddingReport} summary as well as its
     * size and timing, and drops to WARN when there is one. That is the whole point of
     * the report: a run that stranded cells at the origin, embedded a marker nothing was
     * measured for, or bought its layout with one fabricated cell position produces a
     * picture that looks exactly like a clean one. This method decides nothing about
     * which of those happened — {@code EmbeddingReport} does, and is tested without a
     * toolkit — it only puts the answer where the user is already looking: the first
     * finding on the line, the whole report in the tooltip behind it.
     */
    void onUmapComplete(UmapResult result, EmbeddingReport report) {
        // Hand the result to UmapPane for rendering / coloring / legend update
        resultConsumer.accept(result);

        long elapsed = System.currentTimeMillis() - computeStartTime;
        String timeStr = elapsed < 1000 ? "%dms".formatted(elapsed)
                : "%.1fs".formatted(elapsed / 1000.0);
        String base = String.format(Locale.US, "UMAP computed: %,d cells (k=%d) in %s",
                result.size(), result.getParams().k(), timeStr);
        String qualifier = report.summary();
        statusReporter.report(qualifier.isEmpty() ? base : base + " — " + qualifier,
                qualifier.isEmpty() ? UmapPane.StatusLevel.SUCCESS : UmapPane.StatusLevel.WARN);
        // The one line carries the first finding and self-clears; the tooltip carries the
        // whole report and stays. Without it the subsample size — which the brief requires
        // be reported, and which decides how much of the picture was optimised rather than
        // projected — would never be visible in the UI at all.
        statusReporter.detail(report.describe());
        if (!report.isClean()) {
            System.err.println("FlowPath UMAP: " + report.describe());
        }
    }

    /**
     * Compute-error callback. The panel has already been re-synced, so the session's
     * {@code FAILED} stage — and the reason with it — is on screen before this modal alert
     * opens and remains after it is dismissed. This only surfaces the message.
     */
    void onUmapError(String message) {
        statusReporter.report("Error: " + message, UmapPane.StatusLevel.ERROR);

        new Alert(Alert.AlertType.ERROR, message, ButtonType.OK).showAndWait();
    }

    /**
     * Compute-cancelled callback. Idempotent with {@link #cancelUmap()}: the button
     * handler drives the UI back immediately so the click feels responsive, and this
     * arrives later when the background run actually notices. Both do the same thing,
     * and a cancelled outcome is only ever delivered when no newer run has started,
     * so this can never pull the pane out of a live COMPUTING state.
     */
    void onUmapCancelled() {
        statusReporter.report("UMAP cancelled", UmapPane.StatusLevel.WARN);
    }

    // --- Test seams (package-private accessors) ---

    /** Visible for tests: snapshot the current preset's negative-samples count. */
    int getNegativeSamples() { return negativeSamples; }
}
