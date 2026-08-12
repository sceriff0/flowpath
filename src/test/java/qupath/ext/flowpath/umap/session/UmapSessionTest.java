package qupath.ext.flowpath.umap.session;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.Compartment;
import qupath.ext.flowpath.model.CompartmentCapability;
import qupath.ext.flowpath.model.MarkerSelection;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.umap.PhenotypeSnapshot;
import qupath.ext.flowpath.umap.engine.EmbeddingFeatures;
import qupath.ext.flowpath.umap.model.PopulationTag;
import qupath.ext.flowpath.testing.Cells;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The rules {@link UmapSession} took over from {@code UmapPane}: colour derivation, the
 * gate/visibility/highlight masks, feature-selection seeding and the population-tag class
 * name surgery.
 * <p>
 * Every one of these was previously reachable only by constructing a {@code UmapPane},
 * which needs a live JavaFX toolkit and a {@code QuPathGUI}. None of them is about
 * widgets, so none of these tests carries an {@code assumeTrue(toolkitAvailable())}
 * guard — if this file ever needs one, the extraction has leaked.
 */
class UmapSessionTest {

    private static final List<String> PANEL = List.of("CD3", "CD8");

    private static PathObject cell(double x, String pathClass, int argb) {
        var obj = Cells.of(1).at(x, x).marker("CD3", x).marker("CD8", x * 2).only();
        if (pathClass != null) obj.setPathClass(PathClass.fromString(pathClass, argb));
        return obj;
    }

    private static CellIndex indexOf(List<PathObject> objects) {
        return CellIndex.build(objects, PANEL);
    }

    private static PhenotypeSnapshot snapshot(CellIndex index, String[] labels,
                                              int[] colors, boolean[] excluded,
                                              List<String> gated, MarkerSelection gateSel) {
        return snapshot(index, PANEL, labels, colors, excluded, gated, gateSel);
    }

    /** As above, over an explicit panel — the seeding tests need one wider than two. */
    private static PhenotypeSnapshot snapshot(CellIndex index, List<String> panel, String[] labels,
                                              int[] colors, boolean[] excluded,
                                              List<String> gated, MarkerSelection gateSel) {
        return new PhenotypeSnapshot(index, MarkerStats.compute(index), panel,
                CompartmentCapability.empty(), labels, colors, excluded,
                gated, gateSel, 2, "img");
    }

    /** A three-marker population, so a two-marker gate tree can leave something unticked. */
    private static CellIndex threeMarkerIndex() {
        return Cells.of(2).atGrid(1, 1)
                .marker("CD3", i -> i)
                .marker("CD8", i -> i * 2.0)
                .marker("FoxP3", i -> i * 3.0)
                .build();
    }

    private static final List<String> WIDE_PANEL = List.of("CD3", "CD8", "FoxP3");

    private static UmapSession sessionOn(PhenotypeSnapshot s) {
        var session = new UmapSession();
        session.adopt(s);
        return session;
    }

    // ------------------------------------------------------------------
    // Colour derivation
    // ------------------------------------------------------------------

    @Test
    void snapshotColoursWinOverPathClassAndCarrySpecialCasesForExcludedAndUnclassified() {
        var objects = List.of(
                cell(0, "Wrong", 0xFFAAAAAA),
                cell(1, "Wrong", 0xFFAAAAAA),
                cell(2, "Wrong", 0xFFAAAAAA));
        var index = indexOf(objects);
        var session = sessionOn(snapshot(index,
                new String[]{"T cell", PhenotypeSnapshot.UNCLASSIFIED, "T cell"},
                new int[]{0xFF00FF00, 0x00000000, 0xFF00FF00},
                new boolean[]{false, false, true},
                List.of(), new MarkerSelection()));

        int[] colors = session.derivePointColors(index.getObjects());
        assertEquals(0x00FF00, colors[0], "Gate colour, alpha stripped");
        assertEquals(UmapSession.UNCLASSIFIED_RGB, colors[1], "No gate claimed it");
        assertEquals(UmapSession.FILTERED_RGB, colors[2], "Filtered out by the gating pane");
        assertTrue(session.usesGatingColors(3));
        assertArrayEquals(colors, session.baseColors(), "The derivation is cached");
    }

