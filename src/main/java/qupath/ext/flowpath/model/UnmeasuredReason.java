package qupath.ext.flowpath.model;

/**
 * Why a gate could not judge a cell — counted apart, because each one points somewhere else:
 * a missing value at the export, a failed imaging round at QC, a Skip at the reviewer.
 */
public enum UnmeasuredReason {
    /** The axis reads NaN, or the gate's channel is not in this export. */
    NO_VALUE,
    /** The cell's imaging round for this gate's marker failed round QC. */
    ROUND_QC,
    /** This slide's review answered Skip for this gate. */
    SKIPPED
}
