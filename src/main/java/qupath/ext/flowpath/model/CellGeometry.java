package qupath.ext.flowpath.model;

import qupath.lib.images.servers.PixelCalibration;
import qupath.lib.objects.PathObject;
import qupath.lib.roi.interfaces.ROI;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Where every cell is, in <b>both</b> coordinate spaces, resolved once.
 * <p>
 * FlowPath receives two spaces in one file and used to mix them. This class is the
 * single place that resolves them, and it exposes them under names that state their
 * unit — {@link #micronsX(int)} and {@link #pixelsX(int)} — so that no caller can read a
 * coordinate without having said which space it wanted. See {@link CoordinateSpace} for
 * what the two spaces are and where each comes from.
 *
 * <h2>Joint resolution, never per-axis</h2>
 * A position is a pair, so the two axes are resolved <em>together</em>. The old code
 * took X from the {@code "Centroid X µm"} measurement and Y from the ROI whenever only
 * one was present, silently producing a point that was micrometres in X and pixels in Y.
 * Here, an export that does not offer <em>both</em> centroid measurements is treated as
 * offering neither, and the whole index falls back to the ROI — which is always
 * {@link CoordinateSpace#PIXELS}. Whichever way it resolves,
 * {@link #sourceSpace()} records the answer, and {@link #roiFallbackCount()} says how
 * many cells took the fallback.
 *
 * <h2>Converting between the spaces</h2>
 * {@code microns = pixels × pixelWidthMicrons}, with no additive offset. That is
 * QuPath's own definition of {@link PixelCalibration}, and it is also exactly what
 * MIRAGE produces: {@code bin/export_geojson.py} writes
 * {@code Centroid X µm = (x_skimage + 0.5) * pixel_size}, while
 * {@code bin/extract_cell_properties.py} shifts the polygon contours by {@code +0.5} into
 * QuPath's corner-of-pixel convention. The half-pixel therefore already sits inside the
 * ROI centroid QuPath computes, and adding it a second time during conversion would
 * double-count it.
 * <p>
 * A conversion needs a calibration; without one the derived space reads {@code NaN}
 * rather than quietly handing back the other space's numbers.
 *
 * <h2>Unit inference, and why guessing is safe here</h2>
 * The source space is taken from the resolved measurement key: an explicit pixel unit
 * ({@code "Centroid X px"}, which QuPath itself writes for uncalibrated images) means
 * {@link CoordinateSpace#PIXELS}; anything else — including a bare, unit-less
 * {@code "Centroid X"} — is assumed to be micrometres, because that is what every
 * producer FlowPath consumes actually writes, and because the existing CSV contract
 * already treats the column as µm.
 * <p>
 * That assumption is not load-bearing, because {@link #scaleVerdict()} audits it: if
 * those "µm" values are really pixels, the observed ratio against the ROI comes out at
 * {@code 1.0} against an expected {@code 0.325}, and the verdict reports
 * {@link ScaleVerdict.Status#DISAGREE}. A wrong guess surfaces instead of propagating.
 *
 * <h2>Cost</h2>
 * Two {@code double[]} — the same footprint the two centroid arrays already occupied.
 * The second space is derived on read rather than materialised, so this does not double
 * the per-cell memory on a multi-million-cell slide. Keys are resolved once from a
 * sample of the detections, never per cell, preserving the ~30x index-build speedup
 * landed in v2.0.1.
 *
 * @see CoordinateSpace
 * @see ScaleVerdict
 */
public final class CellGeometry {

    /**
     * Cells inspected by the scale cross-check. A scale error is uniform across the
     * slide, so a few hundred cells settles it as well as a few million would.
     */
    static final int SCALE_SAMPLE_SIZE = 512;

    /**
     * Relative disagreement tolerated before the verdict turns to
     * {@link ScaleVerdict.Status#DISAGREE}.
     * <p>
     * Sized to sit well above the noise and well below the signal. The noise is
     * sub-percent: Douglas-Peucker contour simplification moves a polygon centroid
     * slightly off the mask centroid it was derived from, and the corner-of-pixel
     * convention contributes at most half a pixel. The signal is a mis-set
     * {@code params.pixel_size}, which is a gross error — a wrong objective or a wrong
     * binning gives 2x, 4x, or 0.5x, not 5%.
     */
    static final double SCALE_TOLERANCE = 0.05;

    /**
     * Cells closer than this to the origin are skipped by the cross-check. Their
     * µm/px ratio is dominated by the half-pixel convention offset rather than by the
     * scale, so including them would add noise for no signal.
     */
    private static final double MIN_PIXEL_MAGNITUDE = 1.0;

    private final PathObject[] objects;
    private final double[] sourceX;
    private final double[] sourceY;
    private final CoordinateSpace sourceSpace;
    private final double pixelWidthMicrons;   // NaN when the image is uncalibrated
    private final double pixelHeightMicrons;  // NaN when the image is uncalibrated
    private final int roiFallbackCount;
    private final ScaleVerdict scaleVerdict;

    private CellGeometry(PathObject[] objects, double[] sourceX, double[] sourceY,
                         CoordinateSpace sourceSpace,
                         double pixelWidthMicrons, double pixelHeightMicrons,
                         int roiFallbackCount, ScaleVerdict scaleVerdict) {
        this.objects = objects;
        this.sourceX = sourceX;
        this.sourceY = sourceY;
        this.sourceSpace = sourceSpace;
        this.pixelWidthMicrons = pixelWidthMicrons;
        this.pixelHeightMicrons = pixelHeightMicrons;
        this.roiFallbackCount = roiFallbackCount;
        this.scaleVerdict = scaleVerdict;
    }

    /**
     * Resolve both spaces for a set of detections.
     *
     * @param objects     the detections, positionally; the array is retained, not copied
     * @param calibration the image's pixel calibration, or {@code null} when unavailable
     */
    public static CellGeometry of(PathObject[] objects, PixelCalibration calibration) {
        return of(objects, CellIndex.sampleMeasurementKeys(objects), calibration);
    }

    /**
     * Resolve both spaces, reusing a measurement-key sample that has already been taken.
     * {@link CellIndex#build} takes exactly one such sample per build and shares it, so
     * adding geometry costs no extra key scan.
     */
    static CellGeometry of(PathObject[] objects, Set<String> sampleKeys,
                           PixelCalibration calibration) {
        int n = objects.length;

        // Joint, not per-axis: one coordinate of a pair is not a position. An export
        // offering only "Centroid X" offers no usable centroid at all.
        String xKey = CellIndex.resolveMeasurementKey(sampleKeys, "Centroid X");
        String yKey = CellIndex.resolveMeasurementKey(sampleKeys, "Centroid Y");
        boolean measured = xKey != null && yKey != null;

        CoordinateSpace space = measured ? spaceOf(xKey, yKey) : CoordinateSpace.PIXELS;

        double pw = Double.NaN;
        double ph = Double.NaN;
        // hasPixelSizeMicrons() is the authoritative predicate in QuPath 0.7: the
        // getPixel*Microns() accessors return Double.NaN (verified against the 0.7.0
        // bytecode — NOT 1.0; the 1.0 belongs to getPixelWidth(), whose unit is "px")
        // whenever both axes are not in micrometres. Guarding on it keeps a
        // default/uncalibrated server from being read as a 1 µm/px image.
        if (calibration != null && calibration.hasPixelSizeMicrons()) {
            pw = calibration.getPixelWidthMicrons();
            ph = calibration.getPixelHeightMicrons();
            if (!(pw > 0) || !(ph > 0)) {
                pw = Double.NaN;
                ph = Double.NaN;
            }
        }

        double[] sx = new double[n];
        double[] sy = new double[n];
        int fallbacks = 0;

        for (int i = 0; i < n; i++) {
            double mx = Double.NaN;
            double my = Double.NaN;
            if (measured) {
                Map<String, Number> m = CellIndex.getMeasurements(objects[i]);
                Number nx = m.get(xKey);
                Number ny = m.get(yKey);
                if (nx != null) mx = nx.doubleValue();
                if (ny != null) my = ny.doubleValue();
            }

            if (Double.isNaN(mx) || Double.isNaN(my)) {
                // Joint fallback: either axis missing sends BOTH to the ROI, so a row can
                // never hold one axis of measurement and one axis of ROI.
                fallbacks++;
                double rx = roiX(objects[i]);
                double ry = roiY(objects[i]);
                if (space == CoordinateSpace.PIXELS) {
                    sx[i] = rx;
                    sy[i] = ry;
                } else {
                    // Convert into the space this index has declared. Uncalibrated, that
                    // yields NaN — an admission that this cell's µm position is unknown,
                    // which is the honest answer and strictly better than the pixels the
                    // old code silently wrote into a micrometre column.
                    sx[i] = rx * pw;
                    sy[i] = ry * ph;
                }
            } else {
                sx[i] = mx;
                sy[i] = my;
            }
        }

        ScaleVerdict verdict = checkScale(objects, xKey, yKey, space, pw, ph);

        return new CellGeometry(objects, sx, sy, space, pw, ph, fallbacks, verdict);
    }

    /**
     * Decide the measured space from the resolved key names. Only an explicit pixel unit
     * demotes a centroid to {@link CoordinateSpace#PIXELS}; see the class javadoc for why
     * defaulting the unit-less case to micrometres is safe.
     */
    private static CoordinateSpace spaceOf(String xKey, String yKey) {
        return (isPixelUnit(xKey) && isPixelUnit(yKey))
                ? CoordinateSpace.PIXELS
                : CoordinateSpace.MICRONS;
    }

    private static boolean isPixelUnit(String key) {
        String k = key.toLowerCase(Locale.ROOT).trim();
        // A leading space is required: matching a bare "px" suffix would also claim any
        // marker or morphology column that happens to end in those letters.
        return k.endsWith(" px") || k.endsWith(" pixels") || k.endsWith(" pixel");
    }

    private static double roiX(PathObject obj) {
        ROI roi = obj != null ? obj.getROI() : null;
        return roi != null ? roi.getCentroidX() : Double.NaN;
    }

    private static double roiY(PathObject obj) {
        ROI roi = obj != null ? obj.getROI() : null;
        return roi != null ? roi.getCentroidY() : Double.NaN;
    }

    /**
     * Compare the µm centroid measurements against {@code ROI × calibration} over a
     * strided sample.
     * <p>
     * Deliberately a separate, sampled pass rather than an accumulation inside the build
     * loop: it must see only cells that carry a <em>real</em> measurement (a cell filled
     * from the ROI fallback would agree with the ROI trivially and dilute the signal),
     * and it must not call {@code ROI.getCentroidX()} several million times to learn
     * something a few hundred cells already establish.
     * <p>
     * The estimator is a magnitude-weighted ratio, {@code Σ microns / Σ pixels}, rather
     * than a mean of per-cell ratios: near the origin a per-cell ratio is numerically
     * unstable, and this form naturally weights each cell by how much information its
     * coordinate carries. Both axes are estimated separately — a calibration may be
     * anisotropic — and the worse-agreeing axis is the one reported.
     */
    private static ScaleVerdict checkScale(PathObject[] objects, String xKey, String yKey,
                                           CoordinateSpace space, double pw, double ph) {
        if (space != CoordinateSpace.MICRONS || xKey == null || yKey == null) {
            return ScaleVerdict.noMeasurement();
        }
        if (Double.isNaN(pw) || Double.isNaN(ph)) {
            return ScaleVerdict.noCalibration();
        }

        int n = objects.length;
        if (n == 0) return ScaleVerdict.noMeasurement();
        int stride = Math.max(1, n / SCALE_SAMPLE_SIZE);

        double sumMicronsX = 0, sumPixelsX = 0, sumMicronsY = 0, sumPixelsY = 0;
        int used = 0;

        for (int i = 0; i < n && used < SCALE_SAMPLE_SIZE; i += stride) {
            Map<String, Number> m = CellIndex.getMeasurements(objects[i]);
            Number nx = m.get(xKey);
            Number ny = m.get(yKey);
            if (nx == null || ny == null) continue;
            double mx = nx.doubleValue();
            double my = ny.doubleValue();
            if (Double.isNaN(mx) || Double.isNaN(my)) continue;

            double rx = roiX(objects[i]);
            double ry = roiY(objects[i]);
            if (Double.isNaN(rx) || Double.isNaN(ry)) continue;
            if (Math.abs(rx) < MIN_PIXEL_MAGNITUDE || Math.abs(ry) < MIN_PIXEL_MAGNITUDE) continue;

            sumMicronsX += mx;
            sumPixelsX += rx;
            sumMicronsY += my;
            sumPixelsY += ry;
            used++;
        }

        if (used == 0 || sumPixelsX == 0 || sumPixelsY == 0) {
            return ScaleVerdict.noMeasurement();
        }

        double observedX = sumMicronsX / sumPixelsX;
        double observedY = sumMicronsY / sumPixelsY;
        double deviationX = Math.abs(observedX - pw) / pw;
        double deviationY = Math.abs(observedY - ph) / ph;

        boolean xIsWorse = deviationX >= deviationY;
        double observed = xIsWorse ? observedX : observedY;
        double expected = xIsWorse ? pw : ph;
        double deviation = Math.max(deviationX, deviationY);

        return deviation <= SCALE_TOLERANCE
                ? ScaleVerdict.agree(observed, expected, used)
                : ScaleVerdict.disagree(observed, expected, used);
    }

    // ---- accessors: every one names its space -----------------------------------

    /** Cell {@code i}'s X position in micrometres, or {@code NaN} if not derivable. */
    public double micronsX(int i) {
        if (sourceSpace == CoordinateSpace.MICRONS) return sourceX[i];
        return sourceX[i] * pixelWidthMicrons;
    }

    /** Cell {@code i}'s Y position in micrometres, or {@code NaN} if not derivable. */
    public double micronsY(int i) {
        if (sourceSpace == CoordinateSpace.MICRONS) return sourceY[i];
        return sourceY[i] * pixelHeightMicrons;
    }

    /**
     * Cell {@code i}'s X position in full-resolution image pixels, or {@code NaN} if not
     * derivable. Prefers the ROI, which <em>is</em> pixel space and so needs no
     * calibration; only a detection without a ROI falls back to dividing the micrometre
     * value by the calibration.
     */
    public double pixelsX(int i) {
        if (sourceSpace == CoordinateSpace.PIXELS) return sourceX[i];
        double roi = roiX(objects[i]);
        if (!Double.isNaN(roi)) return roi;
        return sourceX[i] / pixelWidthMicrons;
    }

    /** Cell {@code i}'s Y position in full-resolution image pixels, or {@code NaN}. */
    public double pixelsY(int i) {
        if (sourceSpace == CoordinateSpace.PIXELS) return sourceY[i];
        double roi = roiY(objects[i]);
        if (!Double.isNaN(roi)) return roi;
        return sourceY[i] / pixelHeightMicrons;
    }

    /**
     * Cell {@code i}'s X position as it was resolved, in {@link #sourceSpace()}.
     * <p>
     * This is <em>not</em> a "whichever we found" accessor: its unit is declared by
     * {@link #sourceSpace()}, which is a property of the whole index rather than of the
     * row, so a caller cannot read it without being handed its space. It exists so
     * {@link CellIndex#getCentroidX(int)} can keep its long-standing meaning ("the
     * position as exported") for callers that only ever round-trip the number.
     * Prefer {@link #micronsX(int)} or {@link #pixelsX(int)}.
     */
    public double sourceX(int i) {
        return sourceX[i];
    }

    /** Cell {@code i}'s Y position as it was resolved, in {@link #sourceSpace()}. */
    public double sourceY(int i) {
        return sourceY[i];
    }

    /** The space the resolved coordinates are actually in. Never mixed within an index. */
    public CoordinateSpace sourceSpace() {
        return sourceSpace;
    }

    /** True when the image declared a pixel size, so the two spaces are interconvertible. */
    public boolean isCalibrated() {
        return !Double.isNaN(pixelWidthMicrons);
    }

    /** Micrometres per pixel along X, or {@code NaN} when the image is uncalibrated. */
    public double pixelWidthMicrons() {
        return pixelWidthMicrons;
    }

    /** Micrometres per pixel along Y, or {@code NaN} when the image is uncalibrated. */
    public double pixelHeightMicrons() {
        return pixelHeightMicrons;
    }

    /**
     * How many cells took the ROI fallback because their centroid measurements were
     * absent or {@code NaN}. Equals the cell count when the export carried no centroid
     * measurement at all.
     */
    public int roiFallbackCount() {
        return roiFallbackCount;
    }

    /** Whether the exported micrometres agree with the image's own calibration. */
    public ScaleVerdict scaleVerdict() {
        return scaleVerdict;
    }

    /** Number of cells this geometry covers. */
    public int size() {
        return sourceX.length;
    }
}
