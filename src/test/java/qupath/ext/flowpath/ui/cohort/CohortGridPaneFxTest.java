package qupath.ext.flowpath.ui.cohort;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Labeled;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.cohort.ReviewItem;
import qupath.ext.flowpath.model.cohort.LogScale;
import qupath.ext.flowpath.testing.FxTestSupport;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CohortGridPaneFxTest {

    @BeforeAll
    static void fx() throws InterruptedException { FxTestSupport.startToolkit(); }

    private static final CohortGridModel.Cell REFERENCE_CELL =
            new CohortGridModel.Cell(CohortGridModel.CellMark.REFERENCE, "★", "The reference row", List.of());
    private static final CohortGridModel.Cell LOOK_CELL = new CohortGridModel.Cell(CohortGridModel.CellMark.LOOK,
            "⋀", "Needs a look", List.of(ReviewItem.Flag.ON_PEAK));

    private static CohortGridModel model() {
        var cols = List.of(new CohortGridModel.Column(0, "CD8", "CD8"));
        var rows = List.of(
                new CohortGridModel.Row("1", "slide_A", true, false, CohortGridModel.RowStatus.READY, "", 2000,
                        List.of(REFERENCE_CELL), 0, false, false, -1),
                new CohortGridModel.Row("2", "slide_B", false, true, CohortGridModel.RowStatus.READY, "", 2000,
                        List.of(LOOK_CELL), 1, true, true, 0));
        var banner = new CohortGridModel.Banner("★ slide_A · most central of 2", "2", "slide_B",
                List.of("Suggested: slide_B — most central on 1 of 1 gated columns"));
        var detail = new CohortGridModel.Detail(new ReviewItem.Key("2", 0, "CD8"), "slide_B · CD8",
                CohortGridModel.CellMark.LOOK, List.of("Threshold sits on a peak, not in a valley"),
                "reference 412 → this slide 587", "×1.42 · automatic (UniFORM)", "", null, true, false, false);
        return new CohortGridModel(banner, cols, rows, detail);
    }

    @Test
    void slideNamesKeepTheirUnderscores() {
        assumeTrue(FxTestSupport.toolkitAvailable());
        CohortGridPane pane = FxTestSupport.onFx(CohortGridPane::new);
        FxTestSupport.onFxRun(() -> pane.render(model(), 20000, LogScale.LN));
        List<Labeled> labeled = FxTestSupport.onFx(() -> pane.lookupAll("*").stream()
                .filter(n -> n instanceof Labeled).map(n -> (Labeled) n).toList());
        for (Labeled l : labeled) {
            if (l.getText() != null && l.getText().contains("slide_")) {
                assertFalse(l.isMnemonicParsing(), "mnemonic parsing eats '_' in: " + l.getText());
            }
        }
        assertTrue(labeled.stream().anyMatch(l -> "Use slide_B".equals(l.getText())));
    }

    @Test
    void theDetailShowsReferenceToApplied() {
        assumeTrue(FxTestSupport.toolkitAvailable());
        CohortGridPane pane = FxTestSupport.onFx(CohortGridPane::new);
        FxTestSupport.onFxRun(() -> pane.render(model(), 20000, LogScale.LN));
        String text = FxTestSupport.onFx(() -> pane.detailThresholdLabel.getText());
        assertEquals("reference 412 → this slide 587", text);
    }

    @Test
    void onlyAFlaggedCellTakesLooksRightAndSkip() {
        assumeTrue(FxTestSupport.toolkitAvailable());
        CohortGridPane pane = FxTestSupport.onFx(CohortGridPane::new);
        FxTestSupport.onFxRun(() -> pane.render(model(), 20000, LogScale.LN));
        assertFalse(FxTestSupport.onFx(() -> pane.looksRight.isDisable()));
        assertFalse(FxTestSupport.onFx(() -> pane.skip.isDisable()));
        CohortGridModel m = model();
        var d = m.detail();
        var reviewed = new CohortGridModel.Detail(d.key(), d.title(), CohortGridModel.CellMark.REVIEWED,
                d.reasons(), d.valuesLine(), d.correctionLine(), d.usageLine(), d.histogram(), d.canPickPeak(),
                d.hasPickedPeak(), d.region());
        FxTestSupport.onFxRun(() -> pane.render(new CohortGridModel(m.banner(), m.columns(), m.rows(), reviewed), 20000, LogScale.LN));
        assertTrue(FxTestSupport.onFx(() -> pane.looksRight.isDisable()));
        assertTrue(FxTestSupport.onFx(() -> pane.skip.isDisable()));
    }

    @Test
    void aRerenderWithTheSameColumnsKeepsTheTableColumnsAndTheSelection() {
        assumeTrue(FxTestSupport.toolkitAvailable());
        CohortGridPane pane = FxTestSupport.onFx(CohortGridPane::new);
        FxTestSupport.onFxRun(() -> pane.render(model(), 20000, LogScale.LN));
        var before = FxTestSupport.onFx(() -> List.copyOf(pane.table.getColumns()));
        FxTestSupport.onFxRun(() -> {
            before.get(1).setPrefWidth(333);
            pane.table.getSelectionModel().select(1);
            pane.render(model(), 20000, LogScale.LN);
        });
        var after = FxTestSupport.onFx(() -> List.copyOf(pane.table.getColumns()));
        assertEquals(before.size(), after.size());
        for (int i = 0; i < before.size(); i++) assertSame(before.get(i), after.get(i));
        assertEquals(333, after.get(1).getPrefWidth());
        assertEquals("2", FxTestSupport.onFx(() -> pane.table.getSelectionModel().getSelectedItem().slideId()),
                "the model's selection, whatever the table had");

        var cols = List.of(new CohortGridModel.Column(0, "CD8", "CD8"), new CohortGridModel.Column(0, "CD4", "CD4"));
        var m = model();
        var rows = m.rows().stream().map(r -> new CohortGridModel.Row(r.slideId(), r.name(), r.reference(), r.open(),
                r.status(), r.statusText(), r.cellCount(), List.of(r.cells().get(0), r.cells().get(0)), r.lookCount(), r.canExclude(),
                r.canBeReference(), r.selectedColumn())).toList();
        FxTestSupport.onFxRun(() -> pane.render(new CohortGridModel(m.banner(), cols, rows, m.detail()), 20000, LogScale.LN));
        var rebuilt = FxTestSupport.onFx(() -> List.copyOf(pane.table.getColumns()));
        assertEquals(before.size() + 1, rebuilt.size());
        assertNotSame(before.get(1), rebuilt.get(1));
    }

    @Test
    void aCropShowsItsPixelsAndAFailedCropShowsItsText() {
        assumeTrue(FxTestSupport.toolkitAvailable());
        CohortGridPane pane = FxTestSupport.onFx(CohortGridPane::new);
        Object[] shown = FxTestSupport.onFx(() -> {
            pane.showCropLoading();
            String loading = pane.cropStatusLabel.getText();
            pane.showCrop(new qupath.ext.flowpath.cohort.EvidenceCrop.Crop(2, 1, new int[]{0xFF00FF00, 0xFF0000FF}, null));
            boolean hasImage = pane.cropView.getImage() != null;
            int pixel = pane.cropView.getImage().getPixelReader().getArgb(0, 0);
            double width = pane.cropView.getImage().getWidth();
            String okText = pane.cropStatusLabel.getText();
            pane.showCrop(qupath.ext.flowpath.cohort.EvidenceCrop.Crop.failed("server gone"));
            boolean clearedOnFailure = pane.cropView.getImage() == null;
            String failedText = pane.cropStatusLabel.getText();
            pane.showCrop(new qupath.ext.flowpath.cohort.EvidenceCrop.Crop(1, 1, new int[]{0xFF000000}, null));
            pane.clearCrop();
            return new Object[]{loading, hasImage, pixel, width, okText, clearedOnFailure, failedText,
                    pane.cropView.getImage() == null, pane.cropStatusLabel.getText()};
        });
        assertEquals(CohortGridPane.CROP_LOADING, shown[0]);
        assertEquals(true, shown[1], "an ok crop sets an image");
        assertEquals(0xFF00FF00, shown[2]);
        assertEquals(2.0, shown[3]);
        assertEquals("", shown[4]);
        assertEquals(true, shown[5], "a failed crop shows no stale image");
        assertEquals("server gone", shown[6]);
        assertEquals(true, shown[7], "clearCrop drops the image");
        assertEquals("", shown[8]);
    }

    private static CohortGridModel withSelection(CohortGridModel m, String slideId, List<String> notes) {
        var rows = m.rows().stream().map(r -> new CohortGridModel.Row(r.slideId(), r.name(), r.reference(), r.open(),
                r.status(), r.statusText(), r.cellCount(), r.cells(), r.lookCount(), r.canExclude(), r.canBeReference(),
                r.slideId().equals(slideId) ? 0 : -1)).toList();
        return new CohortGridModel(m.banner(), m.columns(), rows, m.detail(), notes);
    }

    /** Final review item 5: the highlighted row and cell are the model's selection, after it moves too. */
    @Test
    void theHighlightFollowsTheModelsSelection() {
        assumeTrue(FxTestSupport.toolkitAvailable());
        CohortGridPane pane = FxTestSupport.onFx(CohortGridPane::new);
        FxTestSupport.onFxRun(() -> {
            new javafx.scene.Scene(pane, 800, 600);
            pane.render(model(), 20000, LogScale.LN);
            pane.applyCss();
            pane.layout();
        });
        assertEquals("2", FxTestSupport.onFx(() -> pane.table.getSelectionModel().getSelectedItem().slideId()));
        assertEquals(List.of("⋀"), FxTestSupport.onFx(() -> selectedCellTexts(pane)));

        FxTestSupport.onFxRun(() -> {
            pane.render(withSelection(model(), "1", List.of()), 20000, LogScale.LN);
            pane.applyCss();
            pane.layout();
        });
        assertEquals("1", FxTestSupport.onFx(() -> pane.table.getSelectionModel().getSelectedItem().slideId()),
                "N / P moved the selection to another slide: the row follows");
        assertEquals(List.of("★"), FxTestSupport.onFx(() -> selectedCellTexts(pane)));

        FxTestSupport.onFxRun(() -> {
            pane.render(withSelection(model(), null, List.of()), 20000, LogScale.LN);
            pane.applyCss();
            pane.layout();
        });
        assertNull(FxTestSupport.onFx(() -> pane.table.getSelectionModel().getSelectedItem()));
        assertEquals(List.of(), FxTestSupport.onFx(() -> selectedCellTexts(pane)));
    }

    private static List<String> selectedCellTexts(CohortGridPane pane) {
        return pane.table.lookupAll(".fp-cohort-cell-selected").stream()
                .filter(n -> n instanceof javafx.scene.control.TableCell<?, ?> c && !c.isEmpty() && c.isVisible())
                .map(n -> ((Labeled) n).getText()).toList();
    }

    /** Final review item 9: a numeric ⚠ column sorts rows by what they have to look at; the footer shows the notes. */
    @Test
    void rowsSortByLookCountAndTheFooterShowsMissingChannels() {
        assumeTrue(FxTestSupport.toolkitAvailable());
        CohortGridPane pane = FxTestSupport.onFx(CohortGridPane::new);
        FxTestSupport.onFxRun(() -> pane.render(withSelection(model(), null, List.of("slide_B — CD8 is not measured")), 20000, LogScale.LN));
        List<String> order = FxTestSupport.onFx(() -> {
            var looks = pane.table.getColumns().stream().filter(c -> "To check".equals(c.getText())).findFirst().orElseThrow();
            looks.setSortType(javafx.scene.control.TableColumn.SortType.DESCENDING);
            pane.table.getSortOrder().setAll(List.of(looks));
            pane.render(withSelection(model(), null, List.of("slide_B — CD8 is not measured")), 20000, LogScale.LN);
            return pane.table.getItems().stream().map(CohortGridModel.Row::slideId).toList();
        });
        assertEquals(List.of("2", "1"), order, "most to look at first, kept across a re-render");
        assertTrue(FxTestSupport.onFx(() -> pane.missingChannels.isVisible()));
        assertEquals("1 note(s): channels missing on some slides", FxTestSupport.onFx(() -> pane.missingChannels.getText()));
        FxTestSupport.onFxRun(() -> pane.render(model(), 20000, LogScale.LN));
        assertFalse(FxTestSupport.onFx(() -> pane.missingChannels.isVisible()));
    }

    private static java.util.prefs.Preferences scratch() {
        return java.util.prefs.Preferences.userRoot().node("flowpath-test/" + java.util.UUID.randomUUID());
    }

    private static List<javafx.scene.control.TableCell<?, ?>> cellsOf(CohortGridPane pane) {
        return pane.table.lookupAll(".table-cell").stream()
                .filter(n -> n instanceof javafx.scene.control.TableCell<?, ?> c && !c.isEmpty())
                .<javafx.scene.control.TableCell<?, ?>>map(n -> (javafx.scene.control.TableCell<?, ?>) n).toList();
    }

    private static void show(CohortGridPane pane, CohortGridModel m) {
        FxTestSupport.onFxRun(() -> {
            if (pane.getScene() == null) new javafx.scene.Scene(pane, 800, 600);
            pane.render(m, 20000, LogScale.LN);
            pane.applyCss();
            pane.layout();
        });
    }

    @Test
    void rendersCellTextAndTooltip() {
        assumeTrue(FxTestSupport.toolkitAvailable());
        CohortGridPane pane = FxTestSupport.onFx(() -> new CohortGridPane(scratch()));
        show(pane, model());
        List<String[]> got = FxTestSupport.onFx(() -> cellsOf(pane).stream()
                .filter(c -> "⋀".equals(c.getText()))
                .map(c -> new String[]{c.getText(), c.getTooltip() == null ? null : c.getTooltip().getText(),
                        String.valueOf(c.getStyleClass().contains("fp-cohort-cell-look"))}).toList());
        assertEquals(1, got.size());
        assertEquals("Needs a look", got.get(0)[1]);
        assertEquals("true", got.get(0)[2]);
        boolean refLook = FxTestSupport.onFx(() -> cellsOf(pane).stream()
                .filter(c -> "★".equals(c.getText()) && c.getStyleClass().contains("fp-cohort-cell-look")).findAny().isPresent());
        assertFalse(refLook, "the look class is only for LOOK");
    }

    @Test
    void starOnlyOnRowsThatCanBeReference() {
        assumeTrue(FxTestSupport.toolkitAvailable());
        CohortGridPane pane = FxTestSupport.onFx(() -> new CohortGridPane(scratch()));
        show(pane, model());
        var star = FxTestSupport.onFx(() -> pane.table.getColumns().get(0));
        List<String> texts = FxTestSupport.onFx(() -> pane.table.getItems().stream()
                .map(r -> String.valueOf(star.getCellData(r))).toList());
        assertEquals(List.of("★", "☆"), texts);
        // a row that cannot be the reference shows nothing
        var m = model();
        var rows = m.rows().stream().map(r -> new CohortGridModel.Row(r.slideId(), r.name(), r.reference(), r.open(),
                r.status(), r.statusText(), r.cellCount(), r.cells(), r.lookCount(), r.canExclude(), false,
                r.selectedColumn())).toList();
        show(pane, new CohortGridModel(m.banner(), m.columns(), rows, m.detail()));
        List<String> after = FxTestSupport.onFx(() -> pane.table.getItems().stream()
                .map(r -> String.valueOf(star.getCellData(r))).toList());
        assertEquals(List.of("★", ""), after);
        assertEquals("Make slide_B the reference", CohortGridPane.starTooltip(m.rows().get(1)));
    }

    @Test
    void theOpenSlideIsMarkedAndNamesKeepUnderscores() {
        assumeTrue(FxTestSupport.toolkitAvailable());
        CohortGridPane pane = FxTestSupport.onFx(() -> new CohortGridPane(scratch()));
        show(pane, model());
        List<String> names = FxTestSupport.onFx(() -> cellsOf(pane).stream().map(javafx.scene.control.Labeled::getText)
                .filter(t -> t != null && t.contains("slide_")).toList());
        assertTrue(names.contains("● slide_B"), names.toString());
        assertTrue(names.contains("slide_A"));
        boolean tip = FxTestSupport.onFx(() -> cellsOf(pane).stream().anyMatch(c -> "● slide_B".equals(c.getText())
                && c.getTooltip() != null && "Open in the viewer".equals(c.getTooltip().getText())));
        assertTrue(tip);
        assertTrue(FxTestSupport.onFx(() -> cellsOf(pane).stream().filter(c -> c.getText() != null && c.getText().contains("slide_"))
                .noneMatch(javafx.scene.control.Labeled::isMnemonicParsing)));
    }

    @Test
    void theReferenceRowIsStyled() {
        assumeTrue(FxTestSupport.toolkitAvailable());
        CohortGridPane pane = FxTestSupport.onFx(() -> new CohortGridPane(scratch()));
        show(pane, model());
        List<Boolean> styled = FxTestSupport.onFx(() -> pane.table.lookupAll(".table-row-cell").stream()
                .filter(n -> n instanceof javafx.scene.control.TableRow<?> r && !r.isEmpty())
                .map(n -> (javafx.scene.control.TableRow<?>) n)
                .sorted(java.util.Comparator.comparing(r -> ((CohortGridModel.Row) r.getItem()).slideId()))
                .map(r -> r.getStyleClass().contains("fp-cohort-row-reference")).toList());
        assertEquals(List.of(true, false), styled);
    }

    @Test
    void legendCollapsesAndRemembers() {
        assumeTrue(FxTestSupport.toolkitAvailable());
        var node = scratch();
        try {
            CohortGridPane pane = FxTestSupport.onFx(() -> new CohortGridPane(node));
            var m = model();
            var withLegend = new CohortGridModel(m.banner(), m.columns(), m.rows(), m.detail(), List.of(),
                    List.of(new CohortGridModel.LegendEntry("⋀", "needs a look"),
                            new CohortGridModel.LegendEntry("★", "reference")));
            show(pane, withLegend);
            assertEquals(2, FxTestSupport.onFx(() -> pane.legendEntries.getChildren().size()));
            assertEquals("⋀ needs a look", FxTestSupport.onFx(() -> ((Labeled) pane.legendEntries.getChildren().get(0)).getText()));
            assertEquals("▾", FxTestSupport.onFx(() -> pane.legendToggle.getText()));
            assertFalse(FxTestSupport.onFx(() -> pane.legendToggle.isMnemonicParsing()));
            FxTestSupport.onFxRun(() -> pane.legendToggle.fire());
            assertEquals("▸", FxTestSupport.onFx(() -> pane.legendToggle.getText()));
            assertFalse(FxTestSupport.onFx(() -> pane.legendEntries.isVisible()));
            assertTrue(FxTestSupport.onFx(() -> pane.legendToggle.isVisible()), "the toggle stays");
            assertFalse(qupath.ext.flowpath.cohort.CohortPrefs.legendExpanded(node));
            CohortGridPane again = FxTestSupport.onFx(() -> new CohortGridPane(node));
            show(again, withLegend);
            assertFalse(FxTestSupport.onFx(() -> again.legendEntries.isVisible()), "remembered");
            assertEquals("▸", FxTestSupport.onFx(() -> again.legendToggle.getText()));
        } finally {
            try { node.removeNode(); } catch (Exception ignored) { }
        }
    }

    // --- Task 9: the detail histogram, peak picking and the scale chooser ---

    private static CohortGridModel.HistogramView histogram() {
        return new CohortGridModel.HistogramView(0.0, 8.0, new long[]{1, 4, 9, 4, 1, 0, 2, 1},
                new long[]{0, 2, 6, 9, 3, 1, 1, 2}, 2.5, 3.2, Double.NaN, Double.NaN, 5.0, 5.7, LogScale.LN);
    }

    private static CohortGridModel withDetail(boolean canPick, boolean hasPicked) {
        var m = model();
        var d = m.detail();
        var detail = new CohortGridModel.Detail(d.key(), d.title(), d.mark(), d.reasons(), d.valuesLine(),
                d.correctionLine(), d.usageLine(), histogram(), canPick, hasPicked, d.region());
        return new CohortGridModel(m.banner(), m.columns(), m.rows(), detail);
    }

    @Test
    void pickButtonsDisabledWhenCannotPick() {
        assumeTrue(FxTestSupport.toolkitAvailable());
        CohortGridPane pane = FxTestSupport.onFx(() -> new CohortGridPane(scratch()));
        FxTestSupport.onFxRun(() -> pane.render(withDetail(false, true), 20000, LogScale.LN));
        assertTrue(FxTestSupport.onFx(() -> pane.pickSlidePeak.isDisable()));
        assertTrue(FxTestSupport.onFx(() -> pane.pickReferencePeak.isDisable()));
        assertTrue(FxTestSupport.onFx(() -> pane.useAutomatic.isDisable()), "even with a pick, when it cannot pick");

        FxTestSupport.onFxRun(() -> pane.render(withDetail(true, false), 20000, LogScale.LN));
        assertFalse(FxTestSupport.onFx(() -> pane.pickSlidePeak.isDisable()));
        assertFalse(FxTestSupport.onFx(() -> pane.pickReferencePeak.isDisable()));
        assertTrue(FxTestSupport.onFx(() -> pane.useAutomatic.isDisable()), "nothing picked: nothing to clear");

        FxTestSupport.onFxRun(() -> pane.render(withDetail(true, true), 20000, LogScale.LN));
        assertFalse(FxTestSupport.onFx(() -> pane.useAutomatic.isDisable()));
        List<Runnable> cleared = new java.util.ArrayList<>();
        FxTestSupport.onFxRun(() -> {
            pane.setOnClearPeak(() -> cleared.add(() -> {}));
            pane.useAutomatic.fire();
        });
        assertEquals(1, cleared.size());

        FxTestSupport.onFxRun(() -> pane.render(model(), 20000, LogScale.LN));
        assertTrue(FxTestSupport.onFx(() -> pane.pickSlidePeak.isDisable()));
        assertFalse(FxTestSupport.onFx(() -> pane.histogramBox.isVisible()), "no histogram: no canvas");
    }

    private static void click(javafx.scene.canvas.Canvas c, double x) {
        javafx.event.Event.fireEvent(c, new javafx.scene.input.MouseEvent(javafx.scene.input.MouseEvent.MOUSE_CLICKED,
                x, 40, x, 40, javafx.scene.input.MouseButton.PRIMARY, 1, false, false, false, false,
                true, false, false, true, false, true, null));
    }

    @Test
    void armedClickReportsLogValue() {
        assumeTrue(FxTestSupport.toolkitAvailable());
        CohortGridPane pane = FxTestSupport.onFx(() -> new CohortGridPane(scratch()));
        List<Object[]> picked = new java.util.ArrayList<>();
        FxTestSupport.onFxRun(() -> {
            pane.setOnPickPeak((t, u) -> picked.add(new Object[]{t, u}));
            pane.render(withDetail(true, false), 20000, LogScale.LN);
            click(pane.histogramCanvas, 150);          // not armed: nothing
        });
        assertEquals(0, picked.size());
        double expected = FxTestSupport.onFx(() -> {
            pane.pickReferencePeak.fire();
            return pane.histogramCanvas.logAtCanvasX(150);
        });
        assertEquals(CohortHistogramCanvas.PickTarget.REFERENCE, FxTestSupport.onFx(() -> pane.histogramCanvas.armed()));
        FxTestSupport.onFxRun(() -> click(pane.histogramCanvas, 150));
        assertEquals(1, picked.size());
        assertEquals(CohortHistogramCanvas.PickTarget.REFERENCE, picked.get(0)[0]);
        assertEquals(expected, (Double) picked.get(0)[1], 1e-12);
        assertTrue(expected > 0.0 && expected < 8.0, "inside the grid: " + expected);
        assertEquals(CohortHistogramCanvas.PickTarget.NONE, FxTestSupport.onFx(() -> pane.histogramCanvas.armed()),
                "a click disarms");
        FxTestSupport.onFxRun(() -> click(pane.histogramCanvas, 150));
        assertEquals(1, picked.size());

        FxTestSupport.onFxRun(() -> {
            pane.pickSlidePeak.fire();
            javafx.event.Event.fireEvent(pane.histogramCanvas, new javafx.scene.input.KeyEvent(
                    javafx.scene.input.KeyEvent.KEY_PRESSED, "", "", javafx.scene.input.KeyCode.ESCAPE,
                    false, false, false, false));
            click(pane.histogramCanvas, 150);
        });
        assertEquals(1, picked.size(), "Esc disarms");
    }

    @Test
    void scaleChooserReportsChange() {
        assumeTrue(FxTestSupport.toolkitAvailable());
        CohortGridPane pane = FxTestSupport.onFx(() -> new CohortGridPane(scratch()));
        List<LogScale> changes = new java.util.ArrayList<>();
        FxTestSupport.onFxRun(() -> {
            pane.setOnScaleChanged(changes::add);
            pane.render(model(), 20000, LogScale.LN);
            pane.render(model(), 20000, LogScale.LN1P);   // a render reports nothing
        });
        assertEquals(List.of(), changes);
        assertEquals(LogScale.LN1P, FxTestSupport.onFx(() -> pane.scaleChoice.getValue()));
        FxTestSupport.onFxRun(() -> pane.scaleChoice.setValue(LogScale.LN));
        assertEquals(List.of(LogScale.LN), changes);
        assertEquals(LogScale.LN.describe(), FxTestSupport.onFx(() -> pane.scaleChoice.getConverter().toString(LogScale.LN)));
        FxTestSupport.onFxRun(() -> pane.render(model(), 20000, LogScale.LN1P));
        assertEquals(LogScale.LN1P, FxTestSupport.onFx(() -> pane.scaleChoice.getValue()),
                "a failed write re-renders the session's scale, reverting the choice");
        assertEquals(List.of(LogScale.LN), changes);
    }

    /** Ruling R8: the reference's pick can be cleared, and only when there is one. */
    @Test
    void useAutomaticReferenceFollowsTheReferencePick() {
        assumeTrue(FxTestSupport.toolkitAvailable());
        CohortGridPane pane = FxTestSupport.onFx(() -> new CohortGridPane(scratch()));
        FxTestSupport.onFxRun(() -> pane.render(withDetail(true, false), 20000, LogScale.LN));
        assertTrue(FxTestSupport.onFx(() -> pane.useAutomaticReference.isDisable()), "no reference pick");

        var m = withDetail(true, false);
        var d = m.detail();
        var h = d.histogram();
        var picked = new CohortGridModel.HistogramView(h.gridMin(), h.gridMax(), h.reference(), h.slide(),
                h.referenceL1(), h.slideL1(), h.pickedSlidePeak(), 2.7, h.referenceThreshold(),
                h.appliedThreshold(), LogScale.LN1P);
        var withPick = new CohortGridModel(m.banner(), m.columns(), m.rows(), new CohortGridModel.Detail(d.key(),
                d.title(), d.mark(), d.reasons(), d.valuesLine(), d.correctionLine(), d.usageLine(), picked,
                d.canPickPeak(), d.hasPickedPeak(), true, d.region()));
        List<String> cleared = new java.util.ArrayList<>();
        FxTestSupport.onFxRun(() -> {
            pane.setOnClearReferencePeak(() -> cleared.add("reference"));
            pane.render(withPick, 20000, LogScale.LN);
        });
        assertFalse(FxTestSupport.onFx(() -> pane.useAutomaticReference.isDisable()));
        assertEquals(LogScale.LN1P, FxTestSupport.onFx(pane::shownScale),
                "a pick converts with the histogram's scale, not the session's");
        FxTestSupport.onFxRun(() -> pane.useAutomaticReference.fire());
        assertEquals(List.of("reference"), cleared);
        assertTrue(FxTestSupport.onFx(() -> pane.pickReferencePeak.getTooltip().getText()
                .contains("only for slides whose own negative peak was picked")));
    }

    /**
     * Final review T9: a reference pick that reads NaN on the current scale (stored under ln1p
     * below 1, shown on ln) has no diamond to draw, but the button that clears it stays enabled —
     * the stored pick, not its log value, decides.
     */
    @Test
    void useAutomaticReferenceIsEnabledForAStoredPickThatReadsNaN() {
        assumeTrue(FxTestSupport.toolkitAvailable());
        CohortGridPane pane = FxTestSupport.onFx(() -> new CohortGridPane(scratch()));
        var m = withDetail(true, false);
        var d = m.detail();
        assertTrue(Double.isNaN(d.histogram().pickedReferencePeak()), "fixture: nothing to draw");
        var stored = new CohortGridModel(m.banner(), m.columns(), m.rows(), new CohortGridModel.Detail(d.key(),
                d.title(), d.mark(), d.reasons(), d.valuesLine(), d.correctionLine(), d.usageLine(), d.histogram(),
                d.canPickPeak(), d.hasPickedPeak(), true, d.region()));
        FxTestSupport.onFxRun(() -> pane.render(stored, 20000, LogScale.LN));
        assertFalse(FxTestSupport.onFx(() -> pane.useAutomaticReference.isDisable()));
    }
}
