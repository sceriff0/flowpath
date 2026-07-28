package qupath.ext.flowpath.model;

import java.util.LinkedHashMap;
import java.util.Map;

/** Per-cell phenotyping state read from a QuPath detection's measurements map. */
public final class CellPhenotype {

    private static final String PHENO_SCORE_PREFIX = "pheno_score: ";
    private static final String STATE_PREFIX = "state: ";
    private static final String P_NEG_SUFFIX = ": p_neg";
    private static final String P_POS_SUFFIX = ": p_pos";

    private long label;
    private PhenotypeOutcome outcome;
    private String committed;
    private Provenance provenance;
    private int emptyType;
    private int violatedConstraintId;
    private int nCandidates;
    private final Map<String, Double> phenoScores = new LinkedHashMap<>();
    private final Map<String, Double> pNeg = new LinkedHashMap<>();
    private final Map<String, Double> pPos = new LinkedHashMap<>();
    private final Map<String, Integer> states = new LinkedHashMap<>();

    private CellPhenotype() {}

    public static CellPhenotype fromMeasurements(Map<String, Number> m, String classificationName,
                                                 PhenotypeTree tree) {
        CellPhenotype c = new CellPhenotype();
        c.label = (long) num(m, "label", -1);
        c.nCandidates = (int) num(m, "n_candidates", 0);
        c.emptyType = (int) num(m, "empty_type", 0);
        c.violatedConstraintId = (int) num(m, "violated_constraint_id", -1);
        c.provenance = Provenance.fromCode(num(m, "provenance", 0));
        boolean named = tree != null && classificationName != null && tree.findByName(classificationName) != null;
        c.outcome = PhenotypeOutcome.fromEvidence(c.nCandidates, c.emptyType, named);
        c.committed = classificationName;

        for (Map.Entry<String, Number> e : m.entrySet()) {
            String k = e.getKey();
            if (e.getValue() == null) continue;
            double v = e.getValue().doubleValue();
            if (k.startsWith(PHENO_SCORE_PREFIX)) {
                c.phenoScores.put(k.substring(PHENO_SCORE_PREFIX.length()), v);
            } else if (k.startsWith(STATE_PREFIX)) {
                c.states.put(k.substring(STATE_PREFIX.length()), (int) v);
            } else if (k.endsWith(P_NEG_SUFFIX)) {
                c.pNeg.put(k.substring(0, k.length() - P_NEG_SUFFIX.length()), v);
            } else if (k.endsWith(P_POS_SUFFIX)) {
                c.pPos.put(k.substring(0, k.length() - P_POS_SUFFIX.length()), v);
            }
        }
        return c;
    }

    private static double num(Map<String, Number> m, String key, double fallback) {
        Number n = m.get(key);
        return n != null ? n.doubleValue() : fallback;
    }

    public long getLabel() { return label; }
    public PhenotypeOutcome getOutcome() { return outcome; }
    public String getCommitted() { return committed; }
    public Provenance getProvenance() { return provenance; }
    public int getEmptyType() { return emptyType; }
    public int getViolatedConstraintId() { return violatedConstraintId; }
    public int getNCandidates() { return nCandidates; }
    public Map<String, Double> getPhenoScores() { return phenoScores; }
    public double pNeg(String marker) { return pNeg.getOrDefault(marker, Double.NaN); }
    public double pPos(String marker) { return pPos.getOrDefault(marker, Double.NaN); }
    public Integer state(String marker) { return states.get(marker); }

    // Public mutators used by ReconciliationController (Task 14, in package `engine`)
    // and by add_cycle carry-forward (Task 21, in package `io`).
    public void setCommitted(String committed) { this.committed = committed; }
    public void setProvenance(Provenance provenance) { this.provenance = provenance; }
    public void setOutcome(PhenotypeOutcome outcome) { this.outcome = outcome; }
}
