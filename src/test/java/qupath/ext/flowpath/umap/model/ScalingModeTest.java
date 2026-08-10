package qupath.ext.flowpath.umap.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScalingModeTest {

    @Test
    void fromLabelRoundTripsEveryMode() {
        for (ScalingMode m : ScalingMode.values()) {
            assertEquals(m, ScalingMode.fromLabel(m.label()),
                    "label() must round-trip through fromLabel() for " + m);
        }
    }

    @Test
    void fromLabelFallsBackToZscoreForUnknownOrNull() {
        assertEquals(ScalingMode.ZSCORE, ScalingMode.fromLabel(null));
        assertEquals(ScalingMode.ZSCORE, ScalingMode.fromLabel("not-a-mode"));
    }

    @Test
    void labelsAreDistinct() {
        long distinct = java.util.Arrays.stream(ScalingMode.values())
                .map(ScalingMode::label).distinct().count();
        assertEquals(ScalingMode.values().length, distinct, "scaling labels must be unique");
    }
}
