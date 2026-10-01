package qupath.ext.flowpath.model.cohort;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UniformShiftTest {

    private static JsonObject golden() throws Exception {
        try (var in = UniformShiftTest.class.getResourceAsStream("/qupath/ext/flowpath/cohort/uniform-golden.json")) {
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static double[] logs(JsonObject g, String slide) {
        var arr = g.getAsJsonObject("raw").getAsJsonArray(slide);
        double[] out = new double[arr.size()];
        for (int i = 0; i < out.length; i++) out[i] = LogScale.LN.toLog(arr.get(i).getAsDouble());
        return out;
    }

    @Test
    void gridHistogramAndShiftMatchUniformExactly() throws Exception {
        JsonObject g = golden();
        String[] slides = {"ref", "bright", "dim", "posdominant", "subone"};
        List<double[]> all = new ArrayList<>();
        for (String s : slides) all.add(logs(g, s));
        UniformShift.Grid grid = UniformShift.grid(all);
        assertEquals(g.get("gridMin").getAsDouble(), grid.min(), 0.0);
        assertEquals(g.get("gridMax").getAsDouble(), grid.max(), 0.0);
        long[] ref = UniformShift.histogram(logs(g, "ref"), grid);
        for (String s : slides) {
            long[] h = UniformShift.histogram(logs(g, s), grid);
            var expected = g.getAsJsonObject("histograms").getAsJsonArray(s);
            for (int i = 0; i < UniformShift.BINS; i++) assertEquals(expected.get(i).getAsLong(), h[i], s + " bin " + i);
        }
        for (var c : g.getAsJsonArray("shifts")) {
            JsonObject o = c.getAsJsonObject();
            long[] h = UniformShift.histogram(logs(g, o.get("slide").getAsString()), grid);
            int shift = UniformShift.shiftBins(h, ref);
            assertEquals(o.get("shiftBins").getAsInt(), shift, o.get("slide").getAsString());
            assertEquals(o.get("logShift").getAsDouble(), UniformShift.logShift(shift, grid), 1e-12);
        }
    }

    @Test
    void valuesBelowOneNeverReachTheHistogram() {
        UniformShift.Grid grid = new UniformShift.Grid(0.0, 10.0);
        double[] u = {LogScale.LN.toLog(0.5), LogScale.LN.toLog(-3), Double.NaN, LogScale.LN.toLog(1.0)};
        long total = 0;
        for (long c : UniformShift.histogram(u, grid)) total += c;
        assertEquals(1, total, "only ln(1) = 0 is counted");
    }

    @Test
    void binOfClampsOutsideTheGrid() {
        UniformShift.Grid grid = new UniformShift.Grid(1.0, 2.0);
        assertEquals(0, UniformShift.binOf(-5.0, grid));
        assertEquals(UniformShift.BINS - 1, UniformShift.binOf(9.0, grid));
        assertEquals(UniformShift.BINS - 1, UniformShift.binOf(2.0, grid), "the right edge is in the last bin, as numpy");
    }

    @Test
    void degenerateGridIsUnusable() {
        assertFalse(UniformShift.grid(List.of(new double[]{3.0, 3.0})).usable());
        assertFalse(UniformShift.grid(List.of(new double[]{Double.NaN})).usable());
    }

    @Test
    void tiesPickTheFirstMaximumLikeNumpyArgmax() {
        long[] ref = new long[UniformShift.BINS];
        long[] slide = new long[UniformShift.BINS];
        ref[10] = 1; slide[10] = 1; slide[20] = 1;   // lags 0 and +10 tie at 1
        assertEquals(0, UniformShift.shiftBins(slide, ref));
    }
}
