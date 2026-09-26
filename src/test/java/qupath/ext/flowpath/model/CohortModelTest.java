package qupath.ext.flowpath.model;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.testing.GateTreeFixtures;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CohortModelTest {

    @Test
    void aNewGateCorrectsStainingByDefault() {
        assertTrue(new GateNode("CD3", 1.0).isCorrectStaining());
        assertTrue(new QuadrantGate("CD3", "CD8", 1, 2).isCorrectStaining());
        assertTrue(new PolygonGate("CD3", "CD8").isCorrectStaining());
    }

    @Test
    void gateValuesRoundTripEveryGateType() {
        PolygonGate poly = new PolygonGate("A", "B");
        poly.setVertices(List.of(new double[]{0, 0}, new double[]{2, 0}, new double[]{1, 3}));
        List<GateNode> gates = List.of(new GateNode("A", 4.0), new QuadrantGate("A", "B", 1, 2),
                new RectangleGate("A", "B", 1, 2, 3, 4), new EllipseGate("A", "B", 5, 6, 1, 2), poly);
        for (GateNode gate : gates) {
            GateValues read = GateValues.read(gate);
            GateNode copy = gate.deepCopy();
            GateValues shifted = read.map(v -> v + 10, v -> v + 20);
            shifted.writeTo(copy);
            assertTrue(GateValues.read(copy).matches(shifted), gate.getGateType());
            assertTrue(GateValues.read(gate).matches(read), "the source gate is untouched");
        }
        assertArrayEquals(new double[]{4, 6}, GateValues.read(new EllipseGate("A", "B", 5, 6, 1, 2)).axis(0));
        assertEquals(1, GateValues.read(new GateNode("A", 4.0)).axisCount());
    }

    @Test
    void writingValuesOfTheWrongShapeIsRefused() {
        GateValues threshold = GateValues.read(new GateNode("A", 4.0));
        QuadrantGate quadrant = new QuadrantGate("A", "B", 1, 2);
        assertFalse(threshold.fits(quadrant));
        assertThrows(IllegalArgumentException.class, () -> threshold.writeTo(quadrant));
    }

    @Test
    void matchesToleratesRoundingButNotARealChange() {
        GateValues a = GateValues.of(new double[]{412.0});
        assertTrue(a.matches(GateValues.of(new double[]{412.0 + 1e-10})));
        assertFalse(a.matches(GateValues.of(new double[]{412.5})));
        assertFalse(a.matches(GateValues.of(new double[]{412.0}, new double[]{1.0})));
    }

    /** Two roots on one channel: the settings are per node, so they never cross. */
    @Test
    void deepCopyCarriesSettingsIndependentlyForTwoSameChannelRoots() {
        GateTree tree = GateTreeFixtures.twoRootsOnCd3AndCd8(1.0, 2.0);
        GateNode a = tree.getRoots().get(0);
        GateNode b = new GateNode("CD3", 7.0);
        tree.addRoot(b);
        a.setSlideSetting("s1", new SlideSetting.Skip());
        b.setSlideSetting("s1", new SlideSetting.Manual(GateValues.of(new double[]{9.0})));
        b.setCorrectStaining(false);
        tree.setReferenceSlideId("ref");

        GateTree copy = tree.deepCopy();
        assertEquals("ref", copy.getReferenceSlideId());
        assertInstanceOf(SlideSetting.Skip.class, copy.getRoots().get(0).slideSetting("s1"));
        assertEquals(new SlideSetting.Manual(GateValues.of(new double[]{9.0})),
                copy.getRoots().get(2).slideSetting("s1"));
        assertFalse(copy.getRoots().get(2).isCorrectStaining());

        copy.getRoots().get(0).setSlideSetting("s1", null);
        assertNotNull(a.slideSetting("s1"), "the copy's map is its own");
    }

    @Test
    void settingsSurviveADragAndDrop() {
        GateTree tree = GateTreeFixtures.twoRootsOnCd3AndCd8(1.0, 2.0);
        GateNode cd8 = tree.getRoots().get(1);
        cd8.setSlideSetting("s1", new SlideSetting.Reviewed(GateValues.of(new double[]{2.5})));
        assertTrue(tree.move(cd8, tree.getRoots().get(0).getBranches().get(0)));
        GateNode moved = tree.getRoots().get(0).getBranches().get(0).getChildren().get(0);
        assertSame(cd8, moved);
        assertEquals(new SlideSetting.Reviewed(GateValues.of(new double[]{2.5})), moved.slideSetting("s1"));
    }
}
