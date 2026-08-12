package qupath.ext.flowpath.umap.engine;

import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.MarkerSelection;
import qupath.lib.objects.PathObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The markers one UMAP run is allowed to see — the include flag's single authoritative
 * consumer.
 *
 * <h2>The hole this closes</h2>
 * {@code MarkerSelection.isIncluded} had three readers in the whole of {@code src/main}
 * and not one of them filtered anything: a label counter, the checkbox that wrote it, and
 * a selection-seeding copy. {@link CellIndex#build} consults the selection only to choose
 * each marker's compartment and statistic, never to drop a column, and the matrix builder
 * emitted every column it held. Unticking a marker in <em>Features…</em> changed one
 * label and nothing else — <b>the embedding always ran on the whole panel</b>, which is
 * the same silent-plausible-wrong shape as the z-score-of-zero and the display/gating
 * divergence before it: nothing throws, a picture appears, and it is a picture of a
 * question the user did not ask.
 *
 * <h2>Why this is a property of the request, not of the index</h2>
 * Inclusion is not a fact about the cells; it is a fact about <em>this run</em>. Baking
 * it into {@link CellIndex} would mean every tick and untick rebuilt the index off-thread
 * — a full re-read of the measurement maps to change which of the columns already in
 * memory get read — and would fork the shared index into a gating flavour and a UMAP
 * flavour. The index stays the whole panel (the marker overlay, the CSV export and the
 * gating tree all still need every column); this type is the window the embedding looks
 * through.
 *
 * <h2>Why an excluded marker now cannot reach the embedding</h2>
 * Not by convention. {@link UmapComputeService#compute} takes an {@code EmbeddingFeatures}
 * and nothing else — there is no overload that accepts a bare {@link CellIndex}, and
 * {@code CellIndex} no longer knows how to build an embedding matrix at all. Every column
 * the run reads comes from {@link Selected#column(int)}, {@link Selected#toMatrix()} or
 * {@link Selected#subMatrix(int[], double[])}, and none of them can address a marker the
 * selection excluded: the excluded columns are not in {@code Selected}'s index map. To
 * embed an unticked marker you would have to add a new way of reading columns, not merely
 * forget a filter.
 *
 * <h2>The edges are a type, not a check</h2>
 * A selection with nothing ticked, or exactly one thing ticked, is not an embedding with
 * a degenerate result; it is not an embedding. {@link #of} therefore returns
 * {@link Refused} for those, and {@code compute} turns a {@code Refused} into
 * {@link UmapOutcome.Failed} through the one terminal seam. The embedding body is typed
 * to {@link Selected}, so a refusal cannot reach it even if a future caller forgets to
 * look: there is no cast from one to the other.
 *
 * <p>A <em>nearly</em>-degenerate selection — two markers ticked, both constant — is a
 * different thing and stays on the existing mechanism: it embeds, and
 * {@link EmbeddingReport#constantMarkers()} says so. Only the counts that make a
 * neighbour graph impossible are refused here.
 */
public sealed interface EmbeddingFeatures {

    /**
     * The fewest ticked markers an embedding means anything over. One feature is a number
     * line, and a 2-D layout of a number line is an artefact of the optimiser rather than
     * a picture of the data; zero is not a space at all.
     */
    int MINIMUM_FEATURES = 2;

    /**
     * Resolve the markers of {@code source} against the user's feature picker.
     *
     * @param source    the index the run would embed; every column stays reachable
     *                  through it for the overlay, the export and the gating tree
     * @param selection the picker's state. A marker with no entry is included — that is
     *                  {@link MarkerSelection}'s own default and the legacy behaviour for
     *                  images saved before the picker existed
     * @return {@link Selected} when at least {@link #MINIMUM_FEATURES} markers survive,
     *         {@link Refused} otherwise
     */
    static EmbeddingFeatures of(CellIndex source, MarkerSelection selection) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(selection, "selection");

        String[] panel = source.getMarkerNames();
        List<Integer> columns = new ArrayList<>(panel.length);
        List<String> excluded = new ArrayList<>(0);
        for (int j = 0; j < panel.length; j++) {
            if (selection.isIncluded(panel[j])) columns.add(j);
            else excluded.add(panel[j]);
        }

        if (columns.size() < MINIMUM_FEATURES) {
            return new Refused(refusalFor(columns.size(), panel.length));
        }

        int[] sourceColumns = new int[columns.size()];
        String[] names = new String[columns.size()];
        for (int f = 0; f < sourceColumns.length; f++) {
            sourceColumns[f] = columns.get(f);
            names[f] = panel[sourceColumns[f]];
        }
        return new Selected(source, sourceColumns, names, List.copyOf(excluded));
    }

    /**
     * Which of {@code panel} the picker leaves in, in panel order.
     * <p>
     * Shared with {@link #of} rather than reimplemented so the "N markers" the empty-state
     * label promises and the columns the run actually reads cannot disagree. Two readers
     * of one rule, not two rules.
     */
    static List<String> includedMarkers(List<String> panel, MarkerSelection selection) {
        Objects.requireNonNull(selection, "selection");
        List<String> out = new ArrayList<>(panel.size());
        for (String marker : panel) {
            if (selection.isIncluded(marker)) out.add(marker);
        }
        return out;
    }

    private static String refusalFor(int included, int panelSize) {
        if (panelSize == 0) {
            return "No markers are available to embed.";
        }
        if (included == 0) {
            return String.format(Locale.US,
                    "No markers are selected for the embedding — all %,d were unticked in "
                            + "the feature picker. UMAP needs at least %d.",
                    panelSize, MINIMUM_FEATURES);
        }
        return String.format(Locale.US,
                "Only %,d of %,d markers is selected for the embedding, and UMAP needs at "
                        + "least %d — a single feature has no two-dimensional layout.",
                included, panelSize, MINIMUM_FEATURES);
    }

    /**
     * A feature set that cannot be embedded, carrying the sentence the user should read.
     * <p>
     * Deliberately not an exception: the caller of {@code compute} has already shown a
     * busy state, and this has to arrive through the same terminal channel as every other
     * ending or that state is never cleared.
     */
    record Refused(String reason) implements EmbeddingFeatures {
        public Refused {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * The columns one run may read, and the only shape of feature set the embedding body
     * accepts.
     * <p>
     * Holds the source index by reference and its columns by index — no copy. That is
     * {@link CellIndex}'s own no-copy contract, for the same reason: a 5M-cell, 40-marker
     * slide is 1.6 GB of doubles, and this type exists to narrow the view of them, not to
     * duplicate them.
     */
    final class Selected implements EmbeddingFeatures {

        private final CellIndex source;
        private final int[] sourceColumns;
        private final String[] names;
        private final List<String> excluded;

        private Selected(CellIndex source, int[] sourceColumns, String[] names,
                         List<String> excluded) {
            this.source = source;
            this.sourceColumns = sourceColumns;
            this.names = names;
            this.excluded = excluded;
        }

        /** Cells in the population being embedded — every cell, included or not. */
        public int cellCount() {
            return source.size();
        }

        /** How many markers the embedding will actually be computed over. */
        public int featureCount() {
            return sourceColumns.length;
        }

        /** The ticked markers, in panel order. A fresh array; callers may keep it. */
        public String[] featureNames() {
            return names.clone();
        }

        /** The markers the picker left out, in panel order. Never null; often empty. */
        public List<String> excludedMarkers() {
            return excluded;
        }

        /**
         * The backing PathObject array of the source index — <b>read-only</b>, per
         * {@link CellIndex#getObjects()}'s contract.
         */
        public PathObject[] objects() {
            return source.getObjects();
        }

        /**
         * The backing value column for feature {@code f} — <b>read-only, do not mutate</b>.
         * <p>
         * {@code f} indexes the <em>ticked</em> markers, not the panel: an excluded
         * marker's column has no {@code f} and so cannot be asked for. Not a defensive
         * copy, for the reason {@link CellIndex#getMarkerValuesRaw(int)} gives.
         */
        public double[] column(int f) {
            return source.getMarkerValuesRaw(sourceColumns[f]);
        }

        /**
         * Every feature column, gathered once — <b>read-only</b>. Convenience for the
         * projection stage, which reads all of them per query cell.
         */
        public double[][] columns() {
            double[][] out = new double[sourceColumns.length][];
            for (int f = 0; f < sourceColumns.length; f++) out[f] = column(f);
            return out;
        }

        /**
         * The full training matrix: {@code [cell][feature]}, with each NaN replaced by the
         * mean of the values its column does carry, so a missing measurement lands at the
         * centre of its distribution instead of poisoning every distance to that cell.
         * <p>
         * This is the method {@code CellIndex.toMatrix} used to be. It moved here because
         * "turn cells into UMAP input" is not something a shared columnar index should
         * know how to do over columns the user excluded — while it lived there, filtering
         * was something a caller had to remember, and no caller did.
         */
        public double[][] toMatrix() {
            int n = cellCount();
            int m = featureCount();
            double[][] matrix = new double[n][m];
            for (int f = 0; f < m; f++) {
                double[] col = column(f);
                double mean = meanOf(col, null, n);
                for (int i = 0; i < n; i++) {
                    double v = col[i];
                    matrix[i][f] = Double.isNaN(v) ? mean : v;
                }
            }
            return matrix;
        }

        /**
         * The training matrix restricted to {@code rows} of the source index, with NaNs
         * imputed from the sampled cells only.
         *
         * @param rows  source-index rows the layout will train on
         * @param means filled with the per-feature imputation mean, for reuse when the
         *              held-out cells are projected into the same frame. Must have
         *              {@link #featureCount()} slots
         */
        public double[][] subMatrix(int[] rows, double[] means) {
            int m = featureCount();
            if (means.length != m) {
                throw new IllegalArgumentException("means has " + means.length
                        + " slots for " + m + " features");
            }
            double[][] matrix = new double[rows.length][m];
            for (int f = 0; f < m; f++) {
                double[] col = column(f);
                double mean = meanOf(col, rows, rows.length);
                means[f] = mean;
                for (int r = 0; r < rows.length; r++) {
                    double v = col[rows[r]];
                    matrix[r][f] = Double.isNaN(v) ? mean : v;
                }
            }
            return matrix;
        }

        /** Mean of the non-NaN entries of {@code col} over {@code rows} (null = all). */
        private static double meanOf(double[] col, int[] rows, int count) {
            double sum = 0;
            int measured = 0;
            for (int r = 0; r < count; r++) {
                double v = col[rows == null ? r : rows[r]];
                if (Double.isNaN(v)) continue;
                sum += v;
                measured++;
            }
            return measured > 0 ? sum / measured : 0.0;
        }
    }
}
