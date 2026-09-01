package qupath.ext.flowpath.ui;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.MouseEvent;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import qupath.ext.flowpath.model.GateNode;

import java.util.function.DoubleConsumer;

/**
 * Canvas-based histogram with a draggable red threshold line.
 * Uses direct Canvas drawing instead of BarChart to avoid 200+ Node objects.
 * Supports percentile clipping for display range.
 */
public class HistogramCanvas extends Canvas {

    private static final int NUM_BINS = 200;
    private static final double PADDING_LEFT = 40;
    private static final double PADDING_RIGHT = 10;
    private static final double PADDING_TOP = 10;
    private static final double PADDING_BOTTOM = 25;

    private double[] binEdges;
    private double[] binCounts;
    private double displayMin;
    private double displayMax;
    private double threshold = Double.NaN;
    private GateNode gate;
    private Color posColor = Color.rgb(0, 200, 0);
    private Color negColor = Color.rgb(160, 160, 160);
    private double maxCount;
    // Number of values passed to setData before any clip/bin logic.
    // Used to distinguish "truly empty" from "all values outside clip range"
    // when rendering the empty-histogram message.
    private int inputCount;

    private int posCount = -1;
    private int negCount = -1;

    private boolean dragging = false;
    private DoubleConsumer onThresholdChanged;
    private DoubleConsumer onMouseHover;

    public HistogramCanvas() {
        super(380, 180);
        setOnMousePressed(this::handleMousePressed);
        setOnMouseDragged(this::handleMouseDragged);
        setOnMouseReleased(this::handleMouseReleased);
        setOnMouseMoved(this::handleMouseMoved);

        // Resize listener
        widthProperty().addListener((obs, oldVal, newVal) -> repaint());
        heightProperty().addListener((obs, oldVal, newVal) -> repaint());
    }

    @Override
    public boolean isResizable() {
        return true;
    }

    @Override
    public double prefWidth(double height) {
        return 380;
    }

    @Override
    public double prefHeight(double width) {
        return 180;
    }

    @Override
    public double minWidth(double height) {
        return 200;
    }

    @Override
    public double minHeight(double width) {
        return 100;
    }

    @Override
    public double maxWidth(double height) {
        return Double.MAX_VALUE;
    }

    @Override
    public double maxHeight(double width) {
        return Double.MAX_VALUE;
    }

    @Override
    public void resize(double width, double height) {
        setWidth(width);
        setHeight(height);
        repaint();
    }

    /**
     * Set histogram data with display range (clipped by percentiles).
     */
    public void setData(double[] rawValues, double clipMin, double clipMax) {
        this.inputCount = rawValues == null ? 0 : rawValues.length;
        if (rawValues == null || rawValues.length == 0) {
            binEdges = null;
            binCounts = null;
            repaint();
            return;
        }

        this.displayMin = clipMin;
        this.displayMax = clipMax;

        if (displayMax <= displayMin) {
            displayMax = displayMin + 1;
        }

        // Compute histogram bins within clip range
        binEdges = new double[NUM_BINS + 1];
        binCounts = new double[NUM_BINS];
        double binWidth = (displayMax - displayMin) / NUM_BINS;

        for (int i = 0; i <= NUM_BINS; i++) {
            binEdges[i] = displayMin + i * binWidth;
        }

        for (double val : rawValues) {
            // NaN first, and explicitly. MIRAGE omits an absent measurement entirely, so
            // this column genuinely holds NaN for cells the marker was never measured on
            // -- ordinary input, not corruption. Both comparisons below are false for NaN
            // (every NaN comparison is), so the range guard waves it through, and
            // `(int) NaN` is 0: every unmeasured cell used to pile into the leftmost bin,
            // which sits below the threshold and is therefore painted in the negative
            // colour. That is "unmeasured is not negative" violated in the display path --
            // the same defect the engine's UNMEASURED sentinel exists to prevent, showing
            // up as a histogram that disagreed with the counts beside it.
            if (Double.isNaN(val)) continue;
            if (val < displayMin || val > displayMax) continue;
            int bin = (int) ((val - displayMin) / binWidth);
            if (bin >= NUM_BINS) bin = NUM_BINS - 1;
            if (bin < 0) bin = 0;
            binCounts[bin]++;
        }

        maxCount = 0;
        for (double c : binCounts) {
            if (c > maxCount) maxCount = c;
        }

        repaint();
    }

