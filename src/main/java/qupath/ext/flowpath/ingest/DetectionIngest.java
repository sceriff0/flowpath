package qupath.ext.flowpath.ingest;

import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.CompartmentCapability;
import qupath.ext.flowpath.model.MarkerSelection;
import qupath.ext.flowpath.model.MeasurementKeys;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.PixelCalibration;
import qupath.lib.objects.PathObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * <b>FlowPath's adapter for the incoming cell data</b> — the one place the QuPath object
 * hierarchy is turned into the columnar substrate the rest of the extension reasons about.
 * <p>
 * FlowPath never opens a file. Its input is whatever QuPath's GeoJSON importer has already
 * put in the hierarchy, which in practice means MIRAGE's {@code cells.geojson}: bare marker
 * names for whole-cell means, {@code "<marker>: <Compartment>: <Stat>"} for per-compartment
 * statistics, {@code "Centroid X µm"} in micrometres, {@code "Area µm²"} and friends for
 * morphology. That contract had no name and no owner. Four modules interrogated the
 * hierarchy independently, with different rules and different sample depths:
 * {@code CellIndex.build} sampled 20 detections, {@code FlowPathPane.discoverMarkerNames}
 * sampled 100, {@code CompartmentCapability.scan} took its own, and {@code UmapSession}
 * carried a second, independent implementation of marker discovery. Release 2.0.1 fixed one
 * symptom of that drift; the cause was that nothing owned the seam.
 *
 * <h2>One read, one sample, one answer</h2>
 * {@link #read} takes exactly one measurement-key sample and derives the panel, the
 * capability and the index from it. The panel a user gates on and the keys the index
 * resolved therefore cannot disagree, because they are the same evidence. The sample depth
 * is {@link CellIndex#KEY_SAMPLE_SIZE}, which is now equal to
 * {@link CompartmentCapability#DEFAULT_SAMPLE_SIZE} for the same reason.
 *
 * <h2>It reports, it does not refuse</h2>
 * Nothing here rejects input that previously loaded, and nothing here changes what a gate
 * computes. A tile in the detection collection is counted, not filtered. A marker that
 * resolves to nothing still gets its all-NaN column. What changes is that all of it now
 * arrives in an {@link IngestReport} instead of arriving as an empty histogram three
 * clicks later.
 *
 * <h2>Cost</h2>
 * One walk over the first {@link CellIndex#KEY_SAMPLE_SIZE} detections' key sets for
 * discovery and capability, then one walk over all detections inside
 * {@link CellIndex#build}. The report's per-cell counts are gathered inside that existing
 * build pass — see {@link CellIndex.BuildDiagnostics} — so this adds no second pass over
 * the cells and preserves the ~30x index-build speedup landed in v2.0.1.
 */
public final class DetectionIngest {

    private DetectionIngest() {}

    /**
     * Read the hierarchy through an {@link ImageData}: channel names and pixel calibration
     * are taken from its server, defensively, and everything resolves to whole-cell mean.
     * This is the gating half's entry point.
     */
    public static IngestResult read(Collection<PathObject> detections, ImageData<?> imageData) {
        return read(detections, IngestOptions.none()
                .withChannelNames(channelNames(imageData))
                .withCalibration(calibration(imageData)));
    }

    /**
     * Read the hierarchy once and return everything derived from it, plus the report of
     * what could not be resolved.
     *
     * @param detections the detections to index; never re-queried from the hierarchy, so
     *                   a caller that has already filtered them (annotation ROI, excluded
     *                   class) keeps its cell set exactly
     */
    public static IngestResult read(Collection<PathObject> detections, IngestOptions options) {
        if (options == null) options = IngestOptions.none();
        if (detections == null) detections = List.of();

        // ---- the one sample -------------------------------------------------------
        Set<String> sampleKeys = sampleMeasurementKeys(detections);
        CompartmentCapability capability = CompartmentCapability.fromKeys(sampleKeys);

        // ---- candidate panel A: the image's declared channels ---------------------
        // Collapsing to base markers is itself a silent narrowing: two declared channels
        // that reduce to the same marker leave one row in the panel and no trace of the
        // other. Counted here so "42 channels in, 41 markers out" is explicable.
        List<String> channels = MeasurementKeys.collapseToBaseMarkers(
                options.channelNames(), capability.compartments());
        List<String> duplicateNames = new ArrayList<>(0);
        int nullNames = 0;
        Set<String> seenChannel = new LinkedHashSet<>();
        for (String raw : options.channelNames()) {
            if (raw == null || raw.isBlank()) {
                nullNames++;
                continue;
            }
            var parsed = MeasurementKeys.parse(raw, capability.compartments());
            String base = parsed != null ? parsed.marker() : MeasurementKeys.stripLayerPrefix(raw);
            if (base == null || base.isBlank()) {
                nullNames++;
                continue;
            }
            if (!seenChannel.add(base) && !duplicateNames.contains(base)) duplicateNames.add(base);
        }
        List<String> fromChannels = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        List<String> structuredOnly = new ArrayList<>();
        for (String name : channels) {
            switch (matchChannel(sampleKeys, capability, name)) {
                case DIRECT -> fromChannels.add(name);
                case STRUCTURED -> {
                    fromChannels.add(name);
                    structuredOnly.add(name);
                }
                case NONE -> dropped.add(name);
            }
        }

        // ---- candidate panel B: the measurements themselves -----------------------
        // Computed even when panel A wins. Building only the winner is precisely why a
        // mismatched image/GeoJSON pair could never be detected: the loser was never
        // constructed, so there was nothing to compare against.
        List<String> fromMeasurements = markersFromMeasurements(sampleKeys, capability);

        boolean channelsWin = !fromChannels.isEmpty();
        List<String> markers = channelsWin ? fromChannels : fromMeasurements;
        IngestReport.Source winner = channelsWin ? IngestReport.Source.IMAGE_CHANNELS
                : (fromMeasurements.isEmpty() ? IngestReport.Source.NONE
                                              : IngestReport.Source.MEASUREMENTS);

        // A disagreement needs two opinions. An import with no channel metadata has one.
        List<String> onlyInChannels = new ArrayList<>();
        List<String> onlyInMeasurements = new ArrayList<>();
        if (!channels.isEmpty() && !fromMeasurements.isEmpty()) {
            for (String m : fromChannels) {
                if (!fromMeasurements.contains(m)) onlyInChannels.add(m);
            }
            for (String m : fromMeasurements) {
                if (!fromChannels.contains(m)) onlyInMeasurements.add(m);
            }
        }

        // ---- the index, at the selection the caller resolves from the above -------
        MarkerSelection selection = options.selectionResolver() == null
                ? null
                : options.selectionResolver().apply(markers, capability);

        CellIndex index = CellIndex.build(detections, markers, selection, options.calibration());
        CellIndex.BuildDiagnostics d = index.diagnostics();

        IngestReport report = new IngestReport(
                d.detectionCount(), d.cellObjects(), d.tileObjects(), d.otherObjects(),
                new IngestReport.Discovery(winner, fromChannels, fromMeasurements,
                        onlyInChannels, onlyInMeasurements),
                dropped, structuredOnly,
                d.unresolvedMarkers(), d.cellsMissingResolvedKey(), d.sampledZeroValueCells(),
                // Union: a name can collapse at the channel list (two channels, one
                // marker) or inside the index (the same marker requested twice).
                union(duplicateNames, d.duplicateMarkerNames()),
                nullNames + d.nullMarkerNames(),
                d.sampledCells(), d.sampleSize(),
                index.geometry().scaleVerdict());

        return new IngestResult(index, capability, markers,
                selection == null ? MarkerSelection.defaultFor(markers) : selection,
                report);
    }

    private static List<String> union(List<String> a, List<String> b) {
        if (b.isEmpty()) return a;
        List<String> out = new ArrayList<>(a);
        for (String s : b) {
            if (!out.contains(s)) out.add(s);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Discovery
    // ------------------------------------------------------------------

    /** How a declared image channel was matched against the measurements actually present. */
    private enum ChannelMatch {
        /** A bare (or layer-prefixed) measurement carries this channel's name. */
        DIRECT,
        /**
         * No bare column, but per-compartment keys exist for it. Accepted because
         * {@code CellIndex} resolves such a marker through its structured key anyway —
         * dropping the channel here while the index could read it was the two halves
         * disagreeing with each other. Reported, because for a MIRAGE export a missing
         * bare column means the whole-cell mean was NaN for every sampled cell.
         */
        STRUCTURED,
        /** Nothing in the measurements answers to this channel. */
        NONE
    }

    private static ChannelMatch matchChannel(Set<String> keys, CompartmentCapability capability,
                                             String channel) {
        if (keys.contains(channel)) return ChannelMatch.DIRECT;
        String suffix = "] " + channel;
        for (String key : keys) {
            if (key.endsWith(suffix)) return ChannelMatch.DIRECT;
        }
        return capability.hasCompartments(channel) ? ChannelMatch.STRUCTURED : ChannelMatch.NONE;
    }

    /**
     * The panel implied by the measurements alone: every key collapsed to its base marker,
     * minus the morphology and bookkeeping columns, de-duplicated and sorted.
     * <p>
     * Sorted rather than first-seen because there is no meaningful order in a measurement
     * map — the channel path is the one that carries panel order.
     */
    private static List<String> markersFromMeasurements(Set<String> keys,
                                                        CompartmentCapability capability) {
        // The capability is threaded in rather than re-derived: it was built from THESE
        // keys a few lines above, and it is the vocabulary the gate editor will offer. Read
        // the same keys with a narrower vocabulary and a discovered compartment stops
        // parsing here while still being offered there -- the whole key then arrives in the
        // panel as a phantom marker spelled "CD3: Membrane: Mean".
        List<String> collapsed = MeasurementKeys.collapseToBaseMarkers(
                new ArrayList<>(keys), capability.compartments());
        List<String> out = new ArrayList<>();
        for (String name : collapsed) {
            if (name.startsWith("_")) continue;
            if (isMorphologyName(name)) continue;
            out.add(name);
        }
        out.sort(String::compareTo);
        return out;
    }

    /**
     * Morphology and geometry columns, matched by lowercase prefix so both naming
     * conventions are covered:
     *   QuPath default — {@code "Area µm²"}, {@code "Centroid X µm"}, {@code "Eccentricity"},
     *   {@code "Perimeter µm"}, {@code "Solidity"}, {@code "Convex Area µm²"},
     *   {@code "Major/Minor Axis Length µm"};
     *   {@code import_phenotype.groovy} — {@code "area µm²"}, {@code "eccentricity"},
     *   {@code "perimeter"}, {@code "convex_area"}, {@code "axis_major_length"},
     *   {@code "axis_minor_length"}.
     * <p>
     * {@code "label"} belongs here: it is the segmentation identity, not a panel member.
     * The CSV exporter writes it as its own column, which is a separate concern.
     */
    private static final Set<String> MORPHOLOGY_PREFIXES = Set.of(
            "centroid", "area", "eccentricity", "perimeter", "convex",
            "solidity", "axis_major", "axis_minor", "major axis", "minor axis",
            "label", "fov", "cell_size"
    );

    /**
     * Spatial-coordinate columns whose names are a single letter. These must be matched
     * exactly, never by prefix: prefix-matching {@code "x"} and {@code "y"} also swallowed
     * real panel markers such as YAP1, XBP1 and Xist, which then vanished from the channel
     * list with no warning shown to the user.
     */
    private static final Set<String> MORPHOLOGY_EXACT = Set.of("x", "y");

    /**
     * True if a measurement name is a morphology/identity column rather than a marker
     * channel. The single implementation — the gating half and the UMAP half each carried
     * their own list, and they did not agree.
     */
    public static boolean isMorphologyName(String name) {
        if (name == null || name.isEmpty()) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        if (MORPHOLOGY_EXACT.contains(lower)) return true;
        for (String prefix : MORPHOLOGY_PREFIXES) {
            if (lower.startsWith(prefix)) return true;
        }
        return false;
    }

    /**
     * Union of measurement keys over the first {@link CellIndex#KEY_SAMPLE_SIZE}
     * detections, in first-seen order. The same depth and the same ordering guarantee
     * {@link CellIndex#build} uses, so the panel offered and the keys resolved agree.
     */
    static Set<String> sampleMeasurementKeys(Collection<PathObject> detections) {
        Set<String> keys = new LinkedHashSet<>();
        int sampled = 0;
        for (PathObject obj : detections) {
            try {
                var m = obj.getMeasurements();
                if (m != null) keys.addAll(m.keySet());
            } catch (Exception ignored) {
                // A measurement list can throw on a partially constructed object; an
                // unreadable cell contributes nothing rather than aborting the ingest.
            }
            if (++sampled >= CellIndex.KEY_SAMPLE_SIZE) break;
        }
        return keys;
    }

    // ------------------------------------------------------------------
    // ImageData accessors, defensively
    // ------------------------------------------------------------------

    /** The server's channel names in panel order, or empty if they cannot be read. */
    public static List<String> channelNames(ImageData<?> imageData) {
        try {
            var server = imageData != null ? imageData.getServer() : null;
            if (server == null) return List.of();
            var channels = server.getMetadata().getChannels();
            if (channels == null) return List.of();
            List<String> names = new ArrayList<>(channels.size());
            for (var ch : channels) {
                String name = ch.getName();
                if (name != null && !name.isEmpty()) names.add(name);
            }
            return names;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * The image's pixel calibration, or {@code null} if it cannot be read. Defensive
     * because a server can be closed or unavailable between the null check and the call.
     */
    public static PixelCalibration calibration(ImageData<?> imageData) {
        try {
            var server = imageData != null ? imageData.getServer() : null;
            return server != null ? server.getPixelCalibration() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
