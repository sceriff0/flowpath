package qupath.ext.flowpath.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Base class for all gate types in the gating hierarchy.
 * <p>
 * Every gate has clipping/outlier parameters and produces N output branches
 * (accessible via {@link #getBranches()}). Subclasses define the gate-specific
 * parameters and evaluation logic.
 * <p>
 * Instantiate this class directly for a threshold gate; there is no separate
 * {@code ThresholdGate} type.
 */
public class GateNode {

    // --- Shared fields (all gate types) ---
    private boolean enabled = true;
    private double clipPercentileLow = 1.0;
    private double clipPercentileHigh = 99.0;
    private boolean excludeOutliers = false;
    // Declared once here, for every gate type: the flag decides whether the engine
    // compares raw intensities or standardised values, and which space the editor
    // draws in. Subclasses must not shadow it, or two gate types end up reading
    // different variables and a gate converted between them changes meaning.
    private boolean thresholdIsZScore = true;

    // --- ThresholdGate-specific fields (kept here for backward compat) ---
    private String channel;
    private double threshold;

    // --- Per-channel measurement compartment + statistic (rich GeoJSON) ---
    // Default to whole-cell median (Mirage always exports "<marker>: Cell: Median").
    // The compartment stays whole-cell so the axis resolves to "<marker>: Cell: Median"
    // on rich data, and GateEditorPane falls back to Mean when no Median column exists.
    private Compartment compartment = Compartment.WHOLE_CELL;
    private Statistic statistic = Statistic.MEDIAN;

    // Branches: index 0 = positive, index 1 = negative
    private final Branch positiveBranch;
    private final Branch negativeBranch;

    /**
     * No-arg constructor for deserialization.
     */
    public GateNode() {
        this.positiveBranch = new Branch("", 0);
        this.negativeBranch = new Branch("", 0);
    }

    public GateNode(String channel) {
        this(channel, 0.0);
    }

    public GateNode(String channel, double threshold) {
        this.channel = channel;
        this.threshold = threshold;
        this.thresholdIsZScore = true;
        int defaultPosColor = (0 << 16) | (200 << 8) | 0; // green
        int defaultNegColor = (128 << 16) | (128 << 8) | 128; // gray
        List<String> names = thresholdBranchNames(channel);
        this.positiveBranch = new Branch(names.get(0), defaultPosColor);
        this.negativeBranch = new Branch(names.get(1), defaultNegColor);
    }

    /**
     * The names this gate's branches carry while the user has not renamed them, for a
     * gate reading {@code channels} (one entry per axis, X first).
     * <p>
     * One spelling, used both by the constructor that first applies the labels and by
     * {@link GateAxis} when an axis is pointed at a different channel — so "is this
     * label still the default?" is answered against the same string the default was
     * built from, for every gate type.
     */
    public List<String> defaultBranchNames(List<String> channels) {
        return thresholdBranchNames(channelAt(channels, 0));
    }

    private static List<String> thresholdBranchNames(String channel) {
        return List.of(channel + "+", channel + "-");
    }

    /** The {@code k}-th channel of a per-axis channel list, or null when absent. */
    protected static String channelAt(List<String> channels, int k) {
        return channels != null && k >= 0 && k < channels.size() ? channels.get(k) : null;
    }

    // ========== Branch-based API (new, generic) ==========

    /**
     * Return the output branches of this gate.
     * Threshold gates return [positive, negative].
     * Override in subclasses for different branch counts.
     */
    public List<Branch> getBranches() {
        return List.of(positiveBranch, negativeBranch);
    }

    /**
     * Return the marker channels this gate operates on.
     * Override in subclasses that use multiple channels.
     */
    public List<String> getChannels() {
        return channel != null ? List.of(channel) : List.of();
    }

    /**
     * Return the per-channel compartments, parallel to {@link #getChannels()}.
     * Override in subclasses that use multiple channels.
     */
    public List<Compartment> getCompartments() {
        return channel != null ? List.of(compartment) : List.of();
    }

    /**
     * Return the per-channel statistics, parallel to {@link #getChannels()}.
     * Override in subclasses that use multiple channels.
     */
    public List<Statistic> getStatistics() {
        return channel != null ? List.of(statistic) : List.of();
    }

    /**
     * Compartment for the {@code k}-th channel of {@link #getChannels()}, or whole-cell
     * when the gate does not specify one. Together with {@link #statisticAt(int)} this
     * is the single place that defines how an axis resolves to a measurement column, so
     * {@code GatingEngine}, {@code PhenotypeCsvExporter} and the gate editor cannot drift
     * apart on it.
     */
    public Compartment compartmentAt(int k) {
        List<Compartment> comps = getCompartments();
        return k >= 0 && k < comps.size() ? comps.get(k) : Compartment.WHOLE_CELL;
    }

    /** Statistic for the {@code k}-th channel of {@link #getChannels()}; mean when unspecified. */
    public Statistic statisticAt(int k) {
        List<Statistic> stats = getStatistics();
        return k >= 0 && k < stats.size() ? stats.get(k) : Statistic.MEAN;
    }

    // ========== Geometry: which branch does a point fall into? ==========

    /**
     * The one spelling of "on the positive side of a 1-D cut": {@code value >= threshold}.
     * <p>
     * Whether a cell exactly <em>on</em> a threshold counts as positive is a decision, not
     * an accident, and it used to be re-typed at every site that drew or classified — the
     * engine walk, the CSV sign column, the histogram bar colours and the scatter plot's
     * quadrant colours. One of them only had to be written {@code >} for the plot to paint
     * a cell in one colour while the phenotype said the other.
     */
    public static boolean isAtOrAbove(double value, double threshold) {
        return value >= threshold;
    }

    /**
     * Is {@code value} on the positive side of this gate's {@code axis}-th 1-D cut?
     * <p>
     * Axes are numbered as in {@link #getChannels()}. Only gate types that impose a
     * per-axis cut answer this — a threshold gate (one axis) and a quadrant gate (two);
     * a region gate has no 1-D cut and refuses (see {@link Region2DGate}).
     */
    public boolean isPositiveAt(int axis, double value) {
        return isAtOrAbove(value, getThreshold());
    }

    /**
     * Which branch of {@link #getBranches()} does a point at plot-space {@code (x, y)}
     * land in? This is the gate's geometry and nothing else: {@code x} and {@code y} are
     * already resolved to this gate's measurement columns and already in the gate's own
     * coordinate space (raw or z-scored, per {@link #isThresholdIsZScore()}).
     * <p>
     * <b>This is the single geometry predicate.</b> {@code GatingEngine} calls it (through
     * {@code ResolvedGate}) to classify; {@code ScatterPlotCanvas} calls it to colour a
     * dot. Sharing it is what stops the plot and the phenotype from disagreeing about a
     * cell on a boundary. A 1-D gate ignores {@code y}.
     */
    public int branchFor(double x, double y) {
        return isPositiveAt(0, x) ? 0 : 1;
    }

    /**
     * Gate type discriminator for serialization.
     */
    public String getGateType() {
        return "threshold";
    }

    // ========== Shared getters/setters ==========

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public double getClipPercentileLow() { return clipPercentileLow; }
    public void setClipPercentileLow(double v) { this.clipPercentileLow = v; }

    public double getClipPercentileHigh() { return clipPercentileHigh; }
    public void setClipPercentileHigh(double v) { this.clipPercentileHigh = v; }

    public boolean isExcludeOutliers() { return excludeOutliers; }
    public void setExcludeOutliers(boolean v) { this.excludeOutliers = v; }

    // ========== ThresholdGate-specific getters/setters ==========

    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }

    public double getThreshold() { return threshold; }
    public void setThreshold(double threshold) { this.threshold = threshold; }

    public boolean isThresholdIsZScore() { return thresholdIsZScore; }
    public void setThresholdIsZScore(boolean v) { this.thresholdIsZScore = v; }

    public Compartment getCompartment() { return compartment; }
    public void setCompartment(Compartment c) { this.compartment = c != null ? c : Compartment.WHOLE_CELL; }

    public Statistic getStatistic() { return statistic; }
    public void setStatistic(Statistic s) { this.statistic = s != null ? s : Statistic.MEAN; }

    // ========== Backward-compatible branch accessors ==========

    public String getPositiveName() { return positiveBranch.getName(); }
    public void setPositiveName(String name) { positiveBranch.setName(name); }

    public String getNegativeName() { return negativeBranch.getName(); }
    public void setNegativeName(String name) { negativeBranch.setName(name); }

    public int getPositiveColor() { return positiveBranch.getColor(); }
    public void setPositiveColor(int color) { positiveBranch.setColor(color); }

    public int getNegativeColor() { return negativeBranch.getColor(); }
    public void setNegativeColor(int color) { negativeBranch.setColor(color); }

    public List<GateNode> getPositiveChildren() { return positiveBranch.getChildren(); }
    public void setPositiveChildren(List<GateNode> children) { positiveBranch.setChildren(children); }

    public List<GateNode> getNegativeChildren() { return negativeBranch.getChildren(); }
    public void setNegativeChildren(List<GateNode> children) { negativeBranch.setChildren(children); }

    public int getPosCount() { return positiveBranch.getCount(); }
    public void setPosCount(int count) { positiveBranch.setCount(count); }

    public int getNegCount() { return negativeBranch.getCount(); }
    public void setNegCount(int count) { negativeBranch.setCount(count); }

    // ========== Generic methods using branches ==========

    public boolean isLeaf() {
        for (Branch b : getBranches()) {
            if (!b.isLeaf()) return false;
        }
        return true;
    }

    public void collectLeafNames(List<String> out) {
        for (Branch branch : getBranches()) {
            if (branch.isLeaf()) {
                out.add(branch.getName());
            } else {
                for (GateNode child : branch.getChildren()) {
                    child.collectLeafNames(out);
                }
            }
        }
    }

    /**
     * Copy shared gate fields (clipping, outlier settings) to a target node.
     * Subclasses should call this in their deepCopy implementations.
     */
    protected void copySharedFieldsTo(GateNode target) {
        target.enabled = this.enabled;
        target.clipPercentileLow = this.clipPercentileLow;
        target.clipPercentileHigh = this.clipPercentileHigh;
        target.excludeOutliers = this.excludeOutliers;
        target.thresholdIsZScore = this.thresholdIsZScore;
        target.compartment = this.compartment;
        target.statistic = this.statistic;
    }

    /**
     * Copy branch metadata and children from this node's branches to the target's.
     * Both nodes must have the same number of branches.
     */
    protected void copyBranchesTo(GateNode target) {
        List<Branch> srcBranches = this.getBranches();
        List<Branch> dstBranches = target.getBranches();
        for (int i = 0; i < srcBranches.size() && i < dstBranches.size(); i++) {
            Branch src = srcBranches.get(i);
            Branch dst = dstBranches.get(i);
            dst.setName(src.getName());
            dst.setColor(src.getColor());
            dst.setChildren(new ArrayList<>());
            for (GateNode child : src.getChildren()) {
                dst.getChildren().add(child.deepCopy());
            }
        }
    }

    /**
     * Create a deep copy of this node and all descendants.
     */
    public GateNode deepCopy() {
        GateNode copy = new GateNode();
        copy.channel = this.channel;
        copy.threshold = this.threshold;
        copy.thresholdIsZScore = this.thresholdIsZScore;
        copySharedFieldsTo(copy);
        copyBranchesTo(copy);
        return copy;
    }

    /**
     * Copy transient counts from another node's branches into this one's.
     */
    public void transferCountsFrom(GateNode source) {
        List<Branch> myBranches = this.getBranches();
        List<Branch> srcBranches = source.getBranches();
        for (int i = 0; i < myBranches.size() && i < srcBranches.size(); i++) {
            myBranches.get(i).transferCountFrom(srcBranches.get(i));
        }
    }
}
