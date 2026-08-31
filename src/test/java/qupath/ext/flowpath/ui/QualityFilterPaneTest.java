package qupath.ext.flowpath.ui;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.QualityFilter;
import qupath.ext.flowpath.testing.Cells;
import qupath.ext.flowpath.testing.FxTestSupport;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The quality-filter panel offers exactly the morphology the export carries.
 * <p>
 * It used to draw five fixed rows and patch their ceilings afterwards through an
 * {@code updateRanges(maxArea, maxTotalIntensity, maxPerimeter)} call that knew about three
 * of the five. That is where the "total intensity max does not start at the top" bug lived,
 * and the shape of the method is why: a guessed slider ceiling has to be corrected once the
 * data arrives, and correcting it must not clobber a bound the user set. Sliders now span
 * their own column, so neither problem is expressible — but both invariants are still
 * pinned below, because they are about the filter, not the guess.
 */
class QualityFilterPaneTest {

    /** A MIRAGE-shaped index whose axis lengths vary, so they are real filterable columns. */
    private static CellIndex mirageIndex() {
        return Cells.of(20)
                .mirageMedianMarker("CD3", i -> 100.0 + i)
                .mirageMorphology(i -> 50.0 + i * 10)          // Area µm², varies
                .morphology("Major Axis Length µm", i -> 8.0 + i * 0.5)
                .morphology("Minor Axis Length µm", i -> 4.0 + i * 0.25)
                .build();
    }

    @Test
    void offersTheMorphologyTheExportCarriesIncludingWhatFlowPathNeverNamed() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        CellIndex index = mirageIndex();
        QualityFilterPane pane = FxTestSupport.onFx(() -> new QualityFilterPane(new QualityFilter()));
        FxTestSupport.onFxRun(() -> pane.setCellIndex(index));

        List<String> shown = pane.shownFields();
        assertTrue(shown.contains("area"), "area: " + shown);
        // The point of the change: these are in every MIRAGE export and were unreachable.
        assertTrue(shown.contains("major_axis_length"),
                "Major Axis Length is in the file and must be filterable: " + shown);
        assertTrue(shown.contains("minor_axis_length"),
                "Minor Axis Length is in the file and must be filterable: " + shown);
        // Convex area backs the solidity derivation; offering it too would be two controls
        // over one quantity.
        assertFalse(shown.contains("convex_area"), "convex area is not its own filter: " + shown);
    }

    /** A column the file does not carry gets no row, rather than a slider over NaN. */
    @Test
    void offersNothingTheExportDoesNotCarry() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        // Bare markers and an area only -- the whole-cell-only shape.
        CellIndex index = Cells.of(10).marker("CD3", i -> 10.0 + i).area(i -> 60.0 + i).build();
        QualityFilterPane pane = FxTestSupport.onFx(() -> new QualityFilterPane(new QualityFilter()));
        FxTestSupport.onFxRun(() -> pane.setCellIndex(index));

        List<String> shown = pane.shownFields();
        assertTrue(shown.contains("area"));
        assertFalse(shown.contains("solidity"),
                "no solidity in this export, so no solidity slider over a column of NaN: " + shown);
        assertFalse(shown.contains("major_axis_length"), shown.toString());
    }

    /** With no cells loaded the panel is empty, not a set of sliders over nothing. */
    @Test
    void offersNothingBeforeAnyCellsAreLoaded() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        QualityFilterPane pane = FxTestSupport.onFx(() -> new QualityFilterPane(new QualityFilter()));
        assertTrue(pane.shownFields().isEmpty());
        FxTestSupport.onFxRun(() -> pane.setCellIndex(null));
        assertTrue(pane.shownFields().isEmpty());
    }

    /**
     * The old {@code updateRanges} bug, restated: loading an image must not turn a fresh,
     * unconstrained filter into one that excludes cells.
     */
    @Test
    void aFreshFilterStaysUnconstrainedWhenTheDataArrives() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        QualityFilter filter = new QualityFilter();
        QualityFilterPane pane = FxTestSupport.onFx(() -> new QualityFilterPane(filter));
        CellIndex index = mirageIndex();

        FxTestSupport.onFxRun(() -> pane.setCellIndex(index));

        assertTrue(pane.getFilter().isEmpty(),
                "nothing was set, so nothing may be constrained: " + pane.getFilter().ranges());
        for (int i = 0; i < index.size(); i++) {
            assertTrue(pane.getFilter().passes(index, i), "cell " + i + " must pass");
        }
    }

    /** And an explicit bound must survive that same load. */
    @Test
    void aUserSetBoundSurvivesTheDataArriving() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        QualityFilter filter = new QualityFilter();
        filter.setRange("area", new QualityFilter.Range(Double.NEGATIVE_INFINITY, 100.0));
        QualityFilterPane pane = FxTestSupport.onFx(() -> new QualityFilterPane(filter));

        CellIndex index = mirageIndex();          // area runs 50..240
        FxTestSupport.onFxRun(() -> pane.setCellIndex(index));

        assertEquals(100.0, pane.getFilter().range("area").max(), 1e-6,
                "an explicit upper bound must not be widened by the data arriving");
        assertFalse(pane.getFilter().passes(index, index.size() - 1),
                "and it must still exclude the cells it names");
    }

    /** A range over a field this export lacks cannot exclude anything. */
    @Test
    void aRangeOverAnAbsentFieldExcludesNoCell() {
        QualityFilter filter = new QualityFilter();
        filter.setRange("solidity", new QualityFilter.Range(0.99, 1.0));
        CellIndex index = Cells.of(5).marker("CD3", i -> 1.0 + i).area(i -> 60.0 + i).build();

        for (int i = 0; i < index.size(); i++) {
            assertTrue(filter.passes(index, i),
                    "this export has no solidity, so a solidity range says nothing about cell " + i);
        }
    }
}
