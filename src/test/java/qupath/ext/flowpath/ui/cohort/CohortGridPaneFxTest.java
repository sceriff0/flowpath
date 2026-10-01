package qupath.ext.flowpath.ui.cohort;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Labeled;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.cohort.ReviewItem;
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
        FxTestSupport.onFxRun(() -> pane.render(model(), 20000));
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
        FxTestSupport.onFxRun(() -> pane.render(model(), 20000));
        String text = FxTestSupport.onFx(() -> pane.detailThresholdLabel.getText());
        assertEquals("reference 412 → this slide 587", text);
    }

    @Test
    void onlyAFlaggedCellTakesLooksRightAndSkip() {
        assumeTrue(FxTestSupport.toolkitAvailable());
        CohortGridPane pane = FxTestSupport.onFx(CohortGridPane::new);
        FxTestSupport.onFxRun(() -> pane.render(model(), 20000));
        assertFalse(FxTestSupport.onFx(() -> pane.looksRight.isDisable()));
        assertFalse(FxTestSupport.onFx(() -> pane.skip.isDisable()));
        CohortGridModel m = model();
        var d = m.detail();
        var reviewed = new CohortGridModel.Detail(d.key(), d.title(), CohortGridModel.CellMark.REVIEWED,
                d.reasons(), d.valuesLine(), d.correctionLine(), d.usageLine(), d.histogram(), d.canPickPeak(),
                d.hasPickedPeak(), d.region());
        FxTestSupport.onFxRun(() -> pane.render(new CohortGridModel(m.banner(), m.columns(), m.rows(), reviewed), 20000));
        assertTrue(FxTestSupport.onFx(() -> pane.looksRight.isDisable()));
        assertTrue(FxTestSupport.onFx(() -> pane.skip.isDisable()));
    }

    @Test
    void aRerenderWithTheSameColumnsKeepsTheTableColumnsAndTheSelection() {
        assumeTrue(FxTestSupport.toolkitAvailable());
        CohortGridPane pane = FxTestSupport.onFx(CohortGridPane::new);
        FxTestSupport.onFxRun(() -> pane.render(model(), 20000));
        var before = FxTestSupport.onFx(() -> List.copyOf(pane.table.getColumns()));
        FxTestSupport.onFxRun(() -> {
            before.get(1).setPrefWidth(333);
            pane.table.getSelectionModel().select(1);
            pane.render(model(), 20000);
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
        FxTestSupport.onFxRun(() -> pane.render(new CohortGridModel(m.banner(), cols, rows, m.detail()), 20000));
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
            pane.render(model(), 20000);
            pane.applyCss();
            pane.layout();
        });
        assertEquals("2", FxTestSupport.onFx(() -> pane.table.getSelectionModel().getSelectedItem().slideId()));
        assertEquals(List.of("⋀"), FxTestSupport.onFx(() -> selectedCellTexts(pane)));

        FxTestSupport.onFxRun(() -> {
            pane.render(withSelection(model(), "1", List.of()), 20000);
            pane.applyCss();
            pane.layout();
        });
        assertEquals("1", FxTestSupport.onFx(() -> pane.table.getSelectionModel().getSelectedItem().slideId()),
                "N / P moved the selection to another slide: the row follows");
        assertEquals(List.of("★"), FxTestSupport.onFx(() -> selectedCellTexts(pane)));

        FxTestSupport.onFxRun(() -> {
            pane.render(withSelection(model(), null, List.of()), 20000);
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
        FxTestSupport.onFxRun(() -> pane.render(withSelection(model(), null, List.of("slide_B — CD8 is not measured")), 20000));
        List<String> order = FxTestSupport.onFx(() -> {
            var looks = pane.table.getColumns().stream().filter(c -> "To check".equals(c.getText())).findFirst().orElseThrow();
            looks.setSortType(javafx.scene.control.TableColumn.SortType.DESCENDING);
            pane.table.getSortOrder().setAll(List.of(looks));
            pane.render(withSelection(model(), null, List.of("slide_B — CD8 is not measured")), 20000);
            return pane.table.getItems().stream().map(CohortGridModel.Row::slideId).toList();
        });
        assertEquals(List.of("2", "1"), order, "most to look at first, kept across a re-render");
        assertTrue(FxTestSupport.onFx(() -> pane.missingChannels.isVisible()));
        assertEquals("1 note(s): channels missing on some slides", FxTestSupport.onFx(() -> pane.missingChannels.getText()));
        FxTestSupport.onFxRun(() -> pane.render(model(), 20000));
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
            pane.render(m, 20000);
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
}
