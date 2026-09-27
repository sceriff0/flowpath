package qupath.ext.flowpath.engine;

import qupath.ext.flowpath.model.Branch;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.EllipseGate;
import qupath.ext.flowpath.model.GateAxis;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.GateValues;
import qupath.ext.flowpath.model.RectangleGate;
import qupath.ext.flowpath.model.Region2DGate;
import qupath.ext.flowpath.model.SlideSetting;
import qupath.ext.flowpath.model.cohort.Alignment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.DoubleUnaryOperator;

/**
 * <b>The one resolution point.</b> Turns the tree's reference-slide numbers into the numbers
 * applied on one slide, returning a {@link GateTree#deepCopy()} with those numbers substituted —
 * same structure, same channels — so the engine never learns alignment exists and
 * {@code BranchTally.rebindTo} pairs the copy with the live tree as it pairs any deep copy.
 * Every path that gates a slide calls this instead of {@code deepCopy()}; nothing else computes
 * an applied value.
 */
public final class TreeResolver {

    public enum Source {
        REFERENCE, CORRECTED, UNCORRECTED, MANUAL, SKIPPED;

        /** The manifest spelling. */
        public String token() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public record Applied(GateValues reference, GateValues applied, List<Source> sources, List<String> columns) {}

    public record ResolvedTree(GateTree tree, Map<GateNode, GateNode> resolvedByLive,
                               Map<GateNode, Applied> appliedByLive) {
        public GateNode resolvedOf(GateNode live) {
            return resolvedByLive.get(live);
        }

        public Applied applied(GateNode live) {
            return appliedByLive.get(live);
        }
    }

    private TreeResolver() {}

    public static ResolvedTree resolve(GateTree tree, String slideId, AlignmentLookup alignments) {
        AlignmentLookup lookup = alignments == null ? AlignmentLookup.NONE : alignments;
        GateTree copy = tree.deepCopy();
        Map<GateNode, GateNode> resolved = new IdentityHashMap<>();
        Map<GateNode, Applied> applied = new IdentityHashMap<>();
        boolean isReference = slideId == null || tree.getReferenceSlideId() == null
                || slideId.equals(tree.getReferenceSlideId());
        walk(tree, tree.getRoots(), copy.getRoots(), slideId, isReference, lookup, resolved, applied);
        return new ResolvedTree(copy, Collections.unmodifiableMap(resolved), Collections.unmodifiableMap(applied));
    }

    /**
     * The alignment {@code gate}'s {@code axis} is corrected with on {@code slideId}, or identity when
     * the slide is the reference (or there is none), correction is off, the axis has no channel, or
     * no non-identity alignment is known. Slide settings are not consulted. The editor's display
     * seam asks this, so it cannot disagree with {@link #resolve} about which axes are corrected.
     */
    public static Alignment correctionFor(GateTree tree, GateNode gate, int axis, String slideId, AlignmentLookup lookup) {
        if (slideId == null || tree.getReferenceSlideId() == null || slideId.equals(tree.getReferenceSlideId())
                || !gate.isCorrectStaining()) return Alignment.identity();
        List<String> channels = gate.getChannels();
        String channel = axis < channels.size() ? channels.get(axis) : null;
        if (channel == null || channel.isEmpty()) return Alignment.identity();
        Alignment a = (lookup == null ? AlignmentLookup.NONE : lookup)
                .alignment(slideId, CellIndex.keyFor(channel, gate.compartmentAt(axis), gate.statisticAt(axis)));
        return a == null ? Alignment.identity() : a;
    }

    private static void walk(GateTree tree, List<GateNode> live, List<GateNode> copies, String slideId, boolean isReference,
                             AlignmentLookup lookup, Map<GateNode, GateNode> resolved,
                             Map<GateNode, Applied> applied) {
        for (int i = 0; i < live.size(); i++) {
            GateNode l = live.get(i);
            GateNode c = copies.get(i);
            resolved.put(l, c);
            applied.put(l, resolveOne(tree, l, c, slideId, isReference, lookup));
            List<Branch> lb = l.getBranches();
            List<Branch> cb = c.getBranches();
            for (int b = 0; b < lb.size(); b++) {
                walk(tree, lb.get(b).getChildren(), cb.get(b).getChildren(), slideId, isReference, lookup, resolved, applied);
            }
        }
    }

