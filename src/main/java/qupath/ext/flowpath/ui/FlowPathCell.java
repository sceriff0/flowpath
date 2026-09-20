package qupath.ext.flowpath.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import qupath.ext.flowpath.model.Branch;
import qupath.ext.flowpath.model.ColorUtils;
import qupath.ext.flowpath.model.Compartment;
import qupath.ext.flowpath.model.EllipseGate;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.PolygonGate;
import qupath.ext.flowpath.model.QuadrantGate;
import qupath.ext.flowpath.model.RectangleGate;
import qupath.ext.flowpath.model.Region2DGate;
import qupath.ext.flowpath.model.Statistic;

import java.util.function.Consumer;

/**
 * Custom TreeCell for rendering gate tree items with a polished dark-theme design.
 * <p>
 * Gate nodes render as full-width colored bars with bold channel name and threshold text.
 * Branch items render as colored pill badges with right-aligned counts and a star marker
 * for leaf phenotypes. Supports both threshold (2 branches) and quadrant (4 branches) gates.
 */
public class FlowPathCell extends TreeCell<Object> {

    // Bar/badge/pill colours live in flowpath.css (fp-bar, fp-bar-disabled, fp-badge-*,
    // fp-pill-text) rather than here: they are fixed swatches on this cell's own
    // self-painted chips, not text sitting on the surrounding theme's background, so they
    // stay literal on purpose (see the stylesheet's file header). Centralising them in CSS
    // is what keeps them in one place instead of a dozen setStyle(...) calls.
    private static final CornerRadii PILL_RADII = new CornerRadii(10);
    private static final Insets BAR_PADDING = new Insets(5, 10, 5, 10);
    private static final Insets PILL_PADDING = new Insets(2, 10, 2, 10);
    private static final Insets CELL_PADDING = new Insets(2, 0, 2, 0);
    private static final String STAR = "\u2605";

    /** The hover cue on a row that would take the dragged gate; see {@code flowpath.css}. */
    static final String DROP_TARGET_CLASS = "fp-drop-target";
    /** The hover cue on a row under the cursor that would <em>not</em> take it. */
    static final String DROP_INVALID_CLASS = "fp-drop-invalid";

    private Consumer<GateNode> onEnabledToggled;
    private GateDragCoordinator dragCoordinator;

    public FlowPathCell() {
        installDragHandlers();
    }

    public void setOnEnabledToggled(Consumer<GateNode> callback) {
        this.onEnabledToggled = callback;
    }

    // ---- drag and drop: reordering gates ---------------------------------------------------

    /**
     * The shared state of one gate drag; {@code FlowPathPane}'s cell factory hands every cell
     * the same instance. Without one the cell is inert, which is what keeps this class usable
     * on its own.
     */
    void setDragCoordinator(GateDragCoordinator drag) {
        this.dragCoordinator = drag;
    }

    /**
     * The JavaFX plumbing, and nothing else: each handler translates a {@code DragEvent} into
     * one of the four package-private decisions below and consumes the event. The decisions
     * themselves hold no JavaFX types, which is what makes them testable — {@code
     * startDragAndDrop} needs a real drag gesture from the platform toolkit and cannot be
     * driven by a synthetic event.
     * <p>
     * Every cell consumes {@code DRAG_OVER} and {@code DRAG_DROPPED}, accepted or not, so an
     * unhandled drop never bubbles up to the {@code TreeView} behind it and get taken somewhere
     * the cue never pointed at. The empty rows below the last gate are cells too — that is how
     * "dropped on the tree's background" reaches {@link #dropHere()} as a promotion to a root.
     */
    private void installDragHandlers() {
        setOnDragDetected(event -> {
            if (!beginDrag()) return;
            Dragboard board = startDragAndDrop(TransferMode.MOVE);
            ClipboardContent content = new ClipboardContent();
            // A label for the platform's benefit only: the gate's identity travels in the
            // coordinator, because a channel name cannot tell two same-channel gates apart.
            content.putString(((GateNode) getItem()).getChannel());
            board.setContent(content);
            event.consume();
        });
        setOnDragOver(event -> {
            if (dragOver()) event.acceptTransferModes(TransferMode.MOVE);
            event.consume();
        });
        setOnDragExited(event -> {
            clearDropCue();
            event.consume();
        });
        setOnDragDropped(event -> {
            boolean moved = dropHere();
            clearDropCue();
            event.setDropCompleted(moved);
            event.consume();
        });
        setOnDragDone(event -> {
            dragFinished();
            event.consume();
        });
    }

