package qupath.ext.flowpath.model;

import qupath.lib.objects.PathObject;
import qupath.lib.roi.interfaces.ROI;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Columnar storage for cell data extracted from QuPath {@link PathObject}s.
 * <p>
 * One {@code double[]} per marker (and per morphology metric) rather than one object
 * per cell, so a gating pass or a UMAP feature scan is a contiguous array walk instead
 * of millions of hash lookups. See {@code ARCHITECTURE.md} for the rationale.
 * <p>
 * This index is the single shared substrate for both halves of FlowPath: the gating
 * tree walks it to assign phenotypes, and the UMAP view embeds it. Building it once
 * and handing the same instance to both is what makes
 * {@link qupath.ext.flowpath.umap.PhenotypeSnapshot} cheap.
 */
public class CellIndex {

    // Lazily-built compartment columns, keyed by resolved measurement key.
    private final Map<String, double[]> resolvedColumns = new ConcurrentHashMap<>();

    private final PathObject[] objects;
    private final String[] markerNames;
    // Marker name -> column index. Resolved once per cell per gate in the gating
    // walk, so a linear scan over the panel here costs O(cells x gates x markers)
    // string comparisons per preview refresh.
    private final Map<String, Integer> markerIndexByName;
    private final double[][] values; // [markerIndex][cellIndex]
    private final double[] areas;
    private final double[] perimeters;
    private final double[] eccentricities;
    private final double[] solidities;
    private final double[] totalIntensities;
    private final double[] centroidX;
    private final double[] centroidY;
    private final int size;

    private CellIndex(PathObject[] objects, String[] markerNames, double[][] values,
                      double[] areas, double[] perimeters, double[] eccentricities,
                      double[] solidities, double[] totalIntensities,
                      double[] centroidX, double[] centroidY) {
        this.objects = objects;
        this.markerNames = markerNames;
        // putIfAbsent keeps first-declared-wins, matching the scan this replaced;
        // a null name is skipped rather than rejected, because the scan simply never
        // matched one and callers should not start seeing an NPE from the constructor.
        Map<String, Integer> byName = new HashMap<>(Math.max(1, markerNames.length * 2));
        for (int i = 0; i < markerNames.length; i++) {
            if (markerNames[i] != null) byName.putIfAbsent(markerNames[i], i);
        }
        this.markerIndexByName = Map.copyOf(byName);
        this.values = values;
        this.areas = areas;
        this.perimeters = perimeters;
        this.eccentricities = eccentricities;
        this.solidities = solidities;
        this.totalIntensities = totalIntensities;
        this.centroidX = centroidX;
        this.centroidY = centroidY;
        this.size = objects.length;
    }

    /**
     * Build an index resolving each marker to its whole-cell mean (the default).
     * Equivalent to {@link #build(Collection, List, MarkerSelection)} with a null selection.
     */
    public static CellIndex build(Collection<PathObject> detections, List<String> markerNames) {
        return build(detections, markerNames, null);
    }

