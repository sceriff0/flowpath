package qupath.ext.flowpath.umap.ui;

import javafx.event.EventHandler;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.IntStream;

/**
 * Interactive polygon drawing controller for UmapCanvas.
 * Click to add vertices, double-click to close and complete the polygon.
 * After completion, drag handles to reposition vertices.
 */
public class PolygonSelector {

    private static final double HANDLE_HIT_RADIUS = 8.0;

    private final UmapCanvas canvas;
    private final List<double[]> vertices = new ArrayList<>();
    private boolean active = false;
    private boolean completed = false;
    private int dragHandleIndex = -1;
    private Consumer<List<double[]>> onPolygonComplete;
    /**
     * Told whenever {@link #isActive()} changes.
     * <p>
     * The Draw toggle used to be set by hand at five sites — the Escape handler, the
     * snapshot teardown, the derived-state teardown and two states of the UI machine —
     * so any path that deactivated the selector without remembering the button left a
     * toggle pressed over a selector that was no longer listening. The button reflects
     * this flag now; nobody sets it.
     */
    private Consumer<Boolean> onActiveChanged;

    private final EventHandler<MouseEvent> pressHandler = this::handlePressed;
    private final EventHandler<MouseEvent> dragHandler = this::handleDragged;
    private final EventHandler<MouseEvent> releaseHandler = this::handleReleased;

    public PolygonSelector(UmapCanvas canvas) {
        this.canvas = canvas;
    }

    public void setOnPolygonComplete(Consumer<List<double[]>> cb) {
        this.onPolygonComplete = cb;
    }

    /** Observe {@link #isActive()}. Fired only on a real change, and never on registration. */
    public void setOnActiveChanged(Consumer<Boolean> cb) {
        this.onActiveChanged = cb;
    }

    private void setActive(boolean now) {
        if (active == now) return;
        active = now;
        if (onActiveChanged != null) onActiveChanged.accept(now);
    }

    public void activate() {
        // Remove first to prevent double-registration if activate() is called while already active
        canvas.removeEventHandler(MouseEvent.MOUSE_PRESSED, pressHandler);
        canvas.removeEventHandler(MouseEvent.MOUSE_DRAGGED, dragHandler);
        canvas.removeEventHandler(MouseEvent.MOUSE_RELEASED, releaseHandler);

        setActive(true);
        completed = false;
        dragHandleIndex = -1;
        vertices.clear();
        canvas.setPolygonOverlay(null);
        canvas.setPolygonCompleted(false);

        canvas.addEventHandler(MouseEvent.MOUSE_PRESSED, pressHandler);
        canvas.addEventHandler(MouseEvent.MOUSE_DRAGGED, dragHandler);
        canvas.addEventHandler(MouseEvent.MOUSE_RELEASED, releaseHandler);
    }

    public void deactivate() {
        setActive(false);
        completed = false;
        dragHandleIndex = -1;

        canvas.removeEventHandler(MouseEvent.MOUSE_PRESSED, pressHandler);
        canvas.removeEventHandler(MouseEvent.MOUSE_DRAGGED, dragHandler);
        canvas.removeEventHandler(MouseEvent.MOUSE_RELEASED, releaseHandler);
    }

    public boolean isActive() { return active; }
    public boolean isCompleted() { return completed; }

    public void clear() {
        vertices.clear();
        completed = false;
        dragHandleIndex = -1;
        canvas.clearPolygonOverlay();
    }

    private void handlePressed(MouseEvent e) {
        if (!active || e.getButton() != MouseButton.PRIMARY) return;

        double sx = e.getX();
        double sy = e.getY();

        if (completed) {
            // Try to start handle drag
            int handle = findHandle(sx, sy);
            if (handle >= 0) {
                dragHandleIndex = handle;
                e.consume();
            }
            return;
        }

        // Drawing mode
        if (e.getClickCount() == 2) {
            // The preceding single-click event already added a vertex at this location.
            // Keep it — the user intended to place this point and close the polygon.
            if (vertices.size() >= 3 && onPolygonComplete != null) {
                completed = true;
                canvas.setPolygonCompleted(true);
                canvas.setPolygonOverlay(new ArrayList<>(vertices));
                onPolygonComplete.accept(new ArrayList<>(vertices));
            }
            e.consume();
        } else if (e.getClickCount() == 1) {
            double dx = canvas.screenXToDataX(sx);
            double dy = canvas.screenYToDataY(sy);
            vertices.add(new double[]{dx, dy});
            canvas.setPolygonOverlay(new ArrayList<>(vertices));
            e.consume();
        }
    }

