package qupath.ext.flowpath.ui;

import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.cohort.CohortState;
import qupath.ext.flowpath.cohort.EvidenceCrop;
import qupath.ext.flowpath.cohort.ReviewItem;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateValues;
import qupath.ext.flowpath.testing.FxTestSupport;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class NeedsALookPaneTest {

    @BeforeAll
    static void initJfx() throws InterruptedException {
        FxTestSupport.startToolkit();
    }

    static ReviewItem item(String slide, int root) {
        return new ReviewItem(new ReviewItem.Key(slide, root, "CD8"), slide + ".tif", new GateNode("CD8", 1),
                List.of(ReviewItem.Flag.ON_PEAK), List.of("Threshold sits on a peak, not in a valley"),
                GateValues.of(new double[]{1}));
    }

    @Test
    void theTitleCountsTheWorkLeftAndAClickReportsTheKey() {
        assumeTrue(FxTestSupport.toolkitAvailable());
        List<ReviewItem.Key> chosen = new ArrayList<>();
        NeedsALookPane pane = FxTestSupport.onFx(NeedsALookPane::new);
        CohortState state = new CohortState(true, false, 3, 3, 0, 2, "ref.tif", null, false, null, false, true);
        FxTestSupport.onFxRun(() -> {
            pane.setOnItemChosen(chosen::add);
            pane.render(state, List.of(item("s1", 0), item("s1", 1)), List.of(), null, 20000);
        });
        assertEquals("Needs a look (2)", FxTestSupport.onFx(pane::getText));
        assertTrue(FxTestSupport.onFx(pane::isVisible));
        FxTestSupport.onFxRun(() -> pane.itemList.getSelectionModel().select(1));
        assertEquals(List.of(new ReviewItem.Key("s1", 1, "CD8")), chosen, "same channel, told apart by rootIndex");
        FxTestSupport.onFxRun(() -> pane.render(CohortState.UNAVAILABLE, List.of(), List.of(), null, 20000));
        assertFalse(FxTestSupport.onFx(pane::isVisible));
    }

    @Test
    void renderingReselectsTheChosenItemByValueWithoutReportingIt() {
        assumeTrue(FxTestSupport.toolkitAvailable());
        List<ReviewItem.Key> chosen = new ArrayList<>();
        NeedsALookPane pane = FxTestSupport.onFx(NeedsALookPane::new);
        CohortState state = new CohortState(true, false, 3, 3, 0, 2, "ref.tif", "s2.tif", false, null, false, true);
        AtomicInteger useReference = new AtomicInteger();
        Object[] shown = FxTestSupport.onFx(() -> {
            pane.setOnItemChosen(chosen::add);
            pane.setOnUseReference(useReference::incrementAndGet);
            // Fresh ReviewItem objects on every rescore: the selection survives by key.
            pane.render(state, List.of(item("s1", 0), item("s1", 1)), List.of(), new ReviewItem.Key("s1", 1, "CD8"), 500);
            pane.useReferenceButton.fire();
            return new Object[]{pane.itemList.getSelectionModel().getSelectedIndex(), pane.sampleSizeField.getText(),
                    pane.useReferenceButton.getText(), pane.useReferenceButton.isVisible(), pane.looksRightButton.isDisable(),
                    pane.referenceLabel.getText()};
        });
        assertEquals(1, shown[0]);
        assertEquals("500", shown[1]);
        assertEquals("Use s2.tif as reference", shown[2]);
        assertEquals(true, shown[3]);
        assertEquals(false, shown[4], "an item is selected, so it can be answered");
        assertEquals("Reference: ref.tif", shown[5]);
        assertTrue(chosen.isEmpty(), "a render is not a click");
        assertEquals(1, useReference.get());
    }

    @Test
    void theSampleSizeFieldReportsOnlyANonNegativeWholeNumber() {
        assumeTrue(FxTestSupport.toolkitAvailable());
        List<Integer> sizes = new ArrayList<>();
        NeedsALookPane pane = FxTestSupport.onFx(NeedsALookPane::new);
        CohortState state = new CohortState(true, false, 3, 3, 0, 0, "ref.tif", null, false, null, false, true);
        String restored = FxTestSupport.onFx(() -> {
            pane.setOnSampleSizeChanged(sizes::add);
            pane.render(state, List.of(), List.of(), null, 20000);
            pane.sampleSizeField.setText("5000");
            pane.sampleSizeField.fireEvent(new javafx.event.ActionEvent());
            pane.sampleSizeField.setText("-3");
            pane.sampleSizeField.fireEvent(new javafx.event.ActionEvent());
            return pane.sampleSizeField.getText();
        });
        assertEquals(List.of(5000), sizes);
        assertEquals("5000", restored, "a bad entry restores the shown value");
    }

    /**
     * The host answers a click by opening the item, which re-renders the list with a rescore's
     * fresh items while the ListView is still inside {@code select()}: the selection must hold.
     */
    @Test
    void aClickWhoseHostReRendersTheListKeepsTheSelection() {
        assumeTrue(FxTestSupport.toolkitAvailable());
        NeedsALookPane pane = FxTestSupport.onFx(NeedsALookPane::new);
        CohortState state = new CohortState(true, false, 3, 3, 0, 3, "ref.tif", null, false, null, false, true);
        List<ReviewItem.Key> chosen = new ArrayList<>();
        Object[] after = FxTestSupport.onFx(() -> {
            pane.setOnItemChosen(k -> {
                chosen.add(k);
                pane.render(state, List.of(item("s1", 0), item("s1", 1), item("s2", 0)), List.of(), k, 20000);
            });
            pane.render(state, List.of(item("s1", 0), item("s1", 1), item("s2", 0)), List.of(), null, 20000);
            pane.itemList.getSelectionModel().select(1);
            ReviewItem sel = pane.itemList.getSelectionModel().getSelectedItem();
            return new Object[]{pane.itemList.getSelectionModel().getSelectedIndex(), sel == null ? null : sel.key()};
        });
        FxTestSupport.onFxRun(() -> {});   // let anything deferred run
        ReviewItem sel = FxTestSupport.onFx(() -> pane.itemList.getSelectionModel().getSelectedItem());
        assertEquals(new ReviewItem.Key("s1", 1, "CD8"), sel == null ? null : sel.key());
        assertEquals(1, FxTestSupport.onFx(() -> pane.itemList.getSelectionModel().getSelectedIndex()));
        assertEquals(List.of(new ReviewItem.Key("s1", 1, "CD8")), chosen, "one click, one report");
        assertNotNull(after[0]);
    }

    @Test
    void aRegionGateItemOffersNoPerSlideAdjust() {
        assumeTrue(FxTestSupport.toolkitAvailable());
        NeedsALookPane pane = FxTestSupport.onFx(NeedsALookPane::new);
        CohortState state = new CohortState(true, false, 3, 3, 0, 2, "ref.tif", null, false, null, false, true);
        ReviewItem region = new ReviewItem(new ReviewItem.Key("s1", 1, "CD8/CD4"), "s1.tif",
                new qupath.ext.flowpath.model.RectangleGate("CD8", "CD4", 0, 1, 0, 1),
                List.of(ReviewItem.Flag.ON_PEAK), List.of("x"), GateValues.of(new double[]{0, 1}, new double[]{0, 1}));
        String[] hints = FxTestSupport.onFx(() -> {
            pane.render(state, List.of(item("s1", 0), region), List.of(), region.key(), 20000);
            String a = pane.adjustHint.getText();
            pane.render(state, List.of(item("s1", 0), region), List.of(), new ReviewItem.Key("s1", 0, "CD8"), 20000);
            return new String[]{a, pane.adjustHint.getText()};
        });
        assertEquals("Per-slide shapes aren't supported yet: Looks right or Skip", hints[0]);
        assertEquals("Adjust: drag the threshold, then Enter", hints[1]);
    }

    /** Groups: one per gate, same-channel roots apart; a click reports the key; the button needs a group. */
    @Test
    void groupsRenderByValueAndAClickReportsTheKey() {
        assumeTrue(FxTestSupport.toolkitAvailable());
        List<qupath.ext.flowpath.cohort.ReviewGroup.Key> chosen = new ArrayList<>();
        AtomicInteger reviewGroup = new AtomicInteger();
        NeedsALookPane pane = FxTestSupport.onFx(NeedsALookPane::new);
        List<qupath.ext.flowpath.cohort.ReviewGroup> groups =
                qupath.ext.flowpath.cohort.ReviewGroup.of(List.of(item("s1", 0), item("s1", 1), item("s2", 1)));
        Object[] shown = FxTestSupport.onFx(() -> {
            pane.setOnGroupChosen(chosen::add);
            pane.setOnReviewGroup(reviewGroup::incrementAndGet);
            pane.renderGroups(groups, null);
            boolean disabledWithout = pane.reviewGroupButton.isDisable();
            // Fresh groups on every rescore: the selection is restored by key, and not reported.
            pane.renderGroups(qupath.ext.flowpath.cohort.ReviewGroup.of(List.of(item("s1", 0), item("s1", 1), item("s2", 1))),
                    new qupath.ext.flowpath.cohort.ReviewGroup.Key(1, "CD8"));
            int selected = pane.groupList.getSelectionModel().getSelectedIndex();
            boolean disabledWith = pane.reviewGroupButton.isDisable();
            pane.reviewGroupButton.fire();
            return new Object[]{disabledWithout, selected, disabledWith, pane.groupList.getItems().size(),
                    pane.reviewGroupButton.getText()};
        });
        assertEquals(true, shown[0], "no group selected, nothing to answer");
        assertEquals(1, shown[1]);
        assertEquals(false, shown[2]);
        assertEquals(2, shown[3], "two same-channel roots are two groups");
        assertEquals("All look right (Shift+Enter)", shown[4]);
        assertTrue(chosen.isEmpty(), "a render is not a click");
        assertEquals(1, reviewGroup.get());
        FxTestSupport.onFxRun(() -> pane.groupList.getSelectionModel().select(0));
        assertEquals(List.of(new qupath.ext.flowpath.cohort.ReviewGroup.Key(0, "CD8")), chosen);
    }

    @Test
    void aCropShowsItsPixelsAFailureItsTextAndTheButtonOpensTheViewer() {
        assumeTrue(FxTestSupport.toolkitAvailable());
        NeedsALookPane pane = FxTestSupport.onFx(NeedsALookPane::new);
        AtomicInteger opened = new AtomicInteger();
        CohortState state = new CohortState(true, false, 3, 3, 0, 2, "ref.tif", null, false, null, false, true);
        Object[] shown = FxTestSupport.onFx(() -> {
            pane.setOnOpenInViewer(opened::incrementAndGet);
            boolean disabledWithoutSelection = pane.openInViewerButton.isDisable();
            pane.render(state, List.of(item("s1", 0), item("s1", 1)), List.of(), new ReviewItem.Key("s1", 1, "CD8"), 500);
            boolean disabledWithSelection = pane.openInViewerButton.isDisable();
            pane.showCropLoading();
            String loading = pane.cropStatusLabel.getText();
            pane.showCrop(new EvidenceCrop.Crop(2, 1, new int[]{0xFF00FF00, 0xFF0000FF}, null));
            int pixel = pane.cropView.getImage().getPixelReader().getArgb(0, 0);
            double width = pane.cropView.getImage().getWidth();
            String okText = pane.cropStatusLabel.getText();
            pane.showCrop(EvidenceCrop.Crop.failed("server gone"));
            boolean imageCleared = pane.cropView.getImage() == null;
            pane.openInViewerButton.fire();
            return new Object[]{disabledWithoutSelection, disabledWithSelection, loading, pixel, width, okText,
                    imageCleared, pane.cropStatusLabel.getText(), pane.cropStatusLabel.getStyleClass().contains("fp-hint")};
        });
        assertEquals(true, shown[0], "nothing selected: nothing to open");
        assertEquals(false, shown[1]);
        assertEquals(NeedsALookPane.CROP_LOADING, shown[2]);
        assertEquals(0xFF00FF00, shown[3]);
        assertEquals(2.0, shown[4]);
        assertEquals("", shown[5]);
        assertEquals(true, shown[6], "a failed crop shows no stale image");
        assertEquals("server gone", shown[7]);
        assertEquals(true, shown[8]);
        assertEquals(1, opened.get());
    }

    @Test
    void theStripHasOneSquarePerSlideAndAClickReportsItsSlide() {
        assumeTrue(FxTestSupport.toolkitAvailable());
        List<String> filtered = new ArrayList<>();
        NeedsALookPane pane = FxTestSupport.onFx(NeedsALookPane::new);
        FxTestSupport.onFxRun(() -> {
            pane.setOnSlideFilter(filtered::add);
            pane.renderStrip(List.of(
                    new qupath.ext.flowpath.cohort.CohortSession.SlideSquare("a", "a.tif",
                            qupath.ext.flowpath.cohort.CohortSession.SlideStatus.READY, 100, 0, null),
                    new qupath.ext.flowpath.cohort.CohortSession.SlideSquare("b", "b.tif",
                            qupath.ext.flowpath.cohort.CohortSession.SlideStatus.NEEDS_LOOK, 100, 2, null)),
                    "2/2 sampled · 2 to review · Ready to run", null);
        });
        assertEquals(2, FxTestSupport.onFx(() -> pane.slideStrip.getChildren().size()));
        assertTrue(FxTestSupport.onFx(() -> pane.slideStrip.getChildren().get(1).getStyleClass().contains("fp-slide-needs-look")));
        assertEquals("2/2 sampled · 2 to review · Ready to run", FxTestSupport.onFx(() -> pane.statusLineLabel.getText()));
        FxTestSupport.onFxRun(() -> pane.slideStrip.getChildren().get(1).getOnMouseClicked().handle(null));
        assertEquals(List.of("b"), filtered);
    }
}
