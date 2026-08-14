package qupath.ext.flowpath.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The measurement-key contract between MIRAGE and FlowPath.
 * <p>
 * Pins the three things that were wrong while {@link Statistic} was a closed enum and the
 * capability was stored as two independent axes. Named for the contract rather than for a
 * class, because it is the contract these assertions defend: <b>the compartment slot is
 * closed and is the parsing anchor; the statistic slot is open; and a marker's capability
 * is a set of pairs, never a product of two projections.</b>
 */
class MeasurementKeyContractTest {

    // ---------------------------------------------------------------- parsing

    /**
     * Finding 1. A statistic FlowPath has never heard of used to make the whole key
     * unparseable, so the column was invisible to gating and to UMAP feature selection.
     */
    @Test
    void aStatisticFlowPathHasNeverSeenStillParses() {
        MeasurementKeys.Parsed parsed = MeasurementKeys.parse("CD3: Cell: REDSEA");

        assertNotNull(parsed, "an unknown statistic is not a malformed key");
        assertEquals("CD3", parsed.marker());
        assertEquals(Compartment.WHOLE_CELL, parsed.compartment());
        assertEquals("REDSEA", parsed.statistic().token());
    }

    /**
     * Finding 2. The unparsed key fell through {@code collapseToBaseMarkers} to
     * {@code stripLayerPrefix}, so the panel grew a row literally named
     * {@code "CD3: Cell: REDSEA"} beside the real {@code CD3}.
     */
    @Test
    void anUnknownStatisticDoesNotBecomeAPhantomMarker() {
        List<String> markers = MeasurementKeys.collapseToBaseMarkers(List.of(
                "CD3", "CD3: Cell: Median", "CD3: Cell: REDSEA", "CD8: Nucleus: REDSEA"));

        assertEquals(List.of("CD3", "CD8"), markers,
                "every key collapses to its marker; none survives as a row of its own");
    }

    /** The split runs right-to-left, so a marker carrying the separator still parses. */
    @Test
    void aMarkerNameMayContainTheSeparator() {
        MeasurementKeys.Parsed parsed = MeasurementKeys.parse("ROI: 0.50 µm per pixel: CD3: Nucleus: Median");

        assertNotNull(parsed);
        assertEquals("ROI: 0.50 µm per pixel: CD3", parsed.marker());
        assertEquals(Compartment.NUCLEAR, parsed.compartment());
        assertEquals(Statistic.MEDIAN, parsed.statistic());
    }

    /**
     * <b>Gap 3: the negative case.</b> The closed enum was doing double duty — vocabulary
     * <em>and</em> a filter rejecting keys that are not MIRAGE measurements. Opening the
     * statistic slot keeps the vocabulary open but withdraws the filter, so the
     * compartment anchor is now the only thing keeping non-measurements out of the panel.
     * These are the shapes it has to keep out.
     */
    @Test
    void nonMeasurementKeysStayOutOfThePanel() {
        assertNull(MeasurementKeys.parse("CD3"), "a bare marker is not a per-compartment key");
        assertNull(MeasurementKeys.parse("Area µm²"), "a morphology field has no separators");
        assertNull(MeasurementKeys.parse("Major Axis Length µm"));
        assertNull(MeasurementKeys.parse("CD3: Cell"), "two tokens is not enough");
        assertNull(MeasurementKeys.parse("CD3: Membrane: Median"), "Membrane is not a compartment");
        assertNull(MeasurementKeys.parse("Nucleus: Area µm^2"), "QuPath's own one-separator shape");
        assertNull(MeasurementKeys.parse(": Cell: Median"), "an empty marker is malformed");
        assertNull(MeasurementKeys.parse(null));

        assertEquals(List.of("Area µm²", "Nucleus: Area µm^2"),
                MeasurementKeys.collapseToBaseMarkers(List.of("Area µm²", "Nucleus: Area µm^2")),
                "unparsed non-measurements keep their own text, as before");
    }

    /** A layer prefix is still stripped, and still only from the marker. */
    @Test
    void aLayerPrefixIsStripped() {
        MeasurementKeys.Parsed parsed = MeasurementKeys.parse("[Layer0] CD3: Nucleus: REDSEA");

        assertNotNull(parsed);
        assertEquals("CD3", parsed.marker());
        assertEquals("REDSEA", parsed.statistic().token());
    }

    /** Build and parse are inverses, for a known statistic and an unknown one alike. */
    @Test
    void buildAndParseRoundTrip() {
        for (Statistic s : List.of(Statistic.MEAN, Statistic.MEDIAN, Statistic.SUM, Statistic.of("REDSEA"))) {
            String key = MeasurementKeys.build("CD3", Compartment.CYTOPLASMIC, s);
            MeasurementKeys.Parsed parsed = MeasurementKeys.parse(key);
            assertNotNull(parsed, key);
            assertEquals("CD3", parsed.marker(), key);
            assertEquals(Compartment.CYTOPLASMIC, parsed.compartment(), key);
            assertEquals(s, parsed.statistic(), key);
        }
    }

