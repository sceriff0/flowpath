package qupath.ext.flowpath.ui.cohort;

import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import qupath.ext.flowpath.cohort.EvidenceCrop;
import qupath.ext.flowpath.cohort.ReviewGroup;
import qupath.ext.flowpath.cohort.ReviewItem;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * The Cohort window's body: banner, slides × gates grid, detail and footer. Renders a
 * {@link CohortGridModel} and reports actions; decides nothing (CLAUDE.md "UI state is derived").
 * Every control that can show a slide name has mnemonic parsing off: JavaFX otherwise reads the
 * {@code _} in {@code slide_B} as a shortcut marker and drops it.
 */
public final class CohortGridPane extends BorderPane {

    final Label headline = unmnemonic(new Label());
    final VBox notes = new VBox(2);
    final Button useSuggested = unmnemonic(new Button());
    final TableView<CohortGridModel.Row> table = new TableView<>();
    final CheckBox onlyLooks = unmnemonic(new CheckBox("Only ⚠"));
    final Label detailTitle = unmnemonic(new Label());
    final Label detailReasons = unmnemonic(new Label());
    final Label detailThresholdLabel = unmnemonic(new Label());
    final Button looksRight = new Button("Looks right (Enter)");
    final Button adjust = new Button("Adjust in editor");
    final Button skip = new Button("Skip this gate (S)");
    final Button useCohortValue = new Button("Use cohort value");
    final TextField sampleSize = new TextField();
    static final String CROP_LOADING = "Loading crop…";
    /** The selected review item's evidence crop (spec §6); its pixels are fixed swatches, not themed text. */
    final ImageView cropView = new ImageView();
    final Label cropStatusLabel = unmnemonic(new Label());

    /** The columns the table was last built for; a re-render with the same ones keeps the TableColumns. */
    private List<CohortGridModel.Column> builtColumns;
    private int shownSampleSize;

    private Consumer<ReviewItem.Key> onCellChosen = k -> {};
    private Runnable onLooksRight = () -> {}, onSkip = () -> {}, onAdjust = () -> {}, onUseCohortValue = () -> {},
            onUseSuggested = () -> {};
    private Consumer<ReviewGroup.Key> onColumnLooksRight = k -> {};
    private Consumer<String> onMakeReference = id -> {}, onToggleExcluded = id -> {};
    private IntConsumer onSampleSizeChanged = n -> {};
    private Consumer<Boolean> onOnlyLooksChanged = b -> {};
    private Consumer<ReviewKey> onKey = k -> {};

    public CohortGridPane() {
        getStyleClass().add("fp-panel");
        headline.getStyleClass().add("fp-cohort-headline");
        useSuggested.setOnAction(e -> onUseSuggested.run());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox top = new HBox(8, headline, spacer, useSuggested);
        top.setAlignment(Pos.CENTER_LEFT);
        VBox banner = new VBox(4, top, notes);
        banner.getStyleClass().add("fp-cohort-banner");
        setTop(banner);

        table.getStyleClass().add("fp-cohort-table");
        table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        table.setRowFactory(tv -> new TableRow<>() {
            @Override protected void updateItem(CohortGridModel.Row row, boolean empty) {
                super.updateItem(row, empty);
                getStyleClass().remove("fp-cohort-row-muted");
                setContextMenu(null);
                if (empty || row == null) return;
                if (row.status() != CohortGridModel.RowStatus.READY) getStyleClass().add("fp-cohort-row-muted");
                MenuItem ref = unmnemonic(new MenuItem("★ Make " + row.name() + " the reference"));
                ref.setDisable(!row.canBeReference());
                ref.setOnAction(e -> onMakeReference.accept(row.slideId()));
                boolean excluded = row.status() == CohortGridModel.RowStatus.EXCLUDED;
                MenuItem ex = unmnemonic(new MenuItem(excluded ? "Include " + row.name() : "Exclude " + row.name() + " from the cohort"));
                ex.setDisable(!excluded && !row.canExclude());
                ex.setOnAction(e -> onToggleExcluded.accept(row.slideId()));
                setContextMenu(new ContextMenu(ref, ex));
            }
        });
        table.setOnKeyPressed(e -> {
            ReviewKey k = ReviewKey.of(e.getCode(), e.isShiftDown(),
                    e.isShortcutDown() || e.isControlDown() || e.isMetaDown() || e.isAltDown(), false);
            if (k != null) { onKey.accept(k); e.consume(); }
        });
        onlyLooks.setOnAction(e -> onOnlyLooksChanged.accept(onlyLooks.isSelected()));
        setCenter(table);

        detailReasons.setWrapText(true);
        detailReasons.getStyleClass().add("fp-muted");
        detailThresholdLabel.getStyleClass().add("fp-mono-field");
        looksRight.setOnAction(e -> onLooksRight.run());
        adjust.setOnAction(e -> onAdjust.run());
        skip.setOnAction(e -> onSkip.run());
        useCohortValue.setOnAction(e -> onUseCohortValue.run());
        HBox answers = new HBox(4, looksRight, adjust, skip, useCohortValue);
        sampleSize.setPrefColumnCount(7);
        sampleSize.getStyleClass().add("fp-mono-field");
        sampleSize.setTooltip(new Tooltip("Cells sampled on each slide for ranking, alignment and review. 0 samples every cell."));
        sampleSize.setOnAction(e -> applySampleSize());
        HBox footer = new HBox(6, onlyLooks, new Label("Cells per slide:"), sampleSize);
        footer.setAlignment(Pos.CENTER_LEFT);
        cropView.setFitWidth(256);
        cropView.setPreserveRatio(true);
        cropStatusLabel.getStyleClass().add("fp-hint");
        cropStatusLabel.setWrapText(true);
        VBox detailText = new VBox(4, detailTitle, detailReasons, detailThresholdLabel, cropStatusLabel);
        HBox.setHgrow(detailText, Priority.ALWAYS);
        HBox detailRow = new HBox(8, cropView, detailText);
        VBox bottom = new VBox(4, detailRow, answers, footer);
        bottom.getStyleClass().add("fp-cohort-detail");
        bottom.setPadding(new Insets(6));
        setBottom(bottom);
    }