    /**
     * How many cells the last {@link #setData} actually drew, summed over every bin.
     * <p>
     * Exists so a test can assert what the picture claims about the population size
     * without rendering it. The number that matters is the one this excludes: a cell with
     * no measurement is not drawn at all, so it can never be read off the plot as a
     * low-valued — and therefore negative — cell.
     */
    int binnedTotal() {
        if (binCounts == null) return 0;
        double sum = 0;
        for (double c : binCounts) sum += c;
        return (int) sum;
    }

    /** The count in one bin, left to right. Package-private for the same reason. */
    int binCount(int bin) {
        return binCounts == null ? 0 : (int) binCounts[bin];
    }

    /** How many bins {@link #setData} builds. */
    static int binCountTotal() {
        return NUM_BINS;
    }

    /**
     * The gate this histogram is showing. Held so the bar colours come from the gate's
     * own geometry rather than a second copy of the threshold comparison.
     */
    public void setGate(GateNode gate) {
        this.gate = gate;
        repaint();
    }

    public void setThreshold(double threshold) {
        this.threshold = threshold;
        repaint();
    }

    public void setPosColor(Color color) {
        this.posColor = color;
        repaint();
    }

    public void setNegColor(Color color) {
        this.negColor = color;
        repaint();
    }

    public void setOnThresholdChanged(DoubleConsumer callback) {
        this.onThresholdChanged = callback;
    }

    public void setPosCount(int count) {
        this.posCount = count;
        repaint();
    }

    public void setNegCount(int count) {
        this.negCount = count;
        repaint();
    }

    public void setOnMouseHover(DoubleConsumer callback) {
        this.onMouseHover = callback;
    }