    // ------------------------------------------------------------ capability

    /**
     * <b>Finding 3, the one this whole change exists for.</b>
     * <p>
     * The capability used to hold two independent sets, so the offerable signals were
     * their Cartesian product. A marker carrying {@code Cell: REDSEA} and
     * {@code Nucleus: Median} advertised {@code Nucleus × REDSEA} — a key that is not in
     * the file, and therefore NaN for every cell, with nothing thrown.
     */
    @Test
    void aPairTheExportLacksIsNeverOffered() {
        var cap = CompartmentCapability.fromKeys(List.of(
                "CD3: Cell: REDSEA",
                "CD3: Nucleus: Median"));

        // Both projections still report what they see...
        assertEquals(Set.of(Compartment.NUCLEAR, Compartment.WHOLE_CELL), cap.compartmentsFor("CD3"));
        assertEquals(Set.of(Statistic.MEDIAN, Statistic.of("REDSEA")), cap.statisticsFor("CD3"));

        // ...but their product is not the capability.
        assertTrue(cap.offers("CD3", Compartment.WHOLE_CELL, Statistic.of("REDSEA")));
        assertTrue(cap.offers("CD3", Compartment.NUCLEAR, Statistic.MEDIAN));
        assertFalse(cap.offers("CD3", Compartment.NUCLEAR, Statistic.of("REDSEA")),
                "the cross-product pair is not in the file");
        assertFalse(cap.offers("CD3", Compartment.WHOLE_CELL, Statistic.MEDIAN));
    }

    /** Resolution can only ever land on a pair the scan actually saw. */
    @Test
    void resolutionNeverLandsOnAnAbsentPair() {
        var cap = CompartmentCapability.fromKeys(List.of(
                "CD3: Cell: REDSEA",
                "CD3: Nucleus: Median"));

        // Asking for the pair that does not exist keeps the compartment and re-picks.
        var resolved = cap.resolvePair("CD3", Compartment.NUCLEAR, Statistic.of("REDSEA"));
        assertTrue(cap.offers("CD3", resolved.compartment(), resolved.statistic()),
                "resolvePair returned " + resolved + ", which is not in the export");
        assertEquals(new CompartmentCapability.Pair(Compartment.NUCLEAR, Statistic.MEDIAN), resolved);

        // Every combination of preferences resolves to something the file carries.
        for (Compartment c : Compartment.values()) {
            for (Statistic s : List.of(Statistic.MEAN, Statistic.MEDIAN, Statistic.SUM, Statistic.of("REDSEA"))) {
                var p = cap.resolvePair("CD3", c, s);
                assertTrue(cap.offers("CD3", p.compartment(), p.statistic()),
                        "preferred (" + c + ", " + s + ") resolved to absent pair " + p);
            }
        }
    }

    /** An exact match is kept, so a configured gate is never silently moved. */
    @Test
    void anExactlyAvailablePairIsKept() {
        var cap = CompartmentCapability.fromKeys(List.of(
                "CD3: Cell: Median", "CD3: Cell: Mean", "CD3: Nucleus: Median"));

        assertEquals(new CompartmentCapability.Pair(Compartment.WHOLE_CELL, Statistic.MEAN),
                cap.resolvePair("CD3", Compartment.WHOLE_CELL, Statistic.MEAN));
        assertEquals(new CompartmentCapability.Pair(Compartment.NUCLEAR, Statistic.MEDIAN),
                cap.resolvePair("CD3", Compartment.NUCLEAR, Statistic.MEDIAN));
    }

    /** A legacy channel with no structured keys resolves to the bare-column signal. */
    @Test
    void aLegacyMarkerResolvesToTheBareColumnSignal() {
        var cap = CompartmentCapability.fromKeys(List.of("CD3: Cell: Median"));

        assertFalse(cap.hasCompartments("DAPI"));
        assertEquals(new CompartmentCapability.Pair(Compartment.WHOLE_CELL, Statistic.MEAN),
                cap.resolvePair("DAPI", Compartment.NUCLEAR, Statistic.SUM),
                "no structured keys: the bare column is the whole-cell mean");
    }

    /** The per-compartment projection is what a statistic selector should be filled from. */
    @Test
    void statisticsAreQueryableWithinACompartment() {
        var cap = CompartmentCapability.fromKeys(List.of(
                "CD3: Cell: REDSEA", "CD3: Cell: Median", "CD3: Nucleus: Median"));

        assertEquals(Set.of(Statistic.MEDIAN, Statistic.of("REDSEA")),
                cap.statisticsFor("CD3", Compartment.WHOLE_CELL));
        assertEquals(Set.of(Statistic.MEDIAN),
                cap.statisticsFor("CD3", Compartment.NUCLEAR),
                "REDSEA is Cell-only by nature and must not leak into the nuclear list");
        assertEquals(Set.of(), cap.statisticsFor("CD3", Compartment.CYTOPLASMIC));
    }

