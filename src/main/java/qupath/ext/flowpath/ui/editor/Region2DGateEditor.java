package qupath.ext.flowpath.ui.editor;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import qupath.ext.flowpath.model.EllipseGate;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.MeasuredColumn;
import qupath.ext.flowpath.model.PolygonGate;
import qupath.ext.flowpath.model.RectangleGate;
import qupath.ext.flowpath.model.Region2DGate;
import qupath.ext.flowpath.ui.widgets.ScatterPlotCanvas;

import java.util.ArrayList;
import java.util.List;

/**
 * A polygon, rectangle or ellipse gate: two channels, a shape toolbar and a scatter plot the
 * shape is drawn on. Drawing a shape of another type converts the gate.
 */
final class Region2DGateEditor extends TwoAxisGateEditor<Region2DGate> {

    Region2DGateEditor(Region2DGate gate, EditorContext context) {
        super(gate, context);
    }

    @Override
    Node buildControls(List<HBox> channelRows, List<ComboBox<String>> channelCombos) {
        ComboBox<String> chXCombo = channelCombos.get(0);
        ComboBox<String> chYCombo = channelCombos.get(1);

        ToggleGroup toolGroup = new ToggleGroup();
        ToggleButton polygonBtn = new ToggleButton("Polygon");
        polygonBtn.setToggleGroup(toolGroup);
        ToggleButton rectBtn = new ToggleButton("Rectangle");
        rectBtn.setToggleGroup(toolGroup);
        ToggleButton ellipseBtn = new ToggleButton("Ellipse");
        ellipseBtn.setToggleGroup(toolGroup);
        Button clearShapeBtn = new Button("Clear Shape");
        clearShapeBtn.setOnAction(e -> {
            if (!accepting()) return;
            gate.clearShape();
            // Rebuild to show a fresh scatter with no shape.
            context.show(gate);
            context.gateChanged();
        });
        HBox drawToolbar = new HBox(4, polygonBtn, rectBtn, ellipseBtn, clearShapeBtn);

        switch (gate) {
            case PolygonGate _ -> polygonBtn.setSelected(true);
            case RectangleGate _ -> rectBtn.setSelected(true);
            case EllipseGate _ -> ellipseBtn.setSelected(true);
        }

        // The channel pickers are wired before the plot is built: a gate whose channels this
        // image does not carry still has to accept a channel change — that is the only way to
        // point it at one the image does carry.

        VBox root = new VBox(4,
                channelRows.get(0), channelRows.get(1),
                modeRow,
                sectionHeader("Shape"), drawToolbar);

        if (!hasPlottableAxes()) {
            Label noData = new Label("Load an image to see the scatter plot");
            noData.getStyleClass().add("fp-hint");
            root.getChildren().add(noData);
            return root;
        }

        // The gate itself is the overlay — including a gate whose shape is not drawable yet.
        // It classifies every cell as outside, and the plot says so.
        ScatterPlotCanvas plot = newScatter();

        toolGroup.selectedToggleProperty().addListener((obs, old, val) -> {
            if (val == polygonBtn) plot.setDrawingMode(ScatterPlotCanvas.DrawingMode.POLYGON);
            else if (val == rectBtn) plot.setDrawingMode(ScatterPlotCanvas.DrawingMode.RECTANGLE);
            else if (val == ellipseBtn) plot.setDrawingMode(ScatterPlotCanvas.DrawingMode.ELLIPSE);
            else plot.setDrawingMode(ScatterPlotCanvas.DrawingMode.NONE);
        });

        plot.setOnPolygonDrawn(vertices -> onShapeDrawn(
                PolygonGate.class,
                () -> new PolygonGate(chXCombo.getValue(), chYCombo.getValue()),
                target -> ((PolygonGate) target).setVertices(new ArrayList<>(vertices)),
                true));
        plot.setOnRectangleDrawn(bounds -> onShapeDrawn(
                RectangleGate.class,
                () -> new RectangleGate(chXCombo.getValue(), chYCombo.getValue(),
                        bounds[0], bounds[1], bounds[2], bounds[3]),
                target -> {
                    RectangleGate rg = (RectangleGate) target;
                    rg.setMinX(bounds[0]); rg.setMaxX(bounds[1]);
                    rg.setMinY(bounds[2]); rg.setMaxY(bounds[3]);
                },
                false));
        plot.setOnEllipseDrawn(params -> onShapeDrawn(
                EllipseGate.class,
                () -> new EllipseGate(chXCombo.getValue(), chYCombo.getValue(),
                        params[0], params[1], params[2], params[3]),
                target -> {
                    EllipseGate eg = (EllipseGate) target;
                    eg.setCenterX(params[0]); eg.setCenterY(params[1]);
                    eg.setRadiusX(params[2]); eg.setRadiusY(params[3]);
                },
                false));

        switch (gate) {
            case PolygonGate _ -> plot.setDrawingMode(ScatterPlotCanvas.DrawingMode.POLYGON);
            case RectangleGate _ -> plot.setDrawingMode(ScatterPlotCanvas.DrawingMode.RECTANGLE);
            case EllipseGate _ -> plot.setDrawingMode(ScatterPlotCanvas.DrawingMode.ELLIPSE);
        }

        root.getChildren().add(plot);
        return root;
    }

    /**
     * A shape was drawn. Into the shown gate if it already is that shape; otherwise the shown
     * gate is converted to {@code type} first — a drawn shape is a decision about the gate's
     * type — and the editor is rebuilt for the replacement on the next pulse.
     * <p>
     * Written to the <em>shown</em> gate rather than {@link #gate}: after a conversion this
     * editor is still on screen until its rebuild, and a second shape drawn in that window
     * belongs to the replacement.
     *
     * @param applyAlways whether {@code apply} also runs on a freshly created replacement (a
     *                    polygon is created empty; a rectangle or ellipse from the drawn bounds)
     */
    private void onShapeDrawn(Class<? extends Region2DGate> type,
                              java.util.function.Supplier<Region2DGate> create,
                              java.util.function.Consumer<GateNode> apply,
                              boolean applyAlways) {
        // isDisposed(), not accepting(): this writes to context.shownGate(), which need not
        // be gate, so accepting()'s "shownGate() == gate" half asks the wrong question here.
        // See AbstractGateTypeEditor#isDisposed for the full asymmetry.
        if (isDisposed()) return;
        GateNode target = context.shownGate();
        if (target == null) return;
        boolean replaced = false;
        if (!type.isInstance(target)) {
            Region2DGate replacement = create.get();
            context.replaceGate(target, replacement);
            target = replacement;
            replaced = true;
        }
        if (!replaced || applyAlways) apply.accept(target);
        scatter.setGateOverlay(target);
        context.gateChanged();
        if (replaced) context.showLater(target);
    }

    /** New data: re-read the plot. A rebuild would discard a half-drawn polygon. */
    @Override
    public void refresh() {
        if (isDisposed()) return;
        redrawScatter();
    }

    /**
     * Carry the drawn region across a column switch by remapping each coordinate to the same
     * percentile of its axis' new column. The per-shape mechanics live on
     * {@link Region2DGate#remapCoordinates}; this only supplies the two axis functions.
     */
    @Override
    Runnable captureForRemap() {
        MeasuredColumn oldX = axisColumn(0);
        MeasuredColumn oldY = axisColumn(1);
        return () -> {
            MeasuredColumn newX = axisColumn(0);
            MeasuredColumn newY = axisColumn(1);
            gate.remapCoordinates(
                    v -> AxisMath.remapRawThreshold(oldX, newX, v),
                    v -> AxisMath.remapRawThreshold(oldY, newY, v));
        };
    }
}