    private static Applied resolveOne(GateTree tree, GateNode live, GateNode copy, String slideId, boolean isReference,
                                      AlignmentLookup lookup) {
        int axes = GateAxis.axisCount(live);
        List<String> columns = new ArrayList<>(axes);
        List<String> channels = live.getChannels();
        for (int k = 0; k < axes; k++) {
            String channel = k < channels.size() ? channels.get(k) : null;
            columns.add(channel == null || channel.isEmpty() ? null
                    : CellIndex.keyFor(channel, live.compartmentAt(k), live.statisticAt(k)));
        }
        GateValues reference = GateValues.read(live);
        SlideSetting setting = live.slideSetting(slideId);
        List<String> immutableColumns = Collections.unmodifiableList(columns);

        if (setting instanceof SlideSetting.Skip) {
            copy.setSkippedOnSlide(true);
            return new Applied(reference, reference, Collections.nCopies(axes, Source.SKIPPED), immutableColumns);
        }
        if (setting instanceof SlideSetting.Manual manual && manual.values().fits(copy)) {
            GateValues normalized = normalizeManual(copy, manual.values());
            if (normalized != null) {
                normalized.writeTo(copy);
                return new Applied(reference, normalized, Collections.nCopies(axes, Source.MANUAL), immutableColumns);
            }
            // A degenerate Manual (zero or negative extent on a rectangle/ellipse bounding
            // box) does not fit, exactly like a Manual of the wrong shape: fall through to
            // the cohort/reference resolution below rather than write an inside-out shape.
        }

        List<Source> sources = new ArrayList<>(axes);
        DoubleUnaryOperator[] maps = new DoubleUnaryOperator[]{DoubleUnaryOperator.identity(), DoubleUnaryOperator.identity()};
        boolean anyCorrected = false;
        for (int k = 0; k < axes; k++) {
            if (isReference) {
                sources.add(Source.REFERENCE);
                continue;
            }
            Alignment a = correctionFor(tree, live, k, slideId, lookup);
            if (a.kind() == Alignment.Kind.IDENTITY) {
                sources.add(Source.UNCORRECTED);
            } else {
                sources.add(Source.CORRECTED);
                maps[k] = a::apply;
                anyCorrected = true;
            }
        }

        // Never rewrite a gate's geometry when no axis actually changed: rebuilding an
        // ellipse (or any region shape) from its bounding box drifts it by a few ulps, which
        // breaks the exact display/classification agreement at the rim (CLAUDE.md "One
        // boundary rule"). The deep copy already holds the reference numbers exactly.
        if (!anyCorrected) {
            return new Applied(reference, reference, List.copyOf(sources), immutableColumns);
        }

        GateValues values;
        if (copy instanceof Region2DGate region) {
            // Region-gate shape edits go through remapCoordinates, never GateValues'
            // instanceof chain, so a corrected shape keeps remapCoordinates' own degenerate
            // guards and bounds re-sort (CLAUDE.md "One boundary rule for 2D gates").
            region.remapCoordinates(maps[0], maps[1]);
            // remapCoordinates leaves a degenerate shape (no usable extent) unchanged rather
            // than mapping it, so the applied value reported here must be read back from the
            // copy rather than computed from `reference.map(...)`, or a review would compare
            // against numbers the gate never actually holds.
            values = GateValues.read(copy);
        } else {
            values = reference.map(maps[0], maps[1]);
            values.writeTo(copy);
        }
        return new Applied(reference, values, List.copyOf(sources), immutableColumns);
    }

    /**
     * Manual values for a rectangle or ellipse are a raw bounding box in axis form
     * ({@code [lo, hi]} per axis) and may arrive inverted (a drag that ended left of where it
     * started) or zero-extent; {@link GateValues#writeTo} does not sort or guard against
     * either, so a rectangle would store {@code minX > maxX} (never contains a cell) and an
     * ellipse would store a negative radius. Sorting each axis ascending here is exactly what
     * {@link RectangleGate#remapCoordinates} and {@link EllipseGate#remapCoordinates} already
     * do to their own bounds after mapping; a polygon's per-vertex axes are left untouched,
     * since sorting them would corrupt vertex order rather than a bounding box.
     *
     * @return the normalized values to write, or {@code null} when the resulting shape has no
     * usable extent (the Manual does not fit, exactly like a Manual of the wrong shape)
     */
    private static GateValues normalizeManual(GateNode copy, GateValues values) {
        if (!(copy instanceof RectangleGate) && !(copy instanceof EllipseGate)) {
            return values;
        }
        double[] x = sortedPair(values.axis(0));
        double[] y = sortedPair(values.axis(1));
        if (x[1] - x[0] <= 0 || y[1] - y[0] <= 0) return null;
        return GateValues.of(x, y);
    }

    private static double[] sortedPair(double[] axis) {
        return axis[0] <= axis[1] ? axis : new double[]{axis[1], axis[0]};
    }
}
