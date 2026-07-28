package qupath.ext.flowpath.engine;

import qupath.ext.flowpath.model.CellPhenotype;
import qupath.ext.flowpath.model.PhenotypeOutcome;
import qupath.ext.flowpath.model.PhenotypeTree;
import qupath.ext.flowpath.model.Provenance;

import java.util.ArrayDeque;
import java.util.Deque;

/** Applies manual reconciliation commits (spec §7.3 step 4): one PathClass, provenance=manual, undoable. */
public final class ReconciliationController {

    private record CommitAction(CellPhenotype cell, String prevCommitted,
                                Provenance prevProvenance, PhenotypeOutcome prevOutcome) {}

    private final PhenotypeTree tree;
    private final Deque<CommitAction> undoStack = new ArrayDeque<>();

    public ReconciliationController(PhenotypeTree tree) { this.tree = tree; }

    /** Commit a cell to a chosen phenotype (a leaf auto-implies its ancestors via count roll-up). */
    public void commit(CellPhenotype cell, String phenotypeName) {
        undoStack.push(new CommitAction(cell, cell.getCommitted(), cell.getProvenance(), cell.getOutcome()));
        cell.setCommitted(phenotypeName);
        cell.setProvenance(Provenance.MANUAL);
        cell.setOutcome(PhenotypeOutcome.PHENOTYPE);
    }

    public boolean canUndo() { return !undoStack.isEmpty(); }

    public int undoDepth() { return undoStack.size(); }

    public void undo() {
        if (undoStack.isEmpty()) return;
        CommitAction a = undoStack.pop();
        a.cell().setCommitted(a.prevCommitted());
        a.cell().setProvenance(a.prevProvenance());
        a.cell().setOutcome(a.prevOutcome());
    }

    public PhenotypeTree getTree() { return tree; }
}
