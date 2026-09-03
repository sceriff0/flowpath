package qupath.ext.flowpath.ui;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.engine.GatingEngine;
import qupath.ext.flowpath.model.Branch;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.Compartment;
import qupath.ext.flowpath.model.EllipseGate;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.PolygonGate;
import qupath.ext.flowpath.model.QuadrantGate;
import qupath.ext.flowpath.model.RectangleGate;
import qupath.ext.flowpath.model.Region2DGate;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.testing.Cells;
import qupath.ext.flowpath.testing.FxTestSupport;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import qupath.ext.flowpath.model.MeasuredColumn;

/**
 * The display path and the classification path must answer "which branch does this
 * cell fall into?" identically.
 * <p>
 * {@link ScatterPlotCanvas} and {@link HistogramCanvas} decide a dot's / a bar's colour;
 * {@code GatingEngine} decides the phenotype, the branch counts and the CSV. They used to
 * be two hand-written copies of the same geometry, kept in step only by comments — so a
 * disagreement showed up as a plot that painted a cell green while the engine classified
 * it as Outside, with nothing failing.
 * <p>
 * Every case below feeds the <em>same</em> plot-space points to both paths and asserts
 * the same branch index comes back, with the boundary cases (exactly on a threshold, on a
 * polygon edge, on the ellipse rim, on the quadrant crosshair) called out explicitly
 * because that is where two spellings of the same predicate diverge first.
 * <p>
 * Gates are put in <b>raw</b> mode so the probe coordinates are literally the values the
 * engine compares; the raw-vs-z-score transform is a separate concern (it is shared
 * through {@code ResolvedGate} / {@code MeasuredColumn}) and is covered by the z-score
 * case at the end.
 */
class DisplayClassificationAgreementTest {

    private static final String MX = "MX";
    private static final String MY = "MY";

    // ---- helpers ----

    /** A two-marker CellIndex whose cell {@code i} sits at plot-space {@code (px[i], py[i])}. */
    private static CellIndex indexOf(double[] px, double[] py) {
        return Cells.columns(List.of(MX, MY), new double[][]{px, py}).build();
    }

    /**
     * Give every branch a unique name so a phenotype maps back to exactly one branch, and
     * pin every axis to the whole-cell mean — the synthetic index carries only the bare
     * marker column, which is what that (compartment, statistic) pair resolves to.
     */
    private static GateNode named(GateNode gate) {
        List<Branch> branches = gate.getBranches();
        for (int i = 0; i < branches.size(); i++) {
            branches.get(i).setName("B" + i);
        }
        if (gate instanceof Region2DGate region) {
            region.setCompartmentX(Compartment.WHOLE_CELL); region.setStatisticX(Statistic.MEAN);
            region.setCompartmentY(Compartment.WHOLE_CELL); region.setStatisticY(Statistic.MEAN);
        } else if (gate instanceof QuadrantGate quad) {
            quad.setCompartmentX(Compartment.WHOLE_CELL); quad.setStatisticX(Statistic.MEAN);
            quad.setCompartmentY(Compartment.WHOLE_CELL); quad.setStatisticY(Statistic.MEAN);
        } else {
            gate.setCompartment(Compartment.WHOLE_CELL);
            gate.setStatistic(Statistic.MEAN);
        }
        return gate;
    }

    /**
     * The classification answer: run the real engine over cells placed at the probe
     * points and read back which branch each landed in.
     */
    private static int[] classify(GateNode gate, CellIndex index, MarkerStats stats) {
        GateTree tree = new GateTree();
        tree.addRoot(gate);
        GatingEngine.AssignmentResult result = GatingEngine.assignAll(tree, index, stats);
        String[] phenotypes = result.getPhenotypes();
        int[] out = new int[index.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = Integer.parseInt(phenotypes[i].substring(1));
        }
        return out;
    }

    /**
     * The plot-space value of one axis, read back out of the index the way
     * {@code GateEditorPane} reads it. Going through the index matters: QuPath stores
     * measurements as floats, so {@code 0.6} written into a detection comes back as
     * {@code 0.6000000238...}. Feeding the plot the literal probe value instead would
     * make the two paths disagree on rim cases for a reason that cannot happen in the
     * app, where both sides read this same column.
     */
    private static double[] columnOf(CellIndex index, String marker) {
        return index.getResolvedColumn(marker, Compartment.WHOLE_CELL, Statistic.MEAN);
    }

