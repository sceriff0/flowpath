package qupath.ext.flowpath.analysis.ui;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import qupath.ext.flowpath.model.PopulationStats;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A composition bar chart: how the whole-slide population splits across its leaf
 * phenotypes, largest first.
 * <p>
 * Always summarises {@link PopulationStats.Scope#WHOLE_SLIDE} — the one scope every pass
 * has, whether or not the image carries annotations — and filters to it itself, so a caller
 * may hand this canvas the full, unfiltered {@link PopulationStats#rows()} the same way it
 * would a single scope's rows.
 * <p>
 * <b>A composition is of ONE gating tree — one root gate, not the forest.</b>
 * {@code FlowPathPane} exposes "+ Add Root Gate" as a repeatable action, and independent
 * parallel gating strategies from one starting population are ordinary FlowJo-style usage.
 * Each root's own leaves already sum to the whole population on their own; pooling leaves
 * across two roots would sum the bars to 2x the true denominator. This canvas therefore
 * scopes itself to one root — {@link #setSelectedRoot(Integer)}, defaulting to the first
 * root found.
 * <p>
 * <b>Identifying "one root": {@link PopulationStats.Row#rootIndex()}, never a name.</b> An
 * earlier version of this class tried to reconstruct root boundaries from row order and
 * {@code gateChannel} adjacency, because {@code Row} carried no root identity of its own.
 * That failed exactly where it mattered: two independent root gates on the identical
 * channel, back to back, were never split into two blocks at all — the second root's rows
 * were appended into the first's still-open block — and the leaf/prefix matching then
 * cross-matched between them, corrupting the composition. {@code Row} now carries
 * {@code rootIndex} for precisely this reason: it is assigned once, by
 * {@code PopulationStats.collectFromRoots}, from the tree structure itself, so partitioning
 * on it is identity-based and cannot collide the way a name (channel or path) can.
 * <p>
 * <b>Leaves only.</b> A branch with children would otherwise be counted once for itself and
 * again for everything under it, inflating the total past the true denominator. "Leaf" is
 * derived from the row set itself — no other row's path continues past this one — rather
 * than from the {@code GateTree}. Because that matching now runs only over one root's own
 * rows ({@link #selectedRootRows}), a leaf of one root can never be mistaken for an internal
 * branch of another root that happens to share a path prefix.
 */
public final class CompositionCanvas extends PlotCanvas {

    private static final int[] PALETTE = {
            0x4C9AFF, 0x57D9A3, 0xFFAB00, 0xFF5C5C, 0xB380FF, 0x00C7E6, 0xFFD666, 0x8590A2
    };

    private List<PopulationStats.Row> wholeSlideRows = List.of();
    private List<PopulationStats.Row> selectedRootRows = List.of();
    private List<PopulationStats.Row> leafRows = List.of();
    private Integer selectedRoot;

    public CompositionCanvas() {
        super(380, 220);
    }

    /**
     * Reduce to one root's whole-slide leaf populations, largest first. Rows from other
     * scopes in {@code rows} (region or annotation rows, if the caller passed the
     * unfiltered set) are simply not this canvas's concern and are ignored. The selected
     * root is kept across calls when it still exists in the new rows; otherwise this falls
     * back to the first root, the same rule a fresh canvas starts with.
     */
    public void setRows(List<PopulationStats.Row> rows) {
        this.wholeSlideRows = rows == null ? List.of() : rows.stream()
                .filter(r -> r.scope() == PopulationStats.Scope.WHOLE_SLIDE)
                .toList();
        List<Integer> roots = availableRoots();
        if (selectedRoot == null || !roots.contains(selectedRoot)) {
            selectedRoot = roots.isEmpty() ? null : roots.get(0);
        }
        recompute();
        repaint();
    }

    /** Choose which root gate's leaves this canvas shows, by {@link PopulationStats.Row#rootIndex()}. */
    public void setSelectedRoot(Integer rootIndex) {
        this.selectedRoot = rootIndex;
        recompute();
        repaint();
    }

    private void recompute() {
        this.selectedRootRows = selectedRoot == null ? List.of() : wholeSlideRows.stream()
                .filter(r -> r.rootIndex() == selectedRoot)
                .toList();
        this.leafRows = leavesOf(selectedRootRows);
    }

    /** Every enabled root's index, in tree order — the choices for {@link #setSelectedRoot}. */
    List<Integer> availableRoots() {
        return wholeSlideRows.stream()
                .map(PopulationStats.Row::rootIndex)
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * A human-readable label for one root — its gate's channel (e.g. {@code "CD45"}). Two
     * roots on the identical channel share this label; that is a display-only ambiguity,
     * not a data one — {@link #setSelectedRoot} still selects unambiguously by
     * {@code rootIndex}, never by this string.
     */
    String rootLabel(int rootIndex) {
        return wholeSlideRows.stream()
                .filter(r -> r.rootIndex() == rootIndex && r.depth() == 0)
                .map(PopulationStats.Row::gateChannel)
                .findFirst()
                .orElse("");
    }

    private static List<PopulationStats.Row> leavesOf(List<PopulationStats.Row> rows) {
        List<PopulationStats.Row> leaves = new ArrayList<>();
        for (PopulationStats.Row row : rows) {
            boolean hasDescendant = rows.stream()
                    .anyMatch(other -> other != row && other.path().startsWith(row.path() + "/"));
            if (!hasDescendant) leaves.add(row);
        }
        return leaves.stream()
                .sorted(Comparator.comparingInt(PopulationStats.Row::count).reversed())
                .toList();
    }

    /** Bar labels (leaf population paths), largest first — the reduction under test. */
    List<String> barLabels() {
        return leafRows.stream().map(PopulationStats.Row::path).toList();
    }

    /** The cell count behind one bar; 0 for a label this canvas is not currently showing. */
    int barValue(String label) {
        return leafRows.stream()
                .filter(r -> r.path().equals(label))
                .mapToInt(PopulationStats.Row::count)
                .findFirst()
                .orElse(0);
    }

    /**
     * The true whole-slide denominator every bar should sum to — a root branch's
     * {@code parentCount}, not a re-sum of the bars themselves, so a leaf filter that
     * silently drops or double-counts a population is caught rather than agreeing with
     * itself by construction.
     */
    int total() {
        return selectedRootRows.stream()
                .filter(r -> r.depth() == 0)
                .mapToInt(PopulationStats.Row::parentCount)
                .findFirst()
                .orElse(0);
    }

    @Override
    protected void repaint() {
        GraphicsContext gc = getGraphicsContext2D();
        gc.setFill(Color.rgb(30, 30, 30));
        gc.fillRect(0, 0, getWidth(), getHeight());

        if (leafRows.isEmpty()) {
            gc.setFill(Color.gray(0.5));
            gc.fillText("No data", getWidth() / 2 - 20, getHeight() / 2);
            return;
        }

        int maxCount = leafRows.stream().mapToInt(PopulationStats.Row::count).max().orElse(1);
        int n = leafRows.size();
        double barW = categoryWidth(n) * 0.7;
        double baseY = valueToY(0, 0, maxCount);

        for (int i = 0; i < n; i++) {
            PopulationStats.Row row = leafRows.get(i);
            double cx = categoryToX(i, n);
            double topY = valueToY(row.count(), 0, maxCount);
            gc.setFill(Color.rgb((PALETTE[i % PALETTE.length] >> 16) & 0xFF,
                    (PALETTE[i % PALETTE.length] >> 8) & 0xFF, PALETTE[i % PALETTE.length] & 0xFF));
            gc.fillRect(cx - barW / 2, topY, barW, baseY - topY);
        }
        drawAxes(gc, "Population", "Count");
        drawCategoryLabels(gc, barLabels());
        drawValueTicks(gc, 0, maxCount, 4);
    }
}
