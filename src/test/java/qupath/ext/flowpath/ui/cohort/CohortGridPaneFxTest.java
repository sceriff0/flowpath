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

    private static CohortGridModel model() {
        var cols = List.of(new CohortGridModel.Column(0, "CD8", "CD8"));
        var rows = List.of(
                new CohortGridModel.Row("1", "slide_A", true, CohortGridModel.RowStatus.READY, "", 2000,
                        List.of(CohortGridModel.CellMark.OK), 0, false, false),
                new CohortGridModel.Row("2", "slide_B", false, CohortGridModel.RowStatus.READY, "", 2000,
                        List.of(CohortGridModel.CellMark.LOOK), 1, true, true));
        var banner = new CohortGridModel.Banner("★ slide_A · most central of 2", "2", "slide_B",
                List.of("Suggested: slide_B — most central on 1 of 1 gated columns"));
        var detail = new CohortGridModel.Detail(new ReviewItem.Key("2", 0, "CD8"), "slide_B · CD8",
                CohortGridModel.CellMark.LOOK, List.of("Threshold sits on a peak, not in a valley"), "412", "587");
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
        assertEquals("412 → 587", text);
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
                d.reasons(), d.referenceValue(), d.appliedValue());
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
        assertEquals("2", FxTestSupport.onFx(() -> pane.table.getSelectionModel().getSelectedItem().slideId()));

        var cols = List.of(new CohortGridModel.Column(0, "CD8", "CD8"), new CohortGridModel.Column(0, "CD4", "CD4"));
        var m = model();
        var rows = m.rows().stream().map(r -> new CohortGridModel.Row(r.slideId(), r.name(), r.reference(), r.status(),
                r.statusText(), r.cells(), List.of(r.marks().get(0), r.marks().get(0)), r.lookCount(), r.canExclude(),
                r.canBeReference())).toList();
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
}