    /** A drag may start from this row: {@code true} only for a gate row, and only when free. */
    boolean beginDrag() {
        if (dragCoordinator == null || isEmpty()) return false;
        return getItem() instanceof GateNode gate && dragCoordinator.begin(gate);
    }

    /**
     * The cursor is over this row during a gate drag: mark it as a drop target or as visibly
     * non-droppable, and answer whether a drop here would be taken. One question, asked of
     * {@link GateDragCoordinator#accepts}, so the row that highlights is the row that accepts.
     */
    boolean dragOver() {
        boolean accepted = acceptsDrop();
        clearDropCue();
        if (dragInProgress()) {
            getStyleClass().add(accepted ? DROP_TARGET_CLASS : DROP_INVALID_CLASS);
        }
        return accepted;
    }

    /** The gate was dropped on this row. {@code false} — changing nothing — when refused. */
    boolean dropHere() {
        if (!acceptsDrop()) return false;
        return dragCoordinator.drop(dropTargetBranch());
    }

    /** The drag gesture ended, dropped or not. */
    void dragFinished() {
        clearDropCue();
        if (dragCoordinator != null) dragCoordinator.end();
    }

    /** Remove the hover cue. Also done on {@link #updateItem}: cells are recycled per row. */
    void clearDropCue() {
        getStyleClass().removeAll(DROP_TARGET_CLASS, DROP_INVALID_CLASS);
    }

    private boolean dragInProgress() {
        return dragCoordinator != null && dragCoordinator.dragged() != null;
    }

    /**
     * Whether this row can hold a dropped gate at all: a branch row (branches hold children),
     * or the empty space below the last gate, which stands for the root list. A gate row is
     * never a target — a gate's children hang off its branches, not off the gate itself.
     */
    private boolean isDropRow() {
        return isEmpty() || getItem() instanceof BranchItem;
    }

    /** The branch this row drops onto, or {@code null} for the background — the root list. */
    private Branch dropTargetBranch() {
        return getItem() instanceof BranchItem bi ? bi.branch : null;
    }

    private boolean acceptsDrop() {
        return dragInProgress() && isDropRow() && dragCoordinator.accepts(dropTargetBranch());
    }

    /**
     * Wrapper for a branch of a gate (generic — works for any gate type).
     */
    public static class BranchItem {
        public final GateNode parentGate;
        public final Branch branch;
        public final int branchIndex;
        /** @deprecated Use the Branch-based constructor instead. */
        public final boolean isPositive;

        /**
         * New generic constructor using Branch reference.
         */
        public BranchItem(GateNode parentGate, Branch branch, int branchIndex) {
            this.parentGate = parentGate;
            this.branch = branch;
            this.branchIndex = branchIndex;
            this.isPositive = (branchIndex == 0);
        }

        /**
         * Backward-compatible constructor for threshold gates.
         */
        public BranchItem(GateNode parentGate, boolean isPositive) {
            this.parentGate = parentGate;
            this.branchIndex = isPositive ? 0 : 1;
            this.branch = parentGate.getBranches().get(this.branchIndex);
            this.isPositive = isPositive;
        }
    }

    @Override
    protected void updateItem(Object item, boolean empty) {
        super.updateItem(item, empty);

        // A TreeCell is recycled: without this, a row that was highlighted mid-drag carries
        // the cue into whatever row it is scrolled into becoming.
        clearDropCue();

        if (empty || item == null) {
            setText(null);
            setGraphic(null);
            setBackground(Background.EMPTY);
            setPadding(Insets.EMPTY);
            return;
        }

        setPadding(CELL_PADDING);

        if (item instanceof GateNode node) {
            setGraphic(buildGateNodeGraphic(node));
            setText(null);
        } else if (item instanceof BranchItem bi) {
            setGraphic(buildBranchGraphic(bi));
            setText(null);
        } else {
            setText(item.toString());
            setGraphic(null);
        }

    }

