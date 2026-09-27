package qupath.ext.flowpath.engine;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.testing.Cells;
import qupath.lib.objects.classes.PathClass;

import static org.junit.jupiter.api.Assertions.*;

class PhenotypeClassWriterTest {

    @Test
    void everyCellGetsItsPhenotypeClassAndASecondApplyChangesNothing() {
        CellIndex index = Cells.of(6).marker("CD3", i -> i).area(i -> i == 0 ? 1.0 : 100.0).build();
        GateTree tree = new GateTree();
        GateNode a = new GateNode("CD3", 3.0);
        a.setStatistic(Statistic.MEAN);
        GateNode b = new GateNode("CD3", 5.0);
        b.setStatistic(Statistic.MEAN);
        tree.addRoot(a);
        tree.addRoot(b);
        tree.getQualityFilter().setMinArea(50);
        MarkerStats stats = GatingEngine.recomputeStats(index, tree.getQualityFilter(), null);
        GatingEngine.AssignmentResult result = GatingEngine.assignAll(tree, index, stats);

        assertTrue(PhenotypeClassWriter.apply(result, index, -1));
        assertSame(PathClass.fromString("Excluded"), index.getObject(0).getPathClass());
        for (int i = 1; i < 6; i++) {
            assertSame(PathClass.fromString(result.getPhenotypes()[i]), index.getObject(i).getPathClass());
        }
        assertFalse(PhenotypeClassWriter.apply(result, index, -1), "nothing left to change");
    }
}