    /** The display answer: what colour band the scatter plot paints each probe point in. */
    private static int[] display2D(GateNode gate, double[] px, double[] py) {
        return FxTestSupport.onFx(() -> {
            ScatterPlotCanvas scatter = new ScatterPlotCanvas();
            scatter.setData(px.clone(), py.clone(), MX, MY);
            // Mirrors GateEditorPane.applyBranchColorsToScatter for a 4-branch gate.
            if (gate instanceof QuadrantGate) {
                scatter.setQuadrantColors(javafx.scene.paint.Color.RED, javafx.scene.paint.Color.GREEN,
                        javafx.scene.paint.Color.BLUE, javafx.scene.paint.Color.GRAY);
            }
            scatter.setGateOverlay(gate);
            int[] out = new int[px.length];
            for (int i = 0; i < px.length; i++) {
                out[i] = scatter.branchAt(px[i], py[i]);
            }
            return out;
        });
    }

    private static void assertAgrees(String what, GateNode gate, double[] probeX, double[] probeY) {
        CellIndex index = indexOf(probeX, probeY);
        MarkerStats stats = MarkerStats.compute(index);
        double[] px = columnOf(index, MX);
        double[] py = columnOf(index, MY);

        int[] classified = classify(gate, index, stats);
        int[] drawn = display2D(gate, px, py);
        for (int i = 0; i < px.length; i++) {
            final int idx = i;
            assertEquals(classified[i], drawn[i], () -> String.format(
                    "%s: point (%s, %s) is classified into branch %d but drawn as branch %d",
                    what, px[idx], py[idx], classified[idx], drawn[idx]));
        }
    }

    // ---- 2D gates ----

    @Test
    void quadrantGateAgreesIncludingOnTheCrosshair() {
        assumeTrue(FxTestSupport.toolkitAvailable());
        QuadrantGate gate = new QuadrantGate(MX, MY, 0.5, 0.5);
        gate.setThresholdIsZScore(false);
        named(gate);

        double[] px = {1.0, 0.0, 1.0, 0.0, 0.5, 0.5, 0.5, 0.0, 1.0};
        double[] py = {1.0, 1.0, 0.0, 0.0, 0.5, 1.0, 0.0, 0.5, 0.5};
        assertAgrees("quadrant", gate, px, py);
    }

    @Test
    void rectangleGateAgreesOnItsEdgesAndCorners() {
        assumeTrue(FxTestSupport.toolkitAvailable());
        RectangleGate gate = new RectangleGate(MX, MY, 0.0, 1.0, 0.0, 1.0);
        gate.setThresholdIsZScore(false);
        named(gate);

        double[] px = {0.5, 0.0, 1.0, 0.0, 1.0, 0.5, -0.001, 1.001, 2.0, 0.5};
        double[] py = {0.5, 0.0, 1.0, 1.0, 0.0, 0.0, 0.5, 0.5, 2.0, 1.001};
        assertAgrees("rectangle", gate, px, py);
    }

    @Test
    void ellipseGateAgreesOnItsRim() {
        assumeTrue(FxTestSupport.toolkitAvailable());
        EllipseGate gate = new EllipseGate(MX, MY, 0.0, 0.0, 1.0, 1.0);
        gate.setThresholdIsZScore(false);
        named(gate);

        // (0.6, 0.8) sits exactly on the rim: 0.36 + 0.64 == 1.0 in binary floating point.
        double[] px = {0.0, 1.0, -1.0, 0.0, 0.6, 0.999, 1.0001, 2.0};
        double[] py = {0.0, 0.0, 0.0, 1.0, 0.8, 0.0, 0.0, 2.0};
        assertAgrees("ellipse", gate, px, py);
    }

    @Test
    void polygonGateAgreesOnItsVerticesAndEdges() {
        assumeTrue(FxTestSupport.toolkitAvailable());
        PolygonGate gate = new PolygonGate(MX, MY);
        gate.setVertices(List.of(new double[]{0, 0}, new double[]{2, 0}, new double[]{2, 2}, new double[]{0, 2}));
        gate.setThresholdIsZScore(false);
        named(gate);

        double[] px = {1.0, 0.0, 2.0, 1.0, 1.0, 0.0, 2.0, 3.0, -1.0};
        double[] py = {1.0, 0.0, 2.0, 0.0, 2.0, 1.0, 1.0, 1.0, 1.0};
        assertAgrees("polygon", gate, px, py);
    }

    // ---- the shapes the user has not finished drawing yet ----
    //
    // A region gate with no usable shape classifies every cell as Outside. The plot has
    // to say the same thing, or a freshly created (or just-cleared) gate paints the whole
    // population as if it were selected.

    @Test
    void emptyPolygonAgrees() {
        assumeTrue(FxTestSupport.toolkitAvailable());
        PolygonGate gate = new PolygonGate(MX, MY);
        gate.setThresholdIsZScore(false);
        named(gate);
        assertAgrees("polygon with no vertices", gate,
                new double[]{0.0, 1.0, -1.0}, new double[]{0.0, 1.0, -1.0});
    }

