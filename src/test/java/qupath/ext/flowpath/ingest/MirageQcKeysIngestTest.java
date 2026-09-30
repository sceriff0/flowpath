package qupath.ext.flowpath.ingest;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.QualityField;
import qupath.ext.flowpath.testing.MirageSample;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MIRAGE's real two-cell export with {@code QC:} and {@code MORPH:} keys
 * ({@code mirage-qc-sample.geojson}, produced by {@code bin/export_geojson.py}): FlowPath 0.9.x
 * read each QC key as a phantom marker and lost the area filter to {@code morph_area}.
 */
class MirageQcKeysIngestTest {

    @Test
    void qcKeysAreNeverMarkers() throws Exception {
        IngestResult r = DetectionIngest.read(MirageSample.cells(), IngestOptions.none());
        assertEquals(List.of("CD3", "CD8", "DAPI", "PANCK"), r.markerNames());
    }

    @Test
    void shapesAndCellQcAreQualityFieldsUnderNamespacedSlugs() throws Exception {
        CellIndex index = DetectionIngest.read(MirageSample.cells(), IngestOptions.none()).index();
        List<String> slugs = index.qualityFields().stream().map(QualityField::slug).toList();
        assertEquals(List.of("qc/total_intensity", "morph/area", "morph/eccentricity", "morph/perimeter",
                "morph/solidity", "morph/convex_area", "morph/major_axis_length", "morph/minor_axis_length"), slugs);

        QualityField area = index.qualityField("morph/area");
        assertEquals("Area", area.label());
        assertEquals("area", area.csvName());
        assertEquals(30.0, area.valueAt(0), 1e-9);
        assertEquals(23.75, area.valueAt(1), 1e-9);

        QualityField total = index.qualityField("qc/total_intensity");
        assertEquals("qc_total_intensity", total.csvName());
        assertEquals(344.0, total.valueAt(0), 1e-9);
    }

    /** Round-level QC is not a filter over whole cells: it never becomes a quality field. */
    @Test
    void roundQcIsNotACellLevelField() throws Exception {
        CellIndex index = DetectionIngest.read(MirageSample.cells(), IngestOptions.none()).index();
        assertTrue(index.qualityFields().stream().noneMatch(f -> f.key().contains("[")));
    }

    @Test
    void theLabelAndMicronCentroidsAreRead() throws Exception {
        CellIndex index = DetectionIngest.read(MirageSample.cells(), IngestOptions.none()).index();
        assertTrue(index.hasLabels());
        assertEquals(2.0, index.getLabel(1), 1e-9);
        assertEquals(20.25, index.geometry().micronsX(1), 1e-9);
    }
}
