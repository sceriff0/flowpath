package qupath.ext.flowpath.model;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.testing.Cells;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the quality filter may offer is read from the export, but a column is a marker or a
 * shape measurement, never both.
 * <p>
 * Discovery used to treat "any key that is not a marker <em>of this index</em>" as
 * morphology. When the image's channel list wins the panel, a marker the export quantified
 * but the image does not name (MIRAGE's {@code cells.geojson} ships one, spelled
 * {@code wrongPANCK}) is not a marker of the index, so it arrived in the quality filter as a
 * shape measurement you could threshold intensity on. The same rule that keeps a column out
 * of the marker panel now decides whether it is morphology.
 */
class MorphologyDiscoveryTest {

    private static List<String> slugs(CellIndex index) {
        return index.qualityFields().stream().map(QualityField::slug).toList();
    }

    @Test
    void aMarkerTheIndexDoesNotCarryIsNotOfferedAsMorphology() {
        CellIndex index = Cells.of(10)
                .marker("DAPI", i -> 100.0 + i)
                .marker("PANCK", i -> 20.0 + i)
                .marker("wrongPANCK", i -> 5.0 + i)       // in the file, not on the image
                .morphology("MORPH: Area µm²", i -> 50.0 + i)
                .morphology("MORPH: Major Axis Length µm", i -> 8.0 + i)
                .panel("DAPI", "PANCK")
                .build();

        List<String> shown = slugs(index);
        assertFalse(shown.stream().anyMatch(sl -> sl.endsWith("wrongpanck")),
                "an intensity column must not become a quality-filter row: " + shown);
        assertTrue(shown.contains(QualityFilter.AREA), shown.toString());
        assertTrue(shown.contains("morph/major_axis_length"), shown.toString());
    }

    @Test
    void anyMorphPrefixedShapeIsOfferedEvenOneFlowPathNeverNamed() {
        CellIndex index = Cells.of(10)
                .marker("CD3", i -> 10.0 + i)
                .morphology("MORPH: Circularity", i -> 0.5 + i * 0.01)
                .morphology("MORPH: Max caliper µm", i -> 10.0 + i)
                .build();

        List<String> shown = slugs(index);
        assertTrue(shown.contains("morph/circularity"), shown.toString());
        assertTrue(shown.contains("morph/max_caliper"), shown.toString());
    }
}
