package qupath.ext.flowpath.testing;

import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.Compartment;
import qupath.ext.flowpath.model.MarkerSelection;
import qupath.ext.flowpath.model.MeasurementKeys;
import qupath.ext.flowpath.model.Statistic;
import qupath.lib.images.servers.PixelCalibration;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;
import java.util.function.IntToDoubleFunction;

/**
 * The one place a synthetic cell population is described, for every test in the suite.
 * <p>
 * Before this existed, roughly a dozen test classes each carried their own private
 * {@code buildIndex(...)} / {@code cell(...)} pair — each one a slightly different
 * spelling of "make some detections, poke measurements into them, hand them to
 * {@link CellIndex#build}". They drifted: some placed cells at {@code (i*10, i*10)},
 * some at the origin; some wrote {@code "area"}, some {@code "Area µm²"}; only one
 * spoke the structured MIRAGE key grammar at all. A fixture that cannot express the
 * real input shape quietly pushes tests toward the shapes it <em>can</em> express.
 * <p>
 * So this builder speaks the MIRAGE key grammar natively — the bare marker key
 * <em>and</em> {@code "<marker>: <Compartment>: <Statistic>"}, the {@code µm}-suffixed
 * morphology names, and the {@code "[Layer0] "} prefix {@code import_phenotype.groovy}
 * adds — and it can express the deliberately malformed inputs tests need: an absent
 * key on some cells but not others, a marker in the panel with no measurement behind
 * it, a duplicate or null panel entry, {@code NaN}.
 *
 * <h2>Shape</h2>
 * <pre>{@code
 * CellIndex index = Cells.of(3)
 *         .atGrid(10, 10)                                     // ROI at (i*10, i*10)
 *         .marker("CD3", 1.0, 2.0, 3.0)                       // bare key == whole-cell mean
 *         .marker("CD3", Compartment.NUCLEAR, Statistic.MEDIAN, 0.5, 0.6, 0.7)
 *         .area(100.0)                                        // one value broadcasts
 *         .build();
 * }</pre>
 * Every column takes either exactly {@code n} values, one value to broadcast to all
 * cells, or an {@link IntToDoubleFunction} of the cell index. Measurements are written
 * in declaration order, which is the order {@code CellIndex} samples keys in.
 * <p>
 * A builder is single-use: {@link #detections()} materialises the population once and
 * memoises it, so {@code detections()} and {@link #build()} always describe the same
 * objects and {@code index.getObject(i) == cells.detections().get(i)}.
 */
public final class Cells {

    /** The layer prefix {@code import_phenotype.groovy} puts in front of every measurement. */
    public static final String LAYER_PREFIX = "[Layer0] ";

    /** One measurement column: its key, its per-cell value, and where it is present at all. */
    private static final class Col {
        final String key;
        final IntToDoubleFunction value;
        IntPredicate present = i -> true;

        Col(String key, IntToDoubleFunction value) {
            this.key = key;
            this.value = value;
        }
    }

    private final int n;
    private final List<Col> cols = new ArrayList<>();
    private final LinkedHashSet<String> declaredMarkers = new LinkedHashSet<>();

    private List<String> panel;                 // null -> the markers declared below
    private IntToDoubleFunction roiX = i -> 0.0;
    private IntToDoubleFunction roiY = i -> 0.0;
    private boolean layerPrefixed = false;
    private MarkerSelection selection;
    private PixelCalibration calibration;
    private IntToDoubleFunction areaMicrons2;   // remembered so a MIRAGE Sum can use it
    private Col lastCol;                        // what absentOn() applies to

    private List<PathObject> materialised;

    private Cells(int n) {
        this.n = n;
    }

    /** Begin describing a population of {@code n} cells, all at the origin. */
    public static Cells of(int n) {
        return new Cells(n);
    }

    /**
     * The plain synthetic population several test classes each open-coded identically: one
     * cell per column of {@code valuesByMarker} ({@code [marker][cell]}), each carrying
     * the bare marker keys and a uniform lowercase {@code "area"} of 100, with cell
     * {@code i}'s ROI at {@code (i*10, i*10)}.
     * <p>
     * No structured keys and no {@code µm} suffixes: this is the <em>legacy</em> GeoJSON
     * shape, and the gating and CSV tests that use it are about arithmetic rather than
     * about key resolution. Override the area with {@link #area}, the positions with
     * {@link #at} or {@link #atGrid}.
     */
    public static Cells columns(List<String> markers, double[][] valuesByMarker) {
        Cells cells = new Cells(valuesByMarker[0].length).atGrid(10, 10);
        for (int m = 0; m < markers.size(); m++) {
            cells.marker(markers.get(m), valuesByMarker[m]);
        }
        return cells.area(100.0);
    }

    // ---- position ---------------------------------------------------------------

