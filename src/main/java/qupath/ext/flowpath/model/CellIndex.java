package qupath.ext.flowpath.model;

import qupath.lib.images.servers.PixelCalibration;
import qupath.lib.objects.PathObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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

    private static final org.slf4j.Logger logger =
            org.slf4j.LoggerFactory.getLogger(CellIndex.class);

    // Lazily-built compartment columns, keyed by resolved measurement key.
    private final Map<String, double[]> resolvedColumns = new ConcurrentHashMap<>();

    private final PathObject[] objects;
    private final String[] markerNames;
    // Marker name -> column index. Resolved once per cell per gate in the gating
    // walk, so a linear scan over the panel here costs O(cells x gates x markers)
    // string comparisons per preview refresh.
    private final Map<String, Integer> markerIndexByName;
    private final double[][] values; // [markerIndex][cellIndex]
    /**
     * Segmentation label — the cell's identity in the mask MIRAGE segmented, as opposed
     * to {@code cell_id}, which is only this collection's index. {@code NaN} where the
     * export carried no label. Held as {@code double} so it shares the columnar
     * representation of every other measurement; it is written back out as an integer.
     */
    private final double[] labels;
    private final boolean hasLabels;
    /** Both coordinate spaces, resolved once. Replaces the old raw centroid arrays. */
    private final CellGeometry geometry;
    private final int size;
    // Union of measurement keys over a sample of the detections, in first-seen order.
    // Kept so a lazily-built compartment column can resolve its concrete key once
    // instead of re-deriving it per cell (see getResolvedColumn).
    private final Set<String> sampleKeys;
    private final BuildDiagnostics diagnostics;

    /** Marker names that appeared more than once in the requested panel (collapsed). */
    private final List<String> duplicateMarkerNames;
    /** How many requested marker names were null and therefore skipped. */
    private final int nullMarkerNames;

    private CellIndex(PathObject[] objects, String[] markerNames, double[][] values,
                      double[] labels, CellGeometry geometry, Set<String> sampleKeys,
                      BuildDiagnostics partialDiagnostics) {
        this.objects = objects;
        this.markerNames = markerNames;
        // putIfAbsent keeps first-declared-wins, matching the scan this replaced;
        // a null name is skipped rather than rejected, because the scan simply never
        // matched one and callers should not start seeing an NPE from the constructor.
        // Both collapses used to be entirely silent — a panel carrying "CD3" twice kept
        // only the first column and a null name vanished — so they are counted here and
        // surfaced through BuildDiagnostics rather than merely tolerated.
        Map<String, Integer> byName = new HashMap<>(Math.max(1, markerNames.length * 2));
        List<String> duplicates = new ArrayList<>(0);
        int nulls = 0;
        for (int i = 0; i < markerNames.length; i++) {
            if (markerNames[i] == null) {
                nulls++;
                continue;
            }
            if (byName.putIfAbsent(markerNames[i], i) != null && !duplicates.contains(markerNames[i])) {
                duplicates.add(markerNames[i]);
            }
        }
        this.duplicateMarkerNames = List.copyOf(duplicates);
        this.nullMarkerNames = nulls;
        this.markerIndexByName = Map.copyOf(byName);
        this.values = values;
        this.labels = labels;
        boolean anyLabel = false;
        for (double label : labels) {
            if (!Double.isNaN(label)) {
                anyLabel = true;
                break;
            }
        }
        this.hasLabels = anyLabel;
        this.geometry = geometry;
        this.size = objects.length;
        // Handed in rather than re-derived: build already sampled these, and taking the
        // sample twice would re-walk the measurement maps of the sampled detections.
        this.sampleKeys = sampleKeys;
        this.diagnostics = partialDiagnostics.withNameCollapses(this.duplicateMarkerNames,
                this.nullMarkerNames);
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
        return build(detections, markerNames, selection, null);
    }

    /**
     * Build an index that also knows the image's pixel calibration, so its
     * {@link CellGeometry} can express positions in <em>both</em> coordinate spaces and
     * can cross-check the exported micrometres against the image's own scale.
     * <p>
     * Pass the calibration whenever it is available — {@code imageData.getServer()
     * .getPixelCalibration()}. A {@code null} calibration is not an error; the geometry
     * simply reports {@link ScaleVerdict.Status#NO_CALIBRATION} and cannot convert
     * between spaces.
     *
     * @param calibration the image's pixel calibration, or {@code null} when unknown
     */
    public static CellIndex build(Collection<PathObject> detections, List<String> markerNames,
                                  MarkerSelection selection, PixelCalibration calibration) {
        int n = detections.size();
        int m = markerNames.size();

        PathObject[] objects = detections.toArray(new PathObject[0]);
        String[] markers = markerNames.toArray(new String[0]);
        double[][] values = new double[m][n];
        double[] labels = new double[n];

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
        Set<String> sampleKeys = MeasurementKeySample.keys(objects);
        String[] markerKeys = new String[m];
        for (int j = 0; j < m; j++) {
            markerKeys[j] = resolveMarkerKey(sampleKeys, markers[j], comps[j], stats[j]);
        }

        // Adapter bookkeeping, gathered INSIDE this one pass. Every counter below is
        // either on a branch that is not the hot path (a null measurement, i.e. a key the
        // sample resolved but this cell lacks) or bounded to the cells the key sample
        // read (MeasurementKeySample.includes). Nothing here adds a second walk over the
        // detections, and nothing adds a per-cell string scan — see the v2.0.1 note above for why that matters.
        int[] missingPerMarker = new int[m];
        int[] sampledZerosPerMarker = new int[m];
        int cellObjects = 0;
        int tileObjects = 0;
        int otherObjects = 0;

        // The segmentation label, when the export carries one: an exact key, never guessed.
        String labelKey = sampleKeys.contains(MeasurementName.LABEL) ? MeasurementName.LABEL : null;
        // Loop-invariant: n is fixed for the whole build, so the sample's stride is computed
        // once here rather than recomputed (a division) inside the per-cell loop below.
        int keySampleStride = MeasurementKeySample.stride(n);

        int i = 0;
        for (PathObject obj : objects) {
            Map<String, Number> measurements = getMeasurements(obj);

            // Object-type census. getDetectionObjects() returns cells, tiles and plain
            // detections alike, so a superpixel or tile silently became a "cell". Counted,
            // not filtered: filtering would change which cells an existing project gates.
            if (obj.isTile()) tileObjects++;
            else if (obj.isCell()) cellObjects++;
            else otherObjects++;

            // A single exact lookup, never a scan: an export without labels must not pay a
            // per-cell search to rediscover an absence.
            if (labelKey != null) {
                Number labelValue = measurements.get(labelKey);
                labels[i] = labelValue != null ? labelValue.doubleValue() : Double.NaN;
            } else {
                labels[i] = Double.NaN;
            }

            // Bounded to the key sample: a literal-zero census over every cell would put
            // an extra compare on the m x n inner loop, and a scale error or a failed
            // upstream join is uniform enough that the sample settles it.
            boolean census = MeasurementKeySample.includes(i, n, keySampleStride);

            for (int j = 0; j < m; j++) {
                String key = markerKeys[j];
                double v;
                if (key != null) {
                    Number num = measurements.get(key);
                    if (num != null) {
                        v = num.doubleValue();
                        // MIRAGE's export_geojson.py OMITS a NaN measurement entirely
                        // (bin/export_geojson.py, `if pd.notna(val)`), while quantify.py
                        // writes a literal 0.0 for a genuinely empty compartment. The two
                        // mean opposite things; this counter is what lets the report say
                        // which of them a marker's blank cells actually are.
                        if (census && v == 0.0) sampledZerosPerMarker[j]++;
                    } else {
                        // The sample resolved this key but this cell does not carry it.
                        // Reads NaN with no rescan (the v2.0.1 tradeoff) and, because
                        // QualityFilter.passes skips every NaN criterion, such a cell
                        // PASSES QC rather than being excluded.
                        v = Double.NaN;
                        missingPerMarker[j]++;
                    }
                } else {
                    // Unresolved against the sample. Fall back to exhaustive per-cell
                    // resolution so a heterogeneous measurement list (a marker present
                    // only on cells outside the sample) still yields a value rather
                    // than silently becoming NaN.
                    v = findMarkerValue(measurements, markers[j], comps[j], stats[j]);
                }
                values[j][i] = v;
            }

            i++;
        }

        // Positions are resolved by CellGeometry, which reuses the key sample taken
        // above and settles both coordinate spaces at once — including the joint
        // (never per-axis) ROI fallback that keeps a row from mixing µm and pixels.
        CellGeometry geometry = CellGeometry.of(objects, sampleKeys, calibration);

        // Assemble what the pass observed. Marker-keyed rather than index-keyed so a
        // duplicate name collapses the same way markerIndexByName does.
        Map<String, String> resolved = new LinkedHashMap<>();
        List<String> unresolved = new ArrayList<>(0);
        Map<String, Integer> missing = new LinkedHashMap<>();
        Map<String, Integer> zeros = new LinkedHashMap<>();
        for (int j = 0; j < m; j++) {
            String marker = markers[j];
            if (marker == null) continue;
            if (markerKeys[j] != null) {
                resolved.putIfAbsent(marker, markerKeys[j]);
                if (missingPerMarker[j] > 0) missing.putIfAbsent(marker, missingPerMarker[j]);
                if (sampledZerosPerMarker[j] > 0) zeros.putIfAbsent(marker, sampledZerosPerMarker[j]);
            } else if (!unresolved.contains(marker)) {
                unresolved.add(marker);
            }
        }
        BuildDiagnostics partial = new BuildDiagnostics(
                n, cellObjects, tileObjects, otherObjects,
                MeasurementKeySample.size(n), MeasurementKeySample.MAX_CELLS,
                Map.copyOf(resolved), List.copyOf(unresolved),
                Map.copyOf(missing), Map.copyOf(zeros),
                List.of(), 0);

        return new CellIndex(objects, markers, values, labels, geometry, sampleKeys, partial);
    }

    /**
     * What {@link #build} observed about the data it was handed but could not act on —
     * the raw material for {@code IngestReport}.
     * <p>
     * Every field is gathered inside the single build pass. This record deliberately
     * states no policy: it does not decide whether a missing key is an error, only that
     * one was missing. {@code qupath.ext.flowpath.ingest.IngestReport} applies the policy.
     *
     * @param detectionCount          objects handed to the build
     * @param cellObjects             of those, true {@code PathCellObject}s
     * @param tileObjects             of those, tiles/superpixels — never really cells
     * @param otherObjects            of those, plain detections
     * @param sampledCells            cells whose key sets formed the resolution sample
     * @param sampleSize              the sample ceiling, {@link MeasurementKeySample#MAX_CELLS}
     * @param resolvedMarkerKeys      marker -&gt; the one concrete measurement key it reads
     * @param unresolvedMarkers       markers the sample offered no key for at all
     * @param cellsMissingResolvedKey marker -&gt; cells lacking a key the sample resolved
     * @param sampledZeroValueCells   marker -&gt; sampled cells whose value was literally 0.0
     * @param duplicateMarkerNames    names requested more than once (only the first kept)
     * @param nullMarkerNames         null names requested, silently skipped
     */
    public record BuildDiagnostics(int detectionCount,
                                   int cellObjects, int tileObjects, int otherObjects,
                                   int sampledCells, int sampleSize,
                                   Map<String, String> resolvedMarkerKeys,
                                   List<String> unresolvedMarkers,
                                   Map<String, Integer> cellsMissingResolvedKey,
                                   Map<String, Integer> sampledZeroValueCells,
                                   List<String> duplicateMarkerNames,
                                   int nullMarkerNames) {

        /** Completed in the constructor, which is where the name collapses are detected. */
        BuildDiagnostics withNameCollapses(List<String> duplicates, int nulls) {
            return new BuildDiagnostics(detectionCount, cellObjects, tileObjects, otherObjects,
                    sampledCells, sampleSize, resolvedMarkerKeys, unresolvedMarkers,
                    cellsMissingResolvedKey, sampledZeroValueCells, duplicates, nulls);
        }
    }

    /** What the build pass observed but could not act on. Never {@code null}. */
    public BuildDiagnostics diagnostics() {
        return diagnostics;
    }

    /**
     * Resolve the concrete measurement key for a marker, mirroring the priority order of
     * {@link #findMarkerValue(Map, String, Compartment, Statistic)}: the structured
     * {@code "<marker>: <Compartment>: <Stat>"} key first, then — for whole-cell mean only —
     * the bare marker key. Returns {@code null} when
     * nothing in {@code keys} matches.
     */
    private static String resolveMarkerKey(Set<String> keys, String marker,
                                           Compartment compartment, Statistic statistic) {
        String hit = matchKey(keys, MeasurementKeys.build(
                marker,
                compartment != null ? compartment : Compartment.defaultCompartment(),
                statistic != null ? statistic : Statistic.defaultStatistic()));
        if (hit != null) return hit;

        // Only the default selection has a second, bare address — see isDefault.
        if (isDefault(compartment, statistic)) {
            return matchKey(keys, marker);
        }
        return null;
    }

    /** Exact match only. */
    private static String matchKey(Set<String> keys, String key) {
        return keys.contains(key) ? key : null;
    }

    /**
     * A detection's measurements, or an empty map if they cannot be read.
     * <p>
     * An empty map here becomes an all-NaN row rather than an error, which is deliberate:
     * one bad detection should not stop a slide loading. It is logged because the symptom
     * otherwise arrives much later and in a different shape -- a marker that reads NaN for
     * some cells, with nothing pointing back to the detection that could not be read.
     */
    static Map<String, Number> getMeasurements(PathObject obj) {
        try {
            var m = obj.getMeasurements();
            if (m != null) return m;
        } catch (Exception e) {
            logger.debug("Detection has unreadable measurements; treating as empty", e);
        }
        return Map.of();
    }

    /**
     * Resolve a marker value for a specific compartment and statistic, using the
     * QuPath-native key {@code "<channel>: <Compartment>: <Stat>"}.
     * <p>
     * Resolution order: the structured key, then — only for the default selection, per
     * {@link #isDefault} — the bare {@code channel} key, which MIRAGE writes on every cell as
     * the whole-cell mean. Returns {@code NaN} if nothing matches.
     * <p>
     * This is the per-cell fallback scan used when a key could not be resolved once for
     * the whole build against {@link #sampleKeys}; it applies the same two rules
     * {@link #resolveMarkerKey} applies to a key set, so the two cannot drift apart.
     * The <em>bare</em> address matters in both directions: a structured-only GeoJSON
     * (MIRAGE with {@code "CD3: Cell: Mean"} but no {@code "CD3"}) resolves through the
     * structured step, a bare-only export through the fallback.
     */
    public static double findMarkerValue(Map<String, Number> measurements, String channel,
                                         Compartment compartment, Statistic statistic) {
        Compartment comp = compartment != null ? compartment : Compartment.WHOLE_CELL;
        Statistic stat = statistic != null ? statistic : Statistic.MEAN;

        double v = lookupKey(measurements, MeasurementKeys.build(channel, comp, stat));
        if (!Double.isNaN(v)) return v;

        // The default selection also answers to the bare key.
        if (isDefault(comp, stat)) {
            return lookupKey(measurements, channel);
        }
        return Double.NaN;
    }

    /** Read one measurement by its exact key, or NaN. */
    private static double lookupKey(Map<String, Number> measurements, String key) {
        Number val = measurements.get(key);
        return val != null ? val.doubleValue() : Double.NaN;
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


    // There is deliberately no toMatrix() here any more.
    //
    // It transposed EVERY column into UMAP input, which is precisely how the feature
    // picker's include flag came to mean nothing: filtering was something a caller had to
    // remember to do, and no caller did. Building embedding input is now
    // qupath.ext.flowpath.umap.engine.EmbeddingFeatures.Selected#toMatrix, which can only
    // address the markers the user ticked. Nothing in the gating half ever called this.

    /**
     * The resolved measurement key for a triple, without an index: the bare channel for
     * whole-cell mean, else {@code "<channel>: <Compartment>: <Stat>"}. The key alignments are
     * stored under; {@link #resolvedKey} is this.
     */
    public static String keyFor(String channel, Compartment compartment, Statistic statistic) {
        if (isDefault(compartment, statistic)) return channel;
        return MeasurementKeys.build(channel, compartment, statistic);
    }

    /**
     * The resolved measurement key for a channel + compartment + statistic.
     * Whole-cell mean resolves to the bare channel name (MIRAGE's whole-cell-mean key);
     * other selections use the
     * {@code "<channel>: <Compartment>: <Stat>"} key.
     */
    public String resolvedKey(String channel, Compartment compartment, Statistic statistic) {
        return keyFor(channel, compartment, statistic);
    }

    /**
     * Column of per-cell values for a channel + compartment + statistic — <b>values
     * only</b>. If you are going to z-score, percentile-clip or otherwise summarise these
     * numbers, call {@link #column(String, Compartment, Statistic, MarkerStats)} instead:
     * it returns the same array inside a {@link MeasuredColumn} whose statistics are
     * guaranteed to be registered.
     * <p>
     * For whole-cell mean this returns the pre-built base column; other selections
     * build the column from the objects' measurements on first use and cache it.
     * Returns a NaN-filled column if the channel/compartment is absent.
     * <p>
     * The concrete key is resolved once against {@link #sampleKeys} and then read with a
     * single map lookup per cell, the same way {@link #build} resolves its columns. The
     * per-cell {@link #findMarkerValue} scan remains as the fallback for a key the sample
     * did not cover, but it is the expensive path: it walks every measurement of every
     * cell up to three times, which on a per-compartment export costs ~100x the resolved
     * path. Selections offered by {@code CompartmentCapability} always resolve.
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
        String resolved = resolveMarkerKey(sampleKeys, channel, compartment, statistic);
        if (resolved != null) {
            for (int i = 0; i < size; i++) {
                Number val = getMeasurements(objects[i]).get(resolved);
                col[i] = val != null ? val.doubleValue() : Double.NaN;
            }
        } else {
            for (int i = 0; i < size; i++) {
                col[i] = findMarkerValue(getMeasurements(objects[i]), channel, compartment, statistic);
            }
        }
        resolvedColumns.put(key, col);
        return col;
    }

    /**
     * The one place that knows the bare-key rule: <b>whole-cell mean is the default
     * selection, and the default selection is also addressed by the bare marker name.</b>
     * <p>
     * {@code null} means "unspecified", which is the default. Every other consumer of the
     * rule — {@link #resolvedKey}, {@link #getResolvedColumn}, {@link #resolveMarkerKey}
     * and {@link #findMarkerValue} — routes through here rather than restating it, so a
     * GeoJSON carrying only {@code "CD3"} and a structured export carrying only
     * {@code "CD3: Cell: Mean"} cannot disagree about which column a default gate reads.
     */
    private static boolean isDefault(Compartment compartment, Statistic statistic) {
        // Statistic is an interned value type, so == would in fact work; this is spelled
        // with equals so the bare-column rule does not silently depend on that. A
        // non-interned Statistic would make of("Mean") == MEAN false, sending every
        // default gate to "CD3: Cell: Mean" instead of the bare "CD3" column and reading
        // NaN for every cell without throwing.
        return (compartment == null || compartment == Compartment.WHOLE_CELL)
                && (statistic == null || Statistic.MEAN.equals(statistic));
    }

    /**
     * The measurement column for a channel + compartment + statistic, <b>with its
     * statistics registered</b> — the single call that replaces the old four-step
     * resolve / materialise / {@code ensureColumn} / read protocol.
     * <p>
     * Skipping the registration step used to be silent and wrong (see
     * {@link MeasuredColumn}); a {@code MeasuredColumn} cannot exist without it, so the
     * mistake is no longer expressible. Cheap to call repeatedly: the column itself and
     * its statistics are cached, and only the small handle is allocated. Resolve it once
     * outside a per-cell loop, then index it.
     *
     * @param stats the statistics instance to register against and read through;
     *              must not be {@code null}
     */
    public MeasuredColumn column(String channel, Compartment compartment, Statistic statistic,
                                 MarkerStats stats) {
        if (stats == null) {
            throw new NullPointerException("MarkerStats is required to resolve a MeasuredColumn");
        }
        String key = resolvedKey(channel, compartment, statistic);
        double[] values = getResolvedColumn(channel, compartment, statistic);
        // Idempotent, and safe to lose the check-then-act race: two threads may both
        // compute this column and publish identical numbers. See MarkerStats.ensureColumn.
        stats.ensureColumn(key, values);
        return new MeasuredColumn(key, values, stats);
    }

    /**
     * The measurement column one gate axis reads, resolved through
     * {@link GateNode#compartmentAt(int)} / {@link GateNode#statisticAt(int)} so callers
     * stop re-deriving the {@code (channel, compartment, statistic)} triple — the
     * re-derivation that let {@code GatingEngine}, the gate editor and the CSV exporter
     * drift apart on which column a gate was actually cutting.
     *
     * @param axis index into {@link GateNode#getChannels()}: 0 for a threshold gate or a
     *             2D gate's X axis, 1 for its Y axis
     * @return the column, or {@code null} when the gate has no usable channel on that axis
     */
    public MeasuredColumn column(GateNode gate, int axis, MarkerStats stats) {
        if (gate == null) return null;
        List<String> channels = gate.getChannels();
        if (axis < 0 || axis >= channels.size()) return null;
        String channel = channels.get(axis);
        if (channel == null || channel.isEmpty()) return null;
        return column(channel, gate.compartmentAt(axis), gate.statisticAt(axis), stats);
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

    public int getSize() {
        return size;
    }

    public int size() {
        return size;
    }

    /** Discovered lazily on first ask; the build loop never touches this. */
    private volatile List<QualityField> qualityFields;

    /**
     * <b>Every shape ({@code MORPH: …}) and cell-level QC ({@code QC: …}) column this export
     * carries</b>, in the order the key sample saw them — the answer to "what can a quality
     * filter filter on?", read from the data rather than declared. A field no cell carries a
     * value for is left out: it is not something a user can filter on.
     * <p>
     * <b>Computed on first call, then cached.</b> The build loop is the {@code cells x markers}
     * hot path and gains nothing here: discovery reuses the key sample, and reading the columns
     * costs one pass over the detections, paid only if something asks.
     */
    public List<QualityField> qualityFields() {
        List<QualityField> cached = qualityFields;
        if (cached != null) return cached;
        synchronized (this) {
            if (qualityFields == null) qualityFields = discoverQualityFields();
            return qualityFields;
        }
    }

    private volatile RoundQc roundQc;

    /**
     * The round-level QC this export carries ({@code QC: <Metric>: [<markers>]}), read on first
     * ask and cached; {@link RoundQc#NONE} for an export without round keys.
     */
    public RoundQc roundQc() {
        RoundQc cached = roundQc;
        if (cached != null) return cached;
        synchronized (this) {
            if (roundQc == null) roundQc = RoundQc.discover(sampleKeys, this);
            return roundQc;
        }
    }

    /** The quality field for {@code slug}, or {@code null} if this export has none. */
    public QualityField qualityField(String slug) {
        if (slug == null) return null;
        for (QualityField f : qualityFields()) {
            if (f.slug().equals(slug)) return f;
        }
        return null;
    }

    private List<QualityField> discoverQualityFields() {
        List<MeasurementName> names = new ArrayList<>();
        Set<String> claimed = new LinkedHashSet<>();
        for (String key : sampleKeys) {
            if (key == null) continue;
            MeasurementName name = MeasurementName.classify(key);
            if (name.kind() != MeasurementName.Kind.MORPHOLOGY && name.kind() != MeasurementName.Kind.QC_CELL) continue;
            if (claimed.add(name.slug())) names.add(name);
        }
        double[][] columns = readColumns(names.stream().map(MeasurementName::key).toList());
        List<QualityField> out = new ArrayList<>();
        for (int c = 0; c < names.size(); c++) {
            MeasurementName name = names.get(c);
            QualityField field = new QualityField(name.slug(), name.key(), name.label(), name.kind(), columns[c]);
            if (field.hasAnyValue()) out.add(field);
        }
        return List.copyOf(out);
    }

    /**
     * {@code keys} read off every detection in one pass, NaN where a cell lacks a key. One
     * pass for all of them together, not one pass each.
     */
    double[][] readColumns(List<String> keys) {
        double[][] columns = new double[keys.size()][objects.length];
        for (double[] col : columns) java.util.Arrays.fill(col, Double.NaN);
        if (keys.isEmpty()) return columns;
        for (int i = 0; i < objects.length; i++) {
            Map<String, Number> measurements = getMeasurements(objects[i]);
            for (int c = 0; c < keys.size(); c++) {
                Number v = measurements.get(keys.get(c));
                if (v != null) columns[c][i] = v.doubleValue();
            }
        }
        return columns;
    }

    /**
     * Cell {@code i}'s X position <b>in {@code geometry().sourceSpace()}</b> — the value
     * exactly as the export supplied it, which for a MIRAGE {@code cells.geojson} is
     * micrometres and for a native QuPath detection is pixels.
     * <p>
     * Kept for callers that only round-trip the number. Anything that reasons about
     * <em>where</em> a cell is should ask {@link #geometry()} for a named space instead:
     * {@link CellGeometry#micronsX(int)} or {@link CellGeometry#pixelsX(int)}.
     */
    public double getCentroidX(int i) {
        return geometry.sourceX(i);
    }

    /** Cell {@code i}'s Y position in {@code geometry().sourceSpace()}. See {@link #getCentroidX(int)}. */
    public double getCentroidY(int i) {
        return geometry.sourceY(i);
    }

    /**
     * Both coordinate spaces for these cells, plus the verdict on whether the exported
     * micrometres match the image's own pixel calibration.
     */
    public CellGeometry geometry() {
        return geometry;
    }

    /**
     * The segmentation label of cell {@code i}, or {@code NaN} when the export carried
     * none.
     * <p>
     * This is the cell's <em>identity</em> in the mask it was segmented from — unlike the
     * {@code cell_id} the CSV writes, which is merely this collection's index and cannot
     * be joined against anything. {@code mirage/bin/join_flowpath.py} joins exactly on
     * this value when FlowPath emits it, and explicitly refuses to align positionally
     * when it does not.
     */
    public double getLabel(int i) {
        return labels[i];
    }

    /**
     * Whether any cell carries a segmentation label. False for exports with no label
     * measurement, in which case consumers should omit the column entirely rather than
     * write a blank one — an all-empty {@code label} column would make
     * {@code join_flowpath.py} attempt an exact join and match nothing.
     */
    public boolean hasLabels() {
        return hasLabels;
    }
}
