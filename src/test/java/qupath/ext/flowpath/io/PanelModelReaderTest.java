package qupath.ext.flowpath.io;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.PhenotypeTree;

import java.io.StringReader;
import static org.junit.jupiter.api.Assertions.*;

class PanelModelReaderTest {

    private static final String JSON = """
        {
          "phenotypes": [
            {"name":"Immune","parent":null,"signature":{"CD45":1,"PanCK":0},"color":[0,160,255],"is_leaf":false},
            {"name":"T_cell","parent":"Immune","signature":{"CD45":1,"CD3":1},"color":[0,192,128],"is_leaf":false},
            {"name":"CD8_T","parent":"T_cell","signature":{"CD3":1,"CD8":1,"CD4":0},"color":[16,255,16],"is_leaf":true}
          ],
          "palette": {
            "Ambiguous":[150,150,150],"Conflict":[230,140,0],
            "Artefact":[220,50,50],"Unclassified":[120,120,120]
          },
          "constraint_table": [
            {"id":0,"markers":["CD3","CD20"],"kind":"never","rate":"never"},
            {"id":2,"markers":["CD4","CD8"],"kind":"enforce","rate":"rare"},
            {"id":5,"markers":["FoxP3","CD4"],"kind":"requires","rate":"requires"}
          ]
        }
        """;

    @Test
    void readBuildsTreeWithHierarchyAndColors() {
        PhenotypeTree t = PanelModelReader.read(new StringReader(JSON));
        assertEquals(1, t.getRoots().size());
        assertEquals("Immune", t.getRoots().get(0).getName());
        assertNotNull(t.findByName("CD8_T"));
        assertTrue(t.findByName("CD8_T").isLeaf());
        assertEquals(java.util.List.of("T_cell", "Immune"), t.ancestorsOf("CD8_T"));
        // color [16,255,16] -> packed 0x10FF10
        assertEquals(0x10FF10, t.findByName("CD8_T").getColor());
    }

    @Test
    void readStoresReservedPaletteAndConstraints() {
        PhenotypeTree t = PanelModelReader.read(new StringReader(JSON));
        assertEquals(0xE68C00, t.reservedColor("Conflict")); // [230,140,0]
        assertEquals("CD3 ⊥ CD20 (never)", t.constraintLabel(0));
        assertEquals("FoxP3 → CD4 (requires)", t.constraintLabel(5));
        assertEquals("rare", t.getConstraint(2).kind());
    }
}