    /** Place cell {@code i}'s ROI at {@code (i * dx, i * dy)}. */
    public Cells atGrid(double dx, double dy) {
        roiX = i -> i * dx;
        roiY = i -> i * dy;
        return this;
    }

    /** Place the ROIs at the given coordinates, one pair per cell. */
    public Cells at(double[] xs, double[] ys) {
        require(xs.length == n && ys.length == n,
                "at() needs " + n + " coordinates, got " + xs.length + "/" + ys.length);
        roiX = i -> xs[i];
        roiY = i -> ys[i];
        return this;
    }

    /** Put every cell's ROI at the same point — the natural spelling for a one-cell fixture. */
    public Cells at(double x, double y) {
        roiX = i -> x;
        roiY = i -> y;
        return this;
    }

    /** Place the ROIs by function of the cell index. */
    public Cells at(IntToDoubleFunction xs, IntToDoubleFunction ys) {
        roiX = xs;
        roiY = ys;
        return this;
    }

    // ---- markers ----------------------------------------------------------------

    /**
     * The bare {@code "<marker>"} key — which MIRAGE defines as the whole-cell mean, and
     * which is the only marker column a legacy (pre-compartment) GeoJSON carries.
     * Declares {@code name} in the panel.
     */
    public Cells marker(String name, double... values) {
        declaredMarkers.add(name);
        return measurement(name, values);
    }

    /** As {@link #marker(String, double...)}, valued by function of the cell index. */
    public Cells marker(String name, IntToDoubleFunction values) {
        declaredMarkers.add(name);
        return measurement(name, values);
    }

    /**
     * The structured {@code "<marker>: <Compartment>: <Statistic>"} key MIRAGE's
     * {@code quantify.py} writes. Declares {@code name} in the panel.
     */
    public Cells marker(String name, Compartment compartment, Statistic statistic, double... values) {
        declaredMarkers.add(name);
        return measurement(MeasurementKeys.build(name, compartment, statistic), values);
    }

    /** As {@link #marker(String, Compartment, Statistic, double...)}, valued by function. */
    public Cells marker(String name, Compartment compartment, Statistic statistic,
                        IntToDoubleFunction values) {
        declaredMarkers.add(name);
        return measurement(MeasurementKeys.build(name, compartment, statistic), values);
    }

    /**
     * One marker as a MIRAGE {@code --expanded_quantification} export carries it: the bare
     * whole-cell mean, {@code Cell: Mean} equal to it, {@code Nucleus: Mean} at 2x and
     * {@code Cytoplasm: Mean} at 0.5x so each compartment is a distinguishable column,
     * plus the expanded-only {@code Nucleus: Median} (1.5x) and {@code Cell: Sum}
     * (value x area). Requires {@link #mirageMorphology} for the area the Sum uses.
     */
    public Cells mirageMarker(String name, double... wholeCellMeans) {
        return mirageMarker(name, broadcast(name, wholeCellMeans));
    }

    /** As {@link #mirageMarker(String, double...)}, valued by function of the cell index. */
    public Cells mirageMarker(String name, IntToDoubleFunction wholeCellMeans) {
        IntToDoubleFunction v = wholeCellMeans;
        marker(name, v);
        marker(name, Compartment.WHOLE_CELL, Statistic.MEAN, v);
        marker(name, Compartment.NUCLEAR, Statistic.MEAN, i -> v.applyAsDouble(i) * 2.0);
        marker(name, Compartment.CYTOPLASMIC, Statistic.MEAN, i -> v.applyAsDouble(i) * 0.5);
        marker(name, Compartment.NUCLEAR, Statistic.MEDIAN, i -> v.applyAsDouble(i) * 1.5);
        marker(name, Compartment.WHOLE_CELL, Statistic.SUM,
                i -> v.applyAsDouble(i) * areaOrThrow().applyAsDouble(i));
        return this;
    }

    /**
     * One marker as a MIRAGE <em>default</em> (non-expanded) run carries it: the bare
     * whole-cell mean plus {@code Median} for every compartment — Cell 0.9x, Nucleus 1.5x,
     * Cytoplasm 0.5x — and no {@code Mean} or {@code Sum} compartment key at all.
     * <p>
     * This, not {@link #mirageMarker}, is the common production shape:
     * {@code --expanded_quantification} is off by default, and pinning a gate axis to a
     * statistic such an export lacks resolves to a key that is not in the file.
     */
    public Cells mirageMedianMarker(String name, double... wholeCellMeans) {
        return mirageMedianMarker(name, broadcast(name, wholeCellMeans));
    }

