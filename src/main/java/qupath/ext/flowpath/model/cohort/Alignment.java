package qupath.ext.flowpath.model.cohort;

/**
 * One slide's staining correction for one column: UniFORM's shift in log space, i.e. one
 * multiplicative factor (Wang et al. 2025, normalization.py:258-266 [FULL]: x_norm = x * exp(-shift)).
 * {@link #apply} carries a reference-slide threshold onto this slide (x * factor), {@link #inverse}
 * brings this slide's values into reference units. {@link Kind#AUTO} is UniFORM's automatic mode,
 * {@link Kind#LANDMARK} its landmark mode (a hand-picked negative peak). Identity returns its input
 * exactly, so an uncorrected number never drifts.
 */
public final class Alignment {

    public enum Kind { IDENTITY, AUTO, LANDMARK }

    private static final Alignment IDENTITY = new Alignment(Kind.IDENTITY, 0, 0.0);

    private final Kind kind;
    private final int shiftBins;
    private final double binWidth;
    private final double factor;

    private Alignment(Kind kind, int shiftBins, double binWidth) {
        this.kind = kind;
        this.shiftBins = shiftBins;
        this.binWidth = binWidth;
        this.factor = kind == Kind.IDENTITY ? 1.0 : Math.exp(shiftBins * binWidth);
    }

    public static Alignment identity() { return IDENTITY; }

    public static Alignment auto(int shiftBins, double binWidth) { return new Alignment(Kind.AUTO, shiftBins, binWidth); }

    public static Alignment landmark(int shiftBins, double binWidth) { return new Alignment(Kind.LANDMARK, shiftBins, binWidth); }

    public double apply(double referenceRaw) {
        return kind == Kind.IDENTITY ? referenceRaw : referenceRaw * factor;
    }

    public double inverse(double slideRaw) {
        return kind == Kind.IDENTITY ? slideRaw : slideRaw / factor;
    }

    public Kind kind() { return kind; }
    public int shiftBins() { return shiftBins; }
    public double binWidth() { return binWidth; }
    /** shiftBins * binWidth, natural-log units; 0 for identity. */
    public double logShift() { return shiftBins * binWidth; }
    public double factor() { return factor; }
}
