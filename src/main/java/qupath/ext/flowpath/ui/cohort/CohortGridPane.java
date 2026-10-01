package qupath.ext.flowpath.ui.cohort;

import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
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
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.util.StringConverter;
import qupath.ext.flowpath.cohort.CohortPrefs;
import qupath.ext.flowpath.cohort.EvidenceCrop;
import qupath.ext.flowpath.cohort.ReviewGroup;
import qupath.ext.flowpath.cohort.ReviewItem;
import qupath.ext.flowpath.model.cohort.LogScale;
import qupath.ext.flowpath.ui.cohort.CohortHistogramCanvas.PickTarget;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.prefs.Preferences;

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
    final Label detailCorrection = unmnemonic(new Label());
    final Label detailUsage = unmnemonic(new Label());
    /** The selected cell's histogram (spec U2); hidden, with its buttons, when the detail has none. */
    final CohortHistogramCanvas histogramCanvas = new CohortHistogramCanvas();
    final Button pickSlidePeak = new Button("Pick this slide's negative peak");
    final Button pickReferencePeak = new Button("Pick the reference's negative peak");
    final Button useAutomatic = new Button("Use automatic");
    final Button useAutomaticReference = new Button("Use automatic (reference)");
    final VBox histogramBox;
    /** The project's log scale (spec U4); set by {@link #render}, reported only when the user changes it. */
    final ChoiceBox<LogScale> scaleChoice = new ChoiceBox<>();
    /** True while {@link #render} sets {@link #scaleChoice}: a render reports no change. */
    private boolean rendering;
    /** The detail the canvas was armed for: another cell's detail disarms it. */
    private ReviewItem.Key shownDetailKey;
    /** The scale the shown histogram was drawn in; null when none is shown. */
    private LogScale shownScale;
    final Button looksRight = new Button("Looks right (Enter)");
    final Button adjust = new Button("Adjust in editor");
    final Button skip = new Button("Skip this gate (S)");
    final Button useCohortValue = new Button("Use cohort value");
    final TextField sampleSize = new TextField();
    /** The footer's "channels missing on some slides" hint; its tooltip names each slide. */
    final Label missingChannels = unmnemonic(new Label());
    static final String CROP_LOADING = "Loading crop…";
    /** The selected review item's evidence crop (spec §6); its pixels are fixed swatches, not themed text. */
    final ImageView cropView = new ImageView();
    final Label cropStatusLabel = unmnemonic(new Label());

    /** The columns the table was last built for; a re-render with the same ones keeps the TableColumns. */
    private List<CohortGridModel.Column> builtColumns;

    /** One grid cell's value: the model's cell and whether it is the model's selected cell. */
    record CellView(CohortGridModel.Cell cell, boolean selected) {
        CohortGridModel.CellMark mark() { return cell.mark(); }
    }
    private int shownSampleSize;

    /** The legend strip under the table: the toggle stays when the entries collapse. */
    final Button legendToggle = unmnemonic(new Button());
    final HBox legendEntries = new HBox(12);
    private final Preferences prefs;

    private Consumer<ReviewItem.Key> onCellChosen = k -> {};
    private Runnable onLooksRight = () -> {}, onSkip = () -> {}, onAdjust = () -> {}, onUseCohortValue = () -> {},
            onUseSuggested = () -> {};
    private Consumer<ReviewGroup.Key> onColumnLooksRight = k -> {};
    private Consumer<String> onMakeReference = id -> {}, onToggleExcluded = id -> {};
    private IntConsumer onSampleSizeChanged = n -> {};
    private Consumer<Boolean> onOnlyLooksChanged = b -> {};
    private Consumer<ReviewKey> onKey = k -> {};
    private BiConsumer<PickTarget, Double> onPickPeak = (t, u) -> {};
    private Runnable onClearPeak = () -> {}, onClearReferencePeak = () -> {};
    private Consumer<LogScale> onScaleChanged = s -> {};

    public CohortGridPane() {
        this(CohortPrefs.node());
    }

    public CohortGridPane(Preferences prefs) {
        this.prefs = Objects.requireNonNull(prefs);
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
                getStyleClass().removeAll("fp-cohort-row-muted", "fp-cohort-row-reference");
                setContextMenu(null);
                if (empty || row == null) return;
                if (row.status() != CohortGridModel.RowStatus.READY) getStyleClass().add("fp-cohort-row-muted");
                if (row.reference()) getStyleClass().add("fp-cohort-row-reference");
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
        legendToggle.setOnAction(e -> {
            CohortPrefs.setLegendExpanded(this.prefs, !CohortPrefs.legendExpanded(this.prefs));
            applyLegendState();
        });
        legendEntries.setAlignment(Pos.CENTER_LEFT);
        HBox legend = new HBox(8, legendToggle, legendEntries);
        legend.setAlignment(Pos.CENTER_LEFT);
        legend.getStyleClass().add("fp-cohort-legend");
        applyLegendState();
        setCenter(new VBox(table, legend));
        VBox.setVgrow(table, Priority.ALWAYS);

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
        missingChannels.getStyleClass().add("fp-hint");
        missingChannels.setVisible(false);
        missingChannels.setManaged(false);
        scaleChoice.getItems().setAll(LogScale.values());
        scaleChoice.setConverter(new StringConverter<>() {
            @Override public String toString(LogScale s) { return s == null ? "" : s.describe(); }
            @Override public LogScale fromString(String text) { return null; }
        });
        scaleChoice.setTooltip(new Tooltip("The log scale every column's shift is estimated on, for the whole project."));
        scaleChoice.valueProperty().addListener((obs, old, now) -> {
            if (!rendering && now != null && now != old) onScaleChanged.accept(now);
        });
        HBox footer = new HBox(6, onlyLooks, new Label("Cells per slide:"), sampleSize,
                new Label("Shift estimated on:"), scaleChoice, missingChannels);
        footer.setAlignment(Pos.CENTER_LEFT);
        cropView.setFitWidth(256);
        cropView.setPreserveRatio(true);
        cropStatusLabel.getStyleClass().add("fp-hint");
        cropStatusLabel.setWrapText(true);
        detailCorrection.getStyleClass().add("fp-primary-text");
        detailUsage.getStyleClass().add("fp-hint");
        detailUsage.setWrapText(true);
        VBox detailText = new VBox(4, detailTitle, detailReasons, detailThresholdLabel, detailCorrection, detailUsage,
                cropStatusLabel);
        HBox.setHgrow(detailText, Priority.ALWAYS);
        pickSlidePeak.setOnAction(e -> histogramCanvas.arm(PickTarget.SLIDE));
        pickReferencePeak.setOnAction(e -> histogramCanvas.arm(PickTarget.REFERENCE));
        useAutomatic.setOnAction(e -> onClearPeak.run());
        useAutomatic.setTooltip(new Tooltip("Forget this slide's picked peak and estimate it automatically"));
        useAutomaticReference.setOnAction(e -> onClearReferencePeak.run());
        useAutomaticReference.setTooltip(new Tooltip("Forget the reference's picked peak on this column and "
                + "estimate it automatically, for every slide whose own negative peak was picked"));
        pickSlidePeak.setTooltip(new Tooltip("Click this slide's negative peak on the histogram; "
                + "its shift is then taken from your pick (UniFORM landmark mode). Esc cancels."));
        pickReferencePeak.setTooltip(new Tooltip("Click the reference's negative peak on the histogram. It is used "
                + "only for slides whose own negative peak was picked (UniFORM landmark mode). Esc cancels."));
        histogramCanvas.setOnPicked((t, u) -> onPickPeak.accept(t, u));
        HBox pickRow = new HBox(4, pickSlidePeak, pickReferencePeak, useAutomatic, useAutomaticReference);
        histogramBox = new VBox(4, histogramCanvas, pickRow);
        // Esc cancels a pick wherever the focus is, and is not also the review's "back".
        addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE && histogramCanvas.armed() != PickTarget.NONE) {
                histogramCanvas.arm(PickTarget.NONE);
                e.consume();
            }
        });
        HBox detailRow = new HBox(8, cropView, detailText, histogramBox);
        VBox bottom = new VBox(4, detailRow, answers, footer);
        bottom.getStyleClass().add("fp-cohort-detail");
        bottom.setPadding(new Insets(6));
        setBottom(bottom);
    }

    private void applyLegendState() {
        boolean open = CohortPrefs.legendExpanded(prefs);
        legendToggle.setText(open ? "▾" : "▸");
        legendEntries.setVisible(open);
        legendEntries.setManaged(open);
    }

    /** The ☆'s tooltip: what clicking it does. */
    static String starTooltip(CohortGridModel.Row row) {
        return "Make " + row.name() + " the reference";
    }

    private static <T extends javafx.scene.control.Labeled> T unmnemonic(T l) {
        l.setMnemonicParsing(false);
        return l;
    }

    private static MenuItem unmnemonic(MenuItem m) {
        m.setMnemonicParsing(false);
        return m;
    }

    public void render(CohortGridModel m, int sampleSizeValue, LogScale scale) {
        rendering = true;
        try {
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
            legendEntries.getChildren().setAll(m.legend().stream().map(e -> {
                Label l = unmnemonic(new Label(e.glyph() + " " + e.label()));
                l.getStyleClass().add("fp-hint");
                return l;
            }).toList());
            setRows(m.rows());
            List<String> missing = m.missingChannels();
            missingChannels.setText(missing.isEmpty() ? "" : missing.size() + " note(s): channels missing on some slides");
            missingChannels.setTooltip(missing.isEmpty() ? null : new Tooltip(String.join("\n", missing)));
            missingChannels.setVisible(!missing.isEmpty());
            missingChannels.setManaged(!missing.isEmpty());
            CohortGridModel.Detail d = m.detail();
            detailTitle.setText(d == null ? "Select a cell to review it" : d.title());
            detailReasons.setText(d == null ? "" : String.join("; ", d.reasons()));
            detailThresholdLabel.setText(d == null ? "" : d.valuesLine());
            boolean none = d == null;
            boolean flagged = !none && d.mark() == CohortGridModel.CellMark.LOOK;
            looksRight.setDisable(!flagged);
            adjust.setDisable(none || d.region());
            skip.setDisable(!flagged);
            useCohortValue.setDisable(none || (d.mark() != CohortGridModel.CellMark.ADJUSTED
                    && d.mark() != CohortGridModel.CellMark.SKIPPED && d.mark() != CohortGridModel.CellMark.REVIEWED));
            shownSampleSize = sampleSizeValue;
            if (!sampleSize.isFocused()) sampleSize.setText(Integer.toString(sampleSizeValue));
            renderHistogram(d);
            scaleChoice.setValue(scale == null ? LogScale.LN : scale);
        } finally {
            rendering = false;
        }
    }

    /** The histogram and its buttons: all three disabled unless the detail can take a pick. */
    private void renderHistogram(CohortGridModel.Detail d) {
        detailCorrection.setText(d == null ? "" : d.correctionLine());
        detailUsage.setText(d == null ? "" : d.usageLine());
        CohortGridModel.HistogramView h = d == null ? null : d.histogram();
        ReviewItem.Key key = d == null ? null : d.key();
        if (!Objects.equals(key, shownDetailKey)) histogramCanvas.arm(PickTarget.NONE);
        shownDetailKey = key;
        histogramCanvas.show(h, d != null && d.region());
        histogramBox.setVisible(h != null);
        histogramBox.setManaged(h != null);
        boolean canPick = d != null && d.canPickPeak() && h != null;
        pickSlidePeak.setDisable(!canPick);
        pickReferencePeak.setDisable(!canPick);
        useAutomatic.setDisable(!(d != null && d.canPickPeak() && d.hasPickedPeak()));
        // Ruling R8: a wrong reference pick shifts every landmark-mode slide on the column, so it must be undoable here.
        useAutomaticReference.setDisable(h == null || !Double.isFinite(h.pickedReferencePeak()));
        shownScale = h == null ? null : h.scale();
        if (!canPick) histogramCanvas.arm(PickTarget.NONE);
    }

    /**
     * Replaces the rows in place, keeping the user's sort and the scroll position. The selected
     * row is the model's ({@link CohortGridModel.Row#selectedColumn}), never the table's own: after
     * N / P or an answer the model's selection moved, and the highlight must follow the detail.
     * Selecting here fires no callback — only a click reports a chosen cell.
     */
    private void setRows(List<CohortGridModel.Row> rows) {
        javafx.scene.control.skin.VirtualFlow<?> flow = table.lookup(".virtual-flow") instanceof
                javafx.scene.control.skin.VirtualFlow<?> f ? f : null;
        int first = -1;
        if (flow != null && flow.getFirstVisibleCell() != null) first = flow.getFirstVisibleCell().getIndex();
        table.getItems().setAll(rows);
        if (!table.getSortOrder().isEmpty()) table.sort();
        CohortGridModel.Row selected = null;
        for (CohortGridModel.Row r : table.getItems()) if (r.selectedColumn() >= 0) selected = r;
        if (selected == null) table.getSelectionModel().clearSelection();
        else table.getSelectionModel().select(selected);
        if (flow != null && first > 0 && first < rows.size()) flow.scrollTo(first);
    }

    private void rebuildColumns(List<CohortGridModel.Column> cols) {
        table.getColumns().clear();
        TableColumn<CohortGridModel.Row, String> star = new TableColumn<>("Ref");
        star.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(
                c.getValue().reference() ? "★" : c.getValue().canBeReference() ? "☆" : ""));
        star.setCellFactory(c -> new TableCell<>() {
            @Override protected void updateItem(String s, boolean empty) {
                super.updateItem(s, empty);
                setText(empty ? null : s);
                CohortGridModel.Row row = empty || getTableRow() == null ? null : getTableRow().getItem();
                boolean clickable = row != null && !row.reference() && row.canBeReference();
                setTooltip(clickable ? new Tooltip(starTooltip(row)) : null);
                setOnMouseClicked(clickable ? e -> onMakeReference.accept(row.slideId()) : null);
            }
        });
        TableColumn<CohortGridModel.Row, String> name = new TableColumn<>("Slide");
        name.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>((c.getValue().open() ? "● " : "") + c.getValue().name()
                + (c.getValue().statusText().isEmpty() ? "" : "  (" + c.getValue().statusText() + ")")));
        name.setCellFactory(c -> {
            TableCell<CohortGridModel.Row, String> cell = new TableCell<>() {
                @Override protected void updateItem(String s, boolean empty) {
                    super.updateItem(s, empty);
                    setText(empty ? null : s);
                    CohortGridModel.Row row = empty || getTableRow() == null ? null : getTableRow().getItem();
                    setTooltip(row != null && row.open() ? new Tooltip("Open in the viewer") : null);
                }
            };
            cell.setMnemonicParsing(false);
            return cell;
        });
        TableColumn<CohortGridModel.Row, Number> cells = new TableColumn<>("Cells");
        cells.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().cellCount()));
        // Sortable by the number of cells to look at (spec §3.2), numerically.
        TableColumn<CohortGridModel.Row, Number> looks = new TableColumn<>("To check");
        looks.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().lookCount()));
        looks.setComparator(Comparator.comparingInt(Number::intValue));
        table.getColumns().addAll(List.of(star, name, cells, looks));
        for (int i = 0; i < cols.size(); i++) {
            int at = i;
            CohortGridModel.Column col = cols.get(i);
            TableColumn<CohortGridModel.Row, CellView> tc = new TableColumn<>(col.header());
            // The selection is part of the value, so a cell whose mark is unchanged still repaints
            // when the selection moves onto or off it.
            tc.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(
                    new CellView(c.getValue().cells().get(at), c.getValue().selectedColumn() == at)));
            tc.setComparator(Comparator.comparing(CellView::mark).thenComparing(v -> v.cell().text()));
            tc.setCellFactory(c -> new TableCell<>() {
                @Override protected void updateItem(CellView view, boolean empty) {
                    super.updateItem(view, empty);
                    getStyleClass().removeAll("fp-cohort-cell", "fp-cohort-cell-look", "fp-cohort-cell-selected");
                    setTooltip(null);
                    CohortGridModel.CellMark mark = view == null ? null : view.mark();
                    setText(empty || mark == null ? null : view.cell().text());
                    if (empty || mark == null) return;
                    String tip = view.cell().tooltip();
                    if (tip != null && !tip.isEmpty()) setTooltip(new Tooltip(tip));
                    getStyleClass().add(mark == CohortGridModel.CellMark.LOOK ? "fp-cohort-cell-look" : "fp-cohort-cell");
                    if (view.selected()) getStyleClass().add("fp-cohort-cell-selected");
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
    /** A pick landed: the target and the LOG value clicked. */
    public void setOnPickPeak(BiConsumer<PickTarget, Double> c) { onPickPeak = Objects.requireNonNull(c); }
    /**
     * The log scale the shown histogram — and so a pick's reported log value — is on: the model's,
     * which can differ from the session's between a scale change and the rescore that follows.
     */
    public LogScale shownScale() { return shownScale; }
    /** "Use automatic (reference)": forget the reference's pick on this column. */
    public void setOnClearReferencePeak(Runnable r) { onClearReferencePeak = Objects.requireNonNull(r); }
    /** "Use automatic": forget this slide's pick. */
    public void setOnClearPeak(Runnable r) { onClearPeak = Objects.requireNonNull(r); }
    /** The user chose another log scale; a re-render with the session's scale reverts a refused one. */
    public void setOnScaleChanged(Consumer<LogScale> c) { onScaleChanged = Objects.requireNonNull(c); }
}
