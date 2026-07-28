package qupath.ext.flowpath.model;

/** The five-term phenotyping taxonomy (spec §10). */
public enum PhenotypeOutcome {
    PHENOTYPE(null),
    AMBIGUOUS("Ambiguous"),
    CONFLICT("Conflict"),
    ARTEFACT("Artefact"),
    UNCLASSIFIED("Unclassified");

    private final String reservedName;
    PhenotypeOutcome(String reservedName) { this.reservedName = reservedName; }

    /** Reserved QuPath class name for this bucket, or {@code null} for a committed phenotype. */
    public String reservedName() { return reservedName; }

    /** Uncertain ≝ Ambiguous ∪ Conflict — the default reconciliation queue. */
    public boolean isUncertain() { return this == AMBIGUOUS || this == CONFLICT; }

    /** Derive the outcome from the numeric evidence columns (spec §10 "keyed by"). */
    public static PhenotypeOutcome fromEvidence(int nCandidates, int emptyType, boolean named) {
        if (emptyType == 2) return ARTEFACT;
        if (emptyType == 1) return CONFLICT;
        if (nCandidates > 1) return AMBIGUOUS;
        return named ? PHENOTYPE : UNCLASSIFIED;
    }
}
