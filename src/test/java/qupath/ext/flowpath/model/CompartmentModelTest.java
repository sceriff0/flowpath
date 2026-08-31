package qupath.ext.flowpath.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F1: per-compartment model + capability detection + measurement resolution.
 */
class CompartmentModelTest {

    // ---- enums ----

    @Test
    void compartmentTokensAndParsing() {
        assertEquals("Nucleus", Compartment.NUCLEAR.token());
        assertEquals("Cytoplasm", Compartment.CYTOPLASMIC.token());
        assertEquals("Cell", Compartment.WHOLE_CELL.token());
        assertEquals(Compartment.NUCLEAR, Compartment.fromToken("nucleus"));
        assertNull(Compartment.fromToken("bogus"));
        assertEquals(Compartment.WHOLE_CELL, Compartment.defaultCompartment());
    }

    @Test
    void statisticTokensAndParsing() {
        assertEquals("Mean", Statistic.MEAN.token());
        assertEquals(Statistic.MEDIAN, Statistic.fromToken("median"));
        assertEquals(Statistic.MEAN, Statistic.defaultStatistic());
    }

    /**
     * The inverse of the assertion this test used to make.
     * <p>
     * {@code assertNull(Statistic.fromToken("variance"))} pinned the old closed-enum
     * contract: a statistic FlowPath had no name for was a parse failure. That is the
     * behaviour that made a new MIRAGE statistic invisible to gating and to UMAP feature
     * selection, so the assertion had to be inverted rather than worked around.
     */
    @Test
    void anUnknownStatisticIsAValueNotAFailure() {
        Statistic redsea = Statistic.fromToken("REDSEA");
        assertNotNull(redsea, "an unrecognised token is a statistic FlowPath has not met, not a parse failure");
        assertEquals("REDSEA", redsea.token());
        assertEquals("REDSEA", redsea.displayName(), "an unknown statistic displays verbatim");
        assertFalse(redsea.isKnown());
        assertTrue(Statistic.MEDIAN.isKnown());
    }

    /** A blank token is still a malformed key, so callers keep their skip-the-line behaviour. */
    @Test
    void aBlankStatisticTokenIsStillNothing() {
        assertNull(Statistic.fromToken(null));
        assertNull(Statistic.fromToken(""));
        assertNull(Statistic.fromToken("   "));
        assertThrows(IllegalArgumentException.class, () -> Statistic.of("  "));
    }

    /**
     * Interning, pinned. {@code CellIndex.isDefault} and {@code FlowPathCell} compared
     * statistics, and a non-canonical instance would have made the bare-column rule miss
     * and resolve every default gate to an absent key.
     */
    @Test
    void statisticsAreCanonicalPerToken() {
        assertSame(Statistic.MEAN, Statistic.of("Mean"));
        assertSame(Statistic.MEAN, Statistic.of("mean"));
        assertSame(Statistic.MEAN, Statistic.of("MEAN"), "legacy workspaces spelled it MEAN");
        assertSame(Statistic.of("REDSEA"), Statistic.of("redsea"));
        assertEquals(Statistic.of("REDSEA").hashCode(), Statistic.of("redsea").hashCode());
    }

    /** Known statistics lead the ordering; discovered ones follow in encounter order. */
    @Test
    void orderingPutsKnownStatisticsFirst() {
        var input = new java.util.LinkedHashSet<Statistic>();
        input.add(Statistic.of("REDSEA"));
        input.add(Statistic.SUM);
        input.add(Statistic.of("Entropy"));
        input.add(Statistic.MEDIAN);
        assertEquals(
                java.util.List.of(Statistic.MEDIAN, Statistic.SUM, Statistic.of("REDSEA"), Statistic.of("Entropy")),
                Statistic.orderKnownFirst(input));
    }

    // ---- MeasurementKeys ----

    @Test
    void buildAndParseRoundTrip() {
        String key = MeasurementKeys.build("CD3", Compartment.NUCLEAR, Statistic.MEAN);
        assertEquals("CD3: Nucleus: Mean", key);
        var parsed = MeasurementKeys.parse(key);
        assertNotNull(parsed);
        assertEquals("CD3", parsed.marker());
        assertEquals(Compartment.NUCLEAR, parsed.compartment());
        assertEquals(Statistic.MEAN, parsed.statistic());
    }

    @Test
    void parseStripsLayerPrefix() {
        var parsed = MeasurementKeys.parse("[Layer0] CD3: Cytoplasm: Sum");
        assertNotNull(parsed);
        assertEquals("CD3", parsed.marker());
        assertEquals(Compartment.CYTOPLASMIC, parsed.compartment());
        assertEquals(Statistic.SUM, parsed.statistic());
    }

    @Test
    void parseReturnsNullForBareOrMorphologyKeys() {
        assertNull(MeasurementKeys.parse("CD3"));
        assertNull(MeasurementKeys.parse("Area µm²"));
        assertNull(MeasurementKeys.parse("CD3: Cell"));        // missing statistic
        assertNull(MeasurementKeys.parse(null));
    }

    // ---- CompartmentCapability ----

