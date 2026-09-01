package qupath.ext.flowpath.analysis.ui;

/**
 * What the pointer is over: the bar (or, for {@link MarkerPositivityCanvas}, the stacked
 * segment of a bar) at some {@code (x, y)}, and what to say about it in a tooltip or a click.
 * <p>
 * Read back from {@link PlotCanvas#hitAt}, never constructed independently of it — a tooltip
 * and a click both go through the same hit, which is what makes "hover to see the number" and
 * "click to select the same thing" one answer rather than two that could disagree.
 *
 * @param title      the first tooltip line: a population path, a region name, a scope's
 *                   display name, or a marker label — see each canvas's own {@code hitAt} for
 *                   the exact wording
 * @param detail     the second tooltip line: the count (and, where the canvas has one, a
 *                   percentage) behind {@code title}
 * @param population which population a click on this hit should select in the gate tree, via
 *                   {@link PlotCanvas#setOnPopulationPicked}, or
 *                   {@code null} when the hit does not name one unambiguous population — every
 *                   hit {@link MarkerPositivityCanvas} reports, because a pooled marker segment
 *                   can span more than one gate node
 */
public record PlotHit(String title, String detail, PopulationRef population) {}
