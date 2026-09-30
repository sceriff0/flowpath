package qupath.ext.flowpath.cohort;

import org.junit.jupiter.api.Test;

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
}
