package qupath.ext.flowpath.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import qupath.ext.flowpath.cohort.CohortSession;
import qupath.ext.flowpath.cohort.CohortState;
import qupath.ext.flowpath.cohort.EvidenceCrop;
import qupath.ext.flowpath.cohort.ReviewGroup;
import qupath.ext.flowpath.cohort.ReviewItem;
import qupath.ext.flowpath.model.Region2DGate;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * "Needs a look": the slide × gate items the review flagged, the answers, and the cohort's
 * reference and sample size. It renders {@link CohortState} and the review as handed in and
 * decides nothing (CLAUDE.md "UI state is derived, never set"); every action is reported to the
 * host, which owns the tree, the undo step and the viewer.
 */
final class NeedsALookPane extends TitledPane {

    /** One square per slide (spec §6 "Slide strip and status line"); see {@link #renderStrip}. */
    final FlowPane slideStrip = new FlowPane(2, 2);
    final Label statusLineLabel = new Label();
    final ListView<ReviewGroup> groupList = new ListView<>();
    final ListView<ReviewItem> itemList = new ListView<>();
    final TextField sampleSizeField = new TextField();
    final Button looksRightButton = new Button("Looks right (Enter)");
    final Button skipButton = new Button("Skip slide for this gate (S)");
    final Button previousButton = new Button("Previous (P)");
    final Button nextButton = new Button("Next (N)");
    final Button reviewGroupButton = new Button("All look right (Shift+Enter)");
    final Button useReferenceButton = new Button();
    static final String ADJUST_HINT = "Adjust: drag the threshold, then Enter";
    static final String NO_REGION_ADJUST_HINT = "Per-slide shapes aren't supported yet: Looks right or Skip";

    final Label adjustHint = new Label(ADJUST_HINT);
    final Label referenceLabel = new Label("Reference: none");
    final Label infoLabel = new Label();
    static final String CROP_LOADING = "Loading crop…";
    /** The selected item's evidence crop (spec §6); its pixels are fixed swatches, not themed text. */
    final ImageView cropView = new ImageView();
    final Label cropStatusLabel = new Label();
    final Button openInViewerButton = new Button("Open in viewer (V)");

    private Consumer<ReviewItem.Key> onItemChosen = k -> {};
    private Consumer<ReviewGroup.Key> onGroupChosen = k -> {};
    private Runnable onReviewGroup = () -> {};
    private Runnable onLooksRight = () -> {};
    private Runnable onSkip = () -> {};
    private IntConsumer onStep = d -> {};
    private Runnable onUseReference = () -> {};
    private IntConsumer onSampleSizeChanged = n -> {};
    private Runnable onOpenInViewer = () -> {};
    private Consumer<String> onSlideFilter = id -> {};

    /** True while {@link #render} sets controls, so the selection it restores is not reported as a click. */
    private boolean rendering;
    private int shownSampleSize;

