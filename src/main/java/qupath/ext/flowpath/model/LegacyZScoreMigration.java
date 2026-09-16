package qupath.ext.flowpath.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Moves a gate tree saved under FlowPath's retired computed z-score onto raw values, once,
 * when the tree first meets an index.
 * <p>
 * <b>Why eagerly.</b> FlowPath used to standardise a column itself, against the cells loaded
 * and filtered at the time, and a gate carrying {@link GateNode#isThresholdIsZScore()} holds
 * its thresholds and shapes in those standard deviations (see {@link ValueMode} for why the
 * mode was retired). The engine now compares every gate against the column as measured, so
 * such a number has to be rewritten before anything classifies with it. The rewrite used to
 * happen lazily, in the gate editor, when a gate was <em>opened</em> — which left every
 * gate the user never clicked comparing a threshold of, say, 1.5 against intensities in the
 * thousands, and a CSV exported straight after loading a legacy tree silently gated on
 * standard deviations. Converting the whole tree as soon as an index exists closes that gap.
 * <p>
 * <b>Every gate.</b> The walk visits every root and every descendant, disabled ones
 * included: a disabled gate is still in the tree and will classify the moment it is
 * re-enabled.
 * <p>
 * <b>How.</b> Each axis is converted through the column the engine resolves for it
 * ({@link CellIndex#column(GateNode, int, MarkerStats)}), with {@link MeasuredColumn#fromZScore}
 * — the same arithmetic the editor applied. Two kinds of gate cannot be converted, and they
 * are handled differently on purpose:
 * <ul>
 *   <li><b>A column with no spread.</b> A zero standard deviation maps every z-value onto the
 *       mean, so there is nothing to convert through on any image. The gate keeps its
 *       numbers, loses the flag — the engine has no z-space to read it in — and is reported
 *       in {@link Result#unconvertible()}.</li>
 *   <li><b>A channel this image does not carry.</b> The engine already compiles such a gate
 *       as unusable here, so keeping the flag costs nothing on this image — and clearing it
 *       would make the tree, saved and opened on a slide that <em>does</em> carry the
 *       channel, gate on standard deviations as if they were intensities. The flag stays,
 *       and the gate is reported in {@link Result#missingChannel()}; a later migration
 *       against an index that has the channel converts it.</li>
 * </ul> A region gate with no shape to
 * convert (an empty polygon, a cleared rectangle, a zero-radius ellipse) encloses nothing in
 * either space and migrates without needing a column.
 * <p>
 * Toolkit-free and pure over its inputs, so it is table-testable without JavaFX.
 */
public final class LegacyZScoreMigration {

    private LegacyZScoreMigration() {
        // static utility class
    }

    /**
     * What a migration did.
     *
     * @param converted      gates whose numbers were rewritten into raw space (or had no
     *                       shape to rewrite) and whose flag was cleared
     * @param unconvertible  gates whose flag was cleared but whose numbers could not be
     *                       converted, because an axis column has no spread
     * @param missingChannel gates left untouched, flag kept, because an axis channel is not
     *                       in this index; they convert when the tree meets one that has it
     */
    public record Result(int converted, List<GateNode> unconvertible, List<GateNode> missingChannel) {

        public Result {
            unconvertible = List.copyOf(Objects.requireNonNull(unconvertible, "unconvertible"));
            missingChannel = List.copyOf(Objects.requireNonNull(missingChannel, "missingChannel"));
        }

        /** Nothing carried the flag, so nothing changed and nothing is pending. */
        public boolean isEmpty() {
            return converted == 0 && unconvertible.isEmpty() && missingChannel.isEmpty();
        }

        /**
         * Whether this migration changed the tree. A result holding only
         * {@link #missingChannel()} gates changed nothing — the same gates are found again on
         * every later call against the same image — so a caller can use this to avoid
         * repeating the same notification on every undo, redo or load.
         */
        public boolean changedTree() {
            return converted > 0 || !unconvertible.isEmpty();
        }

        /**
         * One sentence for a notification, or {@code null} when there is nothing to say.
         * Names each unconvertible gate by its channels, because those are the gates whose
         * thresholds the user has to check by hand.
         */
        public String message() {
            if (isEmpty()) return null;
            StringBuilder sb = new StringBuilder();
            if (converted > 0) {
                sb.append("Converted ").append(converted)
                        .append(converted == 1 ? " gate" : " gates")
                        .append(" from the retired computed z-score to raw values.");
            }
            if (!unconvertible.isEmpty()) {
                if (!sb.isEmpty()) sb.append(' ');
                sb.append(unconvertible.size())
                        .append(unconvertible.size() == 1 ? " gate" : " gates")
                        .append(" could not be converted (a column with no spread) and keep "
                                + "their old numbers: ")
                        .append(names(unconvertible))
                        .append('.');
            }
            if (!missingChannel.isEmpty()) {
                if (!sb.isEmpty()) sb.append(' ');
                sb.append(missingChannel.size())
                        .append(missingChannel.size() == 1 ? " gate reads" : " gates read")
                        .append(" a channel this image does not carry and stay in z-score "
                                + "units until opened on an image that does: ")
                        .append(names(missingChannel))
                        .append('.');
            }
            return sb.toString();
        }

        private static String names(List<GateNode> gates) {
            List<String> names = new ArrayList<>();
            for (GateNode gate : gates) {
                List<String> channels = gate.getChannels();
                names.add(channels.isEmpty() ? gate.getGateType() : String.join("/", channels));
            }
            return String.join(", ", names);
        }
    }

    /** Whether any gate in {@code tree}, at any depth, still carries the retired flag. */
    public static boolean needsMigration(GateTree tree) {
        if (tree == null) return false;
        for (GateNode root : tree.getRoots()) {
            if (anyFlagged(root)) return true;
        }
        return false;
    }

    private static boolean anyFlagged(GateNode node) {
        if (node.isThresholdIsZScore()) return true;
        for (Branch branch : node.getBranches()) {
            for (GateNode child : branch.getChildren()) {
                if (anyFlagged(child)) return true;
            }
        }
        return false;
    }

    /**
     * Convert every flagged gate in {@code tree} from z-space to raw against {@code stats},
     * and clear the flag on all of them.
     *
     * @param stats the statistics of the cells loaded now — the same ones the editor used
     *              when it converted a gate on opening
     */
    public static Result migrate(GateTree tree, CellIndex index, MarkerStats stats) {
        Objects.requireNonNull(index, "index");
        Objects.requireNonNull(stats, "stats");
        int[] converted = {0};
        List<GateNode> unconvertible = new ArrayList<>();
        List<GateNode> missingChannel = new ArrayList<>();
        if (tree != null) {
            for (GateNode root : tree.getRoots()) {
                migrateRecursive(root, index, stats, converted, unconvertible, missingChannel);
            }
        }
        return new Result(converted[0], unconvertible, missingChannel);
    }

    private enum Outcome { CONVERTED, NO_SPREAD, MISSING_CHANNEL }

    private static void migrateRecursive(GateNode node, CellIndex index, MarkerStats stats,
                                         int[] converted, List<GateNode> unconvertible,
                                         List<GateNode> missingChannel) {
        if (node.isThresholdIsZScore()) {
            switch (convert(node, index, stats)) {
                case CONVERTED -> {
                    converted[0]++;
                    node.setThresholdIsZScore(false);
                }
                case NO_SPREAD -> {
                    unconvertible.add(node);
                    node.setThresholdIsZScore(false);
                }
                // Flag kept: this image cannot convert it, but one carrying the channel can.
                case MISSING_CHANNEL -> missingChannel.add(node);
            }
        }
        for (Branch branch : node.getBranches()) {
            for (GateNode child : branch.getChildren()) {
                migrateRecursive(child, index, stats, converted, unconvertible, missingChannel);
            }
        }
    }

    /** Rewrite {@code node}'s numbers into raw space, or say why that is not possible here. */
    private static Outcome convert(GateNode node, CellIndex index, MarkerStats stats) {
        if (node instanceof Region2DGate region && hasNoShape(region)) return Outcome.CONVERTED;

        int axes = (node instanceof QuadrantGate || node instanceof Region2DGate) ? 2 : 1;
        // A missing channel is checked on every axis first: it is the one case that must keep
        // the flag, so it wins over a flat column on the other axis.
        List<String> channels = node.getChannels();
        for (int axis = 0; axis < axes; axis++) {
            if (axis >= channels.size() || index.getMarkerIndex(channels.get(axis)) < 0) {
                return Outcome.MISSING_CHANNEL;
            }
        }
        MeasuredColumn x = convertible(node, 0, index, stats);
        MeasuredColumn y = axes == 2 ? convertible(node, 1, index, stats) : null;
        // All or nothing, as the editor did: converting one axis of a 2D gate and not the
        // other would leave a shape half in each space.
        if (x == null || (axes == 2 && y == null)) return Outcome.NO_SPREAD;

        switch (node) {
            case QuadrantGate qg -> {
                qg.setThresholdX(x.fromZScore(qg.getThresholdX()));
                qg.setThresholdY(y.fromZScore(qg.getThresholdY()));
            }
            // fromZScore is linear (value * std + mean), so routing every shape through the
            // model's generic remapCoordinates reproduces exactly what this used to do by
            // hand per shape: a polygon vertex maps directly, a rectangle bound maps
            // directly (its slope is always positive, so the re-sort is a no-op here), and
            // an ellipse's bounding-box round trip reduces to mapping the centre through
            // fromZScore and scaling each radius by |std| -- the same "radius scales, does
            // not shift" rule this method used to spell out for EllipseGate alone.
            case Region2DGate region -> region.remapCoordinates(x::fromZScore, y::fromZScore);
            default -> node.setThreshold(x.fromZScore(node.getThreshold()));
        }
        return Outcome.CONVERTED;
    }

    /** The axis column, when it has spread to convert through (the channel is known present). */
    private static MeasuredColumn convertible(GateNode node, int axis, CellIndex index,
                                              MarkerStats stats) {
        MeasuredColumn column = index.column(node, axis, stats);
        return column != null && column.hasSpread() ? column : null;
    }

    /**
     * A region that encloses nothing in any space, so there is nothing to convert.
     * Exhaustive over Region2DGate's sealed permits with no default: a new region shape
     * fails to compile here rather than silently being treated as always convertible.
     */
    private static boolean hasNoShape(Region2DGate region) {
        return switch (region) {
            case PolygonGate pg -> pg.getVertices().isEmpty();
            case RectangleGate rg -> !(rg.getMaxX() > rg.getMinX()) || !(rg.getMaxY() > rg.getMinY());
            case EllipseGate eg -> !(eg.getRadiusX() > 0) || !(eg.getRadiusY() > 0);
        };
    }
}
