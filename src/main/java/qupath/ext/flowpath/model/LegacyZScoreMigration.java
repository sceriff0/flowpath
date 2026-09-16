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
 * — the same arithmetic the editor applied. An axis whose column has no spread (or whose
 * channel this image does not carry) cannot be converted: a zero standard deviation maps
 * every z-value onto the mean. Such a gate keeps its numbers, loses the flag like every other
 * gate — the engine no longer has a z-space to read it in — and is reported in
 * {@link Result#unconvertible()} so the caller can say so. A region gate with no shape to
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
     * @param converted     gates whose numbers were rewritten into raw space (or had no shape
     *                      to rewrite) and whose flag was cleared
     * @param unconvertible gates whose flag was cleared but whose numbers could not be
     *                      converted, because an axis column has no spread or is absent
     */
    public record Result(int converted, List<GateNode> unconvertible) {

        public Result {
            unconvertible = List.copyOf(Objects.requireNonNull(unconvertible, "unconvertible"));
        }

        /** Nothing carried the flag, so nothing changed. */
        public boolean isEmpty() {
            return converted == 0 && unconvertible.isEmpty();
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
                List<String> names = new ArrayList<>();
                for (GateNode gate : unconvertible) {
                    List<String> channels = gate.getChannels();
                    names.add(channels.isEmpty() ? gate.getGateType() : String.join("/", channels));
                }
                sb.append(unconvertible.size())
                        .append(unconvertible.size() == 1 ? " gate" : " gates")
                        .append(" could not be converted (a column with no spread, or not in "
                                + "this image) and keep their old numbers: ")
                        .append(String.join(", ", names))
                        .append('.');
            }
            return sb.toString();
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
        if (tree != null) {
            for (GateNode root : tree.getRoots()) {
                migrateRecursive(root, index, stats, converted, unconvertible);
            }
        }
        return new Result(converted[0], unconvertible);
    }

    private static void migrateRecursive(GateNode node, CellIndex index, MarkerStats stats,
                                         int[] converted, List<GateNode> unconvertible) {
        if (node.isThresholdIsZScore()) {
            if (convert(node, index, stats)) converted[0]++;
            else unconvertible.add(node);
            node.setThresholdIsZScore(false);
        }
        for (Branch branch : node.getBranches()) {
            for (GateNode child : branch.getChildren()) {
                migrateRecursive(child, index, stats, converted, unconvertible);
            }
        }
    }

    /** Rewrite {@code node}'s numbers into raw space; false when an axis cannot be. */
    private static boolean convert(GateNode node, CellIndex index, MarkerStats stats) {
        if (node instanceof Region2DGate region && hasNoShape(region)) return true;

        int axes = (node instanceof QuadrantGate || node instanceof Region2DGate) ? 2 : 1;
        MeasuredColumn x = convertible(node, 0, index, stats);
        MeasuredColumn y = axes == 2 ? convertible(node, 1, index, stats) : null;
        // All or nothing, as the editor did: converting one axis of a 2D gate and not the
        // other would leave a shape half in each space.
        if (x == null || (axes == 2 && y == null)) return false;

        switch (node) {
            case QuadrantGate qg -> {
                qg.setThresholdX(x.fromZScore(qg.getThresholdX()));
                qg.setThresholdY(y.fromZScore(qg.getThresholdY()));
            }
            case PolygonGate pg -> {
                List<double[]> raw = new ArrayList<>(pg.getVertices().size());
                for (double[] v : pg.getVertices()) {
                    raw.add(new double[]{x.fromZScore(v[0]), y.fromZScore(v[1])});
                }
                pg.setVertices(raw);
            }
            case RectangleGate rg -> {
                double minX = x.fromZScore(rg.getMinX());
                double maxX = x.fromZScore(rg.getMaxX());
                double minY = y.fromZScore(rg.getMinY());
                double maxY = y.fromZScore(rg.getMaxY());
                rg.setMinX(minX);
                rg.setMaxX(maxX);
                rg.setMinY(minY);
                rg.setMaxY(maxY);
            }
            case EllipseGate eg -> {
                eg.setCenterX(x.fromZScore(eg.getCenterX()));
                eg.setCenterY(y.fromZScore(eg.getCenterY()));
                eg.setRadiusX(eg.getRadiusX() * x.std());
                eg.setRadiusY(eg.getRadiusY() * y.std());
            }
            default -> node.setThreshold(x.fromZScore(node.getThreshold()));
        }
        return true;
    }

    /** The axis column, when it exists on this image and has spread to convert through. */
    private static MeasuredColumn convertible(GateNode node, int axis, CellIndex index,
                                              MarkerStats stats) {
        List<String> channels = node.getChannels();
        if (axis >= channels.size() || index.getMarkerIndex(channels.get(axis)) < 0) return null;
        MeasuredColumn column = index.column(node, axis, stats);
        return column != null && column.hasSpread() ? column : null;
    }

    /** A region that encloses nothing in any space, so there is nothing to convert. */
    private static boolean hasNoShape(Region2DGate region) {
        return switch (region) {
            case PolygonGate pg -> pg.getVertices().isEmpty();
            case RectangleGate rg -> !(rg.getMaxX() > rg.getMinX()) || !(rg.getMaxY() > rg.getMinY());
            case EllipseGate eg -> !(eg.getRadiusX() > 0) || !(eg.getRadiusY() > 0);
            default -> false;
        };
    }
}
