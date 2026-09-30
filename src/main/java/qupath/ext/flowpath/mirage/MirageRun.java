package qupath.ext.flowpath.mirage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The patients of one MIRAGE run, found on disk: one per {@code <outdir>/<patient_id>/} holding
 * {@code pyramid/pyramid.ome.tiff}, paired with the cell GeoJSON under {@code geojson/export/}.
 * <p>
 * The folder is scanned rather than {@code csv/postprocessed.csv} read, although that file indexes
 * the same paths: it holds <em>absolute</em> paths, which point nowhere once a cluster run is
 * copied to a laptop, and MIRAGE does not write it at all at {@code cleanup_level=final}. The
 * layout itself is MIRAGE's {@code conf/modules.config} publishDir, which every cleanup level keeps.
 * <p>
 * Pure: reads directory entries only, never a file's contents, and knows nothing of QuPath.
 *
 * @param folder   the folder scanned — the run's outdir, or one patient folder
 * @param patients every patient folder found, in natural order, whether importable or not
 */
public record MirageRun(Path folder, List<Patient> patients) {

    public static final String PYRAMID = "pyramid.ome.tiff";
    static final String CELLS = "cells.geojson";
    static final String CELLS_WHOLE_CELL = "cells_wholecell.geojson";

    /** Which of MIRAGE's two cell exports to import. Their measurements are identical. */
    public enum Geometry {
        /** {@code cells.geojson}: QuPath cell objects, each with its nucleus outline. */
        CELL_AND_NUCLEUS(CELLS, "Cell + nucleus outlines"),
        /** {@code cells_wholecell.geojson}: one whole-cell outline per cell, lighter on a large slide. */
        WHOLE_CELL(CELLS_WHOLE_CELL, "Whole-cell outline only");

        private final String fileName;
        private final String label;

        Geometry(String fileName, String label) {
            this.fileName = fileName;
            this.label = label;
        }

        public String fileName() { return fileName; }

        @Override public String toString() { return label; }
    }

    public enum Status {
        READY("Ready"),
        ALREADY_IN_PROJECT("Already in project"),
        NO_CELLS("No cells file");

        private final String label;

        Status(String label) { this.label = label; }

        @Override public String toString() { return label; }
    }

    /**
     * One patient folder.
     *
     * @param id      the folder name, which becomes the image name in the project
     * @param cells   the GeoJSON to import, or {@code null} when there is none
     * @param note    what the preview should say beyond the status, or {@code null}
     */
    public record Patient(String id, Path pyramid, Path cells, Status status, String note) {
        public Patient {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(pyramid, "pyramid");
            Objects.requireNonNull(status, "status");
            if ((status == Status.NO_CELLS) != (cells == null)) {
                throw new IllegalArgumentException("cells is null exactly when the status is NO_CELLS");
            }
        }
    }

    public MirageRun {
        patients = List.copyOf(patients);
    }

    /**
     * Where the project goes unless the user says otherwise: {@code qupath_project} in the run's
     * outdir — beside the patient folders, never inside one, so a one-patient run's project does
     * not land among that patient's MIRAGE outputs.
     */
    public Path suggestedProjectDir() {
        boolean onePatientFolder = patients.size() == 1
                && patients.get(0).pyramid().getParent().getParent().equals(folder);
        Path outdir = onePatientFolder && folder.getParent() != null ? folder.getParent() : folder;
        return outdir.resolve("qupath_project");
    }

    /** The patients an import would add, in order. */
    public List<Patient> ready() {
        return patients.stream().filter(p -> p.status() == Status.READY).toList();
    }

    /**
     * Scan {@code folder}: a patient folder itself is a one-patient run; otherwise each direct
     * sub-folder holding a pyramid is a patient.
     *
     * @param inProject image names already in the target project; those patients are not re-added
     * @throws IOException when {@code folder} is not a readable directory
     */
    public static MirageRun scan(Path folder, Geometry geometry, Set<String> inProject) throws IOException {
        Objects.requireNonNull(geometry, "geometry");
        if (!Files.isDirectory(folder)) throw new IOException("Not a folder: " + folder);
        List<Path> dirs = new ArrayList<>();
        if (isPatientFolder(folder)) {
            dirs.add(folder);
        } else {
            try (Stream<Path> children = Files.list(folder)) {
                children.filter(MirageRun::isPatientFolder).forEach(dirs::add);
            }
        }
        List<Patient> patients = dirs.stream()
                .sorted(Comparator.comparing((Path p) -> p.getFileName().toString(), NATURAL_ORDER))
                .map(dir -> patient(dir, geometry, inProject))
                .toList();
        return new MirageRun(folder, patients);
    }

    private static boolean isPatientFolder(Path dir) {
        return Files.isDirectory(dir) && Files.isRegularFile(dir.resolve("pyramid").resolve(PYRAMID));
    }

    private static Patient patient(Path dir, Geometry geometry, Set<String> inProject) {
        String id = dir.getFileName().toString();
        Path pyramid = dir.resolve("pyramid").resolve(PYRAMID);
        Path export = dir.resolve("geojson").resolve("export");
        Path chosen = export.resolve(geometry.fileName());
        Path cells = null;
        String note = null;
        if (Files.isRegularFile(chosen)) {
            cells = chosen;
        } else if (geometry == Geometry.WHOLE_CELL && Files.isRegularFile(export.resolve(CELLS))) {
            // MIRAGE writes the whole-cell file only with quantify_compartments on; without it,
            // cells.geojson is already whole-cell only, so it is the same choice by another name.
            cells = export.resolve(CELLS);
            note = "No " + CELLS_WHOLE_CELL + "; using " + CELLS;
        }
        Status status;
        if (cells == null) status = Status.NO_CELLS;
        else if (inProject.contains(id)) status = Status.ALREADY_IN_PROJECT;
        else status = Status.READY;
        return new Patient(id, pyramid, cells, status, note);
    }

    private static final Pattern CHUNK = Pattern.compile("\\d+|\\D+");

    /**
     * Case-insensitive, digit runs compared as numbers: {@code P2} before {@code P10}, the order
     * a person numbering patients means.
     */
    public static final Comparator<String> NATURAL_ORDER = (a, b) -> {
        List<String> ca = chunks(a);
        List<String> cb = chunks(b);
        for (int i = 0; i < Math.min(ca.size(), cb.size()); i++) {
            String x = ca.get(i);
            String y = cb.get(i);
            int c;
            if (Character.isDigit(x.charAt(0)) && Character.isDigit(y.charAt(0))) {
                // Compared as numbers of any length: strip leading zeros, then longer is larger.
                String tx = x.replaceFirst("^0+(?=.)", "");
                String ty = y.replaceFirst("^0+(?=.)", "");
                c = tx.length() != ty.length() ? Integer.compare(tx.length(), ty.length()) : tx.compareTo(ty);
            } else {
                c = x.compareToIgnoreCase(y);
            }
            if (c != 0) return c;
        }
        int c = Integer.compare(ca.size(), cb.size());
        return c != 0 ? c : a.compareTo(b);
    };

    private static List<String> chunks(String s) {
        List<String> out = new ArrayList<>();
        Matcher m = CHUNK.matcher(s);
        while (m.find()) out.add(m.group());
        return out;
    }
}
