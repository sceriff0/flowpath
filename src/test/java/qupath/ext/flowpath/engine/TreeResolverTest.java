package qupath.ext.flowpath.engine;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.EllipseGate;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.GateValues;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.PolygonGate;
import qupath.ext.flowpath.model.QuadrantGate;
import qupath.ext.flowpath.model.SlideSetting;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.model.cohort.Alignment;
import qupath.ext.flowpath.model.cohort.Landmarks;
import qupath.ext.flowpath.testing.Cells;
import qupath.ext.flowpath.testing.GateTreeFixtures;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static qupath.ext.flowpath.engine.TreeResolver.Source.*;

class TreeResolverTest {

    static final Alignment BRIGHTER = Alignment.between(new Landmarks(1.0, 1.0, 4.0), new Landmarks(1.0, 1.5, 5.0));
    static final Alignment DIMMER = Alignment.between(new Landmarks(1.0, 1.0, 4.0), new Landmarks(1.0, 0.7, 3.4));

    static AlignmentLookup lookup(Map<String, Alignment> byColumn) {
        return (slideId, column) -> "s1".equals(slideId) ? byColumn.get(column) : null;
    }

    private static GateTree cohortTree() {
        GateTree tree = GateTreeFixtures.twoRootsOnCd3AndCd8(10.0, 20.0);
        tree.setReferenceSlideId("ref");
        return tree;
    }

    @Test
    void withNoCohortTheResolvedTreeIsAnExactPairableCopy() {
        GateTree tree = GateTreeFixtures.twoRootsOnCd3AndCd8(10.0, 20.0);
        TreeResolver.ResolvedTree r = TreeResolver.resolve(tree, "s1", lookup(Map.of("CD3", BRIGHTER)));
        assertNotSame(tree.getRoots().get(0), r.tree().getRoots().get(0));
        assertEquals(10.0, r.tree().getRoots().get(0).getThreshold());
        assertEquals(List.of(REFERENCE), r.applied(tree.getRoots().get(0)).sources());
        assertTrue(GateTree.transferCountsIfStructureMatches(tree.getRoots(), r.tree().getRoots()));
    }

    @Test
    void theReferenceSlideAndANullSlideResolveToReference() {
        GateTree tree = cohortTree();
        for (String slide : new String[]{"ref", null}) {
            TreeResolver.ResolvedTree r = TreeResolver.resolve(tree, slide, (s, c) -> BRIGHTER);
            assertEquals(10.0, r.resolvedOf(tree.getRoots().get(0)).getThreshold());
            assertEquals(List.of(REFERENCE), r.applied(tree.getRoots().get(1)).sources());
        }
    }

    @Test
    void correctedAxesApplyTheAlignmentAndUncorrectedOnesDoNot() {
        GateTree tree = cohortTree();
        GateNode cd3 = tree.getRoots().get(0);
        GateNode cd8 = tree.getRoots().get(1);
        cd8.setCorrectStaining(false);
        TreeResolver.ResolvedTree r = TreeResolver.resolve(tree,
                "s1", lookup(Map.of("CD3", BRIGHTER, "CD8", BRIGHTER)));
        assertEquals(BRIGHTER.apply(10.0), r.resolvedOf(cd3).getThreshold(), 1e-12);
        assertEquals(List.of(CORRECTED), r.applied(cd3).sources());
        assertEquals(List.of("CD3"), r.applied(cd3).columns());
        assertEquals(20.0, r.resolvedOf(cd8).getThreshold());
        assertEquals(List.of(UNCORRECTED), r.applied(cd8).sources());
        assertEquals(10.0, cd3.getThreshold(), "the live tree is never mutated");
    }

    @Test
    void aMissingOrIdentityAlignmentIsUncorrected() {
        GateTree tree = cohortTree();
        TreeResolver.ResolvedTree r = TreeResolver.resolve(tree, "s1",
                lookup(Map.of("CD3", Alignment.identity())));
        assertEquals(List.of(UNCORRECTED), r.applied(tree.getRoots().get(0)).sources());
        assertEquals(List.of(UNCORRECTED), r.applied(tree.getRoots().get(1)).sources());
        assertEquals(10.0, r.resolvedOf(tree.getRoots().get(0)).getThreshold());
    }