    private void handleDragged(MouseEvent e) {
        if (!active || !completed || dragHandleIndex < 0) return;
        if (e.getButton() != MouseButton.PRIMARY) return;

        double dx = canvas.screenXToDataX(e.getX());
        double dy = canvas.screenYToDataY(e.getY());
        vertices.get(dragHandleIndex)[0] = dx;
        vertices.get(dragHandleIndex)[1] = dy;
        canvas.setPolygonOverlay(new ArrayList<>(vertices));
        e.consume();
    }

    private void handleReleased(MouseEvent e) {
        if (!active || !completed || dragHandleIndex < 0) return;

        dragHandleIndex = -1;
        // Fire callback to recompute inside/outside mask
        if (onPolygonComplete != null && vertices.size() >= 3) {
            onPolygonComplete.accept(new ArrayList<>(vertices));
        }
        e.consume();
    }

    private int findHandle(double screenX, double screenY) {
        for (int i = 0; i < vertices.size(); i++) {
            double hx = canvas.dataXToScreenX(vertices.get(i)[0]);
            double hy = canvas.dataYToScreenY(vertices.get(i)[1]);
            if (Math.hypot(screenX - hx, screenY - hy) <= HANDLE_HIT_RADIUS) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Compute a boolean mask: true = inside the polygon.
     * <p>
     * Snapshots the current vertices and delegates to {@link #computeInsideMask(double[],
     * double[], List)}, so this is safe to call from a background thread as long as the
     * snapshot happens on the FX thread.
     */
    public boolean[] computeInsideMask(double[] umapX, double[] umapY) {
        return computeInsideMask(umapX, umapY, new ArrayList<>(vertices));
    }

    /**
     * Compute an inside-mask for an explicit vertex list.
     *
     * <p>This runs once per cell on every gate edit, so on a multi-million-cell slide
     * it is the difference between an instant response and a visible stall. Three
     * things make it cheap:
     * <ul>
     *   <li><b>Bounding-box rejection.</b> A ray-cast costs O(vertices); a box test
     *       costs four comparisons. For a typical gate — a small blob inside a much
     *       larger embedding — the box rejects the large majority of cells before any
     *       ray-cast happens. NaN coordinates fail the box test too (every comparison
     *       against NaN is false), so they are rejected for free rather than needing a
     *       separate {@code isFinite} guard.</li>
     *   <li><b>Flattened vertices.</b> The {@code List<double[]>} form costs a bounds
     *       check plus a pointer dereference per vertex per cell; parallel primitive
     *       arrays are a contiguous read.</li>
     *   <li><b>Parallelism.</b> Each cell is independent and writes a distinct mask
     *       slot, so the scan parallelizes with no synchronization.</li>
     * </ul>
     *
     * @param umapX    x coordinates, one per cell
     * @param umapY    y coordinates, one per cell
     * @param vertices polygon vertices as {@code {x, y}} pairs; fewer than 3 yields an
     *                 all-false mask
     * @return a mask of {@code umapX.length} entries; true = inside
     */
    public static boolean[] computeInsideMask(double[] umapX, double[] umapY,
                                              List<double[]> vertices) {
        int n = umapX.length;
        boolean[] mask = new boolean[n];
        int v = vertices.size();
        if (v < 3) return mask;

        double[] vx = new double[v];
        double[] vy = new double[v];
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < v; i++) {
            double[] pt = vertices.get(i);
            vx[i] = pt[0];
            vy[i] = pt[1];
            if (pt[0] < minX) minX = pt[0];
            if (pt[0] > maxX) maxX = pt[0];
            if (pt[1] < minY) minY = pt[1];
            if (pt[1] > maxY) maxY = pt[1];
        }

        final double bMinX = minX, bMaxX = maxX, bMinY = minY, bMaxY = maxY;
        IntStream.range(0, n).parallel().forEach(i -> {
            double x = umapX[i];
            double y = umapY[i];
            // Negated form so NaN (all comparisons false) is rejected here.
            if (!(x >= bMinX && x <= bMaxX && y >= bMinY && y <= bMaxY)) return;
            mask[i] = pointInPolygon(x, y, vx, vy);
        });
        return mask;
    }

    /**
     * Ray-casting point-in-polygon over flattened vertex arrays.
     * Same algorithm as {@link UmapCanvas#pointInPolygon(double, double, List)}, without
     * the per-vertex indirection — this one runs in the per-cell hot loop.
     */
    static boolean pointInPolygon(double x, double y, double[] vx, double[] vy) {
        boolean inside = false;
        int n = vx.length;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            if (((vy[i] > y) != (vy[j] > y))
                    && (x < (vx[j] - vx[i]) * (y - vy[i]) / (vy[j] - vy[i]) + vx[i])) {
                inside = !inside;
            }
        }
        return inside;
    }

    public List<double[]> getVertices() { return vertices; }
}
