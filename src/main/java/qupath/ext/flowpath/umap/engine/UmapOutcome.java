package qupath.ext.flowpath.umap.engine;

import qupath.ext.flowpath.umap.model.UmapResult;

import java.util.Locale;
import java.util.Objects;

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
 * Everything a caller wants to know <em>about a run that worked</em> — which embedding
 * initialisation was actually used, which cell had to be imputed, which markers were
 * degenerate, how many cells the projection could not place — hangs off {@link Succeeded}
 * as a single {@link EmbeddingReport}, alongside the result. One carrier rather than a
 * growing list of loose fields: a caller that wants the provenance of a run reads one
 * object, and a new fact is added in {@code EmbeddingReport} without touching this type
 * or any call site.
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

    /**
     * A run that produced a current embedding, and what producing it cost.
     *
     * <p>There is deliberately no factory that omits the report. A run can succeed while
     * leaving cells stranded at the origin, embedding a marker that was never measured,
     * or fabricating one cell's position to stay off an absent native — and every one of
     * those looks like an ordinary success from the outside. Making the report
     * unskippable is what stops "it worked" being said without saying what "it" was.
     */
    static Succeeded succeeded(UmapResult result, EmbeddingReport report) {
        return new Succeeded(result, report);
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
     * @param result the embedding, never null
     * @param report what producing it cost — degenerate markers, cells the projection
     *               could not place, the initialisation actually used and the cell it was
     *               bought with. Never null: an embedding without an account of itself is
     *               the failure mode this seam exists to close, so the type does not
     *               permit one
     */
    record Succeeded(UmapResult result, EmbeddingReport report) implements UmapOutcome {

        public Succeeded {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(report, "report");
        }

        @Override
        public Kind kind() {
            return Kind.SUCCEEDED;
        }

        @Override
        public String describe() {
            return compose(null);
        }

        /**
         * {@link #describe()} with the run's wall-clock time folded into the same
         * sentence, for the status line that has one to report.
         * <p>
         * The alternative — letting the status line compose its own — is what happened,
         * and the two spellings drifted within one branch: same numbers, a different
         * separator, and only one of them carrying the timing. The sentence is built
         * here, once; the caller supplies the fact it is the only one that knows.
         *
         * @param elapsedMillis wall-clock duration of the run, never negative
         */
        public String describe(long elapsedMillis) {
            // Locale.US, like every other number this project formats: the status line
            // said "1,5s" on an en_IT JVM, because the spelling this replaces reached for
            // String.formatted, which takes the default locale.
            return compose(elapsedMillis < 1000
                    ? String.format(Locale.US, "%dms", elapsedMillis)
                    : String.format(Locale.US, "%.1fs", elapsedMillis / 1000.0));
        }

        /** The one composition. {@code elapsed} is null when there is no timing to say. */
        private String compose(String elapsed) {
            String base = String.format(Locale.US, "UMAP computed: %,d cells (k=%d)",
                    result.size(),
                    result.getParams() == null ? 0 : result.getParams().k());
            if (elapsed != null) {
                base += " in " + elapsed;
            }
            String qualifier = report.summary();
            return qualifier.isEmpty() ? base : base + " — " + qualifier;
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
