package qupath.ext.flowpath.cohort;

import qupath.ext.flowpath.engine.AlignmentLookup;
import qupath.ext.flowpath.engine.TreeResolver;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.MeasuredColumn;
import qupath.ext.flowpath.model.cohort.Alignment;
import qupath.ext.flowpath.model.cohort.Landmarks;
import qupath.lib.images.servers.ImageChannel;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.interfaces.ROI;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The inline evidence for a review item (spec §6): a 200 µm field of tissue around the gate's
 * boundary, the marker in green on a per-slide range that makes a positive cell equally bright on
 * every slide, DAPI in blue, the sample's boundary cells outlined in their branch colour.
 * Toolkit-free; rendered off the FX thread.
 */
public final class EvidenceCrop {

    /** One boundary cell's outline, in level-0 pixels, and its branch colour (packed RGB). */
    public record Outline(ROI roi, int rgb) {}

    /**
     * What to render: the field's centre and side in level-0 pixels, the marker channel's name and
     * its display range in raw slide units (NaN: the crop's own 0.5–99.5 percentiles), and the
     * outlines in the field.
     */
    public record Spec(double centerX, double centerY, double fieldPixels, String markerChannel,
                       double markerLo, double markerHi, List<Outline> outlines) {}

    /** A rendered crop (ARGB, row-major), or the error text a failed one shows instead. */
    public record Crop(int width, int height, int[] argb, String error) {
        public boolean ok() { return error == null; }

        public static Crop failed(String error) { return new Crop(0, 0, new int[0], error); }
    }

    /** A point detection's dot radius, in output pixels. */
    private static final double POINT_RADIUS_OUTPUT = 2;

    private EvidenceCrop() {}

    /**
     * The crop for {@code item} on its slide: centred on the hotspot of the item's own gate's
     * boundary cells in {@code sample} (the same {@link BoundaryHotspot#of} rule the viewer's
     * click-through uses), the marker range the reference slide's landmarks mapped onto this slide
     * through {@link TreeResolver#correctionFor} — so the range is corrected exactly when the gate
     * is. The upper end is the reference's p99.5 when it has no L2; with no reference landmark at
     * all the range is left to the crop.
     *
     * @param reference the reference slide's sample, or null when none is sampled
     * @return the spec, or null when the gate is gone or the sample has no boundary cell
     */
    public static Spec spec(ReviewItem item, GateTree tree, SlideSample sample, SlideSample reference,
                            AlignmentModel model, AlignmentLookup lookup, double fieldPixels) {
        GateNode gate = CohortSession.liveGate(tree, item.key());
        if (gate == null || gate.getChannels().isEmpty()) return null;
        String slideId = item.key().slideId();
        BoundaryHotspot.Boundary boundary = BoundaryHotspot.ofSample(tree, gate, sample, lookup, model::cofactor);
        BoundaryHotspot.Hotspot hot = BoundaryHotspot.hotspot(sample.index(), boundary.cells(), fieldPixels);
        if (hot == null) return null;

        String column = TreeResolver.resolve(tree, slideId, lookup).applied(gate).columns().get(0);
        double lo = Double.NaN, hi = Double.NaN;
        Landmarks ref = column == null ? null : model.referenceLandmarks(column);
        if (ref != null && ref.hasL1()) {
            Alignment a = TreeResolver.correctionFor(tree, gate, 0, slideId, lookup);
            lo = a.apply(Landmarks.sinh(ref.l1(), ref.cofactor()));
            if (ref.hasL2()) {
                hi = a.apply(Landmarks.sinh(ref.l2(), ref.cofactor()));
            } else if (reference != null && reference.index().getMarkerIndex(gate.getChannels().get(0)) >= 0) {
                MeasuredColumn refColumn = reference.index().column(gate, 0, reference.stats());
                if (refColumn != null) hi = a.apply(refColumn.percentile(99.5));
            }
        }

        double half = fieldPixels / 2;
        List<Outline> outlines = new ArrayList<>();
        for (int i = 0; i < boundary.cells().length; i++) {
            if (!boundary.cells()[i] || sample.index().getObject(i) == null) continue;
            ROI roi = sample.index().getObject(i).getROI();
            if (roi == null || Math.abs(roi.getCentroidX() - hot.centerX()) > half
                    || Math.abs(roi.getCentroidY() - hot.centerY()) > half) continue;
            outlines.add(new Outline(roi, boundary.rgb()[i]));
        }
        return new Spec(hot.centerX(), hot.centerY(), fieldPixels, gate.getChannels().get(0), lo, hi,
                List.copyOf(outlines));
    }