    /**
     * The fallback the fused view still needs: standalone, and whenever the snapshot's
     * cell count no longer matches the embedding, colours come back from {@link PathClass}
     * rather than from arrays that would land on the wrong cells.
     */
    @Test
    void fallsBackToPathClassWhenThereIsNoSnapshotOrTheCountDisagrees() {
        var objects = List.of(cell(0, "T cell", 0xFF102030), cell(1, null, 0));
        var index = indexOf(objects);

        var standalone = new UmapSession();
        standalone.installIndex(index, MarkerStats.compute(index), PANEL,
                CompartmentCapability.empty(), MarkerSelection.defaultFor(PANEL));
        int[] colors = standalone.derivePointColors(index.getObjects());
        assertEquals(0x102030, colors[0], "ARGB from the PathClass, alpha stripped");
        assertEquals(UmapSession.UNCLASSIFIED_RGB, colors[1]);
        assertFalse(standalone.usesGatingColors(2));

        // Snapshot present, but the embedding covers a different number of points.
        var session = sessionOn(snapshot(index,
                new String[]{"T cell", "T cell"}, new int[]{0xFF0000, 0xFF0000},
                new boolean[2], List.of(), new MarkerSelection()));
        assertFalse(session.usesGatingColors(5),
                "A count mismatch means the arrays have drifted — do not paint through it");
    }

    @Test
    void gateShadingGreysTheOutsideAndAllocatesNothingWhenThereIsNoGate() {
        var session = new UmapSession();
        int[] colors = {0x111111, 0x222222, 0x333333};
        assertSame(colors, session.applyGateShading(colors), "No gate — no allocation");

        session.setGateMask(new boolean[]{true, false, true});
        int[] shaded = session.applyGateShading(colors);
        assertArrayEquals(new int[]{0x111111, UmapSession.UNFOCUSED_RGB, 0x333333}, shaded);
        assertArrayEquals(new int[]{0x111111, 0x222222, 0x333333}, colors, "Input untouched");

        // A mask shorter than the colours must not throw; the tail is simply outside.
        session.setGateMask(new boolean[]{true});
        assertArrayEquals(new int[]{0x111111, UmapSession.UNFOCUSED_RGB, UmapSession.UNFOCUSED_RGB},
                session.applyGateShading(colors));
    }

    @Test
    void visibilityAndHighlightMasksTrackTheHiddenSetAndTheSnapshotLabels() {
        var objects = List.of(cell(0, null, 0), cell(1, null, 0), cell(2, null, 0));
        var index = indexOf(objects);
        var session = sessionOn(snapshot(index,
                new String[]{"T cell", "B cell", "T cell"},
                new int[3], new boolean[]{false, false, true},
                List.of(), new MarkerSelection()));

        assertNull(session.visibilityMask(3), "Nothing hidden — the canvas draws everything");

        session.togglePhenotype("B cell");
        assertArrayEquals(new boolean[]{true, false, true}, session.visibilityMask(3));
        session.togglePhenotype("B cell");
        assertNull(session.visibilityMask(3), "Toggling twice restores the default");

        assertArrayEquals(new boolean[]{true, false, false},
                session.highlightMask("T cell", 3),
                "Excluded cells never highlight, however they are labelled");
        assertNull(session.highlightMask("T cell", 99), "Count mismatch yields no mask");
        assertNull(session.highlightMask(null, 3));
    }