    /** Review Focus 1: two roots on one channel, different decisions on the same slide. */
    @Test
    void twoSameChannelRootsKeepTheirOwnSlideSettings() {
        GateTree tree = new GateTree();
        tree.setReferenceSlideId("ref");
        GateNode a = new GateNode("CD8", 400.0);
        GateNode b = new GateNode("CD8", 600.0);
        a.setStatistic(Statistic.MEAN);
        b.setStatistic(Statistic.MEAN);
        a.setSlideSetting("s1", new SlideSetting.Skip());
        b.setSlideSetting("s1", new SlideSetting.Manual(GateValues.of(new double[]{612.5})));
        tree.addRoot(a);
        tree.addRoot(b);

        TreeResolver.ResolvedTree s1 = TreeResolver.resolve(tree, "s1", lookup(Map.of("CD8", BRIGHTER)));
        assertTrue(s1.resolvedOf(a).isSkippedOnSlide());
        assertFalse(s1.resolvedOf(b).isSkippedOnSlide());
        assertEquals(612.5, s1.resolvedOf(b).getThreshold());
        assertEquals(List.of(SKIPPED), s1.applied(a).sources());
        assertEquals(List.of(MANUAL), s1.applied(b).sources());

        TreeResolver.ResolvedTree s2 = TreeResolver.resolve(tree, "s2", (s, c) -> BRIGHTER);
        assertFalse(s2.resolvedOf(a).isSkippedOnSlide(), "a setting on s1 says nothing about s2");
        assertEquals(List.of(CORRECTED), s2.applied(b).sources());
    }

    @Test
    void aManualOfTheWrongShapeIsIgnored() {
        GateTree tree = cohortTree();
        GateNode cd3 = tree.getRoots().get(0);
        cd3.setSlideSetting("s1", new SlideSetting.Manual(GateValues.of(new double[]{1}, new double[]{2})));
        assertEquals(List.of(CORRECTED),
                TreeResolver.resolve(tree, "s1", lookup(Map.of("CD3", BRIGHTER))).applied(cd3).sources());
    }

    @Test
    void twoDimensionalGatesMapEachAxisThroughItsOwnColumn() {
        GateTree tree = new GateTree();
        tree.setReferenceSlideId("ref");
        QuadrantGate quad = new QuadrantGate("CD3", "CD4", 10, 20);
        quad.setStatisticX(Statistic.MEAN);
        quad.setStatisticY(Statistic.MEAN);
        PolygonGate poly = new PolygonGate("CD3", "CD4");
        poly.setStatisticX(Statistic.MEAN);
        poly.setStatisticY(Statistic.MEAN);
        poly.setVertices(List.of(new double[]{1, 2}, new double[]{5, 2}, new double[]{3, 9}));
        tree.addRoot(quad);
        tree.addRoot(poly);

        TreeResolver.ResolvedTree r = TreeResolver.resolve(tree, "s1",
                lookup(Map.of("CD3", BRIGHTER, "CD4", DIMMER)));
        QuadrantGate rq = (QuadrantGate) r.resolvedOf(quad);
        assertEquals(BRIGHTER.apply(10), rq.getThresholdX(), 1e-12);
        assertEquals(DIMMER.apply(20), rq.getThresholdY(), 1e-12);
        List<double[]> v = ((PolygonGate) r.resolvedOf(poly)).getVertices();
        assertEquals(BRIGHTER.apply(5), v.get(1)[0], 1e-12);
        assertEquals(DIMMER.apply(9), v.get(2)[1], 1e-12);
        assertEquals(List.of(CORRECTED, CORRECTED), r.applied(poly).sources());
        assertEquals(List.of("CD3", "CD4"), r.applied(poly).columns());
    }

