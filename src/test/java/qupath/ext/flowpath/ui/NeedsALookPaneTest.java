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
}
