package qupath.ext.flowpath.model;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The compartment vocabulary is open, and the anchor that keeps it safe.
 * <p>
 * {@link Compartment} stopped being an enum for the reason {@link Statistic} did: a closed
 * set makes FlowPath unable to see a column it has no name for, and the key then fails to
 * parse and is re-absorbed by {@link MeasurementKeys#collapseToBaseMarkers} as a phantom
 * marker spelled {@code "CD3: Membrane: Median"}.
 * <p>
 * But the compartment slot is what {@link MeasurementKeys#parse} anchors on, so it cannot
 * simply be opened. These tests pin both halves: a genuinely new compartment is picked up,
 * and QuPath's own three-part measurement names still are not.
 */
class OpenCompartmentTest {

    // ---- the anchor still holds -------------------------------------------------

    /**
     * <b>The regression this guard exists for.</b> QuPath writes measurement names with
     * two separators of its own. Accepting any token in the compartment slot would turn
     * {@code "ROI: 0.50 µm per pixel: CD3"} into marker {@code "ROI"}, compartment
     * {@code "0.50 µm per pixel"}, statistic {@code "CD3"} — inventing a compartment,
     * inventing a marker, and losing a real channel.
     * <p>
     * The token never reaches two distinct markers, because the marker slot is the
     * constant {@code "ROI"} however many channels the image has.
     */
    @Test
    void quPathsOwnThreePartNamesAreNotCompartments() {
        Set<String> keys = Set.of(
                "ROI: 0.50 µm per pixel: CD3",
                "ROI: 0.50 µm per pixel: CD8",
                "ROI: 0.50 µm per pixel: DAPI");

        Set<Compartment> discovered = MeasurementKeys.discoverCompartments(keys);
        assertEquals(Set.copyOf(Compartment.known()), discovered,
                "the marker slot is a constant, so nothing is corroborated: " + discovered);

        for (String key : keys) {
            assertNull(MeasurementKeys.parse(key, discovered), key + " must not parse");
        }
        var capability = CompartmentCapability.fromKeys(keys);
        assertFalse(capability.isRich(), "and no panel is built from them");
    }

    /** A single unknown token used by one marker is not enough. */
    @Test
    void oneMarkerIsNotCorroboration() {
        Set<String> keys = Set.of("CD3: Membrane: Median", "CD3: Cell: Median");

        Set<Compartment> discovered = MeasurementKeys.discoverCompartments(keys);
        assertFalse(discovered.stream().anyMatch(c -> c.token().equals("Membrane")),
                "one marker vouching for itself is how the ROI shape would sneak in");
    }

    // ---- and a real new compartment gets through ---------------------------------

    /**
     * Two markers agreeing is the structural signature of a real compartment: a pipeline
     * emits every compartment for every marker in the panel.
     */
    @Test
    void aCompartmentTwoMarkersAgreeOnIsRecognised() {
        Set<String> keys = new LinkedHashSet<>(List.of(
                "CD3: Membrane: Median", "CD3: Cell: Median",
                "CD8: Membrane: Median", "CD8: Cell: Median"));

        Set<Compartment> discovered = MeasurementKeys.discoverCompartments(keys);
        Compartment membrane = Compartment.of("Membrane");
        assertTrue(discovered.contains(membrane), "discovered: " + discovered);

        var parsed = MeasurementKeys.parse("CD3: Membrane: Median", discovered);
        assertNotNull(parsed);
        assertEquals("CD3", parsed.marker());
        assertEquals(membrane, parsed.compartment());
        assertEquals(Statistic.MEDIAN, parsed.statistic());
    }

    /** And it reaches the capability, so the editor can offer it. */
    @Test
    void aDiscoveredCompartmentIsOfferedByTheCapability() {
        var capability = CompartmentCapability.fromKeys(List.of(
                "CD3: Membrane: Median", "CD3: Cell: Median",
                "CD8: Membrane: Median", "CD8: Cell: Median"));

        Compartment membrane = Compartment.of("Membrane");
        assertTrue(capability.compartmentsFor("CD3").contains(membrane));
        assertTrue(capability.offers("CD3", membrane, Statistic.MEDIAN));
        assertTrue(capability.compartments().contains(membrane));

        // Known ones lead, the newcomer follows -- the same ordering rule as statistics.
        List<Compartment> ordered = List.copyOf(capability.compartmentsFor("CD3"));
        assertEquals(Compartment.WHOLE_CELL, ordered.get(0));
        assertEquals(membrane, ordered.get(ordered.size() - 1));
    }

    /**
     * The failure the open vocabulary removes: an unparsed key used to be re-absorbed as a
     * marker, so the panel grew a row literally named after the whole key.
     */
    @Test
    void anUnknownCompartmentDoesNotBecomeAPhantomMarker() {
        List<String> keys = List.of(
                "CD3: Membrane: Median", "CD8: Membrane: Median",
                "CD3: Cell: Median", "CD8: Cell: Median");
        Set<Compartment> discovered = MeasurementKeys.discoverCompartments(keys);

        List<String> markers = new java.util.ArrayList<>();
        for (String key : keys) {
            var parsed = MeasurementKeys.parse(key, discovered);
            String base = parsed != null ? parsed.marker() : key;
            if (!markers.contains(base)) markers.add(base);
        }
        assertEquals(List.of("CD3", "CD8"), markers,
                "two markers, not four keys masquerading as markers");
    }

    // ---- identity and display ----------------------------------------------------

    /** Interned, so {@code ==} and {@code equals} agree, case-insensitively. */
    @Test
    void tokensAreInternedCaseInsensitively() {
        assertSame(Compartment.NUCLEAR, Compartment.of("Nucleus"));
        assertSame(Compartment.NUCLEAR, Compartment.of("nucleus"));
        assertSame(Compartment.of("Membrane"), Compartment.of("MEMBRANE"));
        assertEquals(Compartment.WHOLE_CELL, Compartment.of("Cell"));
    }

    /** The three FlowPath names keep their display text; a newcomer gets a usable default. */
    @Test
    void anUnknownCompartmentStillRendersSensibly() {
        Compartment membrane = Compartment.of("Membrane");
        assertEquals("Membrane", membrane.token());
        assertEquals("Membrane", membrane.displayName());
        assertEquals("M", membrane.abbreviation(), "a badge needs a letter");
        assertFalse(membrane.isKnown());
        assertTrue(Compartment.NUCLEAR.isKnown());
        assertEquals("Nuclear", Compartment.NUCLEAR.displayName());
        assertEquals("N", Compartment.NUCLEAR.abbreviation());
    }

    /** Saved files keep spelling the known three the way they always have. */
    @Test
    void theKnownThreeKeepTheirSerialisedNames() {
        assertEquals("NUCLEAR", Compartment.NUCLEAR.name());
        assertEquals("CYTOPLASMIC", Compartment.CYTOPLASMIC.name());
        assertEquals("WHOLE_CELL", Compartment.WHOLE_CELL.name());
        assertEquals("Membrane", Compartment.of("Membrane").name(),
                "a compartment FlowPath does not name falls back to its token");
    }

    /** {@code known()} is the conservative lookup; it never invents. */
    @Test
    void knownNeverInvents() {
        assertNull(Compartment.known("Membrane"));
        assertNull(Compartment.known("0.50 µm per pixel"));
        assertNull(Compartment.known(null));
        assertEquals(Compartment.NUCLEAR, Compartment.known("nucleus"));
    }

    /** Parsing without a discovered set stays conservative -- the safe default. */
    @Test
    void parsingWithoutADiscoveredSetAcceptsOnlyTheKnownThree() {
        assertNotNull(MeasurementKeys.parse("CD3: Nucleus: Median"));
        assertNull(MeasurementKeys.parse("CD3: Membrane: Median"),
                "no evidence was supplied, so no evidence is assumed");
    }
}