    /**
     * Read the spec's field from {@code server} at the downsample that makes it
     * {@link BoundaryHotspot#CROP_PIXELS} wide and composite it. Never throws: any failure, an
     * {@code Error} included, is the crop's error text.
     */
    public static Crop render(ImageServer<BufferedImage> server, Spec spec) {
        try {
            List<ImageChannel> channels = server.getMetadata().getChannels();
            int marker = -1, dapi = 0;
            for (int c = 0; c < channels.size(); c++) {
                String name = channels.get(c).getName();
                if (name == null) continue;
                if (marker < 0 && name.equals(spec.markerChannel())) marker = c;
                if (dapi == 0 && name.equalsIgnoreCase("DAPI")) dapi = c;
            }
            if (marker < 0) return Crop.failed("Channel " + spec.markerChannel() + " is not in this image");
            int field = (int) Math.round(spec.fieldPixels());
            int w = Math.min(field, server.getWidth()), h = Math.min(field, server.getHeight());
            int x = (int) Math.round(Math.max(0, Math.min(server.getWidth() - w, spec.centerX() - w / 2.0)));
            int y = (int) Math.round(Math.max(0, Math.min(server.getHeight() - h, spec.centerY() - h / 2.0)));
            double down = spec.fieldPixels() / BoundaryHotspot.CROP_PIXELS;
            BufferedImage img = server.readRegion(RegionRequest.createInstance(server.getPath(), down, x, y, w, h));
            if (img == null) return Crop.failed("The image returned no pixels for this field");
            Raster r = img.getRaster();
            int ow = img.getWidth(), oh = img.getHeight();
            double[] m = r.getSamples(0, 0, ow, oh, marker, (double[]) null);
            double[] d = r.getSamples(0, 0, ow, oh, dapi, (double[]) null);
            double[] range = markerRange(spec.markerLo(), spec.markerHi(), percentile(m, 0.5), percentile(m, 99.5));
            double mLo = range[0], mHi = range[1];
            double dLo = percentile(d, 0.5), dHi = percentile(d, 99.5);
            int[] argb = new int[ow * oh];
            for (int i = 0; i < argb.length; i++) {
                argb[i] = 0xFF000000 | (scale(m[i], mLo, mHi) << 8) | scale(d[i], dLo, dHi);
            }
            BufferedImage out = new BufferedImage(ow, oh, BufferedImage.TYPE_INT_ARGB);
            out.setRGB(0, 0, ow, oh, argb, 0, ow);
            Graphics2D g2 = out.createGraphics();
            try {
                double sx = ow / (double) w, sy = oh / (double) h;   // level-0 → output pixels
                g2.scale(sx, sy);
                g2.translate(-x, -y);
                g2.setStroke(new BasicStroke((float) (1.5 / sx)));
                for (Outline o : spec.outlines()) {
                    g2.setColor(new Color(o.rgb() & 0xFFFFFF));
                    Shape shape = CellShapes.shapeOf(o.roi(), POINT_RADIUS_OUTPUT / sx);
                    if (CellShapes.isOutlined(o.roi())) g2.draw(shape);
                    else g2.fill(shape);
                }
            } finally {
                g2.dispose();
            }
            return new Crop(ow, oh, out.getRGB(0, 0, ow, oh, null, 0, ow), null);
        } catch (Exception | Error ex) {
            return Crop.failed(messageOf(ex));
        }
    }

    /** A throwable as the text a failed crop shows: its message, else its class name. */
    public static String messageOf(Throwable ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank() ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    /**
     * The marker's display range: the per-slide mapped reference range when it is usable and
     * overlaps the crop's own 0.5–99.5 percentile span, else that span. A range that misses the
     * pixels entirely — a gate on a pre-standardised column such as MIRAGE's {@code Median Z},
     * whose landmarks are not in pixel units — would paint the whole crop black or saturated.
     */
    static double[] markerRange(double lo, double hi, double ownLo, double ownHi) {
        boolean usable = Double.isFinite(lo) && Double.isFinite(hi) && hi > lo;
        boolean overlaps = !(Double.isFinite(ownLo) && Double.isFinite(ownHi)) || (lo <= ownHi && hi >= ownLo);
        return usable && overlaps ? new double[]{lo, hi} : new double[]{ownLo, ownHi};
    }

    private static int scale(double v, double lo, double hi) {
        if (!(hi > lo) || !Double.isFinite(v)) return 0;
        return (int) Math.round(255 * Math.max(0, Math.min(1, (v - lo) / (hi - lo))));
    }

    private static double percentile(double[] v, double p) {
        double[] s = Arrays.stream(v).filter(Double::isFinite).sorted().toArray();
        return s.length == 0 ? Double.NaN : s[(int) Math.floor(p / 100 * (s.length - 1))];
    }
}