    // ---- Gate node: full-width colored bar ------------------------------------------------

    /**
     * Small coloured pill showing a channel's signal compartment (N/C/W), with the
     * statistic appended when it is not the default Mean. Returns {@code null} for the
     * plain whole-cell mean default so unconfigured gates stay visually clean.
     */
    private static Label compartmentBadge(Compartment c, Statistic s) {
        Compartment comp = (c == null) ? Compartment.WHOLE_CELL : c;
        Statistic stat = (s == null) ? Statistic.MEAN : s;
        if (comp == Compartment.WHOLE_CELL && Statistic.MEAN.equals(stat)) return null;

        String text = comp.abbreviation();
        if (!Statistic.MEAN.equals(stat)) text += "·" + stat.displayName().substring(0, 3).toLowerCase();
        Label badge = new Label(text);
        badge.setFont(Font.font(null, FontWeight.BOLD, 9));
        // Not a switch: the compartment vocabulary is open, so a badge has to have a
        // colour for one FlowPath has never seen rather than failing to compile against it.
        // The four swatches themselves live in flowpath.css (fp-badge-*).
        String styleClass;
        if (Compartment.NUCLEAR.equals(comp)) styleClass = "fp-badge-nuclear";
        else if (Compartment.CYTOPLASMIC.equals(comp)) styleClass = "fp-badge-cytoplasmic";
        else if (Compartment.WHOLE_CELL.equals(comp)) styleClass = "fp-badge-wholecell";
        else styleClass = "fp-badge-other";
        badge.getStyleClass().add(styleClass);
        badge.setPadding(new Insets(0, 4, 0, 4));
        badge.setTooltip(new Tooltip(comp.displayName() + " · " + stat.displayName()));
        return badge;
    }

    /** Bold white channel name, as used in every gate bar. */
    private static Label channelLabel(String name) {
        Label label = new Label(name);
        label.setFont(Font.font(null, FontWeight.BOLD, 13));
        label.getStyleClass().add("fp-bar-text");
        return label;
    }

    /** Muted secondary text (threshold readout, gate-type word). */
    private static Label detailLabel(String text, int size) {
        Label label = new Label(text);
        label.setFont(Font.font(null, FontWeight.NORMAL, size));
        label.getStyleClass().add("fp-bar-muted");
        return label;
    }

    /**
     * The gate-type word shown on a 2D region gate. A genuine per-type dispatch over
     * Region2DGate's sealed permits: exhaustive with no default, so a new region shape
     * fails to compile here instead of silently displaying as a generic "region".
     */
    private static String regionTypeName(Region2DGate gate) {
        return switch (gate) {
            case PolygonGate _ -> "polygon";
            case RectangleGate _ -> "rectangle";
            case EllipseGate _ -> "ellipse";
        };
    }

    /**
     * Append {@code X <badge> / Y <badge>} for a two-axis gate. Shared by the quadrant
     * and region gates so both report their signal compartment the same way.
     */
    private static void addAxisLabels(HBox bar,
                                      String channelX, Compartment compX, Statistic statX,
                                      String channelY, Compartment compY, Statistic statY) {
        Label badgeX = compartmentBadge(compX, statX);
        Label badgeY = compartmentBadge(compY, statY);
        Label sep = new Label("/");
        sep.getStyleClass().add("fp-bar-muted");

        bar.getChildren().add(channelLabel(channelX));
        if (badgeX != null) bar.getChildren().add(badgeX);
        bar.getChildren().add(sep);
        bar.getChildren().add(channelLabel(channelY));
        if (badgeY != null) bar.getChildren().add(badgeY);
    }

