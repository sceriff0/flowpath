package qupath.ext.flowpath.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression coverage for the fallback marker-discovery filter, which separates
 * real marker channels from the morphology/identity columns that share the same
 * measurement map.
 *
 * <p>The filter runs whenever image channel metadata is missing or does not
 * validate against the detections — the common path for GeoJSON imported without
 * a matching OME-TIFF. A marker that the filter rejects never reaches the gate
 * editor, so an over-eager rule removes a channel from the panel.
 *
 * <p>That removal is no longer silent: {@code DetectionIngest} now owns the single
 * copy of this rule (which {@code FlowPathPane.isMorphologyName} delegates to) and
 * names every dropped channel in its {@code IngestReport}. The rule still has to be
 * right — a marker filtered out here is filtered out before the report can see it as
 * a "channel", so these cases remain the first line of defence. See
 * {@code DetectionIngestTest} for the reporting side.
 */
class MarkerDiscoveryTest {

    @Test
    void morphologyAndIdentityColumnsAreRejected() {
        for (String key : new String[]{
                "Centroid X µm", "centroid_y", "Area µm²", "area", "eccentricity",
                "Perimeter µm", "convex_area", "Solidity", "axis_major_length",
                "axis_minor_length", "Major Axis Length µm", "label", "fov", "cell_size",
                "x", "y", "X", "Y"}) {
            assertTrue(FlowPathPane.isMorphologyName(key), key + " is a morphology/identity column");
        }
    }

    @Test
    void markersBeginningWithXOrYAreNotMistakenForCoordinateColumns() {
        // Real multiplexed-imaging panel members. Prefix-matching "x"/"y" swallowed
        // every one of these, dropping the channel from the panel with no warning.
        for (String marker : new String[]{"YAP1", "YAP", "XBP1", "Xist", "XCR1", "yH2AX"}) {
            assertFalse(FlowPathPane.isMorphologyName(marker), marker + " is a real marker");
        }
    }

    @Test
    void ordinaryMarkersAreKept() {
        for (String marker : new String[]{"CD3", "Ki67", "PANCK", "AREG", "Perilipin", "DAPI"}) {
            assertFalse(FlowPathPane.isMorphologyName(marker), marker + " is a real marker");
        }
    }

    @Test
    void nullAndBlankNamesAreNotTreatedAsMorphology() {
        assertFalse(FlowPathPane.isMorphologyName(null));
        assertFalse(FlowPathPane.isMorphologyName(""));
    }
}
