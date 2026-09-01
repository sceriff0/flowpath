package qupath.ext.flowpath.analysis.ui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * A resolved Y axis for one of the Analysis window's bar plots: a value range, whether it reads
 * logarithmically, and whether it was capped short of the data's own maximum.
 * <p>
 * This is the fix for a specific, named complaint: a gated slide routinely has one enormous
 * population and several tiny ones — 214,000 cells in one bar and 3 in another — and a linear
 * axis scaled to the maximum makes every small bar invisible. {@link ScaleOptions} carries the
 * two independent remedies the user asked for; {@link #of} turns a set of bar values and those
 * options into the numbers a plot actually draws with. Nothing downstream needs to know how
 * {@link #max} was chosen — a percentile clip and a plain maximum are indistinguishable once
 * they reach {@link #toFraction} and {@link #ticks}, which is what lets {@code log} and
 * {@code clip} compose instead of being two branches of a mode switch.
 * <p>
 * <b>Pure arithmetic, deliberately.</b> This class touches no {@code PathObject}, no gate, no
 * row and no JavaFX toolkit — it is constructed from a {@code double[]} and an options record,
 * and is table-tested without a {@code Stage}. Task 6 is the only place that reads a canvas's
 * rows, builds the {@code double[]} of bar values, and hands both here.
 * <p>
 * <b>Degenerate inputs still produce a drawable axis.</b> An empty array, an all-zero array and
 * a single value each fall back to a {@code max} of 1 (or, in log mode, 10) rather than 0, so
 * {@code max > min} holds unconditionally and every fraction and tick computation below is safe
 * from a zero-width or zero-height division. See {@link #of} for exactly where that fallback is
 * applied.
 * <p>
 * <b>{@code min == max} cannot come out of {@link #of}, on either branch, for any input.</b>
 * Linear: {@code min} is fixed at 0 and {@code top} (the eventual {@code max}) is floored at 1
 * by the same degenerate-input fallback above, so {@code max >= 1 > 0 = min} always. Log:
 * {@code min} is fixed at 1 and {@code max} is floored at 10 by {@code of}'s own log floor, so
 * {@code max >= 10 > 1 = min} always. That is why {@link #toFraction}'s division by
 * {@code (max - min)} needs no zero-guard for anything this record was built by {@code of} —
 * every production call site in this package goes through {@code of} (see {@code PlotCanvas
 * #scaleFor}), and none constructs this record directly. The canonical constructor itself is
 * deliberately left unvalidated rather than guarded against {@code min >= max}: {@code
 * PlotCanvasCoordinateTest} constructs a degenerate scale directly (bypassing {@code of}
 * entirely) specifically to exercise {@link #toFraction}'s own clamping on a
 * {@code max <= min} range, and a compact-constructor guard would reject that legitimate test
 * input along with the production case it can never actually reach.
 *
 * @param min       the axis floor: 0 for a linear axis, 1 for a logarithmic one. Counts are the
 *                  only thing these axes carry, so there is nothing else {@code min} could mean.
 * @param max       the axis ceiling: the largest bar value, the percentile clip, or one of the
 *                  degenerate fallbacks above — see {@link #of}.
 * @param log       whether {@link #toFraction} and {@link #ticks} read logarithmically.
 * @param anyClipped whether at least one bar value exceeds {@link #max}. False whenever
 *                  clipping was never requested, and also false when it was requested but
 *                  landed on zero or on the data's own maximum — a clip that changes nothing has
 *                  no outlier to mark, and a break-marker drawn anyway would be a plot telling
 *                  the user about a clip that did not happen.
 */
public record AxisScale(double min, double max, boolean log, boolean anyClipped) {

    /**
     * Resolve {@code values} under {@code options} into a drawable axis.
     * <p>
     * The two toggles are applied in sequence, not as alternate branches, which is exactly what
     * lets them compose: the ceiling is chosen first — the plain maximum, or a percentile clip,
     * with the same zero/all-zero/empty fallback either way — and only afterwards, if {@code
     * options.log()}, is that ceiling floored at 10. A single-decade axis (every value under 10)
     * would otherwise report a {@code max} below its own {@code min} of 1 once log floors kick
     * in downstream, or leave a log axis with no tick above its floor to show; raising the
     * ceiling here rather than special-casing it in {@link #ticks} keeps every other method free
     * to assume {@code max > min} without asking why.
     * <p>
     * <b>Nearest-rank percentile</b> ({@code index = ceil(p/100 × n) − 1}, clamped into
     * {@code [0, n-1]}) over the values sorted ascending — not an interpolated percentile, which
     * would invent a bar height nothing in the population actually has. The explicit clamp is
     * defensive rather than load-bearing: {@link ScaleOptions}'s compact constructor already
     * restricts {@code percentile} to {@code [50, 100]}, so for any non-empty array the computed
     * index is provably within range, but a clamp costs nothing and a future relaxation of that
     * range should not have to remember to add one here.
     * <p>
     * <b>{@link #anyClipped} is computed once, last, against the final {@code max}</b> — after
     * the percentile fallback and after the log floor — rather than as a flag threaded out of
     * the percentile decision above. Deciding it earlier would go stale exactly where the two
     * toggles compose: a percentile candidate under 10 clips real values against itself, but
     * once log mode floors {@code max} up to 10 those same values may no longer exceed the
     * floored ceiling, and a flag captured before the floor would keep reporting a clip nothing
     * on the axis still shows. Computing {@code anyClipped} from {@code max} as drawn — the same
     * field {@link #isClipped} reads — is what keeps the two in agreement by construction rather
     * than by the percentile branch happening to run before the floor is known. It also means no
     * clip-active flag has to be threaded out of the fallback branch at all: when clipping is
     * off, or falls back because the candidate landed on zero or on the data's own maximum,
     * {@code top} is set to the true maximum (or its degenerate-input fallback), so no value can
     * exceed it and this final scan reports {@code false} on its own.
     */
    public static AxisScale of(double[] values, ScaleOptions options) {
        double largest = 0;
        for (double v : values) {
            if (v > largest) {
                largest = v;
            }
        }
        // A largest of 0 covers both the empty array (the loop never ran) and an all-zero one
        // (nothing exceeded the seed) — one fallback for both, since neither leaves anything
        // real to scale an axis to.
        double noClipMax = largest <= 0 ? 1 : largest;

        double top = noClipMax;
        if (options.clip() && values.length > 0) {
            double[] sorted = values.clone();
            Arrays.sort(sorted);
            int n = sorted.length;
            int index = (int) Math.ceil(options.percentile() / 100.0 * n) - 1;
            index = Math.max(0, Math.min(n - 1, index));
            double candidate = sorted[index];
            double maxValue = sorted[n - 1];
            if (candidate > 0 && candidate < maxValue) {
                top = candidate;
            }
            // else: landed on zero, or on the data's own maximum — clipping here would not
            // change the axis, so top stays at noClipMax and the scan below reports no clip.
        }

        double min = options.log() ? 1 : 0;
        double max = options.log() ? Math.max(top, 10) : top;

        boolean anyClipped = false;
        for (double v : values) {
            if (v > max) {
                anyClipped = true;
                break;
            }
        }

        return new AxisScale(min, max, options.log(), anyClipped);
    }

    /**
     * Where {@code value} sits on this axis, {@code 0..1}, clamped in both directions so a
     * value below {@link #min} or above {@link #max} — including one this axis clipped — still
     * returns a drawable position rather than a fraction a caller has to remember to clamp
     * itself.
     * <p>
     * Logarithmic mapping is {@code log10(v) / log10(max)}, with any {@code v <= 1} floored to
     * 0 rather than passed to {@code log10} — a zero count is data ("this population is empty"),
     * not an absence of data, and {@code log10(0)} is {@code -Infinity}, which a naive clamp
     * would still report as 0 but only after producing a NaN or Infinity intermediate that a
     * future edit to the clamp could let through.
     */
    public double toFraction(double value) {
        double fraction;
        if (log) {
            fraction = value <= 1 ? 0 : Math.log10(value) / Math.log10(max);
        } else {
            fraction = (value - min) / (max - min);
        }
        return Math.max(0, Math.min(1, fraction));
    }

    /**
     * Whether {@code value} was pushed past this axis's own ceiling — the signal a plot uses to
     * draw a bar to the top and mark it, rather than to decide whether to draw it at all.
     */
    public boolean isClipped(double value) {
        return value > max;
    }

    /**
     * Roughly {@code targetCount} tick values spanning this axis, respecting {@link #log}.
     * <p>
     * <b>Log mode ignores {@code targetCount}</b> and instead emits every decade from 1 up to
     * {@link #max}, plus {@code max} itself when it is not already a decade — a 1-2-5 ladder
     * makes no sense on a logarithmic axis, where the whole point is that equal screen distance
     * is equal ratio, not equal difference. {@code max} is included so the axis's own ceiling
     * always has a labelled tick, even when it falls strictly between two decades (the common
     * case, since {@link #of} sets it from data rather than from a round number); the equality
     * check that decides whether it is "already a decade" is exact rather than tolerant, because
     * every decade this method can produce is itself an exact power of ten with no accumulated
     * floating error to tolerate.
     * <p>
     * Linear mode delegates to {@link PlotCanvas#niceTicks}, unmodified: it already returns
     * {@code {min}} for a non-positive {@code targetCount} and an empty array when the target
     * overshoots the range, and this method composes with both rather than working around
     * either.
     */
    public double[] ticks(int targetCount) {
        if (!log) {
            return PlotCanvas.niceTicks(min, max, targetCount);
        }
        List<Double> decades = new ArrayList<>();
        for (double decade = 1; decade <= max; decade *= 10) {
            decades.add(decade);
        }
        // decades.isEmpty() cannot happen from anything of() builds: it floors a logarithmic
        // max at 10, so the loop above always collects at least {1, 10}. It is reachable all
        // the same, because this is a record — its canonical constructor is public, and nothing
        // stops a caller from writing new AxisScale(1, 4, true, false) directly, which skips
        // of()'s floor entirely and reaches this method with a log-mode max below 10. Without
        // this guard that call returns an empty tick array instead of {4}; keep it so a
        // directly-constructed scale still draws at least one tick, the same way of() guarantees
        // one for every scale it builds itself.
        if (decades.isEmpty() || decades.get(decades.size() - 1) != max) {
            decades.add(max);
        }
        double[] out = new double[decades.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = decades.get(i);
        }
        return out;
    }

    /**
     * A tick label for {@code value}: thousands-grouped with no decimal for a whole number,
     * one decimal place otherwise. Nothing in this class requires {@code value} to be whole —
     * that is a property of the caller's input domain (cell counts are always integers), not an
     * invariant {@link #of} or this method enforces, so both branches are real and reachable
     * rather than one being dead code for this codebase's current callers. In practice the
     * whole-number branch is the one every count-derived tick takes ({@link #of}'s {@code max}
     * — the largest bar value, a percentile candidate, the {@code 1} fallback, the log floor of
     * {@code 10} — is always whole when its inputs are). The one-decimal branch exists for
     * {@link #ticks}' other source of tick values on a small linear range: {@link
     * PlotCanvas#niceTicks}' 1-2-5 stepper picks a step from the range's own magnitude, and on a
     * range as small as {@code [0, 2]} that step is {@code 0.5}, not 1 — a fixed no-decimal
     * format would round {@code 0.5} and {@code 1.5} to the same label.
     * <p>
     * {@link Locale#US} is explicit rather than the platform default, so the grouping separator
     * is always a comma — a plot rendered on a machine set to a locale that groups thousands
     * with a period would otherwise read {@code 214.003} and invite exactly the misreading a
     * thousands separator exists to prevent.
     */
    public String formatTick(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value)) {
            return String.format(Locale.US, "%,d", (long) value);
        }
        return String.format(Locale.US, "%.1f", value);
    }
}