    @Test
    void aSkippedGateCompilesUnmeasuredNeverNegative() {
        GateTree tree = cohortTree();
        GateNode cd3 = tree.getRoots().get(0);
        cd3.setSlideSetting("s1", new SlideSetting.Skip());
        CellIndex index = Cells.of(10).marker("CD3", i -> i * 3.0).marker("CD8", i -> i * 5.0).build();
        TreeResolver.ResolvedTree r = TreeResolver.resolve(tree, "s1", AlignmentLookup.NONE);
        GatingEngine.AssignmentResult result = GatingEngine.assignAll(r.tree(), index, MarkerStats.compute(index));
        for (boolean u : result.getUnmeasured()) assertTrue(u);
        assertEquals(0, result.getTally().total(r.resolvedOf(cd3).getBranches().get(1)), "never counted negative");
        GateNode cd8copy = r.resolvedOf(tree.getRoots().get(1));
        assertEquals(10, result.getTally().total(cd8copy.getBranches().get(0))
                + result.getTally().total(cd8copy.getBranches().get(1)), "the other root still counts every cell");
    }

    /** Review Focus 2: a gate channel absent on this slide resolves (uncorrected) and compiles unusable. */
    @Test
    void aChannelAbsentOnTheSlideStillResolvesAndCompilesUnmeasured() {
        GateTree tree = cohortTree();
        GateNode ghost = new GateNode("CD99", 5.0);
        ghost.setStatistic(Statistic.MEAN);
        tree.addRoot(ghost);
        CellIndex index = Cells.of(6).marker("CD3", i -> i).marker("CD8", i -> i).build();
        TreeResolver.ResolvedTree r = TreeResolver.resolve(tree, "s1", lookup(Map.of("CD3", BRIGHTER)));
        assertEquals(List.of(UNCORRECTED), r.applied(ghost).sources());
        GatingEngine.AssignmentResult result = GatingEngine.assignAll(r.tree(), index, MarkerStats.compute(index));
        assertEquals(0, result.getTally().total(r.resolvedOf(ghost).getBranches().get(1)));
    }

    /**
     * Pins C4: rebuilding an ellipse from its bounding box drifts it by a few ulps
     * ({@code (1.1-0.9)/2 == 0.10000000000000009}), which would break the exact
     * display/classification agreement at the rim. An uncorrected (or reference-slide)
     * ellipse must come out bit-identical to the live gate's own values, not merely
     * numerically close.
     */
    @Test
    void anUncorrectedEllipseIsBitIdenticalNeverRebuiltFromItsBoundingBox() {
        GateTree tree = new GateTree();
        tree.setReferenceSlideId("ref");
        EllipseGate ellipse = new EllipseGate("CD3", "CD4", 1.0, 1.0, 0.1 + 1e-17, 0.2);
        ellipse.setStatisticX(Statistic.MEAN);
        ellipse.setStatisticY(Statistic.MEAN);
        tree.addRoot(ellipse);

        TreeResolver.ResolvedTree onReference = TreeResolver.resolve(tree, "ref", lookup(Map.of("CD3", BRIGHTER)));
        EllipseGate refCopy = (EllipseGate) onReference.resolvedOf(ellipse);
        assertEquals(ellipse.getCenterX(), refCopy.getCenterX(), 0.0);
        assertEquals(ellipse.getCenterY(), refCopy.getCenterY(), 0.0);
        assertEquals(ellipse.getRadiusX(), refCopy.getRadiusX(), 0.0);
        assertEquals(ellipse.getRadiusY(), refCopy.getRadiusY(), 0.0);

        TreeResolver.ResolvedTree onOtherSlide = TreeResolver.resolve(tree, "s1", AlignmentLookup.NONE);
        EllipseGate uncorrectedCopy = (EllipseGate) onOtherSlide.resolvedOf(ellipse);
        assertEquals(ellipse.getCenterX(), uncorrectedCopy.getCenterX(), 0.0);
        assertEquals(ellipse.getCenterY(), uncorrectedCopy.getCenterY(), 0.0);
        assertEquals(ellipse.getRadiusX(), uncorrectedCopy.getRadiusX(), 0.0);
        assertEquals(ellipse.getRadiusY(), uncorrectedCopy.getRadiusY(), 0.0);
        assertEquals(List.of(UNCORRECTED, UNCORRECTED), onOtherSlide.applied(ellipse).sources());
    }
}
