package qupath.ext.flowpath.umap.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UmapParametersTest {

    @Test
    void defaultsHaveExpectedValues() {
        var params = UmapParameters.defaults();

        assertEquals(15, params.k());
        assertEquals(0.1, params.minDist(), 0.001);
        assertEquals(1.0, params.spread(), 0.001);
        // Default carries the adaptive-epochs sentinel; the compute service maps
        // it to a real epoch count based on training-N.
        assertEquals(UmapParameters.ADAPTIVE_EPOCHS, params.epochs());
        assertEquals(5, params.negativeSamples());
    }

    @Test
    void customValuesPreserved() {
        var params = new UmapParameters(30, 0.5, 2.0, 500, 3);

        assertEquals(30, params.k());
        assertEquals(0.5, params.minDist(), 0.001);
        assertEquals(2.0, params.spread(), 0.001);
        assertEquals(500, params.epochs());
        assertEquals(3, params.negativeSamples());
    }

    @Test
    void fourArgConstructorDefaultsNegativeSamples() {
        var params = new UmapParameters(15, 0.1, 1.0, 200);
        assertEquals(5, params.negativeSamples());
    }

    @Test
    void kMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new UmapParameters(0, 0.1, 1.0, 200));
        assertThrows(IllegalArgumentException.class, () -> new UmapParameters(-1, 0.1, 1.0, 200));
    }

    @Test
    void minDistMustBeNonNegative() {
        assertDoesNotThrow(() -> new UmapParameters(15, 0.0, 1.0, 200));
        assertThrows(IllegalArgumentException.class, () -> new UmapParameters(15, -0.1, 1.0, 200));
    }

    @Test
    void spreadMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new UmapParameters(15, 0.1, 0.0, 200));
    }

    @Test
    void minDistMustNotExceedSpread() {
        assertThrows(IllegalArgumentException.class, () -> new UmapParameters(15, 2.0, 1.0, 200));
    }

    @Test
    void epochsMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new UmapParameters(15, 0.1, 1.0, 0));
        assertThrows(IllegalArgumentException.class, () -> new UmapParameters(15, 0.1, 1.0, -10));
    }

    @Test
    void negativeSamplesMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new UmapParameters(15, 0.1, 1.0, 100, 0));
        assertThrows(IllegalArgumentException.class, () -> new UmapParameters(15, 0.1, 1.0, 100, -1));
    }

    @Test
    void boundaryValuesAccepted() {
        assertDoesNotThrow(() -> new UmapParameters(1, 0.001, 0.001, 1, 1));
        assertDoesNotThrow(() -> new UmapParameters(15, 1.0, 1.0, 200)); // minDist == spread
    }

    @Test
    void defaultsForSmallTrainingSetUses200Epochs() {
        assertEquals(200, UmapParameters.defaultsFor(5_000).epochs());
    }

    @Test
    void defaultsForMediumTrainingSetUses100Epochs() {
        assertEquals(100, UmapParameters.defaultsFor(20_000).epochs());
    }

    @Test
    void defaultsForLargeTrainingSetUses60Epochs() {
        assertEquals(60, UmapParameters.defaultsFor(80_000).epochs());
    }

    @Test
    void defaultEpochsSentinelMeansAdaptive() {
        // The default UmapParameters carries a sentinel epochs value (-1) that the
        // compute service interprets as "pick adaptively from training-N".
        var defaults = UmapParameters.defaults();
        assertEquals(UmapParameters.ADAPTIVE_EPOCHS, defaults.epochs(),
                "defaults() should carry the ADAPTIVE_EPOCHS sentinel");
    }
}
