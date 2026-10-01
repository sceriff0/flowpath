package qupath.ext.flowpath.cohort;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CohortPeaksTest {

    @Test
    void inMemorySetClearRead() throws Exception {
        CohortPeaks p = CohortPeaks.inMemory();
        assertTrue(p.peaks().isEmpty());
        p.setPeak("1", "CD3", 12.5);
        p.setPeak("1", "CD8", 3.0);
        p.setPeak("2", "CD3", 7.0);
        assertEquals(Map.of("1", Map.of("CD3", 12.5, "CD8", 3.0), "2", Map.of("CD3", 7.0)), p.peaks());
        p.setPeak("1", "CD3", null);
        assertEquals(Map.of("CD8", 3.0), p.peaks().get("1"));
        p.setPeak("2", "CD3", null);
        assertNull(p.peaks().get("2"), "a slide with no peak left is absent");
    }

    @Test
    void thePrefixIsVerbatim() {
        assertEquals("flowpath.cohort.peak.", CohortPeaks.PREFIX);
    }

    @Test
    void parseKeepsOnlyNumericPeakKeys() {
        Map<String, Double> out = CohortPeaks.parse(Map.of(
                CohortPeaks.PREFIX + "CD3: Nucleus: Median", "4.5",
                CohortPeaks.PREFIX + "CD8", "not a number",
                CohortPeaks.PREFIX + "CD4", "NaN",
                "flowpath.cohort.excluded", "true",
                "other", "1.0"));
        assertEquals(Map.of("CD3: Nucleus: Median", 4.5), out);
    }

    private static final class Value implements CohortExclusions.Flag {
        String value;
        Value(String v) { value = v; }
        @Override public String get() { return value; }
        @Override public void set(String v) { value = v; }
    }

    @Test
    void aSavedWriteKeepsTheValueAndNullClears() throws Exception {
        Value v = new Value(null);
        CohortPeaks.write(v, 2.5, () -> {});
        assertEquals("2.5", v.value);
        CohortPeaks.write(v, null, () -> {});
        assertNull(v.value);
    }

    @Test
    void aFailedSaveRestoresThePreviousValue() {
        Value v = new Value("1.0");
        assertThrows(IOException.class, () -> CohortPeaks.write(v, 9.0, () -> { throw new IOException("ro"); }));
        assertEquals("1.0", v.value);
        Value absent = new Value(null);
        assertThrows(IOException.class, () -> CohortPeaks.write(absent, 9.0, () -> { throw new IOException("ro"); }));
        assertNull(absent.value);
    }
}
