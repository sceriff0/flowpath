package qupath.ext.flowpath.ingest;

import qupath.ext.flowpath.model.CompartmentCapability;
import qupath.ext.flowpath.model.MarkerSelection;
import qupath.lib.images.servers.PixelCalibration;

import java.util.List;
import java.util.function.BiFunction;

/**
 * What {@link DetectionIngest} needs beyond the detections themselves.
 * <p>
 * Every field is optional, and every one of them is optional for a reason: FlowPath is
 * routinely handed a GeoJSON with no matching OME-TIFF (no channel names), an
 * uncalibrated server (no pixel size), or a fresh image with no persisted feature
 * selection. {@link #none()} is the honest "I have nothing else to tell you" and produces
 * exactly the whole-cell/mean behaviour the gating half has always used.
 *
 * @param channelNames      the image server's channel names in panel order, or empty.
 *                          These are <em>candidates</em>: each is validated against the
 *                          measurements actually present, and the ones that fail are
 *                          named in {@link IngestReport#droppedChannels()} rather than
 *                          silently discarded.
 * @param calibration       the image's pixel calibration, or {@code null}. Only FlowPath
 *                          holds this alongside the exported micrometres, which is what
 *                          makes {@link qupath.ext.flowpath.model.ScaleVerdict} possible.
 * @param selectionResolver called <em>after</em> the panel and the capability are known,
 *                          to decide which (compartment, statistic) each marker resolves
 *                          to. {@code null} means whole-cell mean for everything.
 *                          <p>
 *                          A callback rather than a value because the selection cannot be
 *                          loaded until discovery has run — the persisted payload is
 *                          filtered against the markers and compartments that actually
 *                          exist — yet the index cannot be built until the selection is
 *                          known. Reading the hierarchy once means the two steps have to
 *                          interleave inside one call.
 */
public record IngestOptions(List<String> channelNames,
                            PixelCalibration calibration,
                            BiFunction<List<String>, CompartmentCapability, MarkerSelection> selectionResolver) {

    public IngestOptions {
        // Not List.copyOf: that rejects nulls, and a null channel name is exactly the
        // kind of thing the report exists to count. Tolerated here, counted downstream.
        channelNames = channelNames == null
                ? List.of()
                : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(channelNames));
    }

    /** No channel names, no calibration, no selection: whole-cell mean throughout. */
    public static IngestOptions none() {
        return new IngestOptions(List.of(), null, null);
    }

    public IngestOptions withChannelNames(List<String> names) {
        return new IngestOptions(names, calibration, selectionResolver);
    }

    public IngestOptions withCalibration(PixelCalibration cal) {
        return new IngestOptions(channelNames, cal, selectionResolver);
    }

    public IngestOptions withSelectionResolver(
            BiFunction<List<String>, CompartmentCapability, MarkerSelection> resolver) {
        return new IngestOptions(channelNames, calibration, resolver);
    }
}
