package qupath.ext.flowpath.cohort;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CohortExclusionsTest {

    @Test
    void toggleAndList() throws Exception {
        CohortExclusions x = CohortExclusions.inMemory();
        assertFalse(x.isExcluded("1"));
        x.setExcluded("1", true);
        x.setExcluded("3", true);
        assertEquals(Set.of("1", "3"), x.excluded());
        x.setExcluded("1", false);
        assertEquals(Set.of("3"), x.excluded());
    }

    @Test
    void theKeyIsVerbatim() {
        assertEquals("flowpath.cohort.excluded", CohortExclusions.KEY);
    }

    /** A map-backed entry value for {@link CohortExclusions#write}. */
    private static final class Value implements CohortExclusions.Flag {
        String value;
        Value(String v) { value = v; }
        @Override public String get() { return value; }
        @Override public void set(String v) { value = v; }
    }

    @Test
    void aSavedWriteKeepsTheNewValue() throws Exception {
        Value v = new Value(null);
        CohortExclusions.write(v, true, () -> {});
        assertEquals("true", v.value);
        CohortExclusions.write(v, false, () -> {});
        assertNull(v.value);
    }

    @Test
    void aFailedSaveRestoresAnAbsentFlag() {
        Value v = new Value(null);
        IOException thrown = assertThrows(IOException.class,
                () -> CohortExclusions.write(v, true, () -> { throw new IOException("read-only"); }));
        assertEquals("read-only", thrown.getMessage());
        assertNull(v.value, "a refused exclusion must not stay in memory");
    }

    @Test
    void aFailedSaveRestoresAPresentFlag() {
        Value v = new Value("true");
        assertThrows(IOException.class,
                () -> CohortExclusions.write(v, false, () -> { throw new IOException("read-only"); }));
        assertEquals("true", v.value, "a refused inclusion must not stay in memory");
    }
}