    /**
     * Retiring a cell set drops what is positional against it and keeps what is not.
     * The hidden set is keyed by phenotype name, so re-gating the same slide must not
     * silently un-hide the population the user pushed out of the way; the gate mask,
     * the cached colours and the tag masks are all indexed, so they must go.
     */
    @Test
    void retiringACellSetKeepsTheHiddenSetButDropsEverythingPositional() {
        var objects = List.of(cell(0, null, 0), cell(1, null, 0));
        var index = indexOf(objects);
        var session = sessionOn(snapshot(index, new String[]{"T cell", "B cell"},
                new int[2], new boolean[2], List.of(), new MarkerSelection()));
        session.togglePhenotype("B cell");
        session.setGateMask(new boolean[]{true, false});
        session.derivePointColors(index.getObjects());
        session.addTag(new PopulationTag("Rim", 1, new boolean[2]));

        session.retireCellSet();
        assertNull(session.gateMask());
        assertNull(session.baseColors());
        assertTrue(session.tags().isEmpty());
        assertTrue(session.hiddenPhenotypes().contains("B cell"),
                "Hidden populations are named, not indexed — they survive a re-gate");

        session.clearDerivedState();
        assertTrue(session.hiddenPhenotypes().isEmpty(),
                "An image change does retire them: the names stop meaning anything");
    }

    // ------------------------------------------------------------------
    // Feature selection
    // ------------------------------------------------------------------

    @Test
    void seedingTicksTheGatedMarkersAndUntlicksTheRest() {
        var index = threeMarkerIndex();
        var gateSel = new MarkerSelection();
        gateSel.put("CD8", new MarkerSelection.Entry(Compartment.NUCLEAR, Statistic.MEDIAN, true));
        gateSel.put("FoxP3", new MarkerSelection.Entry(Compartment.NUCLEAR, Statistic.MEDIAN, true));

        var session = sessionOn(snapshot(index, WIDE_PANEL, new String[]{"a", "b"},
                new int[2], new boolean[2], List.of("CD8", "FoxP3"), gateSel));

        assertTrue(session.selection().isIncluded("CD8"), "The gated marker is pre-selected");
        assertTrue(session.selection().isIncluded("FoxP3"));
        assertFalse(session.selection().isIncluded("CD3"), "Ungated markers stay available but unticked");
        assertEquals("CD8", session.preferredMarker(),
                "Colour-by-marker should land on something the user gated");
    }

    /**
     * The day-one path. A single ThresholdGate on CD8 is the first gate anyone draws, and
     * seeding it faithfully would tick exactly one marker — which {@code EmbeddingFeatures}
     * refuses, so the pane would offer a Run button that could not succeed. Pre-selection
     * yields to the run being possible.
     */
    @Test
    void oneGatedMarkerDoesNotSeedASelectionTheEmbeddingWouldRefuse() {
        var index = threeMarkerIndex();
        var gateSel = new MarkerSelection();
        gateSel.put("CD8", new MarkerSelection.Entry(Compartment.NUCLEAR, Statistic.MEDIAN, true));

        var session = sessionOn(snapshot(index, WIDE_PANEL, new String[]{"a", "b"},
                new int[2], new boolean[2], List.of("CD8"), gateSel));

        for (String marker : WIDE_PANEL) {
            assertTrue(session.selection().isIncluded(marker),
                    marker + " must stay ticked: one gated marker cannot be embedded");
        }
        assertInstanceOf(EmbeddingFeatures.Selected.class,
                EmbeddingFeatures.of(index, session.selection()),
                "the seeded selection must be one the embedding accepts");
    }

    /**
     * The same shortfall by a longer route: two gates, but one of them on a marker this
     * image does not carry, so seeding would still tick one.
     */
    @Test
    void aGateOnAMarkerThePanelLacksCannotMakeUpTheShortfall() {
        var index = threeMarkerIndex();
        var gateSel = new MarkerSelection();
        gateSel.put("CD8", new MarkerSelection.Entry(Compartment.NUCLEAR, Statistic.MEDIAN, true));
        gateSel.put("Ghost", new MarkerSelection.Entry(Compartment.NUCLEAR, Statistic.MEDIAN, true));

        var session = sessionOn(snapshot(index, WIDE_PANEL, new String[]{"a", "b"},
                new int[2], new boolean[2], List.of("CD8", "Ghost"), gateSel));

        assertTrue(session.selection().isIncluded("CD3"));
        assertTrue(session.selection().isIncluded("FoxP3"));
    }