    private static <T extends javafx.scene.control.Labeled> T unmnemonic(T l) {
        l.setMnemonicParsing(false);
        return l;
    }

    private static MenuItem unmnemonic(MenuItem m) {
        m.setMnemonicParsing(false);
        return m;
    }

    public void render(CohortGridModel m, int sampleSizeValue) {
        {
            headline.setText(m.banner().headline());
            notes.getChildren().setAll(m.banner().notes().stream().map(t -> {
                Label l = unmnemonic(new Label(t));
                l.getStyleClass().add("fp-hint");
                l.setWrapText(true);
                return l;
            }).toList());
            boolean suggest = m.banner().suggestedId() != null;
            useSuggested.setText(suggest ? "Use " + m.banner().suggestedName() : "");
            useSuggested.setVisible(suggest);
            useSuggested.setManaged(suggest);
            if (!m.columns().equals(builtColumns)) {
                rebuildColumns(m.columns());
                builtColumns = List.copyOf(m.columns());
            }
            setRows(m.rows());
            CohortGridModel.Detail d = m.detail();
            detailTitle.setText(d == null ? "Select a cell to review it" : d.title());
            detailReasons.setText(d == null ? "" : String.join("; ", d.reasons()));
            detailThresholdLabel.setText(d == null || d.referenceValue().isEmpty() ? ""
                    : d.referenceValue() + " → " + d.appliedValue());
            boolean none = d == null;
            boolean flagged = !none && d.mark() == CohortGridModel.CellMark.LOOK;
            looksRight.setDisable(!flagged);
            adjust.setDisable(none || "region".equals(d.referenceValue()));
            skip.setDisable(!flagged);
            useCohortValue.setDisable(none || (d.mark() != CohortGridModel.CellMark.ADJUSTED
                    && d.mark() != CohortGridModel.CellMark.SKIPPED && d.mark() != CohortGridModel.CellMark.REVIEWED));
            shownSampleSize = sampleSizeValue;
            if (!sampleSize.isFocused()) sampleSize.setText(Integer.toString(sampleSizeValue));
        }
    }