    /**
     * Build an index resolving each marker via a per-marker {@code (compartment, statistic)}
     * selection. A null {@code selection} — or a marker absent from it — resolves to
     * whole-cell mean, which itself falls back to the bare marker key for legacy GeoJSONs.
     */
    public static CellIndex build(Collection<PathObject> detections, List<String> markerNames,
                                  MarkerSelection selection) {
        int n = detections.size();
        int m = markerNames.size();

        PathObject[] objects = detections.toArray(new PathObject[0]);
        String[] markers = markerNames.toArray(new String[0]);
        double[][] values = new double[m][n];
        double[] areas = new double[n];
        double[] perimeters = new double[n];
        double[] eccentricities = new double[n];
        double[] solidities = new double[n];
        double[] totalIntensities = new double[n];
        double[] centroidX = new double[n];
        double[] centroidY = new double[n];

        // Resolve each marker's (compartment, statistic) once up front.
        Compartment[] comps = new Compartment[m];
        Statistic[] stats = new Statistic[m];
        for (int j = 0; j < m; j++) {
            if (selection != null) {
                comps[j] = selection.compartmentFor(markers[j]);
                stats[j] = selection.statisticFor(markers[j]);
            } else {
                comps[j] = Compartment.defaultCompartment();
                stats[j] = Statistic.defaultStatistic();
            }
        }

        // Resolve each marker to ONE concrete measurement key, once for the whole
        // build, from a sample of the detections' key sets. Without this, every
        // cell x marker pair whose structured key is absent (the common case for
        // legacy bare-marker data) fell through to a full scan of that cell's
        // measurement map — O(cells x markers x keys) string comparisons, which
        // dominated index-build time on large slides. With a resolved key the
        // inner loop is a single hash lookup.
        Set<String> sampleKeys = sampleMeasurementKeys(objects);
        String[] markerKeys = new String[m];
        for (int j = 0; j < m; j++) {
            markerKeys[j] = resolveMarkerKey(sampleKeys, markers[j], comps[j], stats[j]);
        }

        int i = 0;
        for (PathObject obj : objects) {
            Map<String, Number> measurements = getMeasurements(obj);

            double area = findMeasurement(measurements, "area");
            double convexArea = findMeasurement(measurements, "convex_area");
            double eccentricity = findMeasurement(measurements, "eccentricity");
            double perimeter = findMeasurement(measurements, "perimeter");

            areas[i] = area;
            perimeters[i] = perimeter;
            eccentricities[i] = eccentricity;
            // Solidity = area / convex_area, falling back to a directly exported
            // "Solidity" measurement. MIRAGE writes both, but only emits Convex Area
            // when that column survives upstream — without the fallback the quality
            // filter silently drops solidity from its available metrics.
            if (!Double.isNaN(area) && !Double.isNaN(convexArea) && convexArea > 0) {
                solidities[i] = area / convexArea;
            } else {
                solidities[i] = findMeasurement(measurements, "solidity");
            }

            // An explicit centroid measurement wins (it round-trips FlowPath exports
            // faithfully, including their units). Native QuPath detections carry no
            // such measurement — their position lives on the ROI — so the ROI centroid
            // is the fallback. Without it every centroid is NaN for QuPath-detected
            // cells, which blanks the CSV's centroid columns and leaves nothing to map
            // a UMAP point back to tissue coordinates.
            centroidX[i] = findMeasurement(measurements, "Centroid X");
            centroidY[i] = findMeasurement(measurements, "Centroid Y");
            if (Double.isNaN(centroidX[i]) || Double.isNaN(centroidY[i])) {
                ROI roi = obj.getROI();
                if (roi != null) {
                    if (Double.isNaN(centroidX[i])) centroidX[i] = roi.getCentroidX();
                    if (Double.isNaN(centroidY[i])) centroidY[i] = roi.getCentroidY();
                }
            }

            double totalIntensity = 0;
            for (int j = 0; j < m; j++) {
                String key = markerKeys[j];
                double v;
                if (key != null) {
                    Number num = measurements.get(key);
                    v = num != null ? num.doubleValue() : Double.NaN;
                } else {
                    // Unresolved against the sample. Fall back to exhaustive per-cell
                    // resolution so a heterogeneous measurement list (a marker present
                    // only on cells outside the sample) still yields a value rather
                    // than silently becoming NaN.
                    v = findMarkerValue(measurements, markers[j], comps[j], stats[j]);
                }
                values[j][i] = v;
                if (!Double.isNaN(v)) {
                    totalIntensity += v;
                }
            }
            totalIntensities[i] = totalIntensity;

            i++;
        }

        return new CellIndex(objects, markers, values, areas, perimeters, eccentricities,
                solidities, totalIntensities, centroidX, centroidY);
    }

    /** How many detections to inspect when resolving measurement keys. */
    private static final int KEY_SAMPLE_SIZE = 20;

    /**
     * Union of measurement keys across the first {@link #KEY_SAMPLE_SIZE} detections.
     * Matches the sampling depth marker discovery already uses, so a marker that was
     * discoverable is also resolvable here.
     */
    private static Set<String> sampleMeasurementKeys(PathObject[] objects) {
        Set<String> keys = new HashSet<>();
        int sampled = 0;
        for (PathObject obj : objects) {
            keys.addAll(getMeasurements(obj).keySet());
            if (++sampled >= KEY_SAMPLE_SIZE) break;
        }
        return keys;
    }

    /**
     * Resolve the concrete measurement key for a marker, mirroring the priority order of
     * {@link #findMarkerValue(Map, String, Compartment, Statistic)}: the structured
     * {@code "<marker>: <Compartment>: <Stat>"} key first (exact, then layer-prefixed),
     * then — for whole-cell mean only — the bare marker key. Returns {@code null} when
     * nothing in {@code keys} matches.
     */
    private static String resolveMarkerKey(Set<String> keys, String marker,
                                           Compartment compartment, Statistic statistic) {
        if (compartment == null) compartment = Compartment.defaultCompartment();
        if (statistic == null) statistic = Statistic.defaultStatistic();

        String hit = matchKey(keys, MeasurementKeys.build(marker, compartment, statistic));
        if (hit != null) return hit;

        if (compartment == Compartment.WHOLE_CELL && statistic == Statistic.MEAN) {
            return matchKey(keys, marker);
        }
        return null;
    }

    /** Exact match, else the first key ending with {@code "] " + key} (layer-prefixed form). */
    private static String matchKey(Set<String> keys, String key) {
        if (keys.contains(key)) return key;
        String suffix = "] " + key;
        for (String k : keys) {
            if (k.endsWith(suffix)) return k;
        }
        return null;
    }

