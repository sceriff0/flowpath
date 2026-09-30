package qupath.ext.flowpath.cohort;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.GateTree;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CohortIdentityTest {

    private record Case(String name, Map<String, String> recorded, Map<String, String> project, boolean matches) {}

    @Test
    void whenATreeBelongsToAProject() {
        Map<String, String> project = Map.of("1", "a.tif", "2", "b.tif");
        List<Case> cases = List.of(
                new Case("nothing recorded (a legacy or fresh tree)", Map.of(), project, true),
                new Case("every recorded name agrees", Map.of("1", "a.tif", "2", "b.tif"), project, true),
                new Case("recorded ids the project lacks are no contradiction", Map.of("1", "a.tif", "9", "z.tif"), project, true),
                new Case("the same id names another image: another project", Map.of("1", "x.tif"), project, false),
                new Case("one disagreement is enough", Map.of("1", "a.tif", "2", "y.tif"), project, false),
                new Case("no project at all", Map.of("1", "a.tif"), Map.of(), true));
        for (Case c : cases) {
            GateTree tree = new GateTree();
            tree.setSlideNames(c.recorded());
            assertEquals(c.matches(), CohortIdentity.matches(tree, c.project()), c.name());
            assertEquals(c.matches() ? "1" : null, CohortIdentity.resolutionSlideId(tree, c.project(), "1"),
                    c.name() + ": the slide id the pane resolves for");
        }
    }

    @Test
    void theNamesTravelWithADeepCopy() {
        GateTree tree = new GateTree();
        tree.setSlideNames(Map.of("1", "x.tif"));
        assertEquals(Map.of("1", "x.tif"), tree.deepCopy().getSlideNames());
        assertFalse(CohortIdentity.matches(tree.deepCopy(), Map.of("1", "a.tif")));
    }
}
