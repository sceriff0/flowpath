package qupath.ext.flowpath.ui;

import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.cohort.CohortState;
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

    /** The review keys: plain letters and Enter, never while a text field has focus. */
    @Test
    void reviewKeysMapWithoutModifiersAndNeverInATextField() {
        assertEquals(NeedsALookPane.ReviewKey.LOOKS_RIGHT, NeedsALookPane.ReviewKey.of(KeyCode.ENTER, false, false));
        assertEquals(NeedsALookPane.ReviewKey.SKIP, NeedsALookPane.ReviewKey.of(KeyCode.S, false, false));
        assertEquals(NeedsALookPane.ReviewKey.NEXT, NeedsALookPane.ReviewKey.of(KeyCode.N, false, false));
        assertEquals(NeedsALookPane.ReviewKey.PREVIOUS, NeedsALookPane.ReviewKey.of(KeyCode.P, false, false));
        assertEquals(NeedsALookPane.ReviewKey.BACK, NeedsALookPane.ReviewKey.of(KeyCode.ESCAPE, false, false));
        assertEquals(NeedsALookPane.ReviewKey.TOGGLE_OVERLAY, NeedsALookPane.ReviewKey.of(KeyCode.B, false, false));
        assertNull(NeedsALookPane.ReviewKey.of(KeyCode.S, true, false), "Ctrl+S is Save, not Skip");
        assertNull(NeedsALookPane.ReviewKey.of(KeyCode.ENTER, false, true), "Enter in a text field is the field's");
        assertNull(NeedsALookPane.ReviewKey.of(KeyCode.S, false, true), "typing an S is not Skip");
        assertNull(NeedsALookPane.ReviewKey.of(KeyCode.X, false, false));
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

    /** Shift+Enter answers the group; Shift with any other key, or another modifier, is nothing. */
    @Test
    void shiftEnterIsTheGroupsAnswerAndNothingElseTakesShift() {
        assertEquals(NeedsALookPane.ReviewKey.REVIEW_GROUP, NeedsALookPane.ReviewKey.of(KeyCode.ENTER, true, false, false));
        assertEquals(NeedsALookPane.ReviewKey.LOOKS_RIGHT, NeedsALookPane.ReviewKey.of(KeyCode.ENTER, false, false, false));
        assertNull(NeedsALookPane.ReviewKey.of(KeyCode.ENTER, true, true, false), "Ctrl+Shift+Enter is not a review key");
        assertNull(NeedsALookPane.ReviewKey.of(KeyCode.ENTER, true, false, true), "never in a text field");
        assertNull(NeedsALookPane.ReviewKey.of(KeyCode.S, true, false, false), "Shift+S is not Skip");
        assertNull(NeedsALookPane.ReviewKey.of(KeyCode.Z, true, false, false));
    }
}