    @Test
    void seedingWithNoGatesIncludesEverything() {
        var objects = List.of(cell(0, null, 0));
        var index = indexOf(objects);
        var session = sessionOn(snapshot(index, new String[]{"a"}, new int[1], new boolean[1],
                List.of(), new MarkerSelection()));
        assertTrue(session.selection().isIncluded("CD3"));
        assertTrue(session.selection().isIncluded("CD8"));
        assertEquals("CD3", session.preferredMarker(), "No gates — first on the panel");
    }

    /**
     * A stored compartment the image no longer carries must not survive reload: the column
     * would resolve to NaN for every cell.
     */
    @Test
    void storedSelectionIsFilteredThroughWhatTheImageActuallyCarries() {
        var rich = CompartmentCapability.fromKeys(List.of(
                "CD3: Nucleus: Median", "CD8: Nucleus: Median"));
        var stored = new MarkerSelection();
        stored.put("CD3", new MarkerSelection.Entry(Compartment.CYTOPLASMIC, Statistic.SUM, false));
        stored.put("Gone", new MarkerSelection.Entry(Compartment.NUCLEAR, Statistic.MEDIAN, true));

        var loaded = UmapSession.loadSelection(stored.serialize(), PANEL, rich);
        assertEquals(Compartment.defaultCompartment(), loaded.compartmentFor("CD3"),
                "Cytoplasm is not in this image's keys — fall back rather than read NaN");
        assertEquals(Statistic.defaultStatistic(), loaded.statisticFor("CD3"));
        assertFalse(loaded.isIncluded("CD3"), "The include flag is the user's and does survive");
        assertFalse(loaded.markers().contains("Gone"), "A marker the image lost is dropped");

        assertEquals(Compartment.defaultCompartment(),
                UmapSession.loadSelection(null, PANEL, rich).compartmentFor("CD3"));
        // Legacy (non-rich) data is forced to whole-cell mean whatever was stored.
        var legacy = UmapSession.loadSelection(stored.serialize(), PANEL, CompartmentCapability.empty());
        assertEquals(Compartment.defaultCompartment(), legacy.compartmentFor("CD3"));
        assertTrue(legacy.isIncluded("CD3"));
    }

    @Test
    void structuredChannelNamesCollapseToOneRowPerMarker() {
        assertEquals(List.of("CD3", "DAPI", "CD8"),
                UmapSession.collapseToBaseMarkers(List.of(
                        "CD3: Nucleus: Mean", "CD3: Cytoplasm: Median", "DAPI",
                        "[Layer0] CD8", "   ")));
    }

    @Test
    void markerDiscoveryValidatesChannelsAgainstTheMeasurementsAndFallsBackToThem() {
        var detections = List.of(cell(0, null, 0), cell(1, null, 0));

        assertEquals(List.of("CD3", "CD8"),
                UmapSession.discoverMarkerNames(List.of("CD3", "CD8", "Phantom"), detections),
                "A declared channel with no measurement behind it is dropped");

        var fromMeasurements = UmapSession.discoverMarkerNames(List.of(), detections);
        assertTrue(fromMeasurements.containsAll(List.of("CD3", "CD8")),
                "With no usable channel list, fall back to the measurement keys");
        assertFalse(fromMeasurements.contains("Centroid X"), "Morphology keys are not markers");
    }

    // ------------------------------------------------------------------
    // Population tagging
    // ------------------------------------------------------------------

    @Test
    void taggingAppendsAndRetaggingReplacesOnlyAKnownTagSuffix() {
        var session = new UmapSession();
        assertEquals("T cell: Rim", session.tagClassName("T cell", "Rim"));
        assertEquals(PhenotypeSnapshot.UNCLASSIFIED + ": Rim", session.tagClassName(null, "Rim"));

        // A phenotype whose own name contains ": " must not be truncated into another
        // population just because it looks like a tag.
        assertEquals("CD3+: CD8+: Rim", session.tagClassName("CD3+: CD8+", "Rim"));

        session.addTag(new PopulationTag("Rim", 1, new boolean[]{true}));
        assertEquals("T cell: Core", session.tagClassName("T cell: Rim", "Core"),
                "A suffix matching a tag we applied is replaced, not stacked");
        assertEquals("CD3+: CD8+: Rim", session.tagClassName("CD3+: CD8+: Rim", "Rim"),
                "Re-applying the same tag is idempotent");
    }

