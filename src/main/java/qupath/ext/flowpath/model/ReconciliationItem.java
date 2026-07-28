package qupath.ext.flowpath.model;

import qupath.ext.flowpath.engine.DecisiveChannels;
import java.util.List;

/** One queued cell with everything the ReconciliationPane needs to render it. */
public record ReconciliationItem(CellPhenotype cell, List<Candidate> candidates,
                                 String constraintLabel, List<String> decisiveChannels) {

    public static ReconciliationItem of(CellPhenotype cell, PhenotypeTree tree) {
        return new ReconciliationItem(cell, cell.candidates(),
                cell.violatedConstraintLabel(tree),
                DecisiveChannels.forCandidates(cell.candidateNames(), tree));
    }
}
