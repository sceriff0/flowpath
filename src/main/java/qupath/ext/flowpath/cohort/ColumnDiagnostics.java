package qupath.ext.flowpath.cohort;

/**
 * What {@link AlignmentModel#build} measured about one slide x column while aligning it: the raw
 * facts the problem layer (spec §3) turns into flags. Nothing here ever moves a threshold.
 *
 * @param usable               clean cells whose value is inside the {@link qupath.ext.flowpath.model.cohort.LogScale}'s domain
 * @param outsideDomain        clean cells with a finite raw value outside that domain (below 1 on ln)
 * @param outsideFraction      {@code outsideDomain / (usable + outsideDomain)}; 0 when both are 0
 * @param tooFew               this slide or the reference has fewer than {@link AlignmentModel#MIN_USABLE}
 *                             usable values (or the column has no usable range): left uncorrected (spec departure 5)
 * @param peakLock             automatic shift and the detector's L1 shift disagree by more than ln 1.5
 * @param detectorLogShift     {@code L1_slide - L1_reference}; NaN when either is missing
 * @param otsuDiscordance      Harris et al. 2022's discordance after correction
 *                             [FULL: main text incl. MathML equations; figure images not viewed]: the fraction (0–1) of
 *                             the slide's cells its own and the pooled Otsu thresholds classify differently; NaN when not judged
 * @param shiftOutlier         the log shift is over 3 x max(MAD, 0.05) from the cohort median
 * @param cohortMedianLogShift that median; NaN when fewer than three corrected slides
 * @param peakPicked           this (non-reference) slide has a hand-picked negative peak
 * @param pickProblem          why that pick could not be used, leaving the slide uncorrected; {@link PickProblem#NONE}
 *                             when it was used or there is none
 */
public record ColumnDiagnostics(int usable, int outsideDomain, double outsideFraction, boolean tooFew,
                                boolean peakLock, double detectorLogShift, double otsuDiscordance,
                                boolean shiftOutlier, double cohortMedianLogShift, boolean peakPicked,
                                PickProblem pickProblem) {

    /**
     * Why a slide's hand-picked peak was not used (landmark mode needs both its own peak and the
     * reference's on the log scale). Each leaves the slide uncorrected, never silently: the review
     * names it ({@code ReviewScorer.pickUnusedReason}).
     */
    public enum PickProblem {
        NONE,
        /** The slide's pick is outside the current scale's domain (below 1 on ln, below 0 on ln(x + 1)). */
        SLIDE_PICK_OUTSIDE_SCALE,
        /** The reference's own pick is outside the current scale's domain. */
        REFERENCE_PICK_OUTSIDE_SCALE,
        /** The reference has no detected negative peak and no pick to pair the slide's pick with. */
        NO_REFERENCE_PEAK
    }

    public ColumnDiagnostics {
        pickProblem = pickProblem == null ? PickProblem.NONE : pickProblem;
    }

    /** With no pick problem. */
    public ColumnDiagnostics(int usable, int outsideDomain, double outsideFraction, boolean tooFew,
                             boolean peakLock, double detectorLogShift, double otsuDiscordance,
                             boolean shiftOutlier, double cohortMedianLogShift, boolean peakPicked) {
        this(usable, outsideDomain, outsideFraction, tooFew, peakLock, detectorLogShift, otsuDiscordance,
                shiftOutlier, cohortMedianLogShift, peakPicked, PickProblem.NONE);
    }
}
