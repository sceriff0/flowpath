package qupath.ext.flowpath.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PhenotypeOutcomeTest {

    @Test
    void fromEvidenceMapsTheFiveTermTaxonomy() {
        assertEquals(PhenotypeOutcome.ARTEFACT, PhenotypeOutcome.fromEvidence(0, 2, false));
        assertEquals(PhenotypeOutcome.CONFLICT, PhenotypeOutcome.fromEvidence(0, 1, false));
        assertEquals(PhenotypeOutcome.AMBIGUOUS, PhenotypeOutcome.fromEvidence(3, 0, false));
        assertEquals(PhenotypeOutcome.PHENOTYPE, PhenotypeOutcome.fromEvidence(1, 0, true));
        assertEquals(PhenotypeOutcome.UNCLASSIFIED, PhenotypeOutcome.fromEvidence(1, 0, false));
    }

    @Test
    void uncertainIsAmbiguousUnionConflict() {
        assertTrue(PhenotypeOutcome.AMBIGUOUS.isUncertain());
        assertTrue(PhenotypeOutcome.CONFLICT.isUncertain());
        assertFalse(PhenotypeOutcome.ARTEFACT.isUncertain());
        assertFalse(PhenotypeOutcome.PHENOTYPE.isUncertain());
        assertFalse(PhenotypeOutcome.UNCLASSIFIED.isUncertain());
    }

    @Test
    void reservedNameNullForCommittedPhenotype() {
        assertNull(PhenotypeOutcome.PHENOTYPE.reservedName());
        assertEquals("Conflict", PhenotypeOutcome.CONFLICT.reservedName());
        assertEquals(Provenance.MANUAL, Provenance.fromCode(1));
        assertEquals(Provenance.MODEL, Provenance.fromCode(0));
    }
}