    private static Map<String, Number> getMeasurements(PathObject obj) {
        try {
            var m = obj.getMeasurements();
            if (m != null) return m;
        } catch (Exception ignored) {
        }
        return Map.of();
    }

    /**
     * Find a marker intensity value by channel name.
     * Tries exact match first, then looks for "[layer] channel" patterns
     * (from import_phenotype.groovy layer-prefixed measurements), and finally the
     * structured whole-cell mean key {@code "<channel>: Cell: Mean"}.
     * <p>
     * The last step matters for a <em>structured-only</em> GeoJSON: MIRAGE currently
     * writes a bare {@code "CD3"} column alongside the per-compartment keys, but
     * {@code "CD3: Cell: Mean"} <em>is</em> the whole-cell mean by definition. Without
     * this fallback such a file resolves the default (whole-cell/mean) selection to
     * NaN for every cell while {@link CompartmentCapability} still advertises the
     * marker — an empty histogram over data that is right there.
     */
    private static double findMarkerValue(Map<String, Number> measurements, String channel) {
        // Exact match
        Number val = measurements.get(channel);
        if (val != null) return val.doubleValue();

        // Layer-prefixed match: "[something] channel"
        String suffix = "] " + channel;
        for (Map.Entry<String, Number> entry : measurements.entrySet()) {
            if (entry.getKey().endsWith(suffix) && entry.getValue() != null) {
                return entry.getValue().doubleValue();
            }
        }

        // Structured whole-cell mean, exact then layer-prefixed.
        String structured = MeasurementKeys.build(channel, Compartment.WHOLE_CELL, Statistic.MEAN);
        val = measurements.get(structured);
        if (val != null) return val.doubleValue();
        String structuredSuffix = "] " + structured;
        for (Map.Entry<String, Number> entry : measurements.entrySet()) {
            if (entry.getKey().endsWith(structuredSuffix) && entry.getValue() != null) {
                return entry.getValue().doubleValue();
            }
        }
        return Double.NaN;
    }

    /**
     * Resolve a marker value for a specific compartment and statistic, using the
     * QuPath-native key {@code "<channel>: <Compartment>: <Stat>"}.
     * <p>
     * Resolution order: exact key, then layer-prefixed key, then — only for
     * whole-cell mean — the bare {@code channel} key so legacy GeoJSONs (which
     * carry a single {@code "CD3"} measurement) keep working unchanged. Returns
     * {@code NaN} if nothing matches.
     */
    public static double findMarkerValue(Map<String, Number> measurements, String channel,
                                         Compartment compartment, Statistic statistic) {
        Compartment comp = compartment != null ? compartment : Compartment.WHOLE_CELL;
        Statistic stat = statistic != null ? statistic : Statistic.MEAN;

        String key = MeasurementKeys.build(channel, comp, stat);
        Number val = measurements.get(key);
        if (val != null) return val.doubleValue();

        String suffix = "] " + key;
        for (Map.Entry<String, Number> entry : measurements.entrySet()) {
            if (entry.getKey().endsWith(suffix) && entry.getValue() != null) {
                return entry.getValue().doubleValue();
            }
        }

        // Backward compatibility: whole-cell mean falls back to the bare marker key.
        if (comp == Compartment.WHOLE_CELL && stat == Statistic.MEAN) {
            return findMarkerValue(measurements, channel);
        }
        return Double.NaN;
    }

    /**
     * Find a morphological measurement by key name.
     * Tries exact match, then layer-prefixed "[layer] key", then prefix match
     * (e.g., "area" matches "area µm²"). Returns NaN if not found.
     * <p>
     * The layer prefix is stripped before the prefix pass so the two conventions
     * compose: {@code "[Layer0] Area µm²"} carries both a prefix and a unit suffix,
     * and matched neither branch on its own.
     */
    private static double findMeasurement(Map<String, Number> measurements, String key) {
        Number val = measurements.get(key);
        if (val != null) return val.doubleValue();

        // Layer-prefixed match: "[layer] key" (case-insensitive)
        String suffixLower = ("] " + key).toLowerCase();
        for (Map.Entry<String, Number> entry : measurements.entrySet()) {
            if (entry.getKey().toLowerCase().endsWith(suffixLower) && entry.getValue() != null) {
                return entry.getValue().doubleValue();
            }
        }

        // Prefix match: "area" matches "Area µm²" (case-insensitive, underscores treated as spaces)
        String keyLower = key.toLowerCase().replace('_', ' ');
        for (Map.Entry<String, Number> entry : measurements.entrySet()) {
            String entryLower = MeasurementKeys.stripLayerPrefix(entry.getKey())
                    .toLowerCase().replace('_', ' ');
            if (entryLower.startsWith(keyLower) && entry.getValue() != null) {
                return entry.getValue().doubleValue();
            }
        }

        return Double.NaN;
    }

