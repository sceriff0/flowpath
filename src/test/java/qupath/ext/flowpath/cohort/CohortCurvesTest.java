package qupath.ext.flowpath.cohort;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.RectangleGate;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.model.cohort.Alignment;
import qupath.ext.flowpath.model.cohort.Landmarks;
import qupath.ext.flowpath.testing.Cells;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class CohortCurvesTest {

    static final Alignment SHIFT = Alignment.between(new Landmarks(1.0, 1.0, Double.NaN), new Landmarks(1.0, 1.3, Double.NaN));

    static SlideSample sample(String id, boolean withCd4) {
        return sample(id, withCd4, i -> false);
    }

    /** 40 cells, CD8 = i, CD3 = 100 + i and (optionally) CD4 = 2i, CD4 absent where {@code cd4Absent} says. */
    static SlideSample sample(String id, boolean withCd4, java.util.function.IntPredicate cd4Absent) {
        Cells cells = Cells.of(40).marker("CD8", i -> i).marker("CD3", i -> 100 + i);
        if (withCd4) cells.marker("CD4", i -> 2.0 * i).absentOn(cd4Absent);
        CellIndex index = cells.build();
        return new SlideSample(id, id + ".tif", index, Cells.allTrue(40), MarkerStats.compute(index), 40, "f");
    }

    /**
     * Two CD8 roots (10, 20) on the same channel; {@code child} goes under root 1's positive
     * branch, so a parent population found under root 0's cut would show.
     */
    static GateTree tree(GateNode child) {
        GateTree tree = new GateTree();
        tree.setReferenceSlideId("ref");
        GateNode a = new GateNode("CD8", 10);
        GateNode b = new GateNode("CD8", 20);
        for (GateNode g : List.of(a, b)) g.setStatistic(Statistic.MEAN);
        b.getBranches().get(0).getChildren().add(child);
        tree.addRoot(a);
        tree.addRoot(b);
        return tree;
    }

    @Test
    void aChildSeesEachSlidesOwnParentPopulationAligned() {
        GateNode child = new GateNode("CD4", 5);
        child.setStatistic(Statistic.MEAN);
        GateTree tree = tree(child);
        List<CohortCurves.SlideValues> curves = CohortCurves.of(tree, child,
                List.of(sample("ref", true), sample("s1", true), sample("nocd4", false)),
                (slide, col) -> "s1".equals(slide) ? SHIFT : null, "s1");

        assertEquals(List.of("ref", "s1"), curves.stream().map(CohortCurves.SlideValues::slideId).toList(),
                "a slide lacking the channel is left out");
        assertEquals(20, curves.get(0).x().length, "ref: root 1 (CD8 >= 20) passes cells 20..39, not root 0's 30");
        long s1Parent = IntStream.range(0, 40).filter(i -> i >= SHIFT.apply(20)).count();
        assertTrue(s1Parent > 0 && s1Parent < 20, "the corrected cut leaves a non-empty, smaller parent: " + s1Parent);
        assertEquals(s1Parent, curves.get(1).x().length, "s1: root 1's CORRECTED cut, not root 0's");
        assertTrue(curves.get(1).current());
        assertFalse(curves.get(0).current());
        double[] expected = IntStream.range(0, 40).filter(i -> i >= SHIFT.apply(20))
                .mapToDouble(i -> SHIFT.inverse(2.0 * i)).toArray();
        assertArrayEquals(expected, curves.get(1).x(), 1e-9, "values drawn in reference units");
        assertNull(curves.get(1).y());
    }

    @Test
    void aTwoAxisGateGetsPairedFiniteAlignedValues() {
        RectangleGate child = new RectangleGate("CD8", "CD4", 0, 100, 0, 100);
        child.setStatisticX(Statistic.MEAN);
        child.setStatisticY(Statistic.MEAN);
        GateTree tree = tree(child);
        // CD4 absent on every third cell: those cells have no Y and must drop out of X too.
        List<CohortCurves.SlideValues> curves = CohortCurves.of(tree, child,
                List.of(sample("s1", true, i -> i % 3 == 0)),
                (slide, col) -> "s1".equals(slide) ? SHIFT : null, "ref");

        assertEquals(1, curves.size());
        assertFalse(curves.get(0).current(), "the open slide is not sampled here");
        int[] kept = IntStream.range(0, 40).filter(i -> i >= SHIFT.apply(20) && i % 3 != 0).toArray();
        assertTrue(kept.length > 0);
        assertArrayEquals(IntStream.of(kept).mapToDouble(SHIFT::inverse).toArray(), curves.get(0).x(), 1e-9);
        assertArrayEquals(IntStream.of(kept).mapToDouble(i -> SHIFT.inverse(2.0 * i)).toArray(), curves.get(0).y(), 1e-9);
    }
}
