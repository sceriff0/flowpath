package qupath.ext.flowpath.ui;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.Compartment;
import qupath.ext.flowpath.model.EllipseGate;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.PolygonGate;
import qupath.ext.flowpath.model.RectangleGate;
import qupath.ext.flowpath.model.Statistic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression coverage for gate-type conversion: drawing a rectangle on a polygon
 * gate's scatter replaces the node with a {@link RectangleGate}, and the new node
 * must inherit everything that defines the coordinate space the user drew in.
 *
 * <p>The drawn coordinates come straight off the scatter plot, which is rendered
 * in z-score space when the gate's z-score flag is set and standardised against
 * each axis' <em>resolved</em> column (channel + compartment + statistic). If the
 * replacement gate does not carry that flag and those axes, {@code GatingEngine}
 * evaluates the drawn boundary in a different space than it was drawn in and
 * every cell is misclassified — silently, because the shape still renders.
 */
class GateTypeConversionTest {

    private static PolygonGate zScoredNuclearMedianPolygon() {
        PolygonGate source = new PolygonGate("CD3", "CD8");
        source.setThresholdIsZScore(true);
        source.setCompartmentX(Compartment.NUCLEAR);
        source.setStatisticX(Statistic.MEDIAN);
        source.setCompartmentY(Compartment.CYTOPLASMIC);
        source.setStatisticY(Statistic.SUM);
        source.setVertices(List.of(new double[]{0, 0}, new double[]{1, 0}, new double[]{1, 1}));
        source.setClipPercentileLow(2.0);
        source.setClipPercentileHigh(98.0);
        source.setExcludeOutliers(true);
        return source;
    }

    @Test
    void conversionCarriesTheZScoreFlagToTheReplacementGate() {
        PolygonGate source = zScoredNuclearMedianPolygon();
        RectangleGate replacement = new RectangleGate("CD3", "CD8", 0, 1, 0, 1);

        GateEditorPane.copySharedSettings(source, replacement);

        assertTrue(replacement.isThresholdIsZScore(),
                "a rectangle drawn on a z-scored scatter must be evaluated in z-score space");
    }

    @Test
    void conversionCarriesPerAxisCompartmentAndStatistic() {
        PolygonGate source = zScoredNuclearMedianPolygon();
        RectangleGate replacement = new RectangleGate("CD3", "CD8", 0, 1, 0, 1);

        GateEditorPane.copySharedSettings(source, replacement);

        assertEquals(Compartment.NUCLEAR, replacement.getCompartmentX());
        assertEquals(Statistic.MEDIAN, replacement.getStatisticX());
        assertEquals(Compartment.CYTOPLASMIC, replacement.getCompartmentY());
        assertEquals(Statistic.SUM, replacement.getStatisticY());
    }

    @Test
    void conversionStillCarriesClippingAndBranchMetadata() {
        PolygonGate source = zScoredNuclearMedianPolygon();
        source.getBranches().get(0).setName("blasts");
        source.getBranches().get(0).setColor(0x123456);
        source.getBranches().get(1).getChildren().add(new GateNode("CD19"));

        EllipseGate replacement = new EllipseGate("CD3", "CD8", 0, 0, 1, 1);
        GateEditorPane.copySharedSettings(source, replacement);

        assertEquals(2.0, replacement.getClipPercentileLow());
        assertEquals(98.0, replacement.getClipPercentileHigh());
        assertTrue(replacement.isExcludeOutliers());
        assertEquals("blasts", replacement.getBranches().get(0).getName());
        assertEquals(0x123456, replacement.getBranches().get(0).getColor());
        assertEquals(1, replacement.getBranches().get(1).getChildren().size(),
                "child gates must survive the conversion");
    }

    @Test
    void rawSourceGateStaysRawAfterConversion() {
        PolygonGate source = new PolygonGate("CD3", "CD8");
        source.setThresholdIsZScore(false);

        RectangleGate replacement = new RectangleGate("CD3", "CD8", 0, 1, 0, 1);
        GateEditorPane.copySharedSettings(source, replacement);

        assertFalse(replacement.isThresholdIsZScore(),
                "conversion must preserve raw mode too, not blanket-set z-score");
    }

    @Test
    void thresholdToRegionConversionCarriesTheSingleAxisSelection() {
        GateNode source = new GateNode("CD3");
        source.setThresholdIsZScore(false);
        source.setCompartment(Compartment.NUCLEAR);
        source.setStatistic(Statistic.MEDIAN);

        PolygonGate replacement = new PolygonGate("CD3", "CD8");
        GateEditorPane.copySharedSettings(source, replacement);

        assertFalse(replacement.isThresholdIsZScore());
        assertEquals(Compartment.NUCLEAR, replacement.getCompartmentX(),
                "a threshold gate's single axis maps onto the region gate's X axis");
        assertEquals(Statistic.MEDIAN, replacement.getStatisticX());
    }
}
