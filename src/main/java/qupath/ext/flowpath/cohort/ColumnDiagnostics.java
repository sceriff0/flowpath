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
 * @param otsuDiscordance      Harris et al. 2022's discordance after correction; NaN when not judged
 * @param shiftOutlier         the log shift is over 3 x max(MAD, 0.05) from the cohort median
 * @param cohortMedianLogShift that median; NaN when fewer than three corrected slides
 * @param peakPicked           this (non-reference) slide has a hand-picked negative peak
 */
public record ColumnDiagnostics(int usable, int outsideDomain, double outsideFraction, boolean tooFew,
                                boolean peakLock, double detectorLogShift, double otsuDiscordance,
                                boolean shiftOutlier, double cohortMedianLogShift, boolean peakPicked) {}
