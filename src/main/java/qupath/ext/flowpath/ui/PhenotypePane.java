package qupath.ext.flowpath.ui;

import javafx.scene.layout.BorderPane;
import qupath.ext.flowpath.engine.PhenotypePathClassAssigner;
import qupath.ext.flowpath.engine.ReconciliationController;
import qupath.ext.flowpath.model.CellPhenotype;
import qupath.ext.flowpath.model.PhenotypeStats;
import qupath.ext.flowpath.model.PhenotypeTree;
import qupath.ext.flowpath.model.ReconciliationQueue;
import qupath.lib.objects.PathObject;

import java.util.List;

/** Container composing the Phenotype Tree, Reconciliation, and Stats panes (spec §7). */
public class PhenotypePane extends BorderPane {

    private final PhenotypeTree tree;
    private final ReconciliationController controller;
    private final PhenotypeTreePane treePane;
    private final ReconciliationPane reconciliationPane;
    private final PhenotypeStatsPane statsPane;
    private int worklistSize;

    public PhenotypePane(PhenotypeTree tree) {
        this.tree = tree;
        this.controller = new ReconciliationController(tree);
        this.treePane = new PhenotypeTreePane(tree);
        this.reconciliationPane = new ReconciliationPane(tree, controller);
        this.statsPane = new PhenotypeStatsPane();
        setLeft(treePane);
        setCenter(reconciliationPane);
        setRight(statsPane);
    }

    public void load(List<CellPhenotype> cells, List<PathObject> objects) {
        PhenotypePathClassAssigner.assign(objects, cells, tree);
        tree.recomputeCounts(cells);
        treePane.refreshCounts();
        statsPane.update(PhenotypeStats.compute(cells));
        List<CellPhenotype> worklist = ReconciliationQueue.buildWorklist(cells, false);
        worklistSize = worklist.size();
        reconciliationPane.setItems(worklist);
    }

    public int worklistSize() { return worklistSize; }
}