    /** As {@link #mirageMedianMarker(String, double...)}, valued by function of the cell index. */
    public Cells mirageMedianMarker(String name, IntToDoubleFunction wholeCellMeans) {
        marker(name, wholeCellMeans);
        marker(name, Compartment.WHOLE_CELL, Statistic.MEDIAN,
                i -> wholeCellMeans.applyAsDouble(i) * 0.9);
        marker(name, Compartment.NUCLEAR, Statistic.MEDIAN,
                i -> wholeCellMeans.applyAsDouble(i) * 1.5);
        marker(name, Compartment.CYTOPLASMIC, Statistic.MEDIAN,
                i -> wholeCellMeans.applyAsDouble(i) * 0.5);
        return this;
    }

    // ---- morphology and other raw measurements ----------------------------------

    /** An arbitrary measurement key, written verbatim. Does not touch the panel. */
    public Cells measurement(String key, double... values) {
        return measurement(key, broadcast(key, values));
    }

    /**
     * An arbitrary measurement key, valued by function of the cell index.
     * <p>
     * Re-declaring a key <em>replaces</em> its value in place rather than appending a
     * second column, exactly as a second {@code put} on the underlying measurement list
     * would: same key, same position, new value. That is what lets a test take a whole
     * preset like {@link #mirageMarker} and then overwrite one column of it.
     */
    public Cells measurement(String key, IntToDoubleFunction values) {
        for (int i = 0; i < cols.size(); i++) {
            if (cols.get(i).key.equals(key)) {
                Col replacement = new Col(key, values);
                replacement.present = cols.get(i).present;
                cols.set(i, replacement);
                lastCol = replacement;
                return this;
            }
        }
        lastCol = new Col(key, values);
        cols.add(lastCol);
        return this;
    }

    /** Alias of {@link #measurement(String, double...)} that reads better for shape metrics. */
    public Cells morphology(String key, double... values) {
        return measurement(key, values);
    }

    /** Alias of {@link #measurement(String, IntToDoubleFunction)}. */
    public Cells morphology(String key, IntToDoubleFunction values) {
        return measurement(key, values);
    }

    /** The lowercase {@code "area"} key the legacy synthetic fixtures write. */
    public Cells area(double... values) {
        return measurement("area", values);
    }

    /** The lowercase {@code "area"} key, valued by function of the cell index. */
    public Cells area(IntToDoubleFunction values) {
        return measurement("area", values);
    }

    /**
     * The morphology block {@code bin/export_geojson.py} writes, in its own order:
     * {@code Area µm²}, {@code Eccentricity} 0.6, {@code Perimeter µm} 30,
     * {@code Solidity} 0.84, {@code Convex Area µm²} = area/0.84, and
     * {@code Major}/{@code Minor Axis Length µm} 10/5. Also remembers the area so
     * {@link #mirageMarker}'s integrated-density Sum can use it.
     */
    public Cells mirageMorphology(double... areaUm2) {
        return mirageMorphology(broadcast("Area µm²", areaUm2));
    }

    /** As {@link #mirageMorphology(double...)}, with the area a function of the cell index. */
    public Cells mirageMorphology(IntToDoubleFunction areaUm2) {
        return mirageMorphology(areaUm2, i -> areaUm2.applyAsDouble(i) / 0.84);
    }

    /**
     * As {@link #mirageMorphology(IntToDoubleFunction)} but with the convex area given
     * independently — MIRAGE emits {@code Convex Area} only when that column survives
     * upstream, so it is not always area/solidity.
     */
    public Cells mirageMorphology(IntToDoubleFunction areaUm2, IntToDoubleFunction convexAreaUm2) {
        this.areaMicrons2 = areaUm2;
        measurement("Area µm²", areaUm2);
        measurement("Eccentricity", 0.6);
        measurement("Perimeter µm", 30.0);
        measurement("Solidity", 0.84);
        measurement("Convex Area µm²", convexAreaUm2);
        measurement("Major Axis Length µm", 10.0);
        measurement("Minor Axis Length µm", 5.0);
        return this;
    }

    // ---- centroids --------------------------------------------------------------

    /**
     * {@code "Centroid X µm"} / {@code "Centroid Y µm"} carrying the ROI position scaled
     * by {@code micronsPerPixel} — the pair {@code export_geojson.py} writes. Passing a
     * wrong scale here is exactly how a mis-set {@code params.pixel_size} reaches FlowPath.
     */
    public Cells centroidsMicronsFromRoi(double micronsPerPixel) {
        measurement("Centroid X µm", i -> roiX.applyAsDouble(i) * micronsPerPixel);
        measurement("Centroid Y µm", i -> roiY.applyAsDouble(i) * micronsPerPixel);
        return this;
    }

    /** {@code "Centroid X µm"} / {@code "Centroid Y µm"} at explicit micrometre positions. */
    public Cells centroidsMicrons(double[] xs, double[] ys) {
        require(xs.length == n && ys.length == n,
                "centroidsMicrons() needs " + n + " coordinates");
        measurement("Centroid X µm", i -> xs[i]);
        measurement("Centroid Y µm", i -> ys[i]);
        return this;
    }

