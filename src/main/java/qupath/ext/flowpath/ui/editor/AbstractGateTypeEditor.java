package qupath.ext.flowpath.ui.editor;

import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.util.StringConverter;
import qupath.ext.flowpath.model.Compartment;
import qupath.ext.flowpath.model.GateAxis;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.MeasuredColumn;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.model.ValueMode;

import java.util.ArrayList;
import java.util.List;

/**
 * What every gate type's editor does the same way: channel pickers, the per-axis signal
 * selectors, the "Values" row, and carrying the gate across a change of column.
 * <p>
 * Every control handler asks {@link #accepting()} first. That is the whole stale-control
 * guard: once disposed, or once the pane shows another gate, an editor writes to nothing.
 */
abstract class AbstractGateTypeEditor<G extends GateNode> implements GateTypeEditor {

    final G gate;
    final EditorContext context;
    private boolean disposed;

    private final ToggleGroup modeGroup = new ToggleGroup();
    /**
     * The "Values" selector, rebuilt from {@link ValueMode#availableFor} whenever the gate or
     * its resolved columns change.
     */
    final HBox modeRow;
    /** The mode the gate is in, so a selection change knows what it is changing <em>from</em>. */
    private ValueMode currentMode;

    AbstractGateTypeEditor(G gate, EditorContext context) {
        this.gate = gate;
        this.context = context;
        Label valuesLabel = new Label("Values:");
        valuesLabel.getStyleClass().add("fp-primary-text");
        // Built empty; syncModeSelection fills it from what the file turns out to carry.
        modeRow = new HBox(12, valuesLabel);
        modeRow.setAlignment(Pos.CENTER_LEFT);
        modeGroup.selectedToggleProperty().addListener((obs, old, val) -> {
            if (!accepting() || val == null) return;
            if (val.getUserData() instanceof ValueMode selected) onModeSelected(selected);
        });
    }

    @Override
    public void dispose() {
        disposed = true;
    }

    /**
     * Only the disposed flag -- narrower than {@link #accepting()}, which also requires the
     * pane to still be showing {@link #gate} and no programmatic control set to be in
     * progress. Most control handlers want the full guard; {@code Region2DGateEditor
     * #onShapeDrawn} deliberately uses this one alone, because it acts on whatever gate
     * {@code context.shownGate()} currently is (possibly a replacement it creates right
     * there), not on {@link #gate} specifically, so the {@code shownGate() == gate} half of
     * {@link #accepting()} would be the wrong question for it to ask. Both share one
     * definition of "disposed"; they differ in how much else they additionally require.
     */
    final boolean isDisposed() {
        return disposed;
    }

    /**
     * Whether a control event may write to {@link #gate}: this editor is live, the pane still
     * shows this gate, and nobody is setting controls programmatically.
     */
    final boolean accepting() {
        return !disposed && !context.eventsSuppressed() && context.shownGate() == gate;
    }

    // ---- columns ------------------------------------------------------------------------

    /**
     * Resolved column for axis {@code slot} of the gate, or {@code null} when the gate has no
     * such axis or there is nothing to resolve against yet. Addressed by slot, through
     * {@link GateAxis}, so an unset X channel cannot shift the Y axis into slot 0.
     */
    final MeasuredColumn axisColumn(int slot) {
        if (slot >= GateAxis.axisCount(gate)) return null;
        return GateAxis.of(gate, slot).columnIn(context.cellIndex(), context.markerStats());
    }

    /** {@code col}'s clip window under this gate's clip percentiles, or {@code null}. */
    final double[] clipSpan(MeasuredColumn col) {
        return AxisMath.clipSpan(col, gate.getClipPercentileLow(), gate.getClipPercentileHigh());
    }

    // ---- the build ----------------------------------------------------------------------------

    /**
     * Build the editor in the one order that reads no column before it is pinned: a channel row
     * per axis (which pins that axis to a signal the export carries), then the Values row
     * (derived from the pinned signals), then the type's own controls (which read the pinned
     * columns). Each type used to sequence these by hand, and two of three got it wrong at some
     * point: the quadrant ranged its sliders on the stored signal, and the region derived its
     * Values row from it, hiding a MIRAGE z column the pinned signal offers.
     */
    @Override
    public final Node build() {
        int axes = GateAxis.axisCount(gate);
        List<HBox> rows = new ArrayList<>(axes);
        List<ComboBox<String>> combos = new ArrayList<>(axes);
        for (int slot = 0; slot < axes; slot++) {
            ComboBox<String> combo = channelCombo(GateAxis.of(gate, slot).channel(), axes == 1 ? 200 : 150);
            if (axes == 1) combo.setTooltip(new Tooltip("Select the marker channel for this gate"));
            String label = axes == 1 ? "Channel:" : (slot == 0 ? "Channel X:" : "Channel Y:");
            rows.add(channelRow(label, combo, slot));
            wireChannelCombo(combo, slot);
            combos.add(combo);
        }
        syncModeSelection();
        return buildControls(rows, combos);
    }

