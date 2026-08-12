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
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

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
        assertFalse(session.hasGate());
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
        assertSame(session.tag("Rim"), session.removeTag("Rim", null));
        assertNull(session.removeTag("Rim", null));
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

    /**
     * The rail and the status bar say the same three things about the same slide.
     * <p>
     * They used to be two compositions with different wording and different arithmetic —
     * the rail counted the markers the picker had ticked, the status line counted the
     * markers the gate tree had named — printed within an inch of each other. Whichever
     * one a reader believed, the other was quoting a different number.
     */
    @Test
    void theRailAndTheStatusBarShareOneComposition() {
        var objects = new ArrayList<PathObject>();
        for (int i = 0; i < 4; i++) objects.add(cell(i, null, 0));
        var index = indexOf(objects);

        var session = sessionOn(snapshot(index,
                new String[]{"T cell", "T cell", "B cell", "x"},
                new int[4], new boolean[]{false, false, false, true},
                List.of("CD3"), new MarkerSelection()));

        assertEquals(List.of("3 cells \u00b7 1 filtered out",
                        "2 phenotypes from 2 gates",
                        "2 of 2 markers selected"),
                session.overviewLines());
        assertEquals(String.join(", ", session.overviewLines()), session.overviewLine(),
                "the status bar is the rail's paragraph on one line, not a second one");
    }

    @Test
    void anUngatedSnapshotSaysSoRatherThanClaimingZeroPhenotypes() {
        var objects = new ArrayList<PathObject>();
        for (int i = 0; i < 4; i++) objects.add(cell(i, null, 0));
        var index = indexOf(objects);

        var session = sessionOn(snapshot(index,
                new String[]{PhenotypeSnapshot.UNCLASSIFIED, PhenotypeSnapshot.UNCLASSIFIED,
                        PhenotypeSnapshot.UNCLASSIFIED, PhenotypeSnapshot.UNCLASSIFIED},
                new int[4], new boolean[4], List.of(), new MarkerSelection()));

        assertEquals("4 cells, no gates applied yet, 2 of 2 markers selected",
                session.overviewLine());
    }

    /** Standalone there is no gating tree to report on, so the middle line is absent. */
    @Test
    void aStandaloneSessionReportsCellsAndMarkersOnly() {
        var session = new UmapSession();
        var index = threeMarkerIndex();
        assertEquals(List.of("No cells loaded"), session.overviewLines(),
                "and an empty session says exactly that, in both places");

        session.installIndex(index, MarkerStats.compute(index), WIDE_PANEL,
                CompartmentCapability.empty(), MarkerSelection.defaultFor(WIDE_PANEL));
        assertEquals(List.of("2 cells", "3 of 3 markers selected"), session.overviewLines());

        // A stale cell set is not a cell set — the same rule hasCells() states, so the
        // summary cannot go on quoting the previous image's size.
        session.adopt(snapshot(index, WIDE_PANEL, new String[]{"a", "b"}, new int[2],
                new boolean[2], List.of(), new MarkerSelection()));
        session.detachSnapshot();
        assertEquals(List.of("No cells loaded"), session.overviewLines());
    }

    // ------------------------------------------------------------------
    // Rules that used to need a QuPathGUI
    // ------------------------------------------------------------------

    /**
     * The cell-set rule, as data.
     * <p>
     * It was {@code UmapPane.collectDetections}, which took an {@code ImageData} and read a
     * checkbox, so the only way to ask it anything was to build a pane. Its snapshot-side
     * counterpart already lived on the session, which is how the two came to disagree: the
     * rebuild path re-queried the hierarchy <em>without</em> the annotation filter, widening
     * the analysis back to the whole slide.
     */
    @Test
    void theCellSetDropsExcludedCellsAndNarrowsToTheAnnotations() {
        var inside = cell(1, null, 0);
        var outside = cell(90, null, 0);
        var excluded = cell(2, UmapSession.EXCLUDED_CLASS, 0xFF808080);
        var all = List.of(inside, outside, excluded);

        assertEquals(List.of(inside, outside),
                UmapSession.selectDetections(all, List.of()),
                "No annotations means no narrowing — an Excluded cell is dropped either way");

        var roi = ROIs.createRectangleROI(-5, -5, 20, 20, ImagePlane.getDefaultPlane());
        assertEquals(List.of(inside), UmapSession.selectDetections(all, List.of(roi)));
    }

    /**
     * A drawn annotation that contains nothing is not the same as no annotation, and the
     * filter must not quietly widen back to the slide because the user missed.
     */
    @Test
    void anAnnotationCoveringNothingYieldsNothing() {
        var all = List.of(cell(1, null, 0), cell(2, null, 0));
        var elsewhere = ROIs.createRectangleROI(500, 500, 10, 10, ImagePlane.getDefaultPlane());
        assertTrue(UmapSession.selectDetections(all, List.of(elsewhere)).isEmpty());
    }

    /**
     * What the first finished embedding is painted by — a product decision that carried
     * thirteen lines of justifying comment inside a {@code UmapPane} handler and no test.
     */
    @Test
    void theFirstEmbeddingIsColouredByPhenotypeWheneverThereAreAny() {
        var index = threeMarkerIndex();
        var gated = sessionOn(snapshot(index, WIDE_PANEL, new String[]{"T cell", "B cell"},
                new int[2], new boolean[2], List.of("CD8"), new MarkerSelection()));
        assertEquals(UmapSession.ColourMode.PHENOTYPE, gated.firstColourMode(null),
                "the phenotypes ARE what the user came to look at");
        assertEquals(UmapSession.ColourMode.PHENOTYPE, gated.firstColourMode("CD3"),
                "and an arbitrary channel does not get to override them");

        var ungated = sessionOn(snapshot(index, WIDE_PANEL,
                new String[]{PhenotypeSnapshot.UNCLASSIFIED, PhenotypeSnapshot.UNCLASSIFIED},
                new int[2], new boolean[2], List.of(), new MarkerSelection()));
        assertEquals(UmapSession.ColourMode.MARKER, ungated.firstColourMode(UmapSession.NO_MARKER),
                "without phenotypes the alternative is a uniform grey blob");
        assertEquals(UmapSession.ColourMode.UNCHANGED, ungated.firstColourMode("FoxP3"),
                "but a marker the user picked themselves is left alone");

        assertEquals(UmapSession.ColourMode.UNCHANGED, new UmapSession().firstColourMode(null),
                "and with no panel there is nothing to colour by either way");
    }

    @Test
    void markerColourValuesComeBackRawOrStandardisedOnRequest() {
        var index = threeMarkerIndex();
        var session = new UmapSession();
        session.installIndex(index, MarkerStats.compute(index), WIDE_PANEL,
                CompartmentCapability.empty(), MarkerSelection.defaultFor(WIDE_PANEL));

        assertSame(index.getMarkerValues(index.getMarkerIndex("CD8")),
                session.colourValues("CD8", false),
                "raw is the index's backing column — cloning it per repaint is the hot path");

        double[] z = session.colourValues("CD8", true);
        assertNotSame(index.getMarkerValues(index.getMarkerIndex("CD8")), z);
        assertEquals(-z[0], z[1], 1e-9, "two symmetric values standardise symmetrically");
        assertTrue(z[1] > 0);

        assertNull(session.colourValues("NotOnThisPanel", false));
        assertNull(session.colourValues(null, true));
        assertNull(new UmapSession().colourValues("CD8", false), "nothing indexed, nothing to paint");
    }

    // ------------------------------------------------------------------
    // Population tagging: the write loops
    // ------------------------------------------------------------------

    /**
     * Tagging writes the derived class onto the gated cells only, keeps their colour, and
     * retires the gate that selected them. Untagging puts every one of them back —
     * <em>with</em> the colour, which is the part that was missing when the loop lived in
     * the pane and left every untagged cell rendered in QuPath's default grey.
     */
    @Test
    void taggingAndUntaggingRoundTripThroughTheSession() {
        // Distinct class names: QuPath caches PathClass globally by its full path and
        // keeps the colour of whichever test created it first.
        var tCell = cell(0, "Tag-T", 0xFF00FF00);
        var bCell = cell(1, "Tag-B", 0xFF0000FF);
        var index = indexOf(List.of(tCell, bCell));
        var session = sessionOn(snapshot(index, new String[]{"Tag-T", "Tag-B"},
                new int[2], new boolean[2], List.of(), new MarkerSelection()));
        PathObject[] objects = index.getObjects();

        assertNull(session.applyTag("Rim", 0xFF8800, objects), "no gate, nothing to name");

        session.setGateMask(new boolean[]{true, false});
        var tag = session.applyTag("Rim", 0xFF8800, objects);

        assertNotNull(tag);
        assertEquals(1, tag.count());
        assertEquals("Tag-T: Rim", tCell.getPathClass().toString());
        assertEquals(0xFF00FF00, tCell.getPathClass().getColor(), "the phenotype colour rides along");
        assertEquals("Tag-B", bCell.getPathClass().toString(), "cells outside the gate are untouched");
        assertFalse(session.hasGate(), "the gate that selected them is retired with the tag");
        assertEquals(List.of(tag), session.tags());

        assertSame(tag, session.removeTag("Rim", objects));
        assertEquals("Tag-T", tCell.getPathClass().toString());
        assertEquals(0xFF00FF00, tCell.getPathClass().getColor(),
                "and it rides back — dropping it left every untagged cell in the default grey");
        assertTrue(session.tags().isEmpty());
        assertNull(session.removeTag("Rim", objects));
    }

    /**
     * Re-tagging an already-tagged cell replaces the tag rather than nesting under it, and
     * untagging still finds its own suffix.
     * <p>
     * Neither worked. {@code PathClass.fromString("Tag-P: Rim", c)} builds a <em>derived</em>
     * class whose {@code getName()} is the leaf {@code "Rim"} — which both loops read — so
     * a second tag produced {@code "Rim: Core"} and {@code untagClassName("Rim", "Rim")}
     * matched nothing and restored nothing. The loops were in {@code UmapPane}, where no
     * test could reach them.
     */
    @Test
    void aSecondTagReplacesTheFirstRatherThanNestingUnderIt() {
        var cell = cell(0, "Tag-P", 0xFF123456);
        var index = indexOf(List.of(cell));
        var session = sessionOn(snapshot(index, new String[]{"Tag-P"},
                new int[1], new boolean[1], List.of(), new MarkerSelection()));
        PathObject[] objects = index.getObjects();

        session.setGateMask(new boolean[]{true});
        session.applyTag("Rim", 0xFF8800, objects);
        assertEquals("Tag-P: Rim", cell.getPathClass().toString());

        session.setGateMask(new boolean[]{true});
        session.applyTag("Core", 0x0088FF, objects);
        assertEquals("Tag-P: Core", cell.getPathClass().toString(),
                "the previously applied tag is stripped, not nested under");

        assertNotNull(session.removeTag("Core", objects));
        assertEquals("Tag-P", cell.getPathClass().toString());
    }

    @Test
    void ringOverlaysAreOnePairPerTagInTheOrderTheyWereApplied() {
        var session = new UmapSession();
        assertTrue(session.ringColors().isEmpty());

        session.addTag(new PopulationTag("Rim", 0xFF0000, new boolean[]{true, false}));
        session.addTag(new PopulationTag("Core", 0x00FF00, new boolean[]{false, true}));

        assertArrayEquals(new int[]{0xFF0000}, session.ringColors().get(0));
        assertArrayEquals(new int[]{0x00FF00}, session.ringColors().get(1));
        assertArrayEquals(new boolean[]{true, false}, session.ringMasks().get(0));
        assertEquals(2, session.ringMasks().size());

        session.clearTags();
        assertTrue(session.tags().isEmpty());
        assertTrue(session.ringMasks().isEmpty());
    }

    /**
     * The same {@code getName()} defect as the tag write loops, one screen away: the
     * standalone legend keyed its rows by the leaf of the class path, so tagging two
     * phenotypes with one population name collapsed them into a single row wearing
     * whichever colour was seen first and quoting the sum of both counts.
     */
    @Test
    void twoPhenotypesCarryingTheSameTagStayTwoLegendRows() {
        var objects = new PathObject[]{
                cell(0, "Legend-T: Core", 0xFF00FF00),
                cell(1, "Legend-T: Core", 0xFF00FF00),
                cell(2, "Legend-B: Core", 0xFF0000FF)
        };

        var counts = UmapSession.classCounts(objects);

        assertEquals(List.of("Legend-T: Core", "Legend-B: Core"), List.copyOf(counts.keySet()),
                "keyed on getName() both of these are \"Core\", and the legend showed one row");
        assertEquals(2, counts.get("Legend-T: Core")[0]);
        assertEquals(1, counts.get("Legend-B: Core")[0]);
        assertEquals(0xFF00FF00, counts.get("Legend-T: Core")[1]);
        assertEquals(0xFF0000FF, counts.get("Legend-B: Core")[1],
                "and the merged row wore whichever colour happened to be seen first");
    }

    @Test
    void unclassifiedCellsGetTheirOwnLegendRow() {
        var counts = UmapSession.classCounts(new PathObject[]{
                cell(0, "Legend-Plain", 0xFF112233), cell(1, null, 0)});

        assertEquals(1, counts.get("Legend-Plain")[0]);
        assertEquals(0xFF112233, counts.get("Legend-Plain")[1]);
        assertEquals(1, counts.get(PhenotypeSnapshot.UNCLASSIFIED)[0]);
        assertTrue(UmapSession.classCounts(null).isEmpty());
    }

    // ------------------------------------------------------------------
    // Notification
    // ------------------------------------------------------------------

    /**
     * A subscriber that mutates during its own notification is refused, loudly.
     * <p>
     * The mutation depth is already back to zero by the time observers are called, so the
     * nested publish would run to completion and the outer loop would then resume handing
     * the <em>older</em> state to the subscribers it had not reached — the staleness this
     * whole design replaced, reintroduced from the inside.
     */
    @Test
    void aSubscriberThatMutatesDuringItsOwnNotificationIsRefused() {
        var session = new UmapSession();
        var reached = new ArrayList<String>();
        session.observe(state -> {
            reached.add("mutating");
            if (session.isRunning()) session.cancelRun();
        });
        session.observe(state -> reached.add("second"));

        assertThrows(IllegalStateException.class, session::beginRun);
        assertTrue(reached.contains("second"),
                "the round still completes — the throw is the report, not the remedy");
    }

    /**
     * One broken subscriber must not leave the rest of the panel half-updated.
     * <p>
     * Armed after subscription because {@link UmapSession#observe} delivers the current
     * state by calling the new subscriber directly — a throw there belongs to whoever just
     * subscribed and is not this loop's to absorb.
     */
    @Test
    void aThrowingSubscriberDoesNotStopTheOnesBehindIt() {
        var session = new UmapSession();
        var reached = new ArrayList<String>();
        var armed = new java.util.concurrent.atomic.AtomicBoolean(false);
        session.observe(state -> {
            if (armed.get()) throw new IllegalArgumentException("legend blew up");
        });
        session.observe(state -> reached.add("behind it"));
        armed.set(true);

        var thrown = assertThrows(IllegalArgumentException.class, session::beginRun);
        assertEquals("legend blew up", thrown.getMessage(), "and the first failure is still raised");
        assertEquals(List.of("behind it", "behind it"), reached,
                "once at subscription, once for the run it was told about");
        assertTrue(session.isRunning(), "the mutation itself stands; only the reporting failed");
    }

    // ------------------------------------------------------------------
    // Nothing derived leaves this class in a form a caller can edit
    // ------------------------------------------------------------------

    /**
     * The leaks the observer design could not survive. Each of these used to hand out live
     * state that {@code UmapPane} mutated directly — {@code session.tags().clear()} was a
     * TAGGED-to-COMPUTED transition the session never heard about — and the gate mask was
     * iterated in two places outside the class.
     */
    @Test
    void derivedStateIsNotHandedOutInAFormACallerCanChange() {
        var session = new UmapSession();
        session.addTag(new PopulationTag("Rim", 1, new boolean[2]));
        session.togglePhenotype("B cell");

        assertThrows(UnsupportedOperationException.class, () -> session.tags().clear());
        assertThrows(UnsupportedOperationException.class, () -> session.hiddenPhenotypes().clear());
        assertThrows(NullPointerException.class, () -> session.setGateMask(null),
                "dropping a gate is retireGate() — setGateMask(null) said only half of it");

        assertTrue(session.showAllPhenotypes());
        assertTrue(session.hiddenPhenotypes().isEmpty());
        assertFalse(session.showAllPhenotypes(), "and says so when there was nothing to show");
    }

    /**
     * Retiring a gate also invalidates the computation still in flight over it. Spelling
     * that out as {@code beginGateComputation()} plus {@code setGateMask(null)} was three
     * copies of one operation, and a fourth caller would have had to know the generation
     * bump was not optional.
     */
    @Test
    void retiringAGateStopsTheDragThatWasStillComputingIt() {
        var session = new UmapSession();
        session.setGateMask(new boolean[]{true, false, true});
        int inFlight = session.beginGateComputation();
        assertTrue(session.isCurrentGate(inFlight));

        session.retireGate();

        assertFalse(session.hasGate());
        assertFalse(session.isCurrentGate(inFlight),
                "otherwise the drag lands a moment later and re-applies the mask just dropped");
    }

    @Test
    void theGatedObjectsAreCappedRatherThanHandingOutTheMask() {
        var objects = List.of(cell(0, null, 0), cell(1, null, 0), cell(2, null, 0));
        var index = indexOf(objects);
        var session = new UmapSession();
        session.setGateMask(new boolean[]{true, false, true});

        assertEquals(List.of(objects.get(0), objects.get(2)),
                session.gatedObjects(index.getObjects(), 200_000));
        assertEquals(1, session.gatedObjects(index.getObjects(), 1).size(),
                "a gate can cover millions of cells; the viewer selection is capped");

        session.retireGate();
        assertTrue(session.gatedObjects(index.getObjects(), 10).isEmpty());
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
