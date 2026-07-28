package qupath.ext.flowpath.ui;

import javafx.scene.control.TreeItem;
import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.PhenotypeNode;
import qupath.ext.flowpath.model.PhenotypeTree;

import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PhenotypeTreePaneTest {

    private PhenotypeTree tree() {
        PhenotypeTree t = new PhenotypeTree();
        PhenotypeNode immune = new PhenotypeNode("Immune", null, Map.of(), 0x00A0FF, false);
        t.addRoot(immune); t.register(immune);
        return t;
    }

    @Test
    void buildsNamedRootsAndReservedGroups() {
        assumeTrue(FxTestSupport.toolkitAvailable(), "JavaFX toolkit unavailable (headless)");
        PhenotypeTreePane pane = FxTestSupport.onFx(() -> new PhenotypeTreePane(tree()));
        TreeItem<String> root = FxTestSupport.onFx(pane::getRoot);
        boolean hasImmune = root.getChildren().stream().anyMatch(i -> i.getValue().startsWith("Immune"));
        boolean hasUncertain = root.getChildren().stream().anyMatch(i -> i.getValue().startsWith("Uncertain"));
        boolean hasArtefact = root.getChildren().stream().anyMatch(i -> i.getValue().startsWith("Artefact"));
        assertTrue(hasImmune);
        assertTrue(hasUncertain);
        assertTrue(hasArtefact);
    }
}
