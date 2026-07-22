package qupath.ext.flowpath.model;

import qupath.lib.objects.PathObject;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CellIndex {

    // Lazily-built compartment columns, keyed by resolved measurement key.
    private final Map<String, double[]> resolvedColumns = new ConcurrentHashMap<>();

    private final PathObject[] objects;
    private final String[] markerNames;
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

    public static CellIndex build(Collection<PathObject> detections, List<String> markerNames) {
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

            // Spatial coordinates
            centroidX[i] = findMeasurement(measurements, "Centroid X");
            centroidY[i] = findMeasurement(measurements, "Centroid Y");

            double totalIntensity = 0;
            for (int j = 0; j < m; j++) {
                double v = findMarkerValue(measurements, markers[j]);
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

    public double[] getMarkerValues(int markerIndex) {
        return values[markerIndex];
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

    public int getMarkerIndex(String name) {
        for (int i = 0; i < markerNames.length; i++) {
            if (markerNames[i].equals(name))
                return i;
        }
        return -1;
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