    NeedsALookPane() {
        setText("Needs a look (0)");
        setAnimated(false);
        setCollapsible(true);
        getStyleClass().add("fp-panel");

        referenceLabel.getStyleClass().add("fp-muted");
        useReferenceButton.setVisible(false);
        useReferenceButton.setManaged(false);
        useReferenceButton.setOnAction(e -> onUseReference.run());
        Label cellsLabel = new Label("Cells per slide:");
        cellsLabel.getStyleClass().add("fp-primary-text");
        sampleSizeField.getStyleClass().add("fp-mono-field");
        sampleSizeField.setPrefColumnCount(7);
        sampleSizeField.setTooltip(new Tooltip("Cells sampled on each slide for alignment and review. 0 samples every cell."));
        sampleSizeField.setOnAction(e -> applySampleSize());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox headerRow = new HBox(6, referenceLabel, spacer, useReferenceButton, cellsLabel, sampleSizeField);
        headerRow.setAlignment(Pos.CENTER_LEFT);

        infoLabel.getStyleClass().add("fp-hint");
        infoLabel.setWrapText(true);
        infoLabel.setVisible(false);
        infoLabel.setManaged(false);

        statusLineLabel.getStyleClass().add("fp-muted");

        groupList.setPrefHeight(90);
        groupList.setCellFactory(lv -> new GroupCell());
        groupList.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            updateAnswerButtons();
            if (!rendering && sel != null) onGroupChosen.accept(sel.key());
        });
        reviewGroupButton.setOnAction(e -> onReviewGroup.run());

        itemList.setPrefHeight(160);
        itemList.setCellFactory(lv -> new ItemCell());
        itemList.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            updateAnswerButtons();
            if (!rendering && sel != null) onItemChosen.accept(sel.key());
        });

        previousButton.setOnAction(e -> onStep.accept(-1));
        nextButton.setOnAction(e -> onStep.accept(+1));
        looksRightButton.setOnAction(e -> onLooksRight.run());
        skipButton.setOnAction(e -> onSkip.run());
        HBox answerRow = new HBox(4, previousButton, nextButton, looksRightButton, skipButton, reviewGroupButton);
        answerRow.setAlignment(Pos.CENTER_LEFT);
        adjustHint.getStyleClass().add("fp-hint");

        cropView.setFitWidth(256);
        cropView.setPreserveRatio(true);
        cropStatusLabel.getStyleClass().add("fp-hint");
        cropStatusLabel.setWrapText(true);
        openInViewerButton.setOnAction(e -> onOpenInViewer.run());
        VBox cropSide = new VBox(4, cropStatusLabel, openInViewerButton);
        HBox cropRow = new HBox(6, cropView, cropSide);
        HBox.setHgrow(cropSide, Priority.ALWAYS);
        updateAnswerButtons();

        setContent(new VBox(4, headerRow, infoLabel, slideStrip, statusLineLabel, groupList, itemList, cropRow,
                answerRow, adjustHint));
    }

    /**
     * Show the cohort as it now stands. Hidden while the cohort is unavailable; the selection is
     * restored by value ({@code selected}), since every rescore hands in fresh items.
     */
    void render(CohortState state, List<ReviewItem> items, List<ReviewItem.Info> infos, ReviewItem.Key selected,
                int sampleSize) {
        rendering = true;
        try {
            setVisible(state.available());
            setManaged(state.available());
            setText("Needs a look (" + state.remaining() + ")");
            itemList.getItems().setAll(items);
            int at = -1;
            for (int i = 0; i < items.size(); i++) if (items.get(i).key().equals(selected)) at = i;
            if (at >= 0) itemList.getSelectionModel().select(at);
            else itemList.getSelectionModel().clearSelection();
            // A region gate has no per-slide Adjust (v1): its item is Looks right or Skip.
            adjustHint.setText(at >= 0 && items.get(at).gate() instanceof Region2DGate
                    ? NO_REGION_ADJUST_HINT : ADJUST_HINT);

            referenceLabel.setText("Reference: " + (state.referenceName() == null ? "none" : state.referenceName()));
            String suggested = state.suggestedReferenceName();
            useReferenceButton.setText(suggested == null ? "" : "Use " + suggested + " as reference");
            useReferenceButton.setVisible(suggested != null);
            useReferenceButton.setManaged(suggested != null);

            if (infos.isEmpty()) {
                infoLabel.setVisible(false);
                infoLabel.setManaged(false);
                infoLabel.setTooltip(null);
            } else {
                infoLabel.setText(infos.size() + " note(s): channels missing on some slides");
                StringBuilder tip = new StringBuilder();
                for (ReviewItem.Info info : infos) {
                    if (!tip.isEmpty()) tip.append('\n');
                    tip.append(info.slideName()).append(" — ").append(info.message());
                }
                infoLabel.setTooltip(new Tooltip(tip.toString()));
                infoLabel.setVisible(true);
                infoLabel.setManaged(true);
            }

            shownSampleSize = sampleSize;
            if (!sampleSizeField.isFocused()) sampleSizeField.setText(Integer.toString(sampleSize));
            updateAnswerButtons();
        } finally {
            rendering = false;
        }
    }

    /**
     * Show the review grouped by gate, top-down; the selection is restored by value
     * ({@code selected}), since every rescore hands in fresh groups, and is not reported.
     */
    void renderGroups(List<ReviewGroup> groups, ReviewGroup.Key selected) {
        rendering = true;
        try {
            groupList.getItems().setAll(groups);
            int at = -1;
            for (int i = 0; i < groups.size(); i++) if (groups.get(i).key().equals(selected)) at = i;
            if (at >= 0) groupList.getSelectionModel().select(at);
            else groupList.getSelectionModel().clearSelection();
            updateAnswerButtons();
        } finally {
            rendering = false;
        }
    }

    /**
     * The slide strip and the status line below it (spec §6): one square per {@code squares}
     * entry, coloured by its status and marked {@code fp-slide-selected} when it is {@code filter};
     * a click reports the square's slide id. Colour and text are decided by {@link CohortSession}
     * — this only renders them.
     */
    void renderStrip(List<CohortSession.SlideSquare> squares, String statusLine, String filter) {
        slideStrip.getChildren().clear();
        for (CohortSession.SlideSquare square : squares) {
            Region node = new Region();
            node.setPrefSize(12, 12);
            node.setMinSize(12, 12);
            node.setMaxSize(12, 12);
            node.getStyleClass().add("fp-slide-square");
            node.getStyleClass().add(switch (square.status()) {
                case SAMPLING, EXCLUDED -> "fp-slide-sampling";
                case READY -> "fp-slide-ready";
                case NEEDS_LOOK -> "fp-slide-needs-look";
                case FAILED -> "fp-slide-failed";
            });
            if (square.slideId().equals(filter)) node.getStyleClass().add("fp-slide-selected");
            Tooltip.install(node, square.status() == CohortSession.SlideStatus.FAILED
                    ? new Tooltip(square.name() + " — " + square.failure())
                    : new Tooltip(square.name() + " — " + square.cells() + " cells, " + square.items() + " to review"));
            String slideId = square.slideId();
            node.setOnMouseClicked(e -> onSlideFilter.accept(slideId));
            slideStrip.getChildren().add(node);
        }
        statusLineLabel.setText(statusLine);
    }

    void setOnSlideFilter(Consumer<String> callback) { onSlideFilter = Objects.requireNonNull(callback); }

    /** A crop is being read for the selected item: no image, and a line saying so. */
    void showCropLoading() {
        cropView.setImage(null);
        cropStatusLabel.setText(CROP_LOADING);
    }

    /** The selected item's crop, or its error text when it failed; the item stays answerable either way. */
    void showCrop(EvidenceCrop.Crop crop) {
        if (crop.ok()) {
            WritableImage img = new WritableImage(crop.width(), crop.height());
            img.getPixelWriter().setPixels(0, 0, crop.width(), crop.height(), PixelFormat.getIntArgbInstance(),
                    crop.argb(), 0, crop.width());
            cropView.setImage(img);
            cropStatusLabel.setText("");
        } else {
            cropView.setImage(null);
            cropStatusLabel.setText(crop.error());
        }
    }

    /** No item selected: no crop. */
    void clearCrop() {
        cropView.setImage(null);
        cropStatusLabel.setText("");
    }

    void setOnOpenInViewer(Runnable callback) { onOpenInViewer = Objects.requireNonNull(callback); }
    void setOnGroupChosen(Consumer<ReviewGroup.Key> callback) { onGroupChosen = Objects.requireNonNull(callback); }
    void setOnReviewGroup(Runnable callback) { onReviewGroup = Objects.requireNonNull(callback); }
    void setOnItemChosen(Consumer<ReviewItem.Key> callback) { onItemChosen = Objects.requireNonNull(callback); }
    void setOnLooksRight(Runnable callback) { onLooksRight = Objects.requireNonNull(callback); }
    void setOnSkip(Runnable callback) { onSkip = Objects.requireNonNull(callback); }
    void setOnStep(IntConsumer callback) { onStep = Objects.requireNonNull(callback); }
    void setOnUseReference(Runnable callback) { onUseReference = Objects.requireNonNull(callback); }
    void setOnSampleSizeChanged(IntConsumer callback) { onSampleSizeChanged = Objects.requireNonNull(callback); }

    /** A non-negative whole number is reported; anything else restores the shown value. */
    private void applySampleSize() {
        int n;
        try {
            n = Integer.parseInt(sampleSizeField.getText().trim());
        } catch (NumberFormatException ex) {
            n = -1;
        }
        if (n < 0) {
            sampleSizeField.setText(Integer.toString(shownSampleSize));
            return;
        }
        shownSampleSize = n;
        sampleSizeField.setText(Integer.toString(n));
        onSampleSizeChanged.accept(n);
    }

    private void updateAnswerButtons() {
        boolean none = itemList.getSelectionModel().getSelectedItem() == null;
        looksRightButton.setDisable(none);
        skipButton.setDisable(none);
        openInViewerButton.setDisable(none);
        boolean empty = itemList.getItems().isEmpty();
        previousButton.setDisable(empty);
        nextButton.setDisable(empty);
        reviewGroupButton.setDisable(groupList.getSelectionModel().getSelectedItem() == null);
    }

    /** One gate: its root's number (two same-channel roots read apart), its path and how many slides. */
    private static final class GroupCell extends ListCell<ReviewGroup> {
        private final Label title = new Label();

        GroupCell() {
            title.getStyleClass().add("fp-primary-text");
        }

        @Override
        protected void updateItem(ReviewGroup group, boolean empty) {
            super.updateItem(group, empty);
            setText(null);
            if (empty || group == null) {
                setGraphic(null);
                return;
            }
            title.setText("#" + (group.rootIndex() + 1) + "  " + group.gatePath() + " — " + group.items().size() + " slide(s)");
            setGraphic(title);
        }
    }

    /** Slide and gate over the reasons, in words. */
    private static final class ItemCell extends ListCell<ReviewItem> {
        private final Label title = new Label();
        private final Label reasons = new Label();
        private final VBox box = new VBox(1, title, reasons);

        ItemCell() {
            title.getStyleClass().add("fp-primary-text");
            reasons.getStyleClass().add("fp-muted");
            reasons.setWrapText(true);
        }

        @Override
        protected void updateItem(ReviewItem item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            title.setText(item.slideName() + " · " + item.key().gatePath());
            reasons.setText(String.join("; ", item.reasons()));
            setText(null);
            setGraphic(box);
        }
    }
}
