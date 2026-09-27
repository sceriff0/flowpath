package qupath.ext.flowpath.model.cohort;

import java.util.Arrays;

/**
 * The one cohort-level median (pre-flight ruling C6): mean of the two middle values for an
 * even count, the middle value for an odd one. {@link AlignmentModel} uses it for the
 * per-column cofactor and for the shift/stretch spread that flags unusual staining; later
 * consumers ({@code MarkerRules}, {@code CohortSession.mostTypical}) are told to call this
 * rather than keep their own copy — three divergent copies of "median" is exactly the kind
 * of duplicated-predicate defect this codebase's invariants exist to rule out.
 */
public final class CohortStats {

    private CohortStats() {}

    /** The ordinary median of {@code values}; NaN for an empty array. Does not mutate its input. */
    public static double median(double[] values) {
        if (values.length == 0) return Double.NaN;
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int m = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[m] : (sorted[m - 1] + sorted[m]) / 2;
    }

    /** The median absolute deviation of {@code values} from {@code median}: {@code median(|v - median|)}. */
    public static double mad(double[] values, double median) {
        double[] deviations = new double[values.length];
        for (int i = 0; i < values.length; i++) deviations[i] = Math.abs(values[i] - median);
        return median(deviations);
    }
}
