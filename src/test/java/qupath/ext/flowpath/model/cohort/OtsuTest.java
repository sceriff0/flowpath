package qupath.ext.flowpath.model.cohort;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class OtsuTest {

    @Test
    void thresholdMatchesScikitImage() throws Exception {
        try (var in = OtsuTest.class.getResourceAsStream("/qupath/ext/flowpath/cohort/uniform-golden.json")) {
            var g = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (var e : g.getAsJsonObject("otsu").entrySet()) {
                var arr = g.getAsJsonObject("raw").getAsJsonArray(e.getKey());
                double[] u = new double[arr.size()];
                for (int i = 0; i < u.length; i++) u[i] = LogScale.LN.toLog(arr.get(i).getAsDouble());
                assertEquals(e.getValue().getAsDouble(), Otsu.threshold(u), 1e-12, e.getKey());
            }
        }
    }

    @Test
    void discordanceIsTheFractionOnDifferentSides() {
        double[] v = {1, 2, 3, 4};
        assertEquals(0.25, Otsu.discordance(v, 2.5, 3.5), 1e-12);   // only 3 differs
        assertEquals(0.0, Otsu.discordance(new double[]{Double.NaN}, 1, 2), 0.0);
    }
}
