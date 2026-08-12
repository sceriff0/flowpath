package qupath.ext.flowpath.umap.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What a successful UMAP run had to degrade in order to succeed.
 * <p>
 * This is {@code ingest/IngestReport} for the embedding stage, and it exists for the
 * same reason. The ingest adapter could once drop a channel, fail to resolve a marker
 * or read a NaN where a number should have been, and the only symptom was an empty
 * histogram — which reads as "no cells are positive" rather than "this axis never
 * resolved". The compute service had exactly that shape of hole, three times over, and
 * all three produced a <em>plausible</em> embedding:
 * <ul>
 *   <li>A cell the projection could find no usable neighbour for was left at
 *       {@code (0,0)}. So was every cell in a run whose subsample came back empty. A
 *       point at the origin is not marked, not excluded and not distinguishable from
 *       real structure — a pile of them reads as a tight cluster of some rare
 *       phenotype. Counted by {@link #cellsAtOrigin()}.</li>
 *   <li>A marker no training cell carried a value for is imputed with its own column
 *       mean, and the matrix builder computes the mean of nothing as
 *       {@code 0.0}. The marker becomes a column of zeros: it contributes nothing to
 *       any distance, the embedding is quietly over fewer features than the user
 *       selected, and nothing says so. Counted by {@link #unmeasuredMarkers()}.</li>
 *   <li>Getting an embedding at all, below {@code EmbeddingInitialisation}'s spectral
 *       limit, costs one cell's real position and a few per cent of the neighbourhood
 *       weights. Carried by {@link #imputedCell()} and {@link #reweightedCells()}.</li>
 * </ul>
 *
 * <h2>Findings versus notes</h2>
 * As in {@code IngestReport}: {@link #findings()} are things the run could not do
 * properly — they make {@link #isClean()} false and earn space in the status bar.
 * {@link #notes()} are things it did that are worth recording but are not defects.
 *
 * <p>Two things sit on the notes side, by the same rule. <b>Subsampling</b> is the
 * default, the user asked for it, and the cells it holds out are placed by a documented
 * rule rather than left undefined. <b>Steering</b> is a note for the same reason only
 * more so: it is not user-configurable at all but unconditional policy for any connected
 * training graph at or below the spectral limit, and where subsampling can place two
 * thirds of the population by projection, steering fabricates exactly one position and
 * perturbs the edge weights — not the positions — of a few per cent of rows. Applying
 * {@code IngestReport}'s own test settles it: a finding is something the stage could
 * <em>not</em> do, and steering is something it did, deliberately. The alternative to
 * steering is not a better embedding; it is a {@code NoClassDefFoundError}.
 *
 * <p>Both still reach {@link #describe()}, which concatenates findings and notes, so
 * the provenance is in the log, the tooltip and anything that later exports it. What
 * they no longer do is spend the status bar's one line, which is reserved for the things
 * that genuinely cast doubt on the picture.
 *
 * <h2>The distinction this stage can represent and the gating cannot</h2>
 * A marker that reaches the embedding as a constant is inert either way — UMAP is
 * distance-based, and a feature with no variance adds nothing to any distance. But the
 * two ways of getting there mean opposite things, exactly as an omitted MIRAGE
 * measurement and a literal {@code 0.0} do:
 * <ul>
 *   <li>{@link #unmeasuredMarkers()} — <em>no</em> training cell carried a value. The
 *       column is a fiction: mean-imputation of an empty set. The marker was never
 *       measured, and the embedding is over fewer features than it appears to be.</li>
 *   <li>{@link #constantMarkers()} — every training cell carried a value and they were
 *       all the same. That is real, known data. The marker is uninformative rather
 *       than absent, which is a fact about the biology (or about the subsample), not
 *       about a broken join.</li>
 * </ul>
 * Both are findings, because a user who selected twelve features and got an embedding
 * over nine deserves to be told either way. They are reported on separate lines so the
 * cause is never guessed at.
 *
 * <h2>Why this is not constructible in one call</h2>
 * The two halves of a report become available at opposite ends of the run. What the
 * training data looked like is known before {@code UMAP.fit}; what the run cost — which
 * node had to be detached, how many cells the projection could not place — is known
 * only after it, by which point the training matrix has deliberately been released for
 * memory. So the report is assembled in two mandatory stages,
 * {@link #training(EmbeddingFeatures.Selected, int[])} then
 * {@link Training#completedWith(Steering, Projection)}, each taking the artefacts of its own
 * end of the run. There is no other constructor, no empty report and no default:
 * a run that reached an embedding cannot report success without saying what that cost,
 * because {@code UmapOutcome.succeeded} will not accept a result without one of these
 * and neither stage can be skipped.
 */
public final class EmbeddingReport {

    /**
     * The layout initialisation the run actually used.
     * <p>
     * Always PCA — that is the whole point of {@code EmbeddingInitialisation}, which
     * exists because the alternative needs an ARPACK native this extension cannot ship.
     * What varies is whether the graph answered PCA on its own or had to be made to.
     */
    public enum Initialisation {
        /** PCA, because the graph handed to SMILE already routed there. Nothing perturbed. */
        PCA,
        /**
         * PCA, reached by detaching one node from a graph SMILE would otherwise have sent
         * to its native spectral layout. One cell's position is imputed rather than
         * optimised, and the rows that listed it carry rewritten edge weights.
         */
        PCA_STEERED_FROM_SPECTRAL
    }

    /**
     * What steering the initialisation cost, as the run's own
     * {@code EmbeddingInitialisation} reports it.
     * <p>
     * The centre and the blast radius travel together by construction. Nothing but
     * detaching a node perturbs a neighbourhood, so a count of reweighted rows without
     * a detached row describes a run that cannot have happened — and the way to stop
     * that understatement being written is to make it unspellable rather than merely
     * discouraged.
     *
     * @param detachedRow    row of the <em>training matrix</em> whose edges were removed,
     *                       or empty when nothing was steered. Translated into the
     *                       caller's cell index by {@link Training#completedWith}, which
     *                       is the only place holding both the row and the subsample
     * @param reweightedRows how many other training rows listed that node and so had
     *                       their distance vector — and therefore their membership
     *                       strengths — rewritten. Zero when nothing was steered
     */
    public record Steering(OptionalInt detachedRow, int reweightedRows) {

        public Steering {
            Objects.requireNonNull(detachedRow, "detachedRow");
            if (reweightedRows < 0) {
                throw new IllegalArgumentException(
                        "reweightedRows must be >= 0, got: " + reweightedRows);
            }
            if (detachedRow.isEmpty() && reweightedRows != 0) {
                throw new IllegalArgumentException(
                        "reweightedRows=" + reweightedRows + " without a detached row");
            }
            if (detachedRow.isPresent() && detachedRow.getAsInt() < 0) {
                throw new IllegalArgumentException(
                        "detachedRow must be >= 0, got: " + detachedRow.getAsInt());
            }
        }

        /** The graph routed to PCA on its own; no node was detached. */
        public static Steering none() {
            return new Steering(OptionalInt.empty(), 0);
        }

        /** One node detached, and the number of rows that consequently changed weight. */
        public static Steering detaching(int detachedRow, int reweightedRows) {
            return new Steering(OptionalInt.of(detachedRow), reweightedRows);
        }

        /** True when a node was detached to force the pure-Java initialisation. */
        public boolean isSteered() {
            return detachedRow.isPresent();
        }
    }

    /**
     * The tally of cells the projection of held-out cells could not place, kept by the
     * stage that fails to place them.
     * <p>
     * A count is the one fact on this report that cannot be derived from an artefact
     * afterwards, because after the fact a cell at {@code (0,0)} because nothing could
     * be blended for it is byte-identical to a cell the optimiser genuinely put there.
     * So it is not a number a caller states: {@link Training#completedWith} takes this
     * object, and the only way to raise it is to be the code doing the placing. From
     * outside this package the sole spelling is {@link #none()}, which says the run
     * projected nothing at all — which the constructor then checks against the subsample.
     */
    public static final class Projection {

        private final AtomicInteger unplaced = new AtomicInteger();

        private Projection() {
        }

        /** The layout was optimised over every cell, so nothing was projected. */
        public static Projection none() {
            return new Projection();
        }

        /** A fresh tally for a projection stage about to run. */
        static Projection tally() {
            return new Projection();
        }

        /**
         * Record one cell no neighbour could be blended for. Atomic because the
         * projection runs as a parallel stream.
         */
        void unplaceable() {
            unplaced.incrementAndGet();
        }

        /** Record {@code cells} at once, for a stage that fails as a whole. */
        void unplaceable(int cells) {
            unplaced.addAndGet(cells);
        }

        /** How many cells this projection left at {@code (0,0)}. */
        public int unplacedCells() {
            return unplaced.get();
        }
    }

    /**
     * The half of the report that is known before the layout runs: how much of the
     * population the embedding is being trained on, and which of its markers carry no
     * usable variation in that training set.
     * <p>
     * Holds no reference to the training matrix. The matrix is released before the
     * projection stage on purpose (peak memory otherwise spans it and the projection's
     * own allocations at once), and a report that pinned it would undo that. It does not
     * need one: degeneracy is read straight off the selected {@code CellIndex} columns,
     * which the run holds anyway.
     */
    public static final class Training {

        private final int totalCells;
        private final int trainedCells;
        private final int markerCount;
        private final List<String> excludedMarkers;
        private final List<String> unmeasuredMarkers;
        private final List<String> constantMarkers;
        /** Read-only; the run's own array, not a copy. Null means "trained on every cell". */
        private final int[] sampleIndices;

        private Training(int totalCells, int trainedCells, int markerCount,
                         List<String> excludedMarkers, List<String> unmeasuredMarkers,
                         List<String> constantMarkers, int[] sampleIndices) {
            this.totalCells = totalCells;
            this.trainedCells = trainedCells;
            this.markerCount = markerCount;
            this.excludedMarkers = excludedMarkers;
            this.unmeasuredMarkers = unmeasuredMarkers;
            this.constantMarkers = constantMarkers;
            this.sampleIndices = sampleIndices;
        }

        /**
         * Close the report with what the run cost, once that is known.
         *
         * @param steering   the initialisation's own account of itself, from
         *                   {@code EmbeddingInitialisation.steering()}
         * @param projection the tally kept by the projection stage, from
         *                   {@code UmapComputeService.projectRemaining}, or
         *                   {@link Projection#none()} when nothing was held out
         * @throws IllegalArgumentException if the two halves cannot describe one run —
         *         a cell parked at the origin when nothing was held out of training, more
         *         parked than were held out, or a detached row outside the training matrix
         */
        public EmbeddingReport completedWith(Steering steering, Projection projection) {
            Objects.requireNonNull(steering, "steering");
            Objects.requireNonNull(projection, "projection");
            OptionalInt imputedCell = OptionalInt.empty();
            if (steering.isSteered()) {
                int row = steering.detachedRow().getAsInt();
                if (row >= trainedCells) {
                    throw new IllegalArgumentException("detached row " + row
                            + " is outside the training matrix of " + trainedCells + " rows");
                }
                imputedCell = OptionalInt.of(sampleIndices == null ? row : sampleIndices[row]);
            }
            return new EmbeddingReport(totalCells, trainedCells, markerCount,
                    excludedMarkers, unmeasuredMarkers, constantMarkers, imputedCell,
                    steering.reweightedRows(), projection.unplacedCells());
        }
    }

    private final int totalCells;
    private final int trainedCells;
    private final int markerCount;
    private final List<String> excludedMarkers;
    private final List<String> unmeasuredMarkers;
    private final List<String> constantMarkers;
    private final OptionalInt imputedCell;
    private final int reweightedCells;
    private final int cellsAtOrigin;

    private EmbeddingReport(int totalCells, int trainedCells, int markerCount,
                            List<String> excludedMarkers, List<String> unmeasuredMarkers,
                            List<String> constantMarkers,
                            OptionalInt imputedCell, int reweightedCells, int cellsAtOrigin) {
        if (trainedCells < 0 || trainedCells > totalCells) {
            throw new IllegalArgumentException("trained on " + trainedCells
                    + " of " + totalCells + " cells");
        }
        if (cellsAtOrigin < 0) {
            throw new IllegalArgumentException(
                    "cellsAtOrigin must be >= 0, got: " + cellsAtOrigin);
        }
        // Cells are parked in the projection of held-out cells and nowhere else: a cell
        // that took part in the layout optimisation has a position by construction. A
        // count larger than the held-out population is therefore not a big number, it is
        // a bug in whoever counted.
        if (cellsAtOrigin > totalCells - trainedCells) {
            throw new IllegalArgumentException(cellsAtOrigin
                    + " cells at the origin, but only " + (totalCells - trainedCells)
                    + " were held out of training and could be there");
        }
        if (reweightedCells < 0) {
            throw new IllegalArgumentException(
                    "reweightedCells must be >= 0, got: " + reweightedCells);
        }
        if (imputedCell.isEmpty() && reweightedCells != 0) {
            throw new IllegalArgumentException(
                    "reweightedCells=" + reweightedCells + " without an imputed cell");
        }
        this.totalCells = totalCells;
        this.trainedCells = trainedCells;
        this.markerCount = markerCount;
        this.excludedMarkers = List.copyOf(excludedMarkers);
        this.unmeasuredMarkers = List.copyOf(unmeasuredMarkers);
        this.constantMarkers = List.copyOf(constantMarkers);
        this.imputedCell = imputedCell;
        this.reweightedCells = reweightedCells;
        this.cellsAtOrigin = cellsAtOrigin;
    }

    /**
     * Read what the training data looks like, before the layout runs.
     * <p>
     * Degeneracy is measured on the source columns rather than on the matrix handed to
     * {@code UMAP.fit}, and deliberately so. Reading the matrix would work only if this
     * were called before {@code FeatureScaler} standardised it in place — an
     * unenforceable ordering constraint of exactly the kind that produced this project's
     * z-score-of-zero bugs. The source columns give the same answer whenever they are
     * read: mean-imputation replaces every NaN with the mean of the values that <em>are</em>
     * present, which cannot turn a varying column constant nor a constant one varying.
     *
     * <p>
     * Reads {@code source}'s <em>features</em> — the markers the picker left ticked —
     * rather than the whole panel. An excluded marker is not a degraded feature; it is
     * not a feature. Calling a marker the user deliberately unticked "unmeasured" would
     * put the user's own decision in the findings list and make {@link #isClean()} false
     * for a run that did exactly what was asked. What the exclusion earns instead is a
     * note, so the tooltip still says the embedding is over fewer columns than the panel.
     *
     * @param source        the features the run is embedding
     * @param sampleIndices rows of {@code source} the layout will be trained on, or null
     *                      when it trains on all of them. Retained by reference and read
     *                      only, in keeping with {@code CellIndex}'s no-copy contract
     */
    public static Training training(EmbeddingFeatures.Selected source, int[] sampleIndices) {
        Objects.requireNonNull(source, "source");
        int totalCells = source.cellCount();
        String[] markers = source.featureNames();
        List<String> excluded = source.excludedMarkers();
        int trainedCells = sampleIndices == null ? totalCells : sampleIndices.length;
        List<String> unmeasured = new ArrayList<>();
        List<String> constant = new ArrayList<>();

        // A run with no training rows fails its k >= 2 precondition long before here.
        // Calling every marker "unmeasured" for it would be technically true and
        // thoroughly misleading, so say nothing instead.
        if (trainedCells == 0) {
            return new Training(totalCells, 0, markers.length, excluded, unmeasured, constant,
                    sampleIndices);
        }

        for (int j = 0; j < markers.length; j++) {
            // The backing column, per CellIndex's contract. Read-only.
            double[] column = source.column(j);
            int measured = 0;
            double sum = 0.0;
            for (int r = 0; r < trainedCells; r++) {
                double v = column[sampleIndices == null ? r : sampleIndices[r]];
                if (Double.isNaN(v)) continue;
                measured++;
                sum += v;
            }
            if (measured == 0) {
                unmeasured.add(markers[j]);
                continue;
            }
            double mean = sum / measured;
            double ss = 0.0;
            for (int r = 0; r < trainedCells; r++) {
                double v = column[sampleIndices == null ? r : sampleIndices[r]];
                if (Double.isNaN(v)) continue;
                double d = v - mean;
                ss += d * d;
            }
            // The same threshold FeatureScaler refuses to divide by, so "the scaler could
            // not standardise this column" and "the report calls it constant" cannot drift.
            if (Math.sqrt(ss / measured) < FeatureScaler.ZERO_VARIANCE_STD) {
                constant.add(markers[j]);
            }
        }
        return new Training(totalCells, trainedCells, markers.length, excluded, unmeasured,
                constant, sampleIndices);
    }

    /** Cells in the index the run embedded. */
    public int totalCells() { return totalCells; }

    /** Cells the layout was actually optimised over. */
    public int trainedCells() { return trainedCells; }

    /** True when the layout was trained on a subsample and the rest were projected onto it. */
    public boolean subsampled() { return trainedCells < totalCells; }

    /**
     * Markers the feature picker left out of this run, in panel order.
     * <p>
     * Not a finding: the user asked for it. It is recorded because the alternative is a
     * canvas that looks the same whether the run was over twelve markers or nine, and
     * because a selection saved months ago on a different image is easy to forget.
     */
    public List<String> excludedMarkers() { return excludedMarkers; }

    /** Markers no training cell carried a value for — a column of imputed zeros. */
    public List<String> unmeasuredMarkers() { return unmeasuredMarkers; }

    /**
     * Markers every training cell carried a value for, all of them the same value.
     * Real data, and inert: a feature with no variance moves no distance.
     */
    public List<String> constantMarkers() { return constantMarkers; }

    /** The initialisation the layout actually used. */
    public Initialisation initialisation() {
        return imputedCell.isPresent() ? Initialisation.PCA_STEERED_FROM_SPECTRAL
                : Initialisation.PCA;
    }

    /**
     * The one cell, if any, whose coordinates were imputed from its neighbours instead of
     * optimised — as an index into the {@code CellIndex} the caller handed in, already
     * translated out of the training matrix's row numbering.
     */
    public OptionalInt imputedCell() { return imputedCell; }

    /**
     * How many further cells listed the detached one among their neighbours and so had
     * their distance vector rewritten. Their positions were optimised normally; only the
     * edge weights feeding that optimisation shifted. Zero when nothing was steered.
     */
    public int reweightedCells() { return reweightedCells; }

    /**
     * Cells the projection could not place, left at exactly {@code (0,0)} where they are
     * indistinguishable from a real cluster.
     * <p>
     * <b>Zero is the expected reading, and is not evidence of anything.</b> The blend
     * gives up only when no neighbour carries any weight, which needs every one of a
     * cell's five nearest sampled neighbours to be at an infinite distance — in practice
     * a squared distance that saturated, i.e. marker magnitudes past about
     * {@code 1e154}, or a non-finite value in the column. Finite data reads zero here on
     * every run. Read this number as "nothing was silently dumped at the origin", never
     * as "the projection went well".
     */
    public int cellsAtOrigin() { return cellsAtOrigin; }

    /** True when the run degraded nothing. */
    public boolean isClean() {
        return findings().isEmpty();
    }

    /** Everything the run had to degrade, one human-readable line each. */
    public List<String> findings() {
        List<String> out = new ArrayList<>();

        if (cellsAtOrigin > 0) {
            out.add(String.format(Locale.US,
                    "%,d of the %,d projected cells found no usable neighbour and sit at "
                            + "exactly (0,0), where they are indistinguishable from a real "
                            + "cluster rather than marked as unplaced",
                    cellsAtOrigin, totalCells - trainedCells));
        }
        if (!unmeasuredMarkers.isEmpty()) {
            out.add(count(unmeasuredMarkers.size(), "marker", "markers")
                    + " of " + markerCount + " had no value on any training cell — imputed "
                    + "with the mean of nothing, so each is a column of zeros contributing "
                    + "nothing to any distance: " + preview(unmeasuredMarkers));
        }
        if (!constantMarkers.isEmpty()) {
            out.add(count(constantMarkers.size(), "marker", "markers")
                    + " of " + markerCount + " carry the same value on every training cell "
                    + "— known data, unlike the unmeasured case, but a feature with no "
                    + "variance moves no distance: " + preview(constantMarkers));
        }
        return out;
    }

    /** What the run did that is worth recording but is not a defect. */
    public List<String> notes() {
        List<String> out = new ArrayList<>();
        if (!excludedMarkers.isEmpty()) {
            out.add(count(excludedMarkers.size(), "marker", "markers")
                    + " of " + (markerCount + excludedMarkers.size())
                    + " were excluded from the embedding by the feature picker, so the "
                    + "layout is over " + markerCount + ": " + preview(excludedMarkers));
        }
        if (imputedCell.isPresent()) {
            // Deliberately not a finding. Steering is unconditional policy below the
            // spectral limit, not a shortfall — see the class javadoc. The numbers still
            // travel, on describe(); what they no longer do is spend the status bar.
            String line = String.format(Locale.US,
                    "cell %,d imputed from its neighbours", imputedCell.getAsInt());
            out.add(reweightedCells == 0 ? line
                    : String.format(Locale.US, "%s, %,d neighbourhoods reweighted",
                            line, reweightedCells));
        }
        if (subsampled()) {
            out.add(String.format(Locale.US,
                    "trained on %,d of %,d cells; the other %,d were placed by "
                            + "inverse-distance blending of their nearest trained "
                            + "neighbours rather than by the layout optimisation",
                    trainedCells, totalCells, totalCells - trainedCells));
        }
        return out;
    }

    /**
     * One line fit for a status bar: the first finding, plus how many more there are.
     * Empty string when there is nothing to say, so a caller can concatenate it blindly.
     */
    public String summary() {
        List<String> f = findings();
        if (f.isEmpty()) return "";
        String first = f.get(0);
        return f.size() == 1 ? first : first + " (+" + (f.size() - 1) + " more)";
    }

    /** Every finding and note, newline-separated — the tooltip / log form. */
    public String describe() {
        List<String> all = new ArrayList<>(findings());
        all.addAll(notes());
        if (all.isEmpty()) {
            return String.format(Locale.US,
                    "Embedding clean: %,d cells over %d markers, %s initialisation, "
                            + "no cells parked at the origin.",
                    totalCells, markerCount, initialisation());
        }
        return String.join("\n", all);
    }

    private static String count(int n, String singular, String plural) {
        return n + " " + (n == 1 ? singular : plural);
    }

    /** At most five names, so a 40-plex panel does not fill the status bar. */
    private static String preview(List<String> names) {
        if (names.size() <= 5) return String.join(", ", names);
        return String.join(", ", names.subList(0, 5)) + ", … (+" + (names.size() - 5) + ")";
    }
}