    private void repaint() {
        GraphicsContext gc = getGraphicsContext2D();
        double w = getWidth();
        double h = getHeight();

        // Clear
        gc.setFill(Color.rgb(30, 30, 30));
        gc.fillRect(0, 0, w, h);

        if (binEdges == null || binCounts == null || maxCount <= 0) {
            gc.setFill(Color.gray(0.5));
            gc.setFont(Font.font(12));
            if (inputCount > 0) {
                // Data exists but every value fell outside the current clip range
                // (common after cascaded threshold gates with narrow distributions).
                // Report the true cell count so the user isn't misled into thinking
                // the branch is empty — the stats panel and CSV remain authoritative.
                String msg = String.format("%,d cells outside clip range", inputCount);
                double approxW = msg.length() * 6.5;
                gc.fillText(msg, Math.max(4, w / 2 - approxW / 2), h / 2);
            } else {
                gc.fillText("No data", w / 2 - 20, h / 2);
            }
            return;
        }

        double plotW = w - PADDING_LEFT - PADDING_RIGHT;
        double plotH = h - PADDING_TOP - PADDING_BOTTOM;
        double binPixelWidth = plotW / NUM_BINS;

        // Draw bins
        for (int i = 0; i < NUM_BINS; i++) {
            double binCenter = (binEdges[i] + binEdges[i + 1]) / 2.0;
            boolean isPositive = isPositiveAt(binCenter);

            Color barColor = isPositive ? posColor.deriveColor(0, 1, 1, 0.8) : negColor.deriveColor(0, 1, 1, 0.8);
            gc.setFill(barColor);

            double barH = (binCounts[i] / maxCount) * plotH;
            double x = PADDING_LEFT + i * binPixelWidth;
            double y = PADDING_TOP + plotH - barH;

            gc.fillRect(x, y, Math.max(binPixelWidth - 0.5, 1), barH);
        }

        // Draw threshold line
        if (!Double.isNaN(threshold) && threshold >= displayMin && threshold <= displayMax) {
            double threshX = PADDING_LEFT + ((threshold - displayMin) / (displayMax - displayMin)) * plotW;
            gc.setStroke(Color.RED);
            gc.setLineWidth(2);
            gc.strokeLine(threshX, PADDING_TOP, threshX, PADDING_TOP + plotH);

            // Threshold label
            gc.setFill(Color.WHITE);
            gc.setFont(Font.font(10));
            String label = String.format("%.3f", threshold);
            gc.fillText(label, threshX + 3, PADDING_TOP + 12);
        }

        // Draw pos/neg count annotations above the histogram
        if (!Double.isNaN(threshold) && threshold >= displayMin && threshold <= displayMax) {
            double threshX = PADDING_LEFT + ((threshold - displayMin) / (displayMax - displayMin)) * plotW;
            gc.setFont(Font.font(10));
            if (negCount >= 0) {
                gc.setFill(Color.gray(0.8));
                String negLabel = String.format("Neg: %,d", negCount);
                gc.fillText(negLabel, PADDING_LEFT + 4, PADDING_TOP - 2);
            }
            if (posCount >= 0) {
                gc.setFill(posColor.brighter());
                String posLabel = String.format("Pos: %,d", posCount);
                double posLabelWidth = posLabel.length() * 6.0;
                gc.fillText(posLabel, w - PADDING_RIGHT - posLabelWidth, PADDING_TOP - 2);
            }
        }

        // Draw axes labels
        gc.setFill(Color.gray(0.7));
        gc.setFont(Font.font(9));
        gc.fillText(String.format("%.2f", displayMin), PADDING_LEFT, h - 3);
        String maxLabel = String.format("%.2f", displayMax);
        gc.fillText(maxLabel, w - PADDING_RIGHT - maxLabel.length() * 5, h - 3);

        // Y axis: max count
        gc.fillText(String.format("%.0f", maxCount), 2, PADDING_TOP + 10);

        // Border
        gc.setStroke(Color.gray(0.3));
        gc.setLineWidth(1);
        gc.strokeRect(PADDING_LEFT, PADDING_TOP, plotW, plotH);
    }

    /**
     * Does a value at {@code v} fall in the positive branch of the gate this histogram is
     * showing? The gate answers — this is the same {@link GateNode#isPositiveAt} the
     * engine classifies with, so a bar can never be coloured positive while the cells
     * under it are classified negative. Without a gate (nothing to show yet) the
     * standalone threshold is compared through the same primitive.
     * <p>
     * Package-private so the display/classification agreement test can call it directly.
     */
    boolean isPositiveAt(double v) {
        if (gate != null) return gate.isPositiveAt(0, v);
        return !Double.isNaN(threshold) && GateNode.isAtOrAbove(v, threshold);
    }

    private double xToValue(double x) {
        double plotW = getWidth() - PADDING_LEFT - PADDING_RIGHT;
        double frac = (x - PADDING_LEFT) / plotW;
        frac = Math.max(0, Math.min(1, frac));
        return displayMin + frac * (displayMax - displayMin);
    }

    private void handleMousePressed(MouseEvent e) {
        if (binEdges == null) return;
        dragging = true;
        double val = xToValue(e.getX());
        threshold = val;
        repaint();
        if (onThresholdChanged != null) onThresholdChanged.accept(threshold);
    }

    private void handleMouseDragged(MouseEvent e) {
        if (!dragging || binEdges == null) return;
        double val = xToValue(e.getX());
        threshold = val;
        repaint();
        if (onThresholdChanged != null) onThresholdChanged.accept(threshold);
    }

    private void handleMouseReleased(MouseEvent e) {
        dragging = false;
    }

    private void handleMouseMoved(MouseEvent e) {
        if (onMouseHover != null && binEdges != null) {
            onMouseHover.accept(xToValue(e.getX()));
        }
    }
}