    /**
     * The type's own controls, laid out with {@code channelRows} and {@link #modeRow}. Every
     * axis is already pinned and the Values row already derived when this runs.
     *
     * @param channelRows   one row per axis, in slot order
     * @param channelCombos the channel picker in each row, in slot order
     */
    abstract Node buildControls(List<HBox> channelRows, List<ComboBox<String>> channelCombos);

    // ---- channel and signal controls ------------------------------------------------------

    /** A channel picker over the pane's live channel list, showing {@code value}. */
    private ComboBox<String> channelCombo(String value, double prefWidth) {
        ComboBox<String> combo = new ComboBox<>(context.channelNames());
        combo.setPrefWidth(prefWidth);
        combo.setValue(value);
        return combo;
    }

    /**
     * Point {@code combo} at axis {@code slot}: everything a channel change implies is
     * {@link GateAxis#retarget}'s — it repoints the axis, re-pins its signal to a column the
     * new channel is quantified with, and moves the branch labels the user has not claimed.
     * The editor then refreshes the branch names and queues a rebuild, which is what
     * re-derives the signal selectors: a legacy channel offers no compartment choice, and one
     * that replaces it must stop showing one.
     * <p>
     * There is deliberately no immediate plot refresh. {@code retarget} pins before returning,
     * so the rebuild draws the right thing the first time, and a second drawing path is one
     * more place for the two to disagree.
     */
    private void wireChannelCombo(ComboBox<String> combo, int slot) {
        combo.setOnAction(e -> {
            if (!accepting()) return;
            if (!GateAxis.of(gate, slot).retarget(combo.getValue(), context.capability())) return;
            context.branchNamesChanged();
            context.gateChanged();
            context.showLater(gate);
        });
    }

    /** A row holding a styled label and a channel picker, followed by that axis' signal controls. */
    private HBox channelRow(String labelText, ComboBox<String> combo, int slot) {
        Label label = new Label(labelText);
        label.getStyleClass().add("fp-primary-text");
        HBox row = new HBox(8, label, combo);
        addSignalControls(row, GateAxis.of(gate, slot));
        return row;
    }

    /**
     * Append a "Signal:" compartment selector (and, when the export carries more than one
     * statistic, a statistic selector) to {@code row} for one gate axis.
     * <p>
     * The layout is the editor's; the decision is {@link GateAxis}'. {@link
     * GateAxis#choicesFrom} answers both what may be offered and what the axis must be read
     * as, and the axis is pinned to that signal <em>whether or not</em> a selector appears.
     * Skipping the pin because there was nothing to show is how a gate ended up on
     * {@code "<marker>: <Compartment>: Mean"} — MIRAGE's default quantification emits Median
     * only, so that column is not in the file, and the axis read NaN for every cell.
     */
    private void addSignalControls(HBox row, GateAxis axis) {
        GateAxis.Choices choices = axis.choicesFrom(context.capability());
        axis.apply(choices.signal());
        if (!choices.offersCompartment()) return;

        String channel = axis.channel();
        ComboBox<Compartment> compCombo =
                new ComboBox<>(FXCollections.observableArrayList(choices.compartments()));
        compCombo.setValue(choices.signal().compartment());
        compCombo.setConverter(new StringConverter<>() {
            @Override public String toString(Compartment c) { return c == null ? "" : c.displayName(); }
            @Override public Compartment fromString(String s) { return null; }
        });
        compCombo.setTooltip(new Tooltip("Signal compartment for " + channel));
        compCombo.setOnAction(e -> {
            if (!accepting()) return;
            applySignalChange(() ->
                    axis.apply(new GateAxis.Signal(compCombo.getValue(), axis.statistic())));
        });
        Label sigLabel = new Label("Signal:");
        sigLabel.getStyleClass().add("fp-primary-text");
        row.getChildren().addAll(sigLabel, compCombo);

        if (!choices.offersStatistic()) return;
        ComboBox<Statistic> statCombo =
                new ComboBox<>(FXCollections.observableArrayList(choices.statistics()));
        statCombo.setValue(choices.signal().statistic());
        statCombo.setConverter(new StringConverter<>() {
            @Override public String toString(Statistic s) { return s == null ? "" : s.displayName(); }
            @Override public Statistic fromString(String s) { return null; }
        });
        statCombo.setTooltip(new Tooltip("Summary statistic for " + channel));
        statCombo.setOnAction(e -> {
            if (!accepting()) return;
            applySignalChange(() ->
                    axis.apply(new GateAxis.Signal(axis.compartment(), statCombo.getValue())));
        });
        row.getChildren().add(statCombo);
    }

