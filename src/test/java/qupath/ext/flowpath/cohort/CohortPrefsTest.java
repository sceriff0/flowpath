package qupath.ext.flowpath.cohort;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.prefs.Preferences;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CohortPrefsTest {

    @Test
    void sampledCellsDefaultsTo20000AndRoundTrips() throws Exception {
        Preferences node = Preferences.userRoot().node("flowpath-test/" + UUID.randomUUID());
        try {
            assertEquals(20000, CohortPrefs.sampledCellsPerSlide(node));
            CohortPrefs.setSampledCellsPerSlide(node, 0);
            assertEquals(0, CohortPrefs.sampledCellsPerSlide(node));
            CohortPrefs.setSampledCellsPerSlide(node, -5);
            assertEquals(0, CohortPrefs.sampledCellsPerSlide(node));
        } finally {
            node.removeNode();
        }
    }

    @Test
    void legendDefaultsToExpandedAndRoundTrips() throws Exception {
        Preferences node = Preferences.userRoot().node("flowpath-test/" + UUID.randomUUID());
        try {
            assertEquals(true, CohortPrefs.legendExpanded(node));
            CohortPrefs.setLegendExpanded(node, false);
            assertEquals(false, CohortPrefs.legendExpanded(node));
            CohortPrefs.setLegendExpanded(node, true);
            assertEquals(true, CohortPrefs.legendExpanded(node));
        } finally {
            node.removeNode();
        }
    }
}
