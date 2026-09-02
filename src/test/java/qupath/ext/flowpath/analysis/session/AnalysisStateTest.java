package qupath.ext.flowpath.analysis.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import qupath.ext.flowpath.model.PopulationStats.Scope;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link AnalysisState}'s own guard rails, tested directly rather than through the one
 * derivation that happens to produce them.
 * <p>
 * The record is only ever constructed indirectly, by {@code AnalysisSession.state()}, so
 * every {@code throw} in its compact constructor had no coverage at all: the guards existed
 * to catch a <em>future</em> edit to that derivation, and nothing established that they
 * actually fire. This is the same table {@code ViewStateDerivationTest} keeps for
 * {@code ViewState}'s contradictions, and it has the same shape for the same reason — each
 * row violates exactly one rule, so removing any single guard turns exactly one row red.
 * <p>
 * Note the absence of any JavaFX bootstrap: like {@code ViewState}, this record is part of
 * the session layer, and needing a toolkit here would mean the state had leaked into the
 * widgets.
 */
class AnalysisStateTest {

    private static final List<Scope> ALL_SCOPES =
            List.of(Scope.WHOLE_SLIDE, Scope.ANNOTATION_ALL, Scope.ANNOTATION_K);

    /** The state a session with nothing accepted derives, verbatim. */
    private static AnalysisState empty() {
        return new AnalysisState(false, false, false, 0, 0, List.of(),
                "No gating pass to report on yet.", null);
    }

    // ------------------------------------------------------------------
    // The contradictions
    // ------------------------------------------------------------------

    /**
     * Every combination the compact constructor rejects, one rule per row.
     * <p>
     * Field order is {@code (hasData, hasRegions, canExport, cellCount, regionCount,
     * availableScopes, emptyMessage)}.
     */
    static Stream<Object[]> contradictions() {
        return Stream.of(
                new Object[]{"an export offered over a pass that was never accepted",
                        (Builder) () -> new AnalysisState(false, false, true, 0, 0, List.of(),
                                "nothing to report on yet", null)},
                new Object[]{"scopes to pick from with no data behind any of them",
                        (Builder) () -> new AnalysisState(false, false, false, 0, 0,
                                List.of(Scope.WHOLE_SLIDE), "nothing to report on yet", null)},
                new Object[]{"regions announced without a single one to show",
                        (Builder) () -> new AnalysisState(true, true, true, 10, 0,
                                ALL_SCOPES, null, null)},
                new Object[]{"a negative region count is no more a region than zero is",
                        (Builder) () -> new AnalysisState(true, true, true, 10, -1,
                                ALL_SCOPES, null, null)},
                new Object[]{"a full panel that explains itself as if it were empty",
                        (Builder) () -> new AnalysisState(true, false, true, 10, 0,
                                List.of(Scope.WHOLE_SLIDE), "no gating pass yet", null)},
                new Object[]{"an empty panel with no explanation to put in place of the table",
                        (Builder) () -> new AnalysisState(false, false, false, 0, 0,
                                List.of(), null, null)});
    }

    /** A deferred construction, so the table can hold one that throws. */
    interface Builder {
        AnalysisState build();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("contradictions")
    @DisplayName("A state no session can be in is rejected at construction")
    void contradictionsAreRejected(String situation, Builder builder) {
        assertThrows(IllegalArgumentException.class, builder::build, situation);
    }

    // ------------------------------------------------------------------
    // The legal states
    // ------------------------------------------------------------------

    /**
     * The three states {@code AnalysisSession.state()} can actually derive. A guard written
     * a shade too broadly would reject one of these, which is the failure the table above
     * cannot show on its own.
     */
    static Stream<Object[]> legalStates() {
        return Stream.of(
                new Object[]{"nothing accepted yet", (Builder) AnalysisStateTest::empty},
                new Object[]{"a pass over an unannotated slide -- whole slide is the only scope",
                        (Builder) () -> new AnalysisState(true, false, true, 10, 0,
                                List.of(Scope.WHOLE_SLIDE), null, null)},
                new Object[]{"a pass over two annotated regions -- all three scopes",
                        (Builder) () -> new AnalysisState(true, true, true, 20, 2,
                                ALL_SCOPES, null, null)});
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("legalStates")
    @DisplayName("Every state a session can derive constructs")
    void legalStatesConstruct(String situation, Builder builder) {
        assertNotNull(builder.build(), situation);
    }

    @Test
    @DisplayName("The empty state carries its message and offers nothing")
    void theEmptyStateOffersNothing() {
        AnalysisState state = empty();
        assertNotNull(state.emptyMessage(), "an empty panel must explain itself");
        assertEquals(List.of(), state.availableScopes());
        assertEquals(0, state.cellCount());
        assertEquals(0, state.regionCount());
    }

    /**
     * {@code availableScopes} is copied on the way in, so a caller that keeps its list and
     * mutates it afterwards cannot change what the window was told it may offer — the same
     * defensive copy {@code ViewState} and {@code AnalysisInput} make of the collections
     * they take.
     */
    @Test
    @DisplayName("The scope list is copied, not aliased")
    void availableScopesAreCopied() {
        List<Scope> mutable = new ArrayList<>(List.of(Scope.WHOLE_SLIDE));
        AnalysisState state = new AnalysisState(true, false, true, 10, 0, mutable, null, null);

        mutable.add(Scope.ANNOTATION_K);

        assertEquals(List.of(Scope.WHOLE_SLIDE), state.availableScopes(),
                "a scope the session never derived must not appear in the picker");
        assertThrows(UnsupportedOperationException.class,
                () -> state.availableScopes().add(Scope.ANNOTATION_ALL),
                "and the window cannot add one either");
    }

    /**
     * A QuPath image can genuinely have no name, so {@code imageName == null} must be an
     * ordinary, legal state rather than a contradiction the compact constructor rejects —
     * unlike {@code emptyMessage}, whose {@code null}-ness is tied to {@code hasData}.
     */
    @Test
    @DisplayName("An unnamed image is legal and does not break the invariants")
    void anUnnamedImageIsLegalAndDoesNotBreakTheInvariants() {
        assertDoesNotThrow(() -> new AnalysisState(true, false, true, 100, 0,
                List.of(Scope.WHOLE_SLIDE), null, null));
        assertDoesNotThrow(() -> new AnalysisState(true, false, true, 100, 0,
                List.of(Scope.WHOLE_SLIDE), null, "slide-01.ome.tif"));
    }
}