    /** Replaces the rows in place, keeping the selected slide (by id) and the scroll position. */
    private void setRows(List<CohortGridModel.Row> rows) {
        CohortGridModel.Row selected = table.getSelectionModel().getSelectedItem();
        String selectedId = selected == null ? null : selected.slideId();
        javafx.scene.control.skin.VirtualFlow<?> flow = table.lookup(".virtual-flow") instanceof
                javafx.scene.control.skin.VirtualFlow<?> f ? f : null;
        int first = -1;
        if (flow != null && flow.getFirstVisibleCell() != null) first = flow.getFirstVisibleCell().getIndex();
        table.getItems().setAll(rows);
        if (selectedId != null) {
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).slideId().equals(selectedId)) { table.getSelectionModel().select(i); break; }
            }
        }
        if (flow != null && first > 0 && first < rows.size()) flow.scrollTo(first);
    }

    private void rebuildColumns(List<CohortGridModel.Column> cols) {
        table.getColumns().clear();
        TableColumn<CohortGridModel.Row, String> star = new TableColumn<>("Ref");
        star.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().reference() ? "★" : "☆"));
        star.setCellFactory(c -> new TableCell<>() {
            @Override protected void updateItem(String s, boolean empty) {
                super.updateItem(s, empty);
                setText(empty ? null : s);
                setOnMouseClicked(e -> {
                    CohortGridModel.Row row = getTableRow() == null ? null : getTableRow().getItem();
                    if (row != null && row.canBeReference()) onMakeReference.accept(row.slideId());
                });
            }
        });
        TableColumn<CohortGridModel.Row, String> name = new TableColumn<>("Slide");
        name.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().name()
                + (c.getValue().statusText().isEmpty() ? "" : "  (" + c.getValue().statusText() + ")")));
        name.setCellFactory(c -> {
            TableCell<CohortGridModel.Row, String> cell = new TableCell<>() {
                @Override protected void updateItem(String s, boolean empty) {
                    super.updateItem(s, empty);
                    setText(empty ? null : s);
                }
            };
            cell.setMnemonicParsing(false);
            return cell;
        });
        TableColumn<CohortGridModel.Row, Number> cells = new TableColumn<>("Cells");
        cells.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().cells()));
        table.getColumns().addAll(List.of(star, name, cells));
        for (int i = 0; i < cols.size(); i++) {
            int at = i;
            CohortGridModel.Column col = cols.get(i);
            TableColumn<CohortGridModel.Row, CohortGridModel.CellMark> tc = new TableColumn<>(col.header());
            tc.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().marks().get(at)));
            tc.setCellFactory(c -> new TableCell<>() {
                @Override protected void updateItem(CohortGridModel.CellMark mark, boolean empty) {
                    super.updateItem(mark, empty);
                    getStyleClass().removeAll("fp-cohort-cell", "fp-cohort-cell-look");
                    setText(empty || mark == null ? null : mark.glyph);
                    if (empty || mark == null) return;
                    getStyleClass().add(mark == CohortGridModel.CellMark.LOOK ? "fp-cohort-cell-look" : "fp-cohort-cell");
                    setOnMouseClicked(e -> {
                        CohortGridModel.Row row = getTableRow() == null ? null : getTableRow().getItem();
                        if (row != null) onCellChosen.accept(new ReviewItem.Key(row.slideId(), col.rootIndex(), col.gatePath()));
                    });
                }
            });
            MenuItem all = unmnemonic(new MenuItem("All look right for " + col.header()));
            all.setOnAction(e -> onColumnLooksRight.accept(new ReviewGroup.Key(col.rootIndex(), col.gatePath())));
            tc.setContextMenu(new ContextMenu(all));
            table.getColumns().add(tc);
        }
    }

    private void applySampleSize() {
        int n;
        try { n = Integer.parseInt(sampleSize.getText().trim()); } catch (NumberFormatException ex) { n = -1; }
        if (n < 0) { sampleSize.setText(Integer.toString(shownSampleSize)); return; }
        shownSampleSize = n;
        onSampleSizeChanged.accept(n);
    }

    /** A crop is being read for the selected item: no image, and a line saying so. */
    public void showCropLoading() {
        cropView.setImage(null);
        cropStatusLabel.setText(CROP_LOADING);
    }

    /** The selected item's crop, or its error text when it failed; the item stays answerable either way. */
    public void showCrop(EvidenceCrop.Crop crop) {
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

    /** No review item selected (none, or a cell with no item): no crop. */
    public void clearCrop() {
        cropView.setImage(null);
        cropStatusLabel.setText("");
    }

    public void setOnCellChosen(Consumer<ReviewItem.Key> c) { onCellChosen = Objects.requireNonNull(c); }
    public void setOnLooksRight(Runnable r) { onLooksRight = Objects.requireNonNull(r); }
    public void setOnSkip(Runnable r) { onSkip = Objects.requireNonNull(r); }
    public void setOnAdjust(Runnable r) { onAdjust = Objects.requireNonNull(r); }
    public void setOnUseCohortValue(Runnable r) { onUseCohortValue = Objects.requireNonNull(r); }
    public void setOnColumnLooksRight(Consumer<ReviewGroup.Key> c) { onColumnLooksRight = Objects.requireNonNull(c); }
    public void setOnMakeReference(Consumer<String> c) { onMakeReference = Objects.requireNonNull(c); }
    public void setOnToggleExcluded(Consumer<String> c) { onToggleExcluded = Objects.requireNonNull(c); }
    public void setOnUseSuggested(Runnable r) { onUseSuggested = Objects.requireNonNull(r); }
    public void setOnSampleSizeChanged(IntConsumer c) { onSampleSizeChanged = Objects.requireNonNull(c); }
    public void setOnOnlyLooksChanged(Consumer<Boolean> c) { onOnlyLooksChanged = Objects.requireNonNull(c); }
    public void setOnKey(Consumer<ReviewKey> c) { onKey = Objects.requireNonNull(c); }
}
