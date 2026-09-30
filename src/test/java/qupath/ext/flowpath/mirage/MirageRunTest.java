package qupath.ext.flowpath.mirage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static qupath.ext.flowpath.mirage.MirageRun.Geometry.CELL_AND_NUCLEUS;
import static qupath.ext.flowpath.mirage.MirageRun.Geometry.WHOLE_CELL;
import static qupath.ext.flowpath.mirage.MirageRun.Status.ALREADY_IN_PROJECT;
import static qupath.ext.flowpath.mirage.MirageRun.Status.NO_CELLS;
import static qupath.ext.flowpath.mirage.MirageRun.Status.READY;

/** The MIRAGE layout, file names as {@code mirage/conf/modules.config} publishes them. */
class MirageRunTest {

    /** A patient folder as MIRAGE publishes it; {@code cellsFiles} are created under geojson/export. */
    static Path patient(Path outdir, String id, String... cellsFiles) throws IOException {
        Path p = outdir.resolve(id);
        Files.createDirectories(p.resolve("pyramid"));
        Files.writeString(p.resolve("pyramid").resolve(MirageRun.PYRAMID), "");
        Files.createDirectories(p.resolve("geojson/export"));
        for (String f : cellsFiles) Files.writeString(p.resolve("geojson/export").resolve(f), "{}");
        return p;
    }

    static List<String> ids(MirageRun run) {
        return run.patients().stream().map(MirageRun.Patient::id).toList();
    }

    @Test
    void everyPatientFolderWithAPyramidIsFoundInNaturalOrder(@TempDir Path out) throws IOException {
        for (String id : List.of("P10", "P2", "P1")) patient(out, id, "cells.geojson", "cells_wholecell.geojson");
        Files.createDirectories(out.resolve("csv"));          // run-level folders are not patients
        Files.createDirectories(out.resolve("qc"));
        Files.createDirectories(out.resolve("P3/segmentation")); // no pyramid: not a patient

        MirageRun run = MirageRun.scan(out, CELL_AND_NUCLEUS, Set.of());

        assertEquals(List.of("P1", "P2", "P10"), ids(run));
        MirageRun.Patient p1 = run.patients().get(0);
        assertEquals(READY, p1.status());
        assertEquals(out.resolve("P1/pyramid/pyramid.ome.tiff"), p1.pyramid());
        assertEquals(out.resolve("P1/geojson/export/cells.geojson"), p1.cells());
        assertNull(p1.note());
    }

    @Test
    void wholeCellPicksTheWholeCellFile(@TempDir Path out) throws IOException {
        patient(out, "P1", "cells.geojson", "cells_wholecell.geojson");
        MirageRun.Patient p = MirageRun.scan(out, WHOLE_CELL, Set.of()).patients().get(0);
        assertEquals(out.resolve("P1/geojson/export/cells_wholecell.geojson"), p.cells());
        assertEquals(READY, p.status());
        assertNull(p.note());
    }

    /** quantify_compartments=false publishes cells.geojson only: import it, and say so. */
    @Test
    void wholeCellFallsBackToCellsGeojsonAndSaysSo(@TempDir Path out) throws IOException {
        patient(out, "P1", "cells.geojson");
        MirageRun.Patient p = MirageRun.scan(out, WHOLE_CELL, Set.of()).patients().get(0);
        assertEquals(READY, p.status());
        assertEquals(out.resolve("P1/geojson/export/cells.geojson"), p.cells());
        assertNotNull(p.note());
        assertTrue(p.note().contains("cells.geojson"), p.note());
    }

    @Test
    void aPatientWithNoCellsFileIsListedButNotReady(@TempDir Path out) throws IOException {
        patient(out, "P1");
        MirageRun.Patient p = MirageRun.scan(out, CELL_AND_NUCLEUS, Set.of()).patients().get(0);
        assertEquals(NO_CELLS, p.status());
        assertNull(p.cells());
    }

    @Test
    void patientsAlreadyInTheProjectAreSkipped(@TempDir Path out) throws IOException {
        patient(out, "P1", "cells.geojson");
        patient(out, "P2", "cells.geojson");
        MirageRun run = MirageRun.scan(out, CELL_AND_NUCLEUS, Set.of("P1"));
        assertEquals(ALREADY_IN_PROJECT, run.patients().get(0).status());
        assertEquals(READY, run.patients().get(1).status());
        assertEquals(List.of("P2"), run.ready().stream().map(MirageRun.Patient::id).toList());
    }

    /** Picking one patient's own folder is a one-patient run. */
    @Test
    void aPatientFolderItselfIsAOnePatientRun(@TempDir Path out) throws IOException {
        Path p1 = patient(out, "P1", "cells.geojson");
        patient(out, "P2", "cells.geojson");
        assertEquals(List.of("P1"), ids(MirageRun.scan(p1, CELL_AND_NUCLEUS, Set.of())));
    }

    @Test
    void aFolderWithoutMirageOutputFindsNothing(@TempDir Path out) throws IOException {
        Files.createDirectories(out.resolve("something"));
        assertTrue(MirageRun.scan(out, CELL_AND_NUCLEUS, Set.of()).patients().isEmpty());
    }

    @Test
    void aMissingFolderIsAnError(@TempDir Path out) {
        assertThrows(IOException.class, () -> MirageRun.scan(out.resolve("nope"), CELL_AND_NUCLEUS, Set.of()));
    }

    @Test
    void theProjectIsSuggestedBesideThePatients(@TempDir Path out) throws IOException {
        Path p1 = patient(out, "P1", "cells.geojson");
        assertEquals(out.resolve("qupath_project"), MirageRun.scan(out, CELL_AND_NUCLEUS, Set.of()).suggestedProjectDir());
        assertEquals(out.resolve("qupath_project"), MirageRun.scan(p1, CELL_AND_NUCLEUS, Set.of()).suggestedProjectDir(),
                "a one-patient run's project goes beside it, not inside the patient's MIRAGE folder");
    }

    @Test
    void naturalOrderComparesDigitRunsAsNumbers() {
        List<String> sorted = List.of("patient_10", "patient_2", "patient_1", "Patient_3").stream()
                .sorted(MirageRun.NATURAL_ORDER).toList();
        assertEquals(List.of("patient_1", "patient_2", "Patient_3", "patient_10"), sorted);
    }
}
