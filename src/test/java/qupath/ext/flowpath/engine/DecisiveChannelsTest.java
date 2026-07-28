package qupath.ext.flowpath.engine;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.PhenotypeNode;
import qupath.ext.flowpath.model.PhenotypeTree;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DecisiveChannelsTest {

    private PhenotypeTree tree() {
        PhenotypeTree t = new PhenotypeTree();
        PhenotypeNode cd8 = new PhenotypeNode("CD8_T", "T_cell",
                Map.of("CD3", 1, "CD8", 1, "CD4", 0), 0, true);
        PhenotypeNode cd4 = new PhenotypeNode("CD4_T", "T_cell",
                Map.of("CD3", 1, "CD8", 0, "CD4", 1), 0, true);
        t.register(cd8); t.register(cd4);
        return t;
    }

    @Test
    void differingMarkersAreDecisiveSharedOnesAreNot() {
        List<String> decisive = DecisiveChannels.forCandidates(List.of("CD8_T", "CD4_T"), tree());
        assertEquals(List.of("CD4", "CD8"), decisive); // CD3 shared (1==1) → excluded
    }
}
