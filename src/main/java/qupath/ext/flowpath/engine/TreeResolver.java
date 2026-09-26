package qupath.ext.flowpath.engine;

import qupath.ext.flowpath.model.Branch;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.GateAxis;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.GateValues;
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
        walk(tree.getRoots(), copy.getRoots(), slideId, isReference, lookup, resolved, applied);
        return new ResolvedTree(copy, Collections.unmodifiableMap(resolved), Collections.unmodifiableMap(applied));
    }

    private static void walk(List<GateNode> live, List<GateNode> copies, String slideId, boolean isReference,
                             AlignmentLookup lookup, Map<GateNode, GateNode> resolved,
                             Map<GateNode, Applied> applied) {
        for (int i = 0; i < live.size(); i++) {
            GateNode l = live.get(i);
            GateNode c = copies.get(i);
            resolved.put(l, c);
            applied.put(l, resolveOne(l, c, slideId, isReference, lookup));
            List<Branch> lb = l.getBranches();
            List<Branch> cb = c.getBranches();
            for (int b = 0; b < lb.size(); b++) {
                walk(lb.get(b).getChildren(), cb.get(b).getChildren(), slideId, isReference, lookup, resolved, applied);
            }
        }
    }

    private static Applied resolveOne(GateNode live, GateNode copy, String slideId, boolean isReference,
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

        if (setting instanceof SlideSetting.Skip) {
            copy.setSkippedOnSlide(true);
            return new Applied(reference, reference, Collections.nCopies(axes, Source.SKIPPED), columns);
        }
        if (setting instanceof SlideSetting.Manual manual && manual.values().fits(copy)) {
            manual.values().writeTo(copy);
            return new Applied(reference, manual.values(), Collections.nCopies(axes, Source.MANUAL), columns);
        }

        List<Source> sources = new ArrayList<>(axes);
        DoubleUnaryOperator[] maps = new DoubleUnaryOperator[]{DoubleUnaryOperator.identity(), DoubleUnaryOperator.identity()};
        boolean anyCorrected = false;
        for (int k = 0; k < axes; k++) {
            if (isReference) {
                sources.add(Source.REFERENCE);
                continue;
            }
            Alignment a = !live.isCorrectStaining() || columns.get(k) == null
                    ? null : lookup.alignment(slideId, columns.get(k));
            if (a == null || a.kind() == Alignment.Kind.IDENTITY) {
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
            return new Applied(reference, reference, List.copyOf(sources), Collections.unmodifiableList(columns));
        }

        GateValues values = reference.map(maps[0], maps[1]);
        if (copy instanceof Region2DGate region) {
            // Region-gate shape edits go through remapCoordinates, never GateValues'
            // instanceof chain, so a corrected shape keeps remapCoordinates' own degenerate
            // guards and bounds re-sort (CLAUDE.md "One boundary rule for 2D gates").
            region.remapCoordinates(maps[0], maps[1]);
        } else {
            values.writeTo(copy);
        }
        return new Applied(reference, values, List.copyOf(sources), Collections.unmodifiableList(columns));
    }
}
