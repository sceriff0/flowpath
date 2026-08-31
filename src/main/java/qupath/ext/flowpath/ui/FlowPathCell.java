package qupath.ext.flowpath.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
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

    private static final Color GATE_BAR_COLOR = Color.web("#3a4a5a");
    private static final Color GATE_BAR_DISABLED_COLOR = Color.web("#2a2a2a");
    private static final CornerRadii BAR_RADII = new CornerRadii(6);
    private static final CornerRadii PILL_RADII = new CornerRadii(10);
    private static final Insets BAR_PADDING = new Insets(5, 10, 5, 10);
    private static final Insets PILL_PADDING = new Insets(2, 10, 2, 10);
    private static final Insets CELL_PADDING = new Insets(2, 0, 2, 0);
    private static final String STAR = "\u2605";

    private Consumer<GateNode> onEnabledToggled;

    public void setOnEnabledToggled(Consumer<GateNode> callback) {
        this.onEnabledToggled = callback;
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
        badge.setTextFill(Color.WHITE);
        // Not a switch: the compartment vocabulary is open, so a badge has to have a
        // colour for one FlowPath has never seen rather than failing to compile against it.
        Color bg;
        if (Compartment.NUCLEAR.equals(comp)) bg = Color.web("#3a6ea5");
        else if (Compartment.CYTOPLASMIC.equals(comp)) bg = Color.web("#4a9a5a");
        else if (Compartment.WHOLE_CELL.equals(comp)) bg = Color.web("#777777");
        else bg = Color.web("#8a6ea5");
        badge.setBackground(new Background(new BackgroundFill(bg, new CornerRadii(4), Insets.EMPTY)));
        badge.setPadding(new Insets(0, 4, 0, 4));
        badge.setTooltip(new Tooltip(comp.displayName() + " · " + stat.displayName()));
        return badge;
    }

    /** Bold white channel name, as used in every gate bar. */
    private static Label channelLabel(String name) {
        Label label = new Label(name);
        label.setFont(Font.font(null, FontWeight.BOLD, 13));
        label.setTextFill(Color.WHITE);
        return label;
    }

    /** Muted secondary text (threshold readout, gate-type word). */
    private static Label detailLabel(String text, int size) {
        Label label = new Label(text);
        label.setFont(Font.font(null, FontWeight.NORMAL, size));
        label.setTextFill(Color.web("#a0b0c0"));
        return label;
    }

    /** The gate-type word shown on a 2D region gate. */
    private static String regionTypeName(Region2DGate gate) {
        if (gate instanceof PolygonGate) return "polygon";
        if (gate instanceof RectangleGate) return "rectangle";
        if (gate instanceof EllipseGate) return "ellipse";
        return "region";
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
        sep.setTextFill(Color.web("#a0b0c0"));

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
        Color barColor = node.isEnabled() ? GATE_BAR_COLOR : GATE_BAR_DISABLED_COLOR;
        bar.setBackground(new Background(new BackgroundFill(barColor, BAR_RADII, Insets.EMPTY)));
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
            bar.setBackground(new Background(new BackgroundFill(
                val ? GATE_BAR_COLOR : GATE_BAR_DISABLED_COLOR, BAR_RADII, Insets.EMPTY)));
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
        nameLabel.setTextFill(Color.WHITE);
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
        countLabel.setTextFill(Color.web("#888888"));

        row.getChildren().addAll(pill, spacer, countLabel);
        return row;
    }
}