    /**
     * Drop every declared centroid measurement, so positions can only come from the ROI —
     * a native QuPath detection rather than a MIRAGE import.
     */
    public Cells centroidsFromRoi() {
        cols.removeIf(c -> c.key.startsWith("Centroid "));
        return this;
    }

    // ---- deliberately awkward inputs ---------------------------------------------

    /**
     * Omit the most recently declared measurement on the cells {@code where} selects.
     * {@code export_geojson.py} appends nothing at all for a {@code NaN} value, so an
     * upstream join failure arrives as an <em>absent</em> key on some cells — which means
     * something quite different from a literal 0.0, and is the distinction several tests
     * exist to pin.
     */
    public Cells absentOn(IntPredicate where) {
        require(lastCol != null, "absentOn() needs a measurement to apply to");
        lastCol.present = lastCol.present.and(where.negate());
        return this;
    }

    /** Prefix every measurement key with {@value #LAYER_PREFIX}. */
    public Cells layerPrefixed() {
        layerPrefixed = true;
        return this;
    }

    // ---- what gets built ----------------------------------------------------------

    /**
     * The marker panel handed to {@link CellIndex#build}, overriding the markers declared
     * above. Pass no arguments for an empty panel; a name with no measurement behind it,
     * a duplicate, or a {@code null} are all legal and are what the corresponding
     * {@code CellIndex} degradation tests are about.
     */
    public Cells panel(String... markers) {
        this.panel = Arrays.asList(markers);
        return this;
    }

    /** The marker panel as a list. See {@link #panel(String...)}. */
    public Cells panel(List<String> markers) {
        this.panel = markers;
        return this;
    }

    /** Resolve each marker through a per-marker (compartment, statistic) selection. */
    public Cells selection(MarkerSelection selection) {
        this.selection = selection;
        return this;
    }

    /** Give the index the image's pixel calibration, so its geometry knows both spaces. */
    public Cells calibration(PixelCalibration calibration) {
        this.calibration = calibration;
        return this;
    }

    /** The raw detections, materialised once. */
    public List<PathObject> detections() {
        if (materialised != null) return materialised;
        List<PathObject> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            PathObject o = detectionAt(roiX.applyAsDouble(i), roiY.applyAsDouble(i));
            var m = o.getMeasurements();
            for (Col c : cols) {
                if (!c.present.test(i)) continue;
                m.put(layerPrefixed ? LAYER_PREFIX + c.key : c.key, c.value.applyAsDouble(i));
            }
            out.add(o);
        }
        materialised = out;
        return out;
    }

    /** The single detection of a one-cell population, for tests that want the object itself. */
    public PathObject only() {
        require(n == 1, "only() describes a one-cell population, but this one has " + n);
        return detections().get(0);
    }

    /** The index over {@link #detections()}. */
    public CellIndex build() {
        return CellIndex.build(detections(), panelNames(), selection, calibration);
    }

    /** The panel this population will be indexed under. */
    public List<String> panelNames() {
        return panel != null ? panel : new ArrayList<>(declaredMarkers);
    }

    // ---- standalone helpers ---------------------------------------------------------

    /** A bare detection at the origin, carrying no measurements. */
    public static PathObject detection() {
        return detectionAt(0, 0);
    }

    /** A bare detection whose point ROI sits at {@code (x, y)}, carrying no measurements. */
    public static PathObject detectionAt(double x, double y) {
        return PathObjects.createDetectionObject(
                ROIs.createPointsROI(x, y, ImagePlane.getDefaultPlane()));
    }

    /** A detection at {@code (x, y)} carrying exactly {@code measurements}. */
    public static PathObject detectionAt(double x, double y, Map<String, ? extends Number> measurements) {
        PathObject o = detectionAt(x, y);
        measurements.forEach((k, v) -> o.getMeasurements().put(k, v.doubleValue()));
        return o;
    }

    /** An all-true quality mask of length {@code n} — "every cell passes QC". */
    public static boolean[] allTrue(int n) {
        boolean[] mask = new boolean[n];
        Arrays.fill(mask, true);
        return mask;
    }

    // ---- internals -------------------------------------------------------------------

    private IntToDoubleFunction areaOrThrow() {
        require(areaMicrons2 != null,
                "mirageMarker() writes an integrated-density Sum, so it needs mirageMorphology()");
        return areaMicrons2;
    }

    private IntToDoubleFunction broadcast(String key, double[] values) {
        if (values.length == n) return i -> values[i];
        if (values.length == 1) {
            double only = values[0];
            return i -> only;
        }
        throw new IllegalArgumentException("'" + key + "' needs " + n
                + " values or exactly 1 to broadcast, got " + values.length);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
