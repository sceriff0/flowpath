package qupath.ext.flowpath.umap.engine;

import qupath.ext.flowpath.umap.model.UmapResult;

import java.util.Locale;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * How one {@link UmapComputeService#compute} call ended. Exactly one of these is
 * delivered per call, and it is the only thing the seam promises.
 * <p>
 * The promise used to be unwritten. The service exposed three loose callbacks —
 * {@code onComplete}, {@code onError}, {@code onStatusUpdate} — and a {@code void}
 * {@code compute(...)}; whether any of them fired was decided by a dozen scattered
 * {@code return} statements and two {@code catch} clauses. Seven paths delivered
 * nothing at all. The worst was structural rather than incidental: the run body was
 * guarded by {@code catch (OutOfMemoryError)} and {@code catch (Exception)}, so a
 * plain {@link Error} — SMILE's {@code NoClassDefFoundError} for the absent ARPACK
 * native is the one that actually happens — matched neither, escaped the
 * {@code Runnable}, was captured into a {@code Future} nobody read, and the UI sat in
 * COMPUTING forever waiting for a call that could not come. A failure the user cannot
 * see is worse than a crash.
 *
 * <h2>Four ends, not two</h2>
 * "Success or error" is too coarse for a service that can be superseded. A run that
 * was replaced by a newer one must NOT drive the UI — the newer run owns it — whereas
 * a run the user cancelled must, because nothing else will. Collapsing those two into
 * "no callback" is what made the abandoned paths invisible in the first place.
 * <ul>
 *   <li>{@link Succeeded} — an embedding was produced and is current.</li>
 *   <li>{@link Failed} — something went wrong; carries a human-readable reason and,
 *       when a throwable caused it, that throwable's class name.</li>
 *   <li>{@link Cancelled} — the user asked to stop, and no newer run has started.</li>
 *   <li>{@link Superseded} — a newer {@code compute(...)} took over. The consumer
 *       should do nothing: acting would fight the run that is still in flight.</li>
 * </ul>
 *
 * <h2>Where later facts belong</h2>
 * Anything a caller wants to know <em>about a run that worked</em> — which embedding
 * initialisation was actually used, which cells had to be imputed, what the stage had
 * to degrade — hangs off {@link Succeeded}, alongside the result. Use
 * {@link #succeeded(UmapResult)} rather than the canonical constructor so that adding
 * such a fact is one change here and not one at every call site.
 */
public sealed interface UmapOutcome
        permits UmapOutcome.Succeeded, UmapOutcome.Failed,
                UmapOutcome.Cancelled, UmapOutcome.Superseded {

    /**
     * A flat discriminator over the four ends. Consumers that need the payload should
     * pattern-match on the type instead; this exists for tabulation — mapping an
     * outcome to a UI state, or enumerating the ends in a parameterised test.
     */
    enum Kind {
        /** {@link Succeeded}. */
        SUCCEEDED,
        /** {@link Failed}. */
        FAILED,
        /** {@link Cancelled}. */
        CANCELLED,
        /** {@link Superseded}. */
        SUPERSEDED
    }

    /** Which end this is. */
    Kind kind();

    /**
     * One line fit for a status bar. For {@link Failed} this always names the
     * throwable's class when there was one — an {@code Error} whose {@code getMessage()}
     * is null (the ARPACK {@code NoClassDefFoundError} carries only the missing class
     * name) would otherwise read as "UMAP failed: null".
     */
    String describe();

    /** True only for {@link Succeeded}. */
    default boolean isSuccess() {
        return kind() == Kind.SUCCEEDED;
    }

    /**
     * True when the run ended without producing an embedding and without a defect:
     * the user cancelled it, or a newer run replaced it. Neither is an error to report.
     */
    default boolean isAbandoned() {
        return kind() == Kind.CANCELLED || kind() == Kind.SUPERSEDED;
    }

    // --- Factories -----------------------------------------------------------

    /** A run that produced a current embedding, with nothing to qualify it. */
    static Succeeded succeeded(UmapResult result) {
        return new Succeeded(result, OptionalInt.empty(), 0);
    }

    /**
     * A run whose embedding was steered off SMILE's native initialisation path, and the
     * two numbers that says it cost.
     *
     * <p>There is deliberately no factory that records the imputation alone. Reporting
     * one affected cell when a further few per cent carry altered edge weights would be
     * an understatement, and the way to stop an understatement being written is to make
     * it unspellable.
     *
     * @param imputedCell     index into the {@code CellIndex} the run walked
     * @param reweightedCells how many other cells listed that one as a neighbour
     */
    static Succeeded succeeded(UmapResult result, int imputedCell, int reweightedCells) {
        return new Succeeded(result, OptionalInt.of(imputedCell), reweightedCells);
    }

    /** A failure with no throwable behind it — a precondition the run refused. */
    static Failed failed(String reason) {
        return new Failed(reason, null);
    }

    /**
     * A failure caused by {@code cause}. The throwable's class name is recorded
     * separately from the reason so a consumer can act on the type without parsing
     * prose, and is repeated in {@link Failed#describe()} for the human.
     */
    static Failed failed(String reason, Throwable cause) {
        return new Failed(reason, cause == null ? null : cause.getClass().getName());
    }

    /** The user cancelled, and no newer run has started. */
    static Cancelled cancelled() {
        return Cancelled.INSTANCE;
    }

    /** A newer {@code compute(...)} replaced this run. */
    static Superseded superseded() {
        return Superseded.INSTANCE;
    }

    // --- The four ends -------------------------------------------------------

    /**
     * A completed run whose embedding is current.
     *
     * @param result          the embedding, never null
     * @param imputedCell     the one cell, if any, whose coordinates were imputed from its
     *                        neighbours instead of optimised. FlowPath detaches a single
     *                        node from the neighbour graph to keep SMILE off its native
     *                        initialisation path (see {@code EmbeddingInitialisation});
     *                        that node's position is then recomputed from its true
     *                        neighbours. Present here so the qualification travels with
     *                        the embedding rather than living only in a log line.
     * @param reweightedCells how many further cells listed the detached one among their
     *                        neighbours and so had their distance vector rewritten. Their
     *                        positions were optimised normally — only the edge weights
     *                        feeding that optimisation shifted — but at k=15 this is
     *                        typically 3–4% of a small dataset, and an outcome claiming
     *                        one affected cell would be an understatement. Zero whenever
     *                        {@code imputedCell} is empty.
     */
    record Succeeded(UmapResult result, OptionalInt imputedCell, int reweightedCells)
            implements UmapOutcome {

        public Succeeded {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(imputedCell, "imputedCell");
            if (reweightedCells < 0) {
                throw new IllegalArgumentException(
                        "reweightedCells must be >= 0, got: " + reweightedCells);
            }
            // Nothing other than detaching a node perturbs a neighbourhood, so a count
            // without an imputation describes a run that cannot have happened.
            if (imputedCell.isEmpty() && reweightedCells != 0) {
                throw new IllegalArgumentException(
                        "reweightedCells=" + reweightedCells + " without an imputed cell");
            }
        }

        @Override
        public Kind kind() {
            return Kind.SUCCEEDED;
        }

        @Override
        public String describe() {
            String base = String.format(Locale.US, "UMAP computed: %,d cells (k=%d)",
                    result.size(),
                    result.getParams() == null ? 0 : result.getParams().k());
            if (imputedCell.isEmpty()) return base;
            String imputed = String.format(Locale.US, "%s; cell %,d imputed from its neighbours",
                    base, imputedCell.getAsInt());
            return reweightedCells == 0 ? imputed
                    : String.format(Locale.US, "%s, %,d neighbourhoods reweighted",
                            imputed, reweightedCells);
        }
    }

    /**
     * A run that could not produce an embedding.
     *
     * @param reason         human-readable, already tailored to the cause (the
     *                       out-of-memory case keeps its "enable subsampling / raise
     *                       QuPath's memory" advice)
     * @param throwableClass fully-qualified class name of the throwable behind it, or
     *                       null when the failure was a refused precondition rather
     *                       than a throw
     */
    record Failed(String reason, String throwableClass) implements UmapOutcome {

        public Failed {
            Objects.requireNonNull(reason, "reason");
        }

        /** True when a throwable caused this failure, so {@link #throwableClass()} is set. */
        public boolean fromThrowable() {
            return throwableClass != null;
        }

        @Override
        public Kind kind() {
            return Kind.FAILED;
        }

        @Override
        public String describe() {
            return throwableClass == null ? reason : reason + " (" + throwableClass + ")";
        }
    }

    /**
     * The user cancelled the run. Distinct from {@link Superseded}: nothing else is
     * going to drive the UI out of its computing state, so the consumer must.
     */
    record Cancelled() implements UmapOutcome {

        static final Cancelled INSTANCE = new Cancelled();

        @Override
        public Kind kind() {
            return Kind.CANCELLED;
        }

        @Override
        public String describe() {
            return "UMAP cancelled";
        }
    }

    /**
     * A newer run replaced this one. The consumer should ignore it — the newer run is
     * still in flight and owns the UI. Delivered rather than swallowed so that "no
     * outcome" always means "the service is broken", never "the service moved on".
     */
    record Superseded() implements UmapOutcome {

        static final Superseded INSTANCE = new Superseded();

        @Override
        public Kind kind() {
            return Kind.SUPERSEDED;
        }

        @Override
        public String describe() {
            return "UMAP run superseded by a newer one";
        }
    }
}
