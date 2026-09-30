package qupath.ext.flowpath.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.flowpath.mirage.MirageRun;
import qupath.ext.flowpath.mirage.ProjectBuilder;
import qupath.lib.projects.Project;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;

class MirageImportCoordinatorTest {

    private static final class ManualExecutor implements Executor {
        final List<Runnable> queue = new ArrayList<>();
        @Override public void execute(Runnable command) { queue.add(command); }
        void runNext() { queue.remove(0).run(); }
        void runAll() { while (!queue.isEmpty()) runNext(); }
    }

    private static final class Host implements MirageImportCoordinator.Host {
        final List<String> events = new ArrayList<>();
        MirageImportCoordinator.Outcome outcome;
        @Override public void progress(int done, int total, String id) { events.add(done + "/" + total + " " + id); }
        @Override public void finished(MirageImportCoordinator.Outcome o) { outcome = o; events.add("finished"); }
        @Override public void failed(Throwable error) { events.add("failed " + error.getMessage()); }
    }

    /** Adds every patient with 10 cells, except those named in {@code failing}. */
    private static MirageImportCoordinator.Importer importer(Path dir, List<String> added, String... failing) {
        return new MirageImportCoordinator.Importer() {
            @Override public Project<BufferedImage> open(Path projectDir) throws IOException {
                return ProjectBuilder.openOrCreate(dir.resolve("project"));
            }
            @Override public int add(Project<BufferedImage> project, MirageRun.Patient p) throws IOException {
                if (List.of(failing).contains(p.id())) throw new IOException(p.id() + " is broken");
                if (p.id().equals("OOM")) throw new OutOfMemoryError("heap");
                added.add(p.id());
                return 10;
            }
        };
    }

    private static List<MirageRun.Patient> patients(String... ids) {
        return java.util.Arrays.stream(ids).map(id -> new MirageRun.Patient(id, Path.of(id, "pyramid.ome.tiff"),
                Path.of(id, "cells.geojson"), MirageRun.Status.READY, null)).toList();
    }

    @Test
    void theProjectOpensThenOnePatientPerTaskAndOneImportAtATime(@TempDir Path dir) {
        ManualExecutor bg = new ManualExecutor(), fx = new ManualExecutor();
        Host host = new Host();
        List<String> added = new ArrayList<>();
        MirageImportCoordinator c = new MirageImportCoordinator(bg, fx, host);

        c.run(dir, patients("P1", "P2"), importer(dir, added));
        assertTrue(c.running());
        c.run(dir, patients("Z"), importer(dir, added));   // ignored
        bg.runNext(); fx.runAll();                            // project opened
        assertEquals(1, bg.queue.size(), "one patient per task");
        bg.runNext(); fx.runAll();
        assertEquals(1, bg.queue.size(), "the next patient is submitted only after the previous one landed");
        bg.runAll(); fx.runAll();

        assertEquals(List.of("P1", "P2"), added);
        assertEquals(List.of("1/2 P1", "2/2 P2", "finished"), host.events);
        assertFalse(c.running());
        assertEquals(List.of(new MirageImportCoordinator.Added("P1", 10), new MirageImportCoordinator.Added("P2", 10)),
                host.outcome.added());
        assertTrue(host.outcome.failures().isEmpty());
        assertFalse(host.outcome.cancelled());
        assertNotNull(host.outcome.project());
    }

    @Test
    void aFailedPatientIsRecordedAndTheImportGoesOn(@TempDir Path dir) {
        ManualExecutor bg = new ManualExecutor(), fx = new ManualExecutor();
        Host host = new Host();
        List<String> added = new ArrayList<>();
        MirageImportCoordinator c = new MirageImportCoordinator(bg, fx, host);
        c.run(dir, patients("P1", "P2", "OOM", "P3"), importer(dir, added, "P2"));
        while (!bg.queue.isEmpty() || !fx.queue.isEmpty()) { bg.runAll(); fx.runAll(); }

        assertEquals(List.of("P1", "P3"), added);
        assertEquals(List.of(new MirageImportCoordinator.Failure("P2", "P2 is broken"),
                new MirageImportCoordinator.Failure("OOM", "OutOfMemoryError: heap")), host.outcome.failures());
        assertFalse(c.running(), "an Error cannot leave the button stuck on Cancel");
    }

    @Test
    void cancelStopsBeforeTheNextPatientAndKeepsWhatWasAdded(@TempDir Path dir) {
        ManualExecutor bg = new ManualExecutor(), fx = new ManualExecutor();
        Host host = new Host();
        List<String> added = new ArrayList<>();
        MirageImportCoordinator c = new MirageImportCoordinator(bg, fx, host);
        c.run(dir, patients("P1", "P2", "P3"), importer(dir, added));
        bg.runNext(); fx.runAll();
        bg.runNext();
        c.cancel();
        fx.runAll(); bg.runAll(); fx.runAll();

        assertEquals(List.of("P1"), added);
        assertTrue(host.outcome.cancelled());
        assertEquals(3, host.outcome.total());
        assertFalse(c.running());
    }

    @Test
    void aProjectThatCannotBeOpenedFailsTheImport(@TempDir Path dir) {
        ManualExecutor bg = new ManualExecutor(), fx = new ManualExecutor();
        Host host = new Host();
        MirageImportCoordinator c = new MirageImportCoordinator(bg, fx, host);
        c.run(dir, patients("P1"), new MirageImportCoordinator.Importer() {
            @Override public Project<BufferedImage> open(Path d) throws IOException { throw new IOException("not empty"); }
            @Override public int add(Project<BufferedImage> p, MirageRun.Patient patient) { return fail("never reached"); }
        });
        bg.runAll(); fx.runAll();
        assertEquals(List.of("failed not empty"), host.events);
        assertFalse(c.running());
    }

    @Test
    void closeLandsNothing(@TempDir Path dir) {
        ManualExecutor bg = new ManualExecutor(), fx = new ManualExecutor();
        Host host = new Host();
        MirageImportCoordinator c = new MirageImportCoordinator(bg, fx, host);
        c.run(dir, patients("P1", "P2"), importer(dir, new ArrayList<>()));
        c.close();
        bg.runAll(); fx.runAll(); bg.runAll(); fx.runAll();
        assertTrue(host.events.isEmpty());
    }

    @Test
    void startIsRefusedWithUnsavedChangesOrNothingToImport() {
        assertNotNull(MirageImportCoordinator.refusal(true, 3));
        assertTrue(MirageImportCoordinator.refusal(true, 3).contains("Save"));
        assertNotNull(MirageImportCoordinator.refusal(false, 0));
        assertNull(MirageImportCoordinator.refusal(false, 3));
    }

    @Test
    void theSummaryNamesWhatWasAddedSkippedAndWhyPatientsFailed() {
        var outcome = new MirageImportCoordinator.Outcome(null, List.of(new MirageImportCoordinator.Added("P1", 1200)),
                List.of(new MirageImportCoordinator.Failure("P2", "No image reader can open pyramid.ome.tiff")), 2, false);
        String s = MirageImportCoordinator.summary(outcome, 4);
        assertTrue(s.contains("Added 1 of 2"), s);
        assertTrue(s.contains("1,200 cells") || s.contains("1200 cells"), s);
        assertTrue(s.contains("P2: No image reader"), s);
        assertTrue(s.contains("4 skipped"), s);
    }
}