    /** Discovery still ignores keys that are not per-compartment measurements. */
    @Test
    void discoveryIgnoresNonMeasurementKeys() {
        var cap = CompartmentCapability.fromKeys(List.of("Area µm²", "Centroid X µm", "CD3"));

        assertFalse(cap.isRich(), "no per-compartment key means a legacy export");
        assertTrue(cap.pairsFor("CD3").isEmpty());
    }

    // ------------------------------------------- the real MIRAGE REDSEA shape

    /**
     * The actual keys MIRAGE's {@code feat/redsea-compensation} emits, taken from
     * {@code bin/utils/measurements.py}: {@code REDSEA_STATISTICS = ("REDSEA Sum",
     * "REDSEA Mean")}, whole-cell only.
     * <p>
     * Two things here that a hand-written example would have missed. The token contains a
     * <b>space</b>, so no amount of adding names to the old {@code Compartment × Statistic}
     * suffix loop would have been enough — the shape had to change. And MIRAGE's own
     * source note says {@code "X: Cell: REDSEA Sum" does not end with ": Cell: Sum"},
     * which is precisely why the old parser returned null rather than mis-parsing: the
     * column went missing rather than going wrong.
     */
    @Test
    void theRealRedseaKeysParse() {
        for (String token : List.of("REDSEA Sum", "REDSEA Mean")) {
            String key = "CD3: Cell: " + token;
            MeasurementKeys.Parsed parsed = MeasurementKeys.parse(key);

            assertNotNull(parsed, key);
            assertEquals("CD3", parsed.marker(), key);
            assertEquals(Compartment.WHOLE_CELL, parsed.compartment(), key);
            assertEquals(token, parsed.statistic().token(), key);
        }
    }

    /** A REDSEA token must not be confused with the plain statistic it ends with. */
    @Test
    void redseaSumIsNotSum() {
        assertNotEquals(Statistic.SUM, Statistic.of("REDSEA Sum"));
        assertNotEquals(Statistic.MEAN, Statistic.of("REDSEA Mean"));
        assertEquals(Statistic.SUM, MeasurementKeys.parse("CD3: Cell: Sum").statistic());
        assertEquals(Statistic.of("REDSEA Sum"), MeasurementKeys.parse("CD3: Cell: REDSEA Sum").statistic());
    }

    /**
     * A whole REDSEA-enabled export, end to end. This is the shape finding 3 was always
     * about: REDSEA is whole-cell only, every other statistic is per-compartment, and the
     * product of the two projections advertises pairs the file does not carry.
     */
    @Test
    void aRedseaEnabledExportOffersOnlyWhatItCarries() {
        var cap = CompartmentCapability.fromKeys(List.of(
                "CD3: Cell: Median", "CD3: Nucleus: Median", "CD3: Cytoplasm: Median",
                "CD3: Cell: REDSEA Sum", "CD3: Cell: REDSEA Mean"));

        assertEquals(Set.of(Compartment.NUCLEAR, Compartment.CYTOPLASMIC, Compartment.WHOLE_CELL),
                cap.compartmentsFor("CD3"));
        assertEquals(Set.of(Statistic.MEDIAN, Statistic.of("REDSEA Sum"), Statistic.of("REDSEA Mean")),
                cap.statisticsFor("CD3"));

        // The nuclear selector must offer Median alone — the compensation has no
        // nucleus/cytoplasm decomposition to emit.
        assertEquals(Set.of(Statistic.MEDIAN), cap.statisticsFor("CD3", Compartment.NUCLEAR));
        assertEquals(Set.of(Statistic.MEDIAN), cap.statisticsFor("CD3", Compartment.CYTOPLASMIC));
        assertEquals(Set.of(Statistic.MEDIAN, Statistic.of("REDSEA Sum"), Statistic.of("REDSEA Mean")),
                cap.statisticsFor("CD3", Compartment.WHOLE_CELL));

        assertFalse(cap.offers("CD3", Compartment.NUCLEAR, Statistic.of("REDSEA Sum")));
        assertFalse(cap.offers("CD3", Compartment.CYTOPLASMIC, Statistic.of("REDSEA Mean")));

        // And nothing the editor can ask for resolves outside the file.
        for (Compartment c : Compartment.values()) {
            for (Statistic st : cap.statisticsFor("CD3")) {
                var resolved = cap.resolvePair("CD3", c, st);
                assertTrue(cap.offers("CD3", resolved.compartment(), resolved.statistic()),
                        "preferred (" + c + ", " + st + ") resolved to absent pair " + resolved);
            }
        }
    }

    /** The panel still shows one row per marker when REDSEA columns are present. */
    @Test
    void redseaColumnsDoNotMultiplyThePanel() {
        assertEquals(List.of("CD3", "CD8"), MeasurementKeys.collapseToBaseMarkers(List.of(
                "CD3", "CD3: Cell: Median", "CD3: Cell: REDSEA Sum", "CD3: Cell: REDSEA Mean",
                "CD8", "CD8: Cell: Median")));
    }
}
