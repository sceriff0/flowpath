package qupath.ext.flowpath.model.cohort;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AlignmentTest {

    @Test
    void autoIsOneMultiplicativeFactor() {
        Alignment a = Alignment.auto(10, 0.01);                 // log shift 0.1
        assertEquals(Alignment.Kind.AUTO, a.kind());
        assertEquals(Math.exp(0.1), a.factor(), 1e-12);
        assertEquals(200 * Math.exp(0.1), a.apply(200), 1e-9);  // reference threshold -> this slide
        assertEquals(200, a.inverse(a.apply(200)), 1e-9);
        assertEquals(0.5 * Math.exp(0.1), a.apply(0.5), 1e-12, "values below 1 are corrected too");
    }

    @Test
    void identityReturnsItsInputExactly() {
        Alignment id = Alignment.identity();
        assertEquals(123.456, id.apply(123.456));
        assertEquals(1.0, id.factor());
        assertEquals(0, id.shiftBins());
    }

    @Test
    void landmarkKindKeepsItsKind() {
        assertEquals(Alignment.Kind.LANDMARK, Alignment.landmark(-3, 0.02).kind());
        assertEquals(Math.exp(-0.06), Alignment.landmark(-3, 0.02).factor(), 1e-12);
    }
}
