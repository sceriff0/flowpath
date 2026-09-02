package qupath.ext.flowpath.analysis.ui;

/**
 * One number a plot canvas is currently drawing, in the shape {@link PlotCanvas#plotData()}
 * hands it out: which bar it belongs to ({@code category}), which series within that bar
 * ({@code series} — {@code "count"} for a plot that draws only one series per bar), and the
 * value itself.
 * <p>
 * Exists so a plot's own numbers can leave the screen — as a CSV row via
 * {@code qupath.ext.flowpath.io.PlotDataCsvExporter} — without a second reduction of the rows
 * {@code draw(PlotSurface, PlotTheme)} already reduced. A second reduction, even one meant to
 * compute the identical numbers, is exactly the "two implementations of one rule" failure
 * {@code CLAUDE.md}'s "One gate predicate" section documents for the gating path: the two
 * would agree today and silently diverge the next time either is edited without the other,
 * and a CSV that quietly disagrees with the picture beside it is invisible until someone
 * happens to compare the two by hand. See {@link PlotCanvas#plotData()}.
 *
 * @param category the bar this datum belongs to — a population path, a region name, a scope's
 *                  display name, or a marker label, depending on which canvas produced it
 * @param series   which series within that bar — {@code "count"} for the three single-series
 *                  plots, or {@code "positive"}/{@code "negative"}/{@code "ungated"} for
 *                  {@link MarkerPositivityCanvas}
 * @param value    the number itself — always a cell count across the current four plots
 */
public record PlotDatum(String category, String series, double value) {}