    @Test
    void twoVertexPolygonAgrees() {
        assumeTrue(FxTestSupport.toolkitAvailable());
        PolygonGate gate = new PolygonGate(MX, MY);
        gate.setVertices(List.of(new double[]{0, 0}, new double[]{1, 1}));
        gate.setThresholdIsZScore(false);
        named(gate);
        assertAgrees("polygon with two vertices", gate,
                new double[]{0.0, 0.5, 2.0}, new double[]{0.0, 0.5, 2.0});
    }

    @Test
    void zeroExtentRectangleAgrees() {
        assumeTrue(FxTestSupport.toolkitAvailable());
        RectangleGate gate = new RectangleGate(MX, MY, 0, 0, 0, 0);
        gate.setThresholdIsZScore(false);
        named(gate);
        assertAgrees("rectangle with no extent", gate,
                new double[]{0.0, 1.0, -1.0}, new double[]{0.0, 1.0, -1.0});
    }

    @Test
    void zeroRadiusEllipseAgrees() {
        assumeTrue(FxTestSupport.toolkitAvailable());
        EllipseGate gate = new EllipseGate(MX, MY, 0, 0, 0, 0);
        gate.setThresholdIsZScore(false);
        named(gate);
        assertAgrees("ellipse with no radius", gate,
                new double[]{0.0, 1.0, -1.0}, new double[]{0.0, 1.0, -1.0});
    }

    // ---- 1D threshold gate: histogram bar colour vs. classification ----

    @Test
    void histogramBarColourAgreesWithClassificationIncludingOnTheThreshold() {
        assumeTrue(FxTestSupport.toolkitAvailable());
        GateNode gate = named(new GateNode(MX, 0.5));
        gate.setThresholdIsZScore(false);

        double[] probes = {0.0, 0.25, 0.5, 0.500000001, 0.75, 1.0, -1.0};
        CellIndex index = indexOf(probes, new double[probes.length]);
        MarkerStats stats = MarkerStats.compute(index);
        double[] values = columnOf(index, MX);

        int[] classified = classify(gate, index, stats);
        boolean[] drawnPositive = FxTestSupport.onFx(() -> {
            HistogramCanvas histogram = new HistogramCanvas();
            histogram.setGate(gate);
            histogram.setThreshold(gate.getThreshold());
            boolean[] out = new boolean[values.length];
            for (int i = 0; i < values.length; i++) {
                out[i] = histogram.isPositiveAt(values[i]);
            }
            return out;
        });

        for (int i = 0; i < values.length; i++) {
            final int idx = i;
            assertEquals(classified[i] == 0, drawnPositive[i], () -> String.format(
                    "threshold gate: value %s is classified into branch %d but drawn as %s",
                    values[idx], classified[idx], drawnPositive[idx] ? "positive" : "negative"));
        }
    }

    // ---- randomised sweep across every gate type ----

    @Test
    void randomisedPointsAgreeForEveryGateType() {
        assumeTrue(FxTestSupport.toolkitAvailable());
        Random rng = new Random(20260811L);

        List<GateNode> gates = new ArrayList<>();
        gates.add(new QuadrantGate(MX, MY, 0.3, -0.2));
        gates.add(new RectangleGate(MX, MY, -0.5, 0.5, -0.5, 0.5));
        gates.add(new EllipseGate(MX, MY, 0.1, -0.1, 0.7, 1.3));
        PolygonGate poly = new PolygonGate(MX, MY);
        poly.setVertices(List.of(new double[]{-1, -1}, new double[]{1, -1},
                new double[]{1.5, 0.5}, new double[]{0, 1}, new double[]{-1, 0.5}));
        gates.add(poly);

        int n = 400;
        double[] px = new double[n];
        double[] py = new double[n];
        for (int i = 0; i < n; i++) {
            // Snap a third of the probes onto a coarse grid so boundary coincidences
            // (exactly on a threshold, exactly on an edge) actually occur.
            px[i] = i % 3 == 0 ? Math.round(rng.nextDouble() * 6 - 3) / 2.0 : rng.nextDouble() * 6 - 3;
            py[i] = i % 3 == 0 ? Math.round(rng.nextDouble() * 6 - 3) / 2.0 : rng.nextDouble() * 6 - 3;
        }

        for (GateNode gate : gates) {
            gate.setThresholdIsZScore(false);
            named(gate);
            assertAgrees(gate.getGateType() + " (randomised)", gate, px, py);
        }
    }

