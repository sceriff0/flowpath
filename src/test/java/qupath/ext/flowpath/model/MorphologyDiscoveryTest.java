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
        return index.morphology().stream().map(MorphologyField::slug).toList();
    }

    @Test
    void aMarkerTheIndexDoesNotCarryIsNotOfferedAsMorphology() {
        CellIndex index = Cells.of(10)
                .marker("DAPI", i -> 100.0 + i)
                .marker("PANCK", i -> 20.0 + i)
                .marker("wrongPANCK", i -> 5.0 + i)       // in the file, not on the image
                .morphology("Area µm²", i -> 50.0 + i)
                .morphology("Major Axis Length µm", i -> 8.0 + i)
                .panel("DAPI", "PANCK")
                .build();

        List<String> shown = slugs(index);
        assertFalse(shown.contains("wrongpanck"),
                "an intensity column must not become a quality-filter row: " + shown);
        assertTrue(shown.contains("area"), shown.toString());
        assertTrue(shown.contains("major_axis_length"), shown.toString());
    }

    @Test
    void shapeMeasurementsFlowPathNeverNamedAreStillOffered() {
        // QuPath's own cell detection spells these; none is in the MIRAGE vocabulary.
        CellIndex index = Cells.of(10)
                .marker("CD3", i -> 10.0 + i)
                .morphology("Nucleus: Circularity", i -> 0.5 + i * 0.01)
                .morphology("Cell: Max caliper µm", i -> 10.0 + i)
                .morphology("Feret Diameter px", i -> 12.0 + i)
                .build();

        List<String> shown = slugs(index);
        assertTrue(shown.contains("nucleus_circularity"), shown.toString());
        assertTrue(shown.contains("cell_max_caliper"), shown.toString());
        assertTrue(shown.contains("feret_diameter"), shown.toString());
    }

    @Test
    void theRuleAgreesWithMarkerDiscovery() {
        for (String marker : new String[]{"CD3", "wrongPANCK", "YAP1", "Sum", "Perilipin"}) {
            assertFalse(MorphologyField.isMorphologyName(marker), marker);
        }
        for (String shape : new String[]{"Area µm²", "Nucleus: Area µm^2", "Nucleus: Circularity",
                "Cell: Max caliper µm", "Minor Axis Length µm", "Centroid X µm", "label"}) {
            assertTrue(MorphologyField.isMorphologyName(shape), shape);
        }
    }
}
