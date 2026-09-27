package qupath.ext.flowpath.cohort;

import qupath.ext.flowpath.engine.AlignmentLookup;
import qupath.ext.flowpath.engine.GateReadout;
import qupath.ext.flowpath.engine.GatingEngine;
import qupath.ext.flowpath.engine.TreeResolver;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.GateAxis;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.GateValues;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.MeasuredColumn;
import qupath.ext.flowpath.model.Region2DGate;
import qupath.ext.flowpath.model.cohort.Alignment;
import qupath.ext.flowpath.model.cohort.Landmarks;
import qupath.lib.images.servers.PixelCalibration;
import qupath.lib.roi.interfaces.ROI;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.ToDoubleFunction;

/** Where on a slide a gate decides: the tile holding most cells close to its cut. */
public final class BoundaryHotspot {

    /** Half-width of the boundary band, in aligned asinh units — the same meaning on every slide. */
    public static final double BAND = 0.1;
    /** The evidence field, and the hotspot grid's tile: 200 µm (spec §6). */
    public static final double FIELD_MICRONS = 200;
    /** The evidence crop's side in output pixels, and the field in level-0 pixels when uncalibrated. */
    public static final int CROP_PIXELS = 512;

    /** Grey for a boundary cell the gate cannot judge. */
    static final int UNJUDGED_RGB = 0x808080;

    public record Hotspot(double centerX, double centerY, int cells) {}

    /**
     * A gate's boundary cells on one slide, and each one's branch colour (packed RGB; 0 for a
     * cell off the boundary), positional against the index's objects.
     */
    public record Boundary(boolean[] cells, int[] rgb) {}

    private BoundaryHotspot() {}

    /**
     * Cells of {@code parent} (null: every cell) within {@link #BAND} aligned asinh units of the
     * cut: each slide value is mapped back into reference units through {@code alignment}'s
     * inverse, so the band sits at the cut applied on this slide. A cell with no finite value is
     * never on the boundary.
     */
    public static boolean[] boundaryCells(double[] raw, boolean[] parent, Alignment alignment,
                                          double referenceCut, double cofactor) {
        double cut = Landmarks.asinh(referenceCut, cofactor);
        boolean[] out = new boolean[raw.length];
        for (int i = 0; i < raw.length; i++) {
            if ((parent != null && !parent[i]) || !Double.isFinite(raw[i])) continue;
            out[i] = Math.abs(Landmarks.asinh(alignment.inverse(raw[i]), cofactor) - cut) <= BAND;
        }
        return out;
    }

    /** A region gate has no 1-D cut: its "boundary" is its whole parent population. */
    public static boolean[] parentCells(boolean[] parent) {
        return parent.clone();
    }

    /**
     * {@code gate}'s boundary on {@code slideId}: its parent population in the slide's resolved
     * tree, narrowed to the band around each axis's cut (a region gate keeps the whole parent
     * population), coloured by the branch each cell falls in — read through {@link GateReadout}
     * on the same resolved tree, so the colour is the classification. The corrections come from
     * {@link TreeResolver#correctionFor}; nothing here computes an applied value.
     *
     * @param base      quality + ROI mask the parent population starts from; null for every cell
     * @param cofactors a column key's fixed cofactor, NaN when unknown (the column's own pooled
     *                  cofactor is used then)
     */
    public static Boundary of(GateTree live, GateNode gate, String slideId, AlignmentLookup lookup,
                              CellIndex index, MarkerStats stats, boolean[] base,
                              ToDoubleFunction<String> cofactors) {
        TreeResolver.ResolvedTree resolved = TreeResolver.resolve(live, slideId, lookup);
        GateNode target = resolved.resolvedOf(gate);
        if (target == null) throw new IllegalArgumentException("gate is not part of the tree: " + gate);
        boolean[] parent = GatingEngine.computeAncestorMask(resolved.tree(), target, index, stats, base);

        boolean[] cells;
        if (gate instanceof Region2DGate) {
            cells = parentCells(parent);
        } else {
            cells = new boolean[parent.length];
            GateValues reference = GateValues.read(gate);
            List<String> columns = resolved.applied(gate).columns();
            for (int k = 0; k < GateAxis.axisCount(gate); k++) {
                MeasuredColumn column = index.column(gate, k, stats);
                if (column == null) continue;
                double[] raw = column.values();
                double c = columns.get(k) == null ? Double.NaN : cofactors.applyAsDouble(columns.get(k));
                if (!Double.isFinite(c) || c <= 0) c = Landmarks.cofactor(raw);
                boolean[] near = boundaryCells(raw, parent,
                        TreeResolver.correctionFor(live, gate, k, slideId, lookup), reference.axis(k)[0], c);
                for (int i = 0; i < near.length; i++) cells[i] |= near[i];
            }
        }

        GateReadout readout = GateReadout.compile(resolved.tree(), index, stats);
        int[] rgb = new int[cells.length];
        for (int i = 0; i < cells.length; i++) {
            if (!cells[i]) continue;
            int b = readout.branchIgnoringClip(target, i);
            rgb[i] = b >= 0 ? target.getBranches().get(b).getColor() & 0xFFFFFF : UNJUDGED_RGB;
        }
        return new Boundary(cells, rgb);
    }

    /**
     * {@link #FIELD_MICRONS} in level-0 pixels, or {@link #CROP_PIXELS} when the image has no pixel
     * size. The one tile the viewer's click-through and the evidence crop both centre on, so the
     * two show the same cells.
     */
    public static double fieldPixels(PixelCalibration cal) {
        return cal != null && cal.hasPixelSizeMicrons() && cal.getAveragedPixelSizeMicrons() > 0
                ? FIELD_MICRONS / cal.getAveragedPixelSizeMicrons() : CROP_PIXELS;
    }

    /**
     * {@code gate}'s boundary on a slide's <em>sample</em>: {@link #of} over the sample's cells,
     * starting from its clean mask. The hotspot the evidence crop and the viewer's click-through
     * both centre on is taken from this, so the two land on the same tile.
     */
    public static Boundary ofSample(GateTree live, GateNode gate, SlideSample sample, AlignmentLookup lookup,
                                    ToDoubleFunction<String> cofactors) {
        return of(live, gate, sample.slideId(), lookup, sample.index(), sample.stats(), sample.clean(), cofactors);
    }

    /** ROI centroids are level-0 pixels — the space {@code QuPathViewer.setCenterPixelLocation} takes. */
    public static Hotspot hotspot(CellIndex index, boolean[] cells, double tilePixels) {
        Map<Long, Integer> counts = new TreeMap<>();
        for (int i = 0; i < cells.length; i++) {
            if (!cells[i] || index.getObject(i) == null) continue;
            ROI roi = index.getObject(i).getROI();
            if (roi == null) continue;
            long tx = (long) Math.floor(roi.getCentroidX() / tilePixels);
            long ty = (long) Math.floor(roi.getCentroidY() / tilePixels);
            counts.merge((tx << 32) ^ (ty & 0xffffffffL), 1, Integer::sum);
        }
        Long best = null;
        for (var e : counts.entrySet()) if (best == null || e.getValue() > counts.get(best)) best = e.getKey();
        if (best == null) return null;
        long tx = best >> 32;
        long ty = (int) (best & 0xffffffffL);
        return new Hotspot((tx + 0.5) * tilePixels, (ty + 0.5) * tilePixels, counts.get(best));
    }
}
