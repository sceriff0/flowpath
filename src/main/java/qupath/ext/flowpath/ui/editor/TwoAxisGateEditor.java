package qupath.ext.flowpath.ui.editor;

import qupath.ext.flowpath.model.Branch;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.ColorUtils;
import qupath.ext.flowpath.model.GateAxis;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.QuadrantGate;
import qupath.ext.flowpath.ui.widgets.ScatterPlotCanvas;

import java.util.List;

/**
 * The scatter plot a quadrant and a region gate share: which points it plots, which window its
 * axes show, and its branch colours.
 */
abstract class TwoAxisGateEditor<G extends GateNode> extends AbstractGateTypeEditor<G> {

    /** The plot, once {@link #newScatter()} has built one; {@code null} when there is no data. */
    ScatterPlotCanvas scatter;

    TwoAxisGateEditor(G gate, EditorContext context) {
        super(gate, context);
    }

    /** True when both axes name a channel the loaded index carries, so there is something to plot. */
    final boolean hasPlottableAxes() {
        CellIndex index = context.cellIndex();
        if (index == null || GateAxis.axisCount(gate) < 2) return false;
        for (GateAxis axis : GateAxis.axesOf(gate)) {
            String channel = axis.channel();
            if (channel == null || index.getMarkerIndex(channel) < 0) return false;
        }
        return true;
    }

    /** A scatter plot of the gate's current points, axes anchored and coloured, overlay set. */
    final ScatterPlotCanvas newScatter() {
        scatter = new ScatterPlotCanvas();
        redrawScatter();
        branchColorsChanged();
        scatter.setGateOverlay(gate);
        return scatter;
    }

    /**
     * Re-read the gate's points onto the scatter and re-anchor its axes: each axis read through
     * its <em>own</em> resolved column, as measured. One spelling — this block once existed
     * four times, and three copies plotted the bare whole-cell mean while the gate classified
     * on a nuclear median (commit {@code 6b66868}).
     */
    final void redrawScatter() {
        if (scatter == null || !hasPlottableAxes()) return;
        GateAxis x = GateAxis.of(gate, 0);
        GateAxis y = GateAxis.of(gate, 1);
        CellIndex index = context.cellIndex();
        double[] allX = index.getResolvedColumn(x.channel(), x.compartment(), x.statistic());
        double[] allY = index.getResolvedColumn(y.channel(), y.compartment(), y.statistic());
        boolean[] roi = context.roiMask();
        boolean[] ancestor = context.ancestorMask();
        double[][] filtered = AxisMath.pairedMaskedValues(allX, allY, roi, ancestor);
        scatter.setData(filtered[0], filtered[1], x.channel(), y.channel());
        if (context.markerStats() != null) applyAxisRange();
    }

    /**
     * Anchor the scatter axes on the clip percentiles of each axis' own resolved column, so a
     * nuclear or median axis anchors on its own distribution (commit {@code d9c1de9} anchored
     * both on X).
     */
    private void applyAxisRange() {
        double[] x = clipSpan(axisColumn(0));
        double[] y = clipSpan(axisColumn(1));
        if (x == null || y == null) {
            scatter.clearAxisRange();
            return;
        }
        scatter.setAxisRange(x[0], x[1], y[0], y[1]);
    }

    /** Paint the scatter in the shown gate's branch colours (a replacement's, after a conversion). */
    @Override
    public void branchColorsChanged() {
        if (scatter == null) return;
        GateNode shown = isDisposed() ? gate : context.shownGate();
        if (shown == null) return;
        List<Branch> branches = shown.getBranches();
        if (shown instanceof QuadrantGate && branches.size() == 4) {
            scatter.setQuadrantColors(
                    ColorUtils.intToColor(branches.get(0).getColor()).deriveColor(0, 1, 1, 0.6),
                    ColorUtils.intToColor(branches.get(1).getColor()).deriveColor(0, 1, 1, 0.6),
                    ColorUtils.intToColor(branches.get(2).getColor()).deriveColor(0, 1, 1, 0.6),
                    ColorUtils.intToColor(branches.get(3).getColor()).deriveColor(0, 1, 1, 0.6));
        } else if (branches.size() >= 2) {
            scatter.setInsideColor(ColorUtils.intToColor(branches.get(0).getColor()).deriveColor(0, 1, 1, 0.6));
            scatter.setOutsideColor(ColorUtils.intToColor(branches.get(1).getColor()).deriveColor(0, 1, 1, 0.3));
        }
    }

    /** A 2D gate's sliders and axes are built from its columns, so it is rebuilt, not patched. */
    @Override
    void afterSignalChange() {
        context.gateChanged();
        context.showLater(gate);
    }
}