    /**
     * The backing value column for a marker — <b>read-only, do not mutate</b>.
     * <p>
     * Deliberately not a defensive copy. Every caller on the gating and UMAP hot paths
     * pre-fetches whole columns to scan them; cloning here would allocate a complete
     * copy of the dataset (N x M x 8 bytes — over a gigabyte on a multi-million-cell
     * slide) purely to read it.
     */
    public double[] getMarkerValues(int markerIndex) {
        return values[markerIndex];
    }

    /**
     * Explicit read-only alias for {@link #getMarkerValues(int)}, kept for call sites
     * that want the no-copy contract stated at the call rather than inferred.
     */
    public double[] getMarkerValuesRaw(int markerIndex) {
        return values[markerIndex];
    }

    /**
     * Extract marker data as a cell-by-marker matrix for UMAP input.
     * Transposes from {@code [marker][cell]} to {@code [cell][marker]} layout and
     * replaces NaN values with that marker's column mean, so a missing measurement
     * lands at the centre of its distribution instead of poisoning the embedding.
     */
    public double[][] toMatrix() {
        int n = size;
        int m = markerNames.length;
        double[][] matrix = new double[n][m];

        for (int j = 0; j < m; j++) {
            double sum = 0;
            int count = 0;
            for (int i = 0; i < n; i++) {
                double v = values[j][i];
                if (!Double.isNaN(v)) {
                    sum += v;
                    count++;
                }
            }
            double mean = count > 0 ? sum / count : 0.0;

            for (int i = 0; i < n; i++) {
                double v = values[j][i];
                matrix[i][j] = Double.isNaN(v) ? mean : v;
            }
        }

        return matrix;
    }

    /**
     * The resolved measurement key for a channel + compartment + statistic.
     * Whole-cell mean resolves to the bare channel name (so legacy/default data
     * uses the existing column and stats unchanged); other selections use the
     * {@code "<channel>: <Compartment>: <Stat>"} key.
     */
    public String resolvedKey(String channel, Compartment compartment, Statistic statistic) {
        if (isDefault(compartment, statistic)) return channel;
        return MeasurementKeys.build(channel, compartment, statistic);
    }

    /**
     * Column of per-cell values for a channel + compartment + statistic.
     * For whole-cell mean this returns the pre-built base column; other selections
     * build the column from the objects' measurements on first use and cache it.
     * Returns a NaN-filled column if the channel/compartment is absent.
     */
    public double[] getResolvedColumn(String channel, Compartment compartment, Statistic statistic) {
        int mi = getMarkerIndex(channel);
        if (isDefault(compartment, statistic) && mi >= 0) {
            return values[mi];
        }
        String key = resolvedKey(channel, compartment, statistic);
        double[] cached = resolvedColumns.get(key);
        if (cached != null) return cached;

        double[] col = new double[size];
        for (int i = 0; i < size; i++) {
            col[i] = findMarkerValue(getMeasurements(objects[i]), channel, compartment, statistic);
        }
        resolvedColumns.put(key, col);
        return col;
    }

    private static boolean isDefault(Compartment compartment, Statistic statistic) {
        return (compartment == null || compartment == Compartment.WHOLE_CELL)
                && (statistic == null || statistic == Statistic.MEAN);
    }

    /** Column index for a marker name, or {@code -1} if this index has no such marker. */
    public int getMarkerIndex(String name) {
        if (name == null) return -1;
        return markerIndexByName.getOrDefault(name, -1);
    }

    public PathObject getObject(int cellIndex) {
        return objects[cellIndex];
    }

    public PathObject[] getObjects() {
        return objects;
    }

    public String[] getMarkerNames() {
        return markerNames;
    }

    public double[] getAreas() {
        return areas;
    }

    public double[] getPerimeters() {
        return perimeters;
    }

    public double[] getEccentricities() {
        return eccentricities;
    }

    public double[] getSolidities() {
        return solidities;
    }

    public double[] getTotalIntensities() {
        return totalIntensities;
    }

    public int getSize() {
        return size;
    }

    public int size() {
        return size;
    }

    public double getArea(int i) {
        return areas[i];
    }

    public double getPerimeter(int i) {
        return perimeters[i];
    }

    public double getEccentricity(int i) {
        return eccentricities[i];
    }

    public double getSolidity(int i) {
        return solidities[i];
    }

    public double getTotalIntensity(int i) {
        return totalIntensities[i];
    }

    public double getCentroidX(int i) {
        return centroidX[i];
    }

    public double getCentroidY(int i) {
        return centroidY[i];
    }
}
