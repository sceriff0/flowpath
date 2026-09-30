package qupath.ext.flowpath.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static qupath.ext.flowpath.model.MeasurementName.Kind.*;

/** Every key of MIRAGE's two-cell sample export, and the names that used to be guessed. */
class MeasurementNameTest {

    private static MeasurementName.Kind kind(String key) {
        return MeasurementName.classify(key).kind();
    }

    @Test
    void theSampleExportsKeysClassifyByPrefix() {
        for (String k : List.of("DAPI", "DAPI: Nucleus: Median", "DAPI: Cell: Median", "CD3", "CD3: Cell: Median",
                "PANCK", "PANCK: Cell: Median")) {
            assertEquals(MARKER, kind(k), k);
        }
        for (String k : List.of("label", "Centroid X µm", "Centroid Y µm")) assertEquals(IDENTITY, kind(k), k);
        assertEquals(QC_CELL, kind("QC: Total intensity"));
        for (String k : List.of("QC: Nuclear retention: [CD3, CD8]", "QC: Registration displacement µm: [CD3, CD8]",
                "QC: Registration Dice: [CD3, CD8]")) {
            assertEquals(QC_ROUND, kind(k), k);
        }
        for (String k : List.of("MORPH: Area µm²", "MORPH: Eccentricity", "MORPH: Perimeter µm", "MORPH: Solidity",
                "MORPH: Convex Area µm²", "MORPH: Major Axis Length µm", "MORPH: Minor Axis Length µm")) {
            assertEquals(MORPHOLOGY, kind(k), k);
        }
    }

    @Test
    void aRoundKeyCarriesItsMetricAndMarkers() {
        MeasurementName n = MeasurementName.classify("QC: Registration displacement µm: [CD3, CD8, FOXP3]");
        assertEquals("Registration displacement µm", n.metric());
        assertEquals(List.of("CD3", "CD8", "FOXP3"), n.roundMarkers());
        assertEquals("qcround/registration_displacement", n.slug());
        assertEquals("Registration displacement", n.label());
    }

    @Test
    void slugsAreNamespacedAndUnitFree() {
        assertEquals("morph/area", MeasurementName.classify("MORPH: Area µm²").slug());
        assertEquals("morph/major_axis_length", MeasurementName.classify("MORPH: Major Axis Length µm").slug());
        assertEquals("qc/total_intensity", MeasurementName.classify("QC: Total intensity").slug());
        assertEquals("Area", MeasurementName.classify("MORPH: Area µm²").label());
        assertNull(MeasurementName.classify("CD3").slug());
    }

    /** The prefix is the contract: an unknown metric is shown, not dropped. */
    @Test
    void anUnknownQcMetricIsStillQc() {
        assertEquals(QC_CELL, kind("QC: Focus score"));
        assertEquals(QC_ROUND, kind("QC: Tissue fold: [CD68]"));
    }

    /** No guessing from spelling: unprefixed shape names are markers now. */
    @Test
    void unprefixedShapeNamesAreNotGuessed() {
        for (String k : List.of("Area µm²", "Eccentricity", "Nucleus: Area µm^2", "Cell: Circularity", "area", "x")) {
            assertEquals(MARKER, kind(k), k);
        }
    }

    @Test
    void prefixesAreExactAndCaseSensitive() {
        assertEquals(MARKER, kind("qc: Total intensity"));
        assertEquals(MARKER, kind("MORPH:Area"));
        assertEquals(QC_CELL, kind("QC: Weird: [unterminated"), "not a well-formed round list");
    }

    @Test
    void theSumStatisticIsNotAUnit() {
        assertEquals("sum", MeasurementName.slugOf("Sum"));
    }
}
