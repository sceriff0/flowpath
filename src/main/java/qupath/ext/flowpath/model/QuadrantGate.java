package qupath.ext.flowpath.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A quadrant gate that splits cells into 4 populations based on thresholds
 * on two marker channels (X and Y axes).
 * <p>
 * The four quadrants are:
 * <ul>
 *   <li>Q1 (++): X >= thresholdX AND Y >= thresholdY (top-right)</li>
 *   <li>Q2 (-+): X < thresholdX AND Y >= thresholdY (top-left)</li>
 *   <li>Q3 (+-): X >= thresholdX AND Y < thresholdY (bottom-right)</li>
 *   <li>Q4 (--): X < thresholdX AND Y < thresholdY (bottom-left)</li>
 * </ul>
 */
public class QuadrantGate extends GateNode {

    private String channelX;
    private String channelY;
    private double thresholdX;
    private double thresholdY;

    // Per-axis measurement compartment + statistic (each channel chosen independently).
    private Compartment compartmentX = Compartment.WHOLE_CELL;
    private Compartment compartmentY = Compartment.WHOLE_CELL;
    private Statistic statisticX = Statistic.MEDIAN;
    private Statistic statisticY = Statistic.MEDIAN;

    // 4 branches: PP(++), NP(-+), PN(+-), NN(--)
    private final Branch branchPP;
    private final Branch branchNP;
    private final Branch branchPN;
    private final Branch branchNN;

    public QuadrantGate() {
        int green = (0 << 16) | (200 << 8) | 0;
        int blue = (0 << 16) | (100 << 8) | 200;
        int orange = (200 << 16) | (150 << 8) | 0;
        int gray = (128 << 16) | (128 << 8) | 128;
        this.branchPP = new Branch("", green);
        this.branchNP = new Branch("", blue);
        this.branchPN = new Branch("", orange);
        this.branchNN = new Branch("", gray);
    }

    public QuadrantGate(String channelX, String channelY) {
        this(channelX, channelY, 0.0, 0.0);
    }

    public QuadrantGate(String channelX, String channelY, double thresholdX, double thresholdY) {
        this.channelX = channelX;
        this.channelY = channelY;
        this.thresholdX = thresholdX;
        this.thresholdY = thresholdY;

        int green = (0 << 16) | (200 << 8) | 0;
        int blue = (0 << 16) | (100 << 8) | 200;
        int orange = (200 << 16) | (150 << 8) | 0;
        int gray = (128 << 16) | (128 << 8) | 128;

        this.branchPP = new Branch(channelX + "+/" + channelY + "+", green);
        this.branchNP = new Branch(channelX + "-/" + channelY + "+", blue);
        this.branchPN = new Branch(channelX + "+/" + channelY + "-", orange);
        this.branchNN = new Branch(channelX + "-/" + channelY + "-", gray);
    }

    @Override
    public List<Branch> getBranches() {
        return List.of(branchPP, branchNP, branchPN, branchNN);
    }

    @Override
    public List<String> getChannels() {
        List<String> channels = new ArrayList<>();
        if (channelX != null) channels.add(channelX);
        if (channelY != null) channels.add(channelY);
        return channels;
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

    @Override
    public String getGateType() {
        return "quadrant";
    }

    @Override
    public String getChannel() {
        return channelX;
    }

    @Override
    public void setChannel(String channel) {
        this.channelX = channel;
    }

    // ---- Quadrant-specific getters/setters ----

    public String getChannelX() { return channelX; }
    public void setChannelX(String channelX) { this.channelX = channelX; }

    public String getChannelY() { return channelY; }
    public void setChannelY(String channelY) { this.channelY = channelY; }

    public double getThresholdX() { return thresholdX; }
    public void setThresholdX(double thresholdX) { this.thresholdX = thresholdX; }

    public double getThresholdY() { return thresholdY; }
    public void setThresholdY(double thresholdY) { this.thresholdY = thresholdY; }

    @Override
    public double getThreshold() { return thresholdX; }

    @Override
    public void setThreshold(double threshold) { this.thresholdX = threshold; }

    // The z-score flag is inherited from GateNode — see the field declaration there
    // for why it is not redeclared per gate type.

    // The base single-channel accessors map to the X axis (mirrors getChannel()).
    @Override
    public Compartment getCompartment() { return compartmentX; }

    @Override
    public void setCompartment(Compartment c) { this.compartmentX = c != null ? c : Compartment.WHOLE_CELL; }

    @Override
    public Statistic getStatistic() { return statisticX; }

    @Override
    public void setStatistic(Statistic s) { this.statisticX = s != null ? s : Statistic.MEAN; }

    public Compartment getCompartmentX() { return compartmentX; }
    public void setCompartmentX(Compartment c) { this.compartmentX = c != null ? c : Compartment.WHOLE_CELL; }

    public Compartment getCompartmentY() { return compartmentY; }
    public void setCompartmentY(Compartment c) { this.compartmentY = c != null ? c : Compartment.WHOLE_CELL; }

    public Statistic getStatisticX() { return statisticX; }
    public void setStatisticX(Statistic s) { this.statisticX = s != null ? s : Statistic.MEAN; }

    public Statistic getStatisticY() { return statisticY; }
    public void setStatisticY(Statistic s) { this.statisticY = s != null ? s : Statistic.MEAN; }

    // Branch accessors
    public Branch getBranchPP() { return branchPP; }
    public Branch getBranchNP() { return branchNP; }
    public Branch getBranchPN() { return branchPN; }
    public Branch getBranchNN() { return branchNN; }

    /**
     * Evaluate which quadrant a cell falls into based on its X and Y values.
     * Returns the branch index: 0=PP, 1=NP, 2=PN, 3=NN.
     */
    public int evaluateQuadrant(double valueX, double valueY) {
        boolean xPos = valueX >= thresholdX;
        boolean yPos = valueY >= thresholdY;
        if (xPos && yPos) return 0;   // PP
        if (!xPos && yPos) return 1;  // NP
        if (xPos) return 2;           // PN
        return 3;                      // NN
    }

    @Override
    public GateNode deepCopy() {
        QuadrantGate copy = new QuadrantGate();
        copy.channelX = this.channelX;
        copy.channelY = this.channelY;
        copy.thresholdX = this.thresholdX;
        copy.thresholdY = this.thresholdY;
        copy.compartmentX = this.compartmentX;
        copy.compartmentY = this.compartmentY;
        copy.statisticX = this.statisticX;
        copy.statisticY = this.statisticY;
        copySharedFieldsTo(copy);
        copyBranchesTo(copy);
        return copy;
    }
}