    private HBox buildGateNodeGraphic(GateNode node) {
        HBox bar = new HBox(6);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(BAR_PADDING);
        bar.getStyleClass().add(node.isEnabled() ? "fp-bar" : "fp-bar-disabled");
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.setOpacity(node.isEnabled() ? 1.0 : 0.5);
        HBox.setHgrow(bar, Priority.ALWAYS);

        // Enabled checkbox — directly in the tree row
        CheckBox enabledBox = new CheckBox();
        enabledBox.setSelected(node.isEnabled());
        enabledBox.selectedProperty().addListener((obs, old, val) -> {
            node.setEnabled(val);
            // Update visual immediately
            bar.setOpacity(val ? 1.0 : 0.5);
            bar.getStyleClass().setAll(val ? "fp-bar" : "fp-bar-disabled");
            if (onEnabledToggled != null) onEnabledToggled.accept(node);
        });

        if (node instanceof QuadrantGate qg) {
            String threshText = qg.isThresholdIsZScore()
                    ? String.format("X:%.2f Y:%.2f", qg.getThresholdX(), qg.getThresholdY())
                    : String.format("X=%.2f Y=%.2f", qg.getThresholdX(), qg.getThresholdY());

            bar.getChildren().add(enabledBox);
            addAxisLabels(bar, qg.getChannelX(), qg.getCompartmentX(), qg.getStatisticX(),
                    qg.getChannelY(), qg.getCompartmentY(), qg.getStatisticY());
            bar.getChildren().add(detailLabel(threshText, 10));
        } else if (node instanceof Region2DGate rg) {
            // Polygon / rectangle / ellipse render identically apart from the type word.
            // They go through the same two-axis layout as the quadrant gate so their
            // compartment badges show too: a nuclear-vs-cytoplasmic region gate was
            // previously indistinguishable from a whole-cell one in the tree.
            bar.getChildren().add(enabledBox);
            addAxisLabels(bar, rg.getChannelX(), rg.getCompartmentX(), rg.getStatisticX(),
                    rg.getChannelY(), rg.getCompartmentY(), rg.getStatisticY());
            bar.getChildren().add(detailLabel(regionTypeName(rg), 10));
        } else {
            String thresholdText = node.isThresholdIsZScore()
                    ? String.format("z = %.3f", node.getThreshold())
                    : String.format("t = %.3f", node.getThreshold());

            Label badge = compartmentBadge(node.getCompartment(), node.getStatistic());
            bar.getChildren().add(enabledBox);
            bar.getChildren().add(channelLabel(node.getChannel()));
            if (badge != null) bar.getChildren().add(badge);
            bar.getChildren().add(detailLabel(thresholdText, 11));
        }

        return bar;
    }

    // ---- Branch item: colored pill/badge --------------------------------------------------

    private HBox buildBranchGraphic(BranchItem bi) {
        Branch branch = bi.branch;
        Color pillColor = ColorUtils.intToColor(branch.getColor());
        String name = branch.getName();
        int count = branch.getCount();
        boolean isLeaf = branch.isLeaf();

        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(row, Priority.ALWAYS);

        HBox pill = new HBox(4);
        pill.setAlignment(Pos.CENTER_LEFT);
        pill.setPadding(PILL_PADDING);
        pill.setBackground(new Background(new BackgroundFill(pillColor, PILL_RADII, Insets.EMPTY)));

        String displayName = isLeaf ? (STAR + " " + name) : name;
        Label nameLabel = new Label(displayName);
        nameLabel.getStyleClass().add("fp-pill-text");
        nameLabel.setFont(Font.font(null, isLeaf ? FontWeight.BOLD : FontWeight.NORMAL, 12));

        pill.getChildren().add(nameLabel);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // Show count with percentage relative to parent gate total
        int totalParent = 0;
        for (Branch b : bi.parentGate.getBranches()) {
            totalParent += b.getCount();
        }
        String countText = totalParent > 0
            ? String.format("%,d (%.1f%%)", count, 100.0 * count / totalParent)
            : String.format("%,d", count);
        Label countLabel = new Label(countText);
        countLabel.setFont(Font.font(null, FontWeight.NORMAL, 11));
        countLabel.getStyleClass().add("fp-muted");

        row.getChildren().addAll(pill, spacer, countLabel);
        return row;
    }
}