    @Test
    void untaggingOnlyTouchesCellsCarryingThatExactSuffix() {
        assertEquals("T cell", UmapSession.untagClassName("T cell: Rim", "Rim"));
        assertNull(UmapSession.untagClassName("T cell: Core", "Rim"));
        assertNull(UmapSession.untagClassName("T cell", "Rim"));
        assertNull(UmapSession.untagClassName(null, "Rim"));
    }

    @Test
    void tagMasksAreConsideredStaleWhenTheEmbeddingChangesSize() {
        var session = new UmapSession();
        assertFalse(session.tagsAreStaleFor(10), "No tags, nothing to go stale");
        session.addTag(new PopulationTag("Rim", 1, new boolean[4]));
        assertFalse(session.tagsAreStaleFor(4));
        assertTrue(session.tagsAreStaleFor(5),
                "Masks are positional — a different cell count makes them meaningless");
        assertNotNull(session.tag("Rim"));
        assertSame(session.tag("Rim"), session.removeTag("Rim"));
        assertNull(session.removeTag("Rim"));
    }

    // ------------------------------------------------------------------
    // Generation guards and reporting
    // ------------------------------------------------------------------

    @Test
    void aNewerBuildSupersedesAnOlderOne() {
        var session = new UmapSession();
        int first = session.beginIndexBuild();
        assertTrue(session.isCurrentBuild(first));
        int second = session.beginIndexBuild();
        assertFalse(session.isCurrentBuild(first),
                "The result the user no longer asked for must not be applied");
        assertTrue(session.isCurrentBuild(second));

        int gate = session.beginGateComputation();
        assertTrue(session.isCurrentGate(gate));
        session.beginGateComputation();
        assertFalse(session.isCurrentGate(gate));

        int liveGate = session.beginGateComputation();
        session.beginIndexBuild();
        assertFalse(session.isCurrentGate(liveGate),
                "Starting an index build also retires any gate computed on the old cells");
    }

    @Test
    void describeSummarisesCellsFiltersPhenotypesAndGatedMarkers() {
        var objects = new ArrayList<PathObject>();
        for (int i = 0; i < 4; i++) objects.add(cell(i, null, 0));
        var index = indexOf(objects);

        String withGates = UmapSession.describe(snapshot(index,
                new String[]{"T cell", "T cell", "B cell", "x"},
                new int[4], new boolean[]{false, false, false, true},
                List.of("CD3"), new MarkerSelection()));
        assertTrue(withGates.startsWith("3 cells (1 filtered out)"), withGates);
        assertTrue(withGates.contains("2 phenotypes from 2 gates"), withGates);
        assertTrue(withGates.contains("1 gated marker pre-selected"), withGates);

        String noGates = UmapSession.describe(snapshot(index,
                new String[]{PhenotypeSnapshot.UNCLASSIFIED, PhenotypeSnapshot.UNCLASSIFIED,
                        PhenotypeSnapshot.UNCLASSIFIED, PhenotypeSnapshot.UNCLASSIFIED},
                new int[4], new boolean[4], List.of(), new MarkerSelection()));
        assertEquals("4 cells, no gates applied yet", noGates);
    }

    @Test
    void aRebuiltIndexIsRefusedWhileTheStandaloneInstallPathWouldOrphanTheSnapshot() {
        var objects = List.of(cell(0, null, 0), cell(1, null, 0));
        var index = indexOf(objects);
        var session = sessionOn(snapshot(index, new String[]{"a", "b"}, new int[2], new boolean[2],
                List.of(), new MarkerSelection()));

        var other = indexOf(List.of(cell(9, null, 0), cell(8, null, 0)));
        assertThrows(IllegalStateException.class, () -> session.installIndex(
                other, MarkerStats.compute(other), PANEL,
                CompartmentCapability.empty(), MarkerSelection.defaultFor(PANEL)),
                "The standalone path discovers its own cell set, which a snapshot forbids");
        assertSame(index, session.index());
    }
}
