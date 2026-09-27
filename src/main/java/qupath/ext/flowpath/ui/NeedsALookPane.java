package qupath.ext.flowpath.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import qupath.ext.flowpath.cohort.CohortState;
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

    /** The review keys; see {@link #of}. */
    enum ReviewKey {
        LOOKS_RIGHT, SKIP, NEXT, PREVIOUS, BACK, TOGGLE_OVERLAY;

        /**
         * The review action a key press asks for, or null. Plain keys only — every existing
         * FlowPath shortcut carries a modifier, so none of these collide — and never while a text
         * field has focus, where Enter and letters belong to the field.
         */
        static ReviewKey of(KeyCode code, boolean anyModifier, boolean textFieldFocused) {
            if (anyModifier || textFieldFocused || code == null) return null;
            return switch (code) {
                case ENTER -> LOOKS_RIGHT;
                case S -> SKIP;
                case N -> NEXT;
                case P -> PREVIOUS;
                case ESCAPE -> BACK;
                case B -> TOGGLE_OVERLAY;
                default -> null;
            };
        }
    }

    final ListView<ReviewItem> itemList = new ListView<>();
    final TextField sampleSizeField = new TextField();
    final Button looksRightButton = new Button("Looks right (Enter)");
    final Button skipButton = new Button("Skip slide for this gate (S)");
    final Button previousButton = new Button("Previous (P)");
    final Button nextButton = new Button("Next (N)");
    final Button useReferenceButton = new Button();
    static final String ADJUST_HINT = "Adjust: drag the threshold, then Enter";
    static final String NO_REGION_ADJUST_HINT = "Per-slide shapes aren't supported yet: Looks right or Skip";

    final Label adjustHint = new Label(ADJUST_HINT);
    final Label referenceLabel = new Label("Reference: none");
    final Label infoLabel = new Label();

    private Consumer<ReviewItem.Key> onItemChosen = k -> {};
    private Runnable onLooksRight = () -> {};
    private Runnable onSkip = () -> {};
    private IntConsumer onStep = d -> {};
    private Runnable onUseReference = () -> {};
    private IntConsumer onSampleSizeChanged = n -> {};

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
        HBox answerRow = new HBox(4, previousButton, nextButton, looksRightButton, skipButton);
        answerRow.setAlignment(Pos.CENTER_LEFT);
        adjustHint.getStyleClass().add("fp-hint");
        updateAnswerButtons();

        setContent(new VBox(4, headerRow, infoLabel, itemList, answerRow, adjustHint));
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
        boolean empty = itemList.getItems().isEmpty();
        previousButton.setDisable(empty);
        nextButton.setDisable(empty);
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
