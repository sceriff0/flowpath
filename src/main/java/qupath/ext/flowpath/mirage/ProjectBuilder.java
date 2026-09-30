package qupath.ext.flowpath.mirage;

import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerBuilder;
import qupath.lib.images.servers.ImageServerProvider;
import qupath.lib.images.servers.ImageServers;
import qupath.lib.io.PathIO;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectIO;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.projects.Projects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Puts MIRAGE patients into a QuPath project, one at a time: the pyramid as a Fluorescence image
 * named after the patient, its cells imported from the GeoJSON and <b>saved</b> into the image's
 * data file — cohort sampling and "Run on all slides" read saved data files, never the viewer.
 * <p>
 * A patient is added whole or not at all. Anything that fails after the entry exists (the image
 * will not open, the GeoJSON will not parse, it holds no cells, the save fails) removes the
 * entry again, so a project never holds an image without its cells — which cohort gating would
 * otherwise show as a red "no detections" slide with nothing to say why. The project file is
 * written after every patient, so an import stopped half-way keeps what it finished.
 * <p>
 * Not thread-safe; one caller at a time, off the FX thread. No JavaFX: the image opener and the
 * thumbnailer are injected, so a test runs on a real project on disk with in-memory images.
 */
public final class ProjectBuilder {

    private static final Logger logger = LoggerFactory.getLogger(ProjectBuilder.class);

    public static final String PROJECT_FILE = "project.qpproj";

    /** The server builder for a pyramid; production asks QuPath's installed image readers. */
    @FunctionalInterface
    public interface ImageOpener {
        ImageServerBuilder.ServerBuilder<BufferedImage> open(Path pyramid) throws IOException;
    }

    /** The project-browser thumbnail for an image, or {@code null} for none. */
    @FunctionalInterface
    public interface Thumbnailer {
        BufferedImage thumbnail(ImageServer<BufferedImage> server) throws IOException;
    }

    private final ImageOpener opener;
    private final Thumbnailer thumbnailer;

    public ProjectBuilder(ImageOpener opener, Thumbnailer thumbnailer) {
        this.opener = Objects.requireNonNull(opener, "opener");
        this.thumbnailer = Objects.requireNonNull(thumbnailer, "thumbnailer");
    }

    /**
     * QuPath's own image readers (Bio-Formats, OpenSlide…) and QuPath's own thumbnails. The
     * thumbnail class lives in the GUI module; it is reached only from this lambda, so a
     * headless caller that never asks for a thumbnail never loads it.
     */
    public static ProjectBuilder standard() {
        return new ProjectBuilder(ProjectBuilder::openWithInstalledReaders,
                qupath.lib.gui.commands.ProjectCommands::getThumbnailRGB);
    }

    private static ImageServerBuilder.ServerBuilder<BufferedImage> openWithInstalledReaders(Path pyramid)
            throws IOException {
        var support = ImageServerProvider.getPreferredUriImageSupport(BufferedImage.class, pyramid.toUri().toString());
        if (support == null || support.getBuilders().isEmpty()) {
            throw new IOException("No image reader can open " + pyramid.getFileName());
        }
        return support.getBuilders().get(0);
    }

    /**
     * The project in {@code dir}: opened when {@code dir} holds {@value #PROJECT_FILE}, created
     * when {@code dir} is missing or empty, and refused otherwise — a new project written into a
     * folder of someone's other files would be impossible to tell apart from them afterwards.
     */
    public static Project<BufferedImage> openOrCreate(Path dir) throws IOException {
        // Loading a project's entries needs the JSON adapter ImageServers registers when it loads.
        // Inside QuPath it has long been loaded; in a headless JVM nothing else may have touched it.
        ImageServers.getServerBuilderFactory();
        Path file = dir.resolve(PROJECT_FILE);
        if (Files.isRegularFile(file)) return ProjectIO.loadProject(file.toFile(), BufferedImage.class);
        if (Files.isDirectory(dir)) {
            try (Stream<Path> entries = Files.list(dir)) {
                if (entries.findAny().isPresent()) {
                    throw new IOException("The folder " + dir + " is not empty and holds no QuPath project. "
                            + "Choose an empty folder, or one that already holds a project.");
                }
            }
        } else {
            Files.createDirectories(dir);
        }
        Project<BufferedImage> project = Projects.createProject(dir.toFile(), BufferedImage.class);
        project.syncChanges();
        return project;
    }

    /**
     * The image names of the project in {@code dir}, or none when it holds no project. Reads only:
     * the dialog's preview calls this each time the folder changes and must never create one.
     */
    public static Set<String> existingImageNames(Path dir) throws IOException {
        Path file = dir.resolve(PROJECT_FILE);
        if (!Files.isRegularFile(file)) return Set.of();
        ImageServers.getServerBuilderFactory();
        return imageNames(ProjectIO.loadProject(file.toFile(), BufferedImage.class));
    }

    /** The image names already in {@code project}: a patient is known by its name. */
    public static Set<String> imageNames(Project<BufferedImage> project) {
        return project.getImageList().stream().map(ProjectImageEntry::getImageName).collect(Collectors.toSet());
    }

    /**
     * Add {@code patient} to {@code project} and save both the image's data and the project.
     *
     * @return the number of cells imported
     * @throws IOException when the patient could not be added; the project is then as it was
     */
    public int addPatient(Project<BufferedImage> project, MirageRun.Patient patient) throws IOException {
        if (patient.cells() == null) throw new IOException(patient.id() + " has no cells file");
        ProjectImageEntry<BufferedImage> entry = project.addImage(opener.open(patient.pyramid()));
        try {
            entry.setImageName(patient.id());
            ImageData<BufferedImage> data = entry.readImageData();
            try {
                data.setImageType(ImageData.ImageType.FLUORESCENCE);
                List<PathObject> cells = PathIO.readObjects(patient.cells());
                if (cells.isEmpty()) {
                    throw new IOException(patient.cells().getFileName() + " holds no cells");
                }
                data.getHierarchy().addObjects(cells);
                entry.saveImageData(data);
                thumbnail(entry, data.getServer(), patient.id());
                project.syncChanges();
                return cells.size();
            } finally {
                closeQuietly(data.getServer());
            }
        } catch (IOException | RuntimeException e) {
            remove(project, entry, patient.id());
            throw e instanceof IOException io ? io : new IOException(describe(e), e);
        }
    }

    private void thumbnail(ProjectImageEntry<BufferedImage> entry, ImageServer<BufferedImage> server, String id) {
        try {
            BufferedImage thumb = thumbnailer.thumbnail(server);
            if (thumb != null) entry.setThumbnail(thumb);
        } catch (Exception e) {
            // A missing thumbnail is cosmetic: the image and its cells are what the import is for.
            logger.warn("No thumbnail for {}: {}", id, e.toString());
        }
    }

    private static void remove(Project<BufferedImage> project, ProjectImageEntry<BufferedImage> entry, String id) {
        try {
            project.removeImage(entry, true);
            project.syncChanges();
        } catch (Exception e) {
            logger.error("Could not remove the half-added image {} from the project", id, e);
        }
    }

    private static void closeQuietly(ImageServer<BufferedImage> server) {
        if (server == null) return;
        try {
            server.close();
        } catch (Exception e) {
            logger.debug("Closing an image server failed", e);
        }
    }

    private static String describe(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
