package qupath.ext.flowpath.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared base for the 2D region gates — {@link PolygonGate}, {@link RectangleGate}
 * and {@link EllipseGate}. Holds the two marker axes (each with its own compartment
 * and statistic) and the inside/outside branches; subclasses add only their shape
 * parameters and the containment test.
 * <p>
 * {@link #getCompartments()} and {@link #getStatistics()} are kept strictly parallel
 * to {@link #getChannels()}, which is the contract {@code GatingEngine} and
 * {@code PhenotypeCsvExporter} rely on to resolve each axis to a measurement column.
 */
public abstract class Region2DGate extends GateNode {

    private static final int GREEN = (0 << 16) | (200 << 8) | 0;
    private static final int GRAY = (128 << 16) | (128 << 8) | 128;

    private String channelX;
    private String channelY;
    private Compartment compartmentX = Compartment.WHOLE_CELL;
    private Compartment compartmentY = Compartment.WHOLE_CELL;
    private Statistic statisticX = Statistic.MEDIAN;
    private Statistic statisticY = Statistic.MEDIAN;

    private final Branch insideBranch;
    private final Branch outsideBranch;

    /** No-arg constructor for deserialization. */
    protected Region2DGate() {
        this.insideBranch = new Branch("Inside", GREEN);
        this.outsideBranch = new Branch("Outside", GRAY);
    }

    protected Region2DGate(String channelX, String channelY) {
        this.channelX = channelX;
        this.channelY = channelY;
        this.insideBranch = new Branch(channelX + "/" + channelY + " (in)", GREEN);
        this.outsideBranch = new Branch(channelX + "/" + channelY + " (out)", GRAY);
    }

    /** Does the point fall inside this gate's region, in the gate's own coordinate space? */
    public abstract boolean contains(double x, double y);

    /** Branch 0 is inside the region, branch 1 outside. */
    @Override
    public int branchFor(double x, double y) {
        return contains(x, y) ? 0 : 1;
    }

    /**
     * A region gate has no 1-D cut — positivity on a single axis is not defined for it,
     * and answering anyway (with the unused inherited {@code threshold} field, which is
     * always 0) would look like a real answer. Callers that mix 1-D cuts and regions
     * (the CSV {@code _sign} column) must ask {@link #branchFor} with both axes instead.
     */
    @Override
    public boolean isPositiveAt(int axis, double value) {
        throw new UnsupportedOperationException(
                getGateType() + " gates have no 1-D cut; use branchFor(x, y)");
    }

    @Override
    public List<Branch> getBranches() {
        return List.of(insideBranch, outsideBranch);
    }

    @Override
    public List<String> getChannels() {
        List<String> ch = new ArrayList<>();
        if (channelX != null) ch.add(channelX);
        if (channelY != null) ch.add(channelY);
        return ch;
    }

    @Override
    public List<Compartment> getCompartments() {
        List<Compartment> out = new ArrayList<>();
        if (channelX != null) out.add(compartmentX);
        if (channelY != null) out.add(compartmentY);
        return out;
    }

    @Override
    public List<Statistic> getStatistics() {
        List<Statistic> out = new ArrayList<>();
        if (channelX != null) out.add(statisticX);
        if (channelY != null) out.add(statisticY);
        return out;
    }

    @Override public String getChannel() { return channelX; }
    @Override public void setChannel(String channel) { this.channelX = channel; }

    public String getChannelX() { return channelX; }
    public void setChannelX(String v) { this.channelX = v; }
    public String getChannelY() { return channelY; }
    public void setChannelY(String v) { this.channelY = v; }

    public Compartment getCompartmentX() { return compartmentX; }
    public void setCompartmentX(Compartment c) { this.compartmentX = c != null ? c : Compartment.WHOLE_CELL; }
    public Compartment getCompartmentY() { return compartmentY; }
    public void setCompartmentY(Compartment c) { this.compartmentY = c != null ? c : Compartment.WHOLE_CELL; }

    public Statistic getStatisticX() { return statisticX; }
    public void setStatisticX(Statistic s) { this.statisticX = s != null ? s : Statistic.MEAN; }
    public Statistic getStatisticY() { return statisticY; }
    public void setStatisticY(Statistic s) { this.statisticY = s != null ? s : Statistic.MEAN; }

    public Branch getInsideBranch() { return insideBranch; }
    public Branch getOutsideBranch() { return outsideBranch; }

    /**
     * Copy the shared axis fields (channels, compartments, statistics) into
     * {@code target}. Subclass {@code deepCopy} calls this alongside
     * {@code copySharedFieldsTo} and {@code copyBranchesTo}.
     */
    protected void copyAxesTo(Region2DGate target) {
        target.channelX = this.channelX;
        target.channelY = this.channelY;
        target.compartmentX = this.compartmentX;
        target.compartmentY = this.compartmentY;
        target.statisticX = this.statisticX;
        target.statisticY = this.statisticY;
    }
}