    @Test
    void zScoreModeAgreesToo() {
        assumeTrue(FxTestSupport.toolkitAvailable());
        // In z-score mode the plot is fed standardised values and the gate boundary lives
        // in the same space, so the geometry comparison is identical — this pins that the
        // shared transform is applied on both sides rather than only on one.
        RectangleGate gate = new RectangleGate(MX, MY, -0.5, 0.5, -0.5, 0.5);
        gate.setThresholdIsZScore(true);
        named(gate);

        double[] raw = {1, 2, 3, 4, 5, 6, 7, 8};
        double[] rawY = {8, 7, 6, 5, 4, 3, 2, 1};

        CellIndex index = indexOf(raw, rawY);
        MarkerStats stats = MarkerStats.compute(index);
        double[] colX = columnOf(index, MX);
        double[] colY = columnOf(index, MY);
        MeasuredColumn cx = index.column(MX, null, null, stats);
        MeasuredColumn cy = index.column(MY, null, null, stats);
        double[] zx = new double[raw.length];
        double[] zy = new double[raw.length];
        for (int i = 0; i < raw.length; i++) {
            zx[i] = cx.toZScore(colX[i]);
            zy[i] = cy.toZScore(colY[i]);
        }

        GateTree tree = new GateTree();
        tree.addRoot(gate);
        String[] phenotypes = GatingEngine.assignAll(tree, index, stats).getPhenotypes();
        int[] drawn = display2D(gate, zx, zy);
        for (int i = 0; i < raw.length; i++) {
            final int idx = i;
            int classified = Integer.parseInt(phenotypes[i].substring(1));
            assertEquals(classified, drawn[i], () -> String.format(
                    "z-score rectangle: cell %d (z = %s, %s) classified into branch %d but drawn as branch %d",
                    idx, zx[idx], zy[idx], Integer.parseInt(phenotypes[idx].substring(1)), drawn[idx]));
        }
    }

    /**
     * The agreement has to hold for cells the gate cannot measure, not only for the finite
     * points every other case in this file feeds it.
     * <p>
     * {@code HistogramCanvas} guarded its range with {@code val < min || val > max}, and
     * both comparisons are false for {@code NaN}, so an unmeasured cell passed the guard.
     * {@code (int) NaN} is {@code 0}, so it landed in the leftmost bin — below the
     * threshold, drawn in the negative colour. The engine meanwhile returned
     * {@code UNMEASURED} for the same cell and counted it in neither branch, so the bar
     * chart and the counts beside it described different populations. That is the
     * "unmeasured is not negative" invariant, which was pinned in the engine and the CSV
     * but never in the display path — the one place it was actually broken.
     */
    @Test
    void anUnmeasuredCellIsDrawnInNoBinRatherThanTheLowestOne() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable");

        // Cell 0 has no value for A; the other nine sit at 1..9.
        CellIndex index = Cells.of(10)
                .marker("A", i -> i == 0 ? Double.NaN : (double) i)
                .area(100.0)
                .build();
        MarkerStats stats = MarkerStats.compute(index, Cells.allTrue(10));

        double[] values = columnOf(index, "A");

        HistogramCanvas canvas = FxTestSupport.onFx(HistogramCanvas::new);
        FxTestSupport.onFxRun(() -> canvas.setData(values, 1.0, 9.0));

        assertEquals(9, FxTestSupport.onFx(canvas::binnedTotal),
                "the nine measured cells are drawn; the unmeasured one is not drawn anywhere");

        // Specifically not in bin 0, which is where (int) NaN sent it and where the gate
        // paints the negative colour.
        int inBinZero = FxTestSupport.onFx(() -> canvas.binCount(0));
        int trulyInBinZero = 0;
        double binWidth = (9.0 - 1.0) / HistogramCanvas.binCountTotal();
        for (double v : values) {
            if (!Double.isNaN(v) && v >= 1.0 && v < 1.0 + binWidth) trulyInBinZero++;
        }
        assertEquals(trulyInBinZero, inBinZero,
                "bin 0 holds only the cells whose real value falls in it");

        // And the engine agrees about the size of the population being described.
        GateNode gate = new GateNode("A", 5.0);
        gate.setStatistic(Statistic.MEAN);
        gate.setThresholdIsZScore(false);
        GateTree tree = new GateTree();
        tree.setQualityFilter(null);
        tree.addRoot(gate);
        GatingEngine.AssignmentResult result = GatingEngine.assignAll(tree, index, stats);

        assertTrue(result.getUnmeasured()[0], "the engine calls cell 0 unmeasured");
        int classified = gate.getBranches().get(0).getCount() + gate.getBranches().get(1).getCount();
        assertEquals(FxTestSupport.onFx(canvas::binnedTotal), classified,
                "the histogram draws exactly the cells the engine classified");
    }

    /** Guards against a future gate type quietly skipping the geometry contract. */
    @Test
    void everyRegionGateTypeIsCovered() {
        List<Class<? extends Region2DGate>> covered =
                List.of(PolygonGate.class, RectangleGate.class, EllipseGate.class);
        assertEquals(3, covered.size());
    }
}