    // ---- the "Values" row -----------------------------------------------------------------

    /**
     * Rebuild the "Values" row from what this gate can actually offer, and select the mode it
     * is in.
     * <p>
     * The row is <b>derived, never set</b>: {@link ValueMode#availableFor} is the only thing
     * that decides which buttons exist, from the gate, the capability scanned at ingest and
     * (when loaded) the data. This method renders that answer and decides nothing.
     * <p>
     * When the gate's own combination is not on offer — a saved gate pinned to a column this
     * file does not carry — {@link ValueMode#selectedIn} falls back to raw, and that fallback
     * is <b>written back onto the gate</b>, so the engine never reads a column the editor has
     * stopped drawing. During a build that write is not a user edit: {@code GateEditorPane}
     * notices the gate's signals changed and reports it through {@code onNodeNormalised}, so it
     * is settled on its own rather than folded into the next edit's undo step.
     */
    private void syncModeSelection() {
        List<ValueMode> modes = ValueMode.availableFor(gate, context.capability());
        ValueMode selected = ValueMode.selectedIn(modes, gate);

        // One mode is not a choice. On a typical export the file carries no pre-standardised
        // column, so there is exactly one way to read the gate and the row is hidden rather
        // than shown with a single button nobody can act on.
        boolean offerRow = ValueMode.isAChoice(modes);

        context.withSuppressedEvents(() -> {
            modeGroup.getToggles().clear();
            modeRow.getChildren().remove(1, modeRow.getChildren().size());
            modeRow.setVisible(offerRow);
            modeRow.setManaged(offerRow);
            if (!offerRow) return;
            for (ValueMode mode : modes) {
                RadioButton button = new RadioButton(mode.label());
                button.setToggleGroup(modeGroup);
                button.setUserData(mode);
                button.getStyleClass().add("fp-primary-text");
                button.setTooltip(new Tooltip(mode.tooltip()));
                if (mode.equals(selected)) button.setSelected(true);
                modeRow.getChildren().add(button);
            }
        });

        currentMode = selected;

        // A gate saved under the retired computed z-score is not migrated here:
        // LegacyZScoreMigration converts the whole tree when it first meets an index.
        if (selected != null && !alreadyIn(gate, selected)) {
            selected.applyTo(gate);
        }
    }

    /**
     * Move the gate into {@code selected}. Every mode names a different column, so switching
     * is always a change of scale; {@link #applySignalChange} re-maps the threshold to the
     * same percentile of the new column, so the gate keeps the cells it had.
     */
    private void onModeSelected(ValueMode selected) {
        ValueMode previous = currentMode;
        if (previous != null && previous.normalisation().equals(selected.normalisation())) {
            return;
        }
        currentMode = selected;
        applySignalChange(() -> selected.applyTo(gate));
    }

    /** Whether {@code node} already reads the way {@code mode} says it should. */
    private static boolean alreadyIn(GateNode node, ValueMode mode) {
        for (GateAxis axis : GateAxis.axesOf(node)) {
            Statistic statistic = axis.statistic();
            if (statistic == null) continue;
            if (!statistic.normalisation().equals(mode.normalisation())) return false;
        }
        return true;
    }

    // ---- a change of column -----------------------------------------------------------------

    /**
     * Apply a compartment/statistic/mode selection and bring the gate and the editor with it.
     * <p>
     * The gate's geometry is remapped to the same percentile of the newly selected column: a
     * bare number does not carry across columns (a Sum is ~100x the corresponding Mean), so
     * without this the gate silently collapses to "everything positive" or "everything
     * negative". Which numbers that is, and what to redraw afterwards, is each type's.
     */
    final void applySignalChange(Runnable mutation) {
        Runnable remap = captureForRemap();
        mutation.run();
        remap.run();
        // The axis now resolves to a different column, so which modes are on offer has to be
        // asked again: a Median column and a "Median Z" column are not the same offer.
        syncModeSelection();
        afterSignalChange();
    }

    /**
     * Capture the gate's current columns and return what, once the axes have moved, re-maps
     * the gate's geometry onto the new ones.
     */
    abstract Runnable captureForRemap();

    /** Redraw after a signal change and report it. */
    abstract void afterSignalChange();

    // ---- layout helpers ---------------------------------------------------------------------

    static Label sectionHeader(String text) {
        return EditorLabels.sectionHeader(text);
    }

    static Label styledLabel(String text, String styleClass) {
        return EditorLabels.styledLabel(text, styleClass);
    }
}
