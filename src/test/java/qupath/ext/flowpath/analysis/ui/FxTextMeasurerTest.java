package qupath.ext.flowpath.analysis.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * No toolkit: pins {@link FxTextMeasurer}'s degrade-to-{@link ApproxTextMeasurer} path via the
 * package-private {@code Supplier<Text>} constructor, for both a {@link RuntimeException} and
 * an {@link Error} thrown while constructing the underlying {@code Text} node. Both are
 * exercised because the catch in {@code FxTextMeasurer} is deliberately {@code Throwable} —
 * real toolkit failures have surfaced as {@code Error} subtypes elsewhere in this codebase
 * ({@code PlotTheme}'s {@code ExceptionInInitializerError}), and a test that only throws a
 * {@code RuntimeException} would still pass against an accidentally narrowed
 * {@code catch (Exception)}, which is exactly the regression worth catching here.
 */
class FxTextMeasurerTest {

    @Test
    void fallsBackToApproxMeasurerWhenTextConstructionThrowsARuntimeException() {
        FxTextMeasurer measurer = new FxTextMeasurer(() -> {
            throw new RuntimeException("no toolkit");
        });
        assertMatchesApprox(measurer);
    }

    @Test
    void fallsBackToApproxMeasurerWhenTextConstructionThrowsAnError() {
        FxTextMeasurer measurer = new FxTextMeasurer(() -> {
            throw new ExceptionInInitializerError("no toolkit");
        });
        assertMatchesApprox(measurer);
    }

    private static void assertMatchesApprox(FxTextMeasurer measurer) {
        ApproxTextMeasurer approx = new ApproxTextMeasurer();
        assertEquals(approx.width("CD45+", 12, false), measurer.width("CD45+", 12, false), 1e-9);
        assertEquals(approx.width("CD45+", 12, true), measurer.width("CD45+", 12, true), 1e-9);
        assertEquals(approx.width(null, 12, false), measurer.width(null, 12, false), 1e-9);
    }
}
