package qupath.ext.flowpath.model;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.testing.Cells;
import qupath.lib.objects.PathObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The one measurement-key sample. It used to be the first 100 detections, taken by two
 * separate loops, so a key present only on later cells -- a merged export, a first field
 * of view with a different panel -- was never offered at all.
 */
class MeasurementKeySampleTest {

    @Test
    void aSmallCollectionIsSampledInFull() {
        for (int n : new int[]{0, 1, 99, 100, 101, 600, 1000}) {
            assertEquals(n, MeasurementKeySample.size(n), "n=" + n);
            for (int i = 0; i < n; i++) {
                assertTrue(MeasurementKeySample.includes(i, n), "n=" + n + " cell " + i);
            }
        }
    }

    @Test
    void aLargeCollectionTakesTheHeadThenAFixedStrideAcrossTheRest() {
        for (int n : new int[]{1001, 1901, 5000, 1_234_567}) {
            List<Integer> sampled = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (MeasurementKeySample.includes(i, n)) sampled.add(i);
            }
            String where = "n=" + n;
            assertEquals(sampled.size(), MeasurementKeySample.size(n), where + ": size agrees with includes");
            assertTrue(sampled.size() <= MeasurementKeySample.MAX_CELLS, where + ": at most the ceiling");
            for (int i = 0; i < MeasurementKeySample.HEAD; i++) {
                assertEquals(i, sampled.get(i), where + ": the head is sampled in full");
            }
            int stride = sampled.get(MeasurementKeySample.HEAD + 1) - sampled.get(MeasurementKeySample.HEAD);
            assertTrue(stride > 1, where);
            for (int k = MeasurementKeySample.HEAD + 1; k < sampled.size(); k++) {
                assertEquals(stride, sampled.get(k) - sampled.get(k - 1), where + ": evenly spaced");
            }
            assertTrue(sampled.get(sampled.size() - 1) >= n - stride,
                    where + ": the stride reaches the end of the collection");
        }
    }

    @Test
    void aKeyOnlyOnCell500Of600IsDiscovered() {
        List<PathObject> cells = Cells.of(600).marker("CD3", 1.0)
                .measurement("Late", i -> 5.0).absentOn(i -> i != 500)
                .detections();

        Set<String> keys = MeasurementKeySample.keys(cells);
        assertTrue(keys.contains("Late"), keys.toString());
        assertEquals(keys, MeasurementKeySample.keys(cells.toArray(new PathObject[0])),
                "array and collection forms are one sample");
    }

    @Test
    void aKeyOnALateStrideCellOfALargeCollectionIsDiscoveredAndOneOffItIsNot() {
        int n = 3000;   // stride ceil(2900 / 900) = 4, so 2900 is sampled and 2901 is not
        List<PathObject> cells = Cells.of(n).marker("CD3", 1.0)
                .measurement("OnStride", i -> 5.0).absentOn(i -> i != 2900)
                .measurement("OffStride", i -> 5.0).absentOn(i -> i != 2901)
                .detections();

        Set<String> keys = MeasurementKeySample.keys(cells);
        assertTrue(keys.contains("OnStride"), keys.toString());
        assertFalse(keys.contains("OffStride"), keys.toString());
    }

    @Test
    void firstSeenOrderIsDeterministic() {
        List<PathObject> cells = Cells.of(1500)
                .measurement("B", i -> 1.0).absentOn(i -> i < 700)
                .measurement("A", i -> 1.0)
                .measurement("C", i -> 1.0).absentOn(i -> i < 1200)
                .detections();

        Set<String> first = MeasurementKeySample.keys(cells);
        assertEquals(List.of("A", "B", "C"), new ArrayList<>(first));
        assertEquals(new ArrayList<>(first), new ArrayList<>(MeasurementKeySample.keys(cells)));
        // A non-RandomAccess collection walks the same positions.
        assertEquals(new ArrayList<>(first),
                new ArrayList<>(MeasurementKeySample.keys(new LinkedHashSet<>(cells))));
    }
}