    @Test
    void capabilityDetectsRichKeys() {
        var cap = CompartmentCapability.fromKeys(List.of(
                "CD3: Nucleus: Mean", "CD3: Cytoplasm: Mean", "CD3: Cell: Mean",
                "CD3: Cell: Median", "DAPI", "Area µm²"));
        assertTrue(cap.isRich());
        assertTrue(cap.hasCompartments("CD3"));
        assertEquals(
                java.util.Set.of(Compartment.NUCLEAR, Compartment.CYTOPLASMIC, Compartment.WHOLE_CELL),
                cap.compartmentsFor("CD3"));
        assertEquals(
                java.util.Set.of(Statistic.MEAN, Statistic.MEDIAN),
                cap.statisticsFor("CD3"));
        assertFalse(cap.hasCompartments("DAPI"));
    }

    @Test
    void capabilityLegacyIsNotRich() {
        var cap = CompartmentCapability.fromKeys(List.of("CD3", "DAPI", "Area µm²"));
        assertFalse(cap.isRich());
        assertFalse(cap.hasCompartments("CD3"));
        assertTrue(cap.compartmentsFor("CD3").isEmpty());
    }

    // ---- CellIndex compartment-aware resolution ----

    @Test
    void resolveExactCompartmentKey() {
        Map<String, Number> m = Map.of(
                "CD3: Nucleus: Mean", 100.0,
                "CD3: Cytoplasm: Mean", 10.0,
                "CD3: Cell: Mean", 24.0);
        assertEquals(100.0, CellIndex.findMarkerValue(m, "CD3", Compartment.NUCLEAR, Statistic.MEAN));
        assertEquals(10.0, CellIndex.findMarkerValue(m, "CD3", Compartment.CYTOPLASMIC, Statistic.MEAN));
        assertEquals(24.0, CellIndex.findMarkerValue(m, "CD3", Compartment.WHOLE_CELL, Statistic.MEAN));
    }

    @Test
    void resolveLayerPrefixedCompartmentKey() {
        Map<String, Number> m = Map.of("[L0] CD3: Nucleus: Median", 7.0);
        assertEquals(7.0, CellIndex.findMarkerValue(m, "CD3", Compartment.NUCLEAR, Statistic.MEDIAN));
    }

    @Test
    void wholeCellMeanFallsBackToBareMarkerOnLegacy() {
        Map<String, Number> legacy = Map.of("CD3", 42.0);
        // Whole-cell mean must resolve via the bare key for legacy GeoJSONs.
        assertEquals(42.0, CellIndex.findMarkerValue(legacy, "CD3", Compartment.WHOLE_CELL, Statistic.MEAN));
        // But a non-whole-cell compartment is genuinely unavailable -> NaN.
        assertTrue(Double.isNaN(CellIndex.findMarkerValue(legacy, "CD3", Compartment.NUCLEAR, Statistic.MEAN)));
    }

    @Test
    void nullCompartmentDefaultsToWholeCellMean() {
        Map<String, Number> m = Map.of("CD3", 5.0);
        assertEquals(5.0, CellIndex.findMarkerValue(m, "CD3", null, null));
    }

    // ---- gate model fields ----

    @Test
    void thresholdGateCompartmentDefaultsAndCopy() {
        GateNode g = new GateNode("CD3");
        assertEquals(Compartment.WHOLE_CELL, g.getCompartment());
        assertEquals(Statistic.MEDIAN, g.getStatistic());
        assertEquals(List.of(Compartment.WHOLE_CELL), g.getCompartments());

        g.setCompartment(Compartment.NUCLEAR);
        g.setStatistic(Statistic.MEDIAN);
        GateNode copy = g.deepCopy();
        assertEquals(Compartment.NUCLEAR, copy.getCompartment());
        assertEquals(Statistic.MEDIAN, copy.getStatistic());
        // null-safety
        g.setCompartment(null);
        assertEquals(Compartment.WHOLE_CELL, g.getCompartment());
    }

    @Test
    void quadrantGatePerAxisCompartmentsAndCopy() {
        QuadrantGate q = new QuadrantGate("CD3", "CD8");
        q.setCompartmentX(Compartment.NUCLEAR);
        q.setCompartmentY(Compartment.CYTOPLASMIC);
        q.setStatisticX(Statistic.MEAN);
        q.setStatisticY(Statistic.SUM);

        // Order parallels getChannels(): [X, Y]
        assertEquals(List.of("CD3", "CD8"), q.getChannels());
        assertEquals(List.of(Compartment.NUCLEAR, Compartment.CYTOPLASMIC), q.getCompartments());
        assertEquals(List.of(Statistic.MEAN, Statistic.SUM), q.getStatistics());
        // Base single-channel accessor maps to the X axis (mirrors getChannel()).
        assertEquals(Compartment.NUCLEAR, q.getCompartment());

        QuadrantGate copy = (QuadrantGate) q.deepCopy();
        assertEquals(Compartment.NUCLEAR, copy.getCompartmentX());
        assertEquals(Compartment.CYTOPLASMIC, copy.getCompartmentY());
        assertEquals(Statistic.SUM, copy.getStatisticY());
    }
}
