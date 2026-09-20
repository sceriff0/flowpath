package qupath.ext.flowpath.testing;

import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.Statistic;

/**
 * Small {@link GateTree} fixtures shared across test files that would otherwise each grow
 * their own near-identical copy. {@code CsvExportJobTest} and {@code CsvExportCoordinatorTest}
 * both built a two-root CD3/CD8 threshold tree byte-for-byte -- one parametrized on the
 * thresholds, one not -- for the CSV export path's own reasons; this is the one copy.
 */
public final class GateTreeFixtures {

    private GateTreeFixtures() {}

    /** Two enabled threshold roots, CD3 then CD8, mean statistic, at the given thresholds. */
    public static GateTree twoRootsOnCd3AndCd8(double cd3Threshold, double cd8Threshold) {
        GateNode cd3Root = new GateNode("CD3", cd3Threshold);
        cd3Root.setStatistic(Statistic.MEAN);
        GateNode cd8Root = new GateNode("CD8", cd8Threshold);
        cd8Root.setStatistic(Statistic.MEAN);

        GateTree tree = new GateTree();
        tree.addRoot(cd3Root);
        tree.addRoot(cd8Root);
        return tree;
    }
}
