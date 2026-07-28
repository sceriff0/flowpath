package qupath.ext.flowpath.model;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ConstraintEntryTest {

    @Test
    void getConstraintReturnsEntryOrNull() {
        PhenotypeTree t = new PhenotypeTree();
        t.addConstraint(new ConstraintEntry(0, List.of("CD3", "CD20"), "never", 0.0));
        assertEquals("never", t.getConstraint(0).kind());
        assertNull(t.getConstraint(99));
    }

    @Test
    void constraintLabelNamesExclusivePairs() {
        PhenotypeTree t = new PhenotypeTree();
        t.addConstraint(new ConstraintEntry(2, List.of("CD4", "CD8"), "rare", 0.01));
        assertEquals("CD4 ⊥ CD8 (rare)", t.constraintLabel(2));
    }

    @Test
    void constraintLabelIsDashForUnknownOrMinusOne() {
        PhenotypeTree t = new PhenotypeTree();
        assertEquals("—", t.constraintLabel(-1));
        assertEquals("—", t.constraintLabel(7));
    }
}
