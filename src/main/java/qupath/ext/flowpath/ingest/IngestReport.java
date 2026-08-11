package qupath.ext.flowpath.ingest;

import qupath.ext.flowpath.model.ScaleVerdict;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the ingest adapter found, and — the point of the type — what it could not resolve.
 * <p>
 * FlowPath sits entirely downstream of QuPath's hierarchy: it never opens a file, so
 * every disagreement with the incoming data is a disagreement it can only <em>notice</em>,
 * never fix. Before this record existed it did not even notice. A channel with no
 * measurement behind it was dropped from the panel in silence; a marker the key sample
 * could not resolve became a NaN column, which the quality filter then let <em>pass</em>
 * (see {@code QualityFilter.passes}, which skips every NaN criterion); a duplicate marker
 * name kept one column and discarded the other. The only symptom any of that produced was
 * an empty histogram, which reads as "no cells are positive" rather than "this axis never
 * resolved".
 *
 * <h2>Findings versus notes</h2>
 * {@link #findings()} are things the adapter could not resolve — they make
 * {@link #isClean()} false and earn space in the status bar. {@link #notes()} are things
 * it observed and wants on the record but which are not defects; a literal {@code 0.0} in
 * a compartment column is ordinary data, not a failure.
 *
 * <h2>The one distinction FlowPath cannot represent</h2>
 * MIRAGE writes two different things that arrive here looking similar and mean opposite
 * things:
 * <ul>
 *   <li>{@code bin/export_geojson.py} <b>omits</b> a measurement whose value is NaN — not
 *       {@code null}, not {@code 0}, the entry is simply absent. That means a failed
 *       upstream join: the value is <em>unknown</em>. It reaches FlowPath as NaN, and a
 *       cell whose criterion is NaN passes the quality filter rather than being excluded.
 *       Counted by {@link #cellsMissingResolvedKey()}.</li>
 *   <li>{@code bin/quantify.py} writes a literal {@code 0.0} for a compartment with
 *       genuinely zero pixels — an anucleate cell has no nuclear signal. That value is
 *       <em>known</em>, and it is zero. Counted by {@link #sampledZeroValueCells()}.</li>
 * </ul>
 * FlowPath's gating collapses these: both look like "no signal" to a user reading a
 * histogram. Changing the gating semantics of the {@code 0.0} case would alter every
 * existing result, so this record does not — it reports the two counts separately so the
 * distinction is at least visible.
 *
 * @param detectionCount                     objects read from the hierarchy
 * @param cellObjects                        true cell objects among them
 * @param tileObjects                        tiles/superpixels among them — not cells
 * @param otherObjects                       plain detections (the legacy import shape)
 * @param discovery                          which panel each source proposed and which won
 * @param droppedChannels                    image channels with no matching measurement
 * @param channelsResolvedOnlyByStructuredKey channels kept only because per-compartment
 *                                           keys exist; their bare column is absent, which
 *                                           for a MIRAGE export means the whole-cell mean
 *                                           was NaN for every sampled cell
 * @param unresolvedMarkers                  panel markers the key sample offered no
 *                                           measurement key for. Their column falls back to
 *                                           the exhaustive per-cell scan and is NaN wherever
 *                                           that also fails — the classic cause of a gate
 *                                           whose histogram is empty
 * @param cellsMissingResolvedKey            marker -&gt; cells lacking a key the sample
 *                                           resolved (the "omitted upstream" case)
 * @param sampledZeroValueCells              marker -&gt; sampled cells whose value was
 *                                           literally {@code 0.0} (the "truly empty" case)
 * @param duplicateMarkerNames               names requested twice; only the first column
 *                                           survives
 * @param nullMarkerNames                    null names requested and skipped
 * @param sampledCells                       cells whose key sets formed the sample
 * @param sampleSize                         the sample ceiling
 * @param scaleVerdict                       the µm-vs-calibration cross-check
 */
public record IngestReport(int detectionCount,
                           int cellObjects,
                           int tileObjects,
                           int otherObjects,
                           Discovery discovery,
                           List<String> droppedChannels,
                           List<String> channelsResolvedOnlyByStructuredKey,
                           List<String> unresolvedMarkers,
                           Map<String, Integer> cellsMissingResolvedKey,
                           Map<String, Integer> sampledZeroValueCells,
                           List<String> duplicateMarkerNames,
                           int nullMarkerNames,
                           int sampledCells,
                           int sampleSize,
                           ScaleVerdict scaleVerdict) {

    public IngestReport {
        droppedChannels = List.copyOf(droppedChannels);
        channelsResolvedOnlyByStructuredKey = List.copyOf(channelsResolvedOnlyByStructuredKey);
        unresolvedMarkers = List.copyOf(unresolvedMarkers);
        cellsMissingResolvedKey = Map.copyOf(cellsMissingResolvedKey);
        sampledZeroValueCells = Map.copyOf(sampledZeroValueCells);
        duplicateMarkerNames = List.copyOf(duplicateMarkerNames);
    }

    /** Where a marker panel came from. */
    public enum Source {
        /** The image server's channel metadata, validated against the measurements. */
        IMAGE_CHANNELS,
        /** The detections' own measurement keys, minus the morphology columns. */
        MEASUREMENTS,
        /** Neither source produced a panel. */
        NONE
    }

    /**
     * The two candidate panels and which one was adopted.
     * <p>
     * Both are always computed, even though only one is used. That is the whole value of
     * the record: the image and the GeoJSON are separate artefacts that can disagree about
     * what was measured, and before this the losing candidate was never even built, so a
     * disagreement could not be seen. A channel list naming markers the export never
     * quantified, or an export carrying markers the image does not declare, is the visible
     * signature of a mismatched pair of files.
     *
     * @param winner              the panel actually adopted
     * @param fromChannels        image-channel candidates that validated
     * @param fromMeasurements    measurement-derived candidates
     * @param onlyInChannels      validated channels the measurements do not offer
     * @param onlyInMeasurements  measurement markers the channel list does not declare
     */
    public record Discovery(Source winner,
                            List<String> fromChannels,
                            List<String> fromMeasurements,
                            List<String> onlyInChannels,
                            List<String> onlyInMeasurements) {

        public Discovery {
            fromChannels = List.copyOf(fromChannels);
            fromMeasurements = List.copyOf(fromMeasurements);
            onlyInChannels = List.copyOf(onlyInChannels);
            onlyInMeasurements = List.copyOf(onlyInMeasurements);
        }

        /**
         * True when the two sources proposed different panels. Only meaningful when both
         * produced one — a GeoJSON imported without an OME-TIFF has no channel list to
         * disagree with, and that is not a mismatch.
         */
        public boolean disagreed() {
            return !onlyInChannels.isEmpty() || !onlyInMeasurements.isEmpty();
        }
    }

    /** An empty report for "no detections were read". */
    public static IngestReport empty() {
        return new IngestReport(0, 0, 0, 0,
                new Discovery(Source.NONE, List.of(), List.of(), List.of(), List.of()),
                List.of(), List.of(), List.of(), Map.of(), Map.of(), List.of(), 0,
                0, 0, ScaleVerdict.noMeasurement());
    }

    /**
     * True when the adapter resolved everything it was asked to.
     * <p>
     * A clean MIRAGE export is clean: its channels all have measurements behind them, its
     * markers all resolve, no cell is missing a resolved key, and the two discovery paths
     * agree. An uncalibrated image is <em>also</em> clean — having no pixel size is
     * ordinary, and only an actual {@link ScaleVerdict.Status#DISAGREE} is a finding.
     * Literal zeros are data, not defects, so they never make a report unclean.
     */
    public boolean isClean() {
        return findings().isEmpty();
    }

    /**
     * Everything the adapter could not resolve, one human-readable line each. Empty for a
     * clean export.
     */
    public List<String> findings() {
        List<String> out = new ArrayList<>();

        if (!droppedChannels.isEmpty()) {
            out.add(count(droppedChannels.size(), "image channel", "image channels")
                    + " dropped — no matching measurement: " + preview(droppedChannels));
        }
        if (!channelsResolvedOnlyByStructuredKey.isEmpty()) {
            out.add(preview(channelsResolvedOnlyByStructuredKey)
                    + " kept only via per-compartment keys — the bare whole-cell column is "
                    + "absent, which upstream means it was NaN for every sampled cell");
        }
        if (discovery.disagreed()) {
            out.add("image channels and measurements disagree on the panel ("
                    + (discovery.winner() == Source.IMAGE_CHANNELS
                        ? "image channels won" : "measurements won")
                    + ")"
                    + (discovery.onlyInChannels().isEmpty() ? ""
                        : "; only in channels: " + preview(discovery.onlyInChannels()))
                    + (discovery.onlyInMeasurements().isEmpty() ? ""
                        : "; only in measurements: " + preview(discovery.onlyInMeasurements())));
        }
        if (!unresolvedMarkers.isEmpty()) {
            out.add(count(unresolvedMarkers.size(), "marker", "markers")
                    + " unresolved against the key sample — read through the slow per-cell "
                    + "fallback and NaN wherever that also fails, which shows up as an "
                    + "empty histogram: " + preview(unresolvedMarkers));
        }
        if (!cellsMissingResolvedKey.isEmpty()) {
            out.add(count(cellsMissingResolvedKey.size(), "marker", "markers")
                    + " missing on some cells (omitted upstream, so NaN — and a NaN "
                    + "criterion PASSES the quality filter): " + previewCounts(cellsMissingResolvedKey));
        }
        if (!duplicateMarkerNames.isEmpty()) {
            out.add("duplicate marker " + (duplicateMarkerNames.size() == 1 ? "name" : "names")
                    + " collapsed to the first column: " + preview(duplicateMarkerNames));
        }
        if (nullMarkerNames > 0) {
            out.add(nullMarkerNames + " null marker "
                    + (nullMarkerNames == 1 ? "name was" : "names were") + " skipped");
        }
        if (tileObjects > 0) {
            out.add(String.format(Locale.US,
                    "%,d of %,d detections are tiles/superpixels, not cells — they are "
                            + "gated as if they were cells", tileObjects, detectionCount));
        }
        if (scaleVerdict != null && scaleVerdict.isDisagreement()) {
            out.add(scaleVerdict.describe());
        }
        return out;
    }

    /** Observations worth recording that are not defects. Never affects {@link #isClean()}. */
    public List<String> notes() {
        List<String> out = new ArrayList<>();
        if (!sampledZeroValueCells.isEmpty()) {
            out.add(count(sampledZeroValueCells.size(), "marker", "markers")
                    + " carry a literal 0.0 in the sample — a genuinely empty compartment "
                    + "(e.g. an anucleate cell), which is NOT the same as the omitted-and-"
                    + "therefore-NaN case above: " + previewCounts(sampledZeroValueCells));
        }
        return out;
    }

    /**
     * One line for a status bar: the first finding, plus how many more there are.
     * Empty string when there is nothing to say, so a caller can concatenate it blindly.
     */
    public String summary() {
        List<String> f = findings();
        if (f.isEmpty()) return "";
        String first = f.get(0);
        return f.size() == 1 ? first : first + " (+" + (f.size() - 1) + " more)";
    }

    /** Every finding and note, newline-separated — the tooltip / log form. */
    public String describe() {
        List<String> all = new ArrayList<>(findings());
        all.addAll(notes());
        if (all.isEmpty()) {
            return String.format(Locale.US,
                    "Ingest clean: %,d detections, %d markers resolved from %,d sampled cells.",
                    detectionCount,
                    discovery.winner() == Source.IMAGE_CHANNELS
                            ? discovery.fromChannels().size() : discovery.fromMeasurements().size(),
                    sampledCells);
        }
        return String.join("\n", all);
    }

    private static String count(int n, String singular, String plural) {
        return n + " " + (n == 1 ? singular : plural);
    }

    /** At most five names, so a 40-plex panel does not fill the status bar. */
    private static String preview(List<String> names) {
        if (names.size() <= 5) return String.join(", ", names);
        return String.join(", ", names.subList(0, 5)) + ", … (+" + (names.size() - 5) + ")";
    }

    private static String previewCounts(Map<String, Integer> counts) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            parts.add(e.getKey() + " (" + e.getValue() + ")");
        }
        return preview(parts);
    }
}
