package qupath.ext.flowpath.model.cohort;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LogScaleTest {
    @Test
    void lnDropsBelowOneAndLn1pKeepsZero() {
        assertTrue(Double.isNaN(LogScale.LN.toLog(0.99)));
        assertEquals(0.0, LogScale.LN.toLog(1.0));
        assertEquals(0.0, LogScale.LN1P.toLog(0.0));
        assertTrue(Double.isNaN(LogScale.LN1P.toLog(-0.1)));
        assertEquals(42.0, LogScale.LN.fromLog(LogScale.LN.toLog(42.0)), 1e-9);
        assertEquals(42.0, LogScale.LN1P.fromLog(LogScale.LN1P.toLog(42.0)), 1e-9);
        assertEquals(LogScale.LN1P, LogScale.ofToken("ln1p"));
        assertEquals(LogScale.LN, LogScale.ofToken("garbage"), "unknown tokens fall back to UniFORM's scale");
    }
}
