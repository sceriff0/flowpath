package qupath.ext.flowpath.model.cohort;

/**
 * A monotone map from reference-slide units to one slide's units for one column: an increasing
 * affine map g(u) = stretch * u + offset in asinh space, i.e. f(x) = c * sinh(g(asinh(x / c))).
 * Identity returns its input exactly (no asinh round trip), so an uncorrected number never drifts.
 */
public final class Alignment {

    public enum Kind { IDENTITY, SHIFT, TWO_LANDMARK }

    private static final Alignment IDENTITY = new Alignment(Kind.IDENTITY, 1.0, 1.0, 0.0, 0.0);

    private final Kind kind;
    private final double cofactor;
    private final double stretch;
    private final double offset;
    private final double shift;

    private Alignment(Kind kind, double cofactor, double stretch, double offset, double shift) {
        this.kind = kind;
        this.cofactor = cofactor;
        this.stretch = stretch;
        this.offset = offset;
        this.shift = shift;
    }

    public static Alignment identity() {
        return IDENTITY;
    }

    public static Alignment between(Landmarks reference, Landmarks slide) {
        if (!reference.hasL1() || !slide.hasL1()) return IDENTITY;
        double c = reference.cofactor();
        double shift = slide.l1() - reference.l1();
        if (reference.hasL2() && slide.hasL2()) {
            double refSpan = reference.l2() - reference.l1();
            double slideSpan = slide.l2() - slide.l1();
            if (refSpan > 0 && slideSpan > 0) {
                double stretch = slideSpan / refSpan;
                return new Alignment(Kind.TWO_LANDMARK, c, stretch, slide.l1() - stretch * reference.l1(), shift);
            }
        }
        return new Alignment(Kind.SHIFT, c, 1.0, shift, shift);
    }

    /** Reference units → this slide's units. */
    public double apply(double referenceRaw) {
        if (kind == Kind.IDENTITY) return referenceRaw;
        return Landmarks.sinh(stretch * Landmarks.asinh(referenceRaw, cofactor) + offset, cofactor);
    }

    /** This slide's units → reference units. */
    public double inverse(double slideRaw) {
        if (kind == Kind.IDENTITY) return slideRaw;
        return Landmarks.sinh((Landmarks.asinh(slideRaw, cofactor) - offset) / stretch, cofactor);
    }

    public Kind kind() { return kind; }
    public double cofactor() { return cofactor; }
    public double stretch() { return stretch; }
    /** The asinh-space offset of g(u) = stretch * u + offset; 0 for identity. */
    public double offset() { return offset; }
    /** Slide L1 minus reference L1, asinh units; 0 for identity. */
    public double shift() { return shift; }
}
