package qupath.ext.flowpath.model;

import java.util.Locale;

/**
 * The result of cross-checking the µm centroid measurements against the image's own
 * pixel calibration — <b>the one consistency check only FlowPath can perform</b>.
 * <p>
 * MIRAGE scales every µm measurement it writes ({@code Centroid X/Y µm},
 * {@code Area µm²}, {@code Perimeter µm}, the axis lengths) by a single static Nextflow
 * parameter, {@code params.pixel_size} (default {@code 0.325},
 * {@code nextflow.config}). That parameter is <em>not</em> auto-detected:
 * {@code bin/preprocess.py} does parse {@code PhysicalSizeX} out of the input OME-XML,
 * but never feeds it back into {@code params.pixel_size}. A mis-set value therefore
 * scales every µm measurement uniformly and silently, and nothing inside MIRAGE can
 * notice — the same wrong number is also stamped into the pyramid's OME
 * {@code PhysicalSizeX/Y} ({@code modules/local/merge_and_pyramid.nf}), so the file
 * agrees with itself.
 * <p>
 * FlowPath is the only place that holds all three at once: the pyramid's
 * {@link qupath.lib.images.servers.PixelCalibration}, the pixel-space ROI, and the µm
 * centroid measurement. If the µm centroids do not equal {@code roiCentroid ×
 * pixelWidthMicrons}, then either {@code params.pixel_size} or the pyramid metadata is
 * wrong, and every downstream micrometre is off by the same factor.
 * <p>
 * Immutable. {@code observedMicronsPerPixel} and {@code expectedMicronsPerPixel} are
 * meaningful only for {@link Status#AGREE} and {@link Status#DISAGREE}; they are
 * {@code NaN} otherwise.
 *
 * @param status                   what could be concluded
 * @param observedMicronsPerPixel  µm/px implied by the exported centroids
 * @param expectedMicronsPerPixel  µm/px the image's own calibration declares
 * @param sampledCells             how many cells contributed to the comparison
 */
public record ScaleVerdict(Status status,
                           double observedMicronsPerPixel,
                           double expectedMicronsPerPixel,
                           int sampledCells) {

    /** What the cross-check was able to conclude. */
    public enum Status {
        /** Centroids and calibration agree within tolerance. */
        AGREE,
        /** They disagree — suspect {@code params.pixel_size} or the pyramid metadata. */
        DISAGREE,
        /** The image reports no pixel size, so there is nothing to check against. */
        NO_CALIBRATION,
        /** No usable µm centroid measurement, so there is nothing to check. */
        NO_MEASUREMENT
    }

    public static ScaleVerdict agree(double observed, double expected, int sampled) {
        return new ScaleVerdict(Status.AGREE, observed, expected, sampled);
    }

    public static ScaleVerdict disagree(double observed, double expected, int sampled) {
        return new ScaleVerdict(Status.DISAGREE, observed, expected, sampled);
    }

    public static ScaleVerdict noCalibration() {
        return new ScaleVerdict(Status.NO_CALIBRATION, Double.NaN, Double.NaN, 0);
    }

    public static ScaleVerdict noMeasurement() {
        return new ScaleVerdict(Status.NO_MEASUREMENT, Double.NaN, Double.NaN, 0);
    }

    /** True only for {@link Status#DISAGREE} — the one state worth interrupting a user for. */
    public boolean isDisagreement() {
        return status == Status.DISAGREE;
    }

    /**
     * How far off the observed scale is, as a fraction of the expected one
     * ({@code 1.0} == wrong by a factor of two). {@code NaN} when no comparison was made.
     */
    public double relativeError() {
        if (Double.isNaN(observedMicronsPerPixel) || Double.isNaN(expectedMicronsPerPixel)
                || expectedMicronsPerPixel == 0) {
            return Double.NaN;
        }
        return Math.abs(observedMicronsPerPixel - expectedMicronsPerPixel)
                / Math.abs(expectedMicronsPerPixel);
    }

    /**
     * One line a user can act on. {@code Locale.US} throughout — the JVM default here is
     * {@code en_IT}, whose decimal comma would make {@code 0,3250 µm/px} read as two
     * numbers.
     */
    public String describe() {
        return switch (status) {
            case AGREE -> String.format(Locale.US,
                    "pixel size verified (%.4f µm/px, %d cells)",
                    expectedMicronsPerPixel, sampledCells);
            case DISAGREE -> String.format(Locale.US,
                    "SCALE MISMATCH: centroids imply %.4f µm/px, image says %.4f µm/px "
                            + "(%.0f%% off, %d cells) — check MIRAGE params.pixel_size",
                    observedMicronsPerPixel, expectedMicronsPerPixel,
                    relativeError() * 100, sampledCells);
            case NO_CALIBRATION -> "pixel size unverified (image has no calibration)";
            case NO_MEASUREMENT -> "pixel size unverified (no µm centroid measurement)";
        };
    }
}
