package qupath.ext.flowpath.ui;

import qupath.ext.flowpath.batch.BatchSlide;
import qupath.ext.flowpath.cohort.CohortSession;
import qupath.ext.flowpath.cohort.SlideSource;
import qupath.lib.images.ImageData;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;

/** {@code ProjectImageEntry} → the headless {@link SlideSource} / {@link BatchSlide} seams. */
final class ProjectSlides {

    private ProjectSlides() {}

    static List<CohortSession.SlideRef> refs(Project<BufferedImage> project) {
        return project.getImageList().stream().map(e -> new CohortSession.SlideRef(e.getID(), e.getImageName())).toList();
    }

    static List<SlideSource> sources(Project<BufferedImage> project) {
        return project.getImageList().stream().<SlideSource>map(e -> new SlideSource() {
            @Override public String id() { return e.getID(); }
            @Override public String name() { return e.getImageName(); }
            @Override public PathObjectHierarchy readHierarchy() throws Exception { return e.readHierarchy(); }
        }).toList();
    }

    static List<BatchSlide> batchSlides(Project<BufferedImage> project) {
        return project.getImageList().stream().<BatchSlide>map(e -> new BatchSlide() {
            @Override public String id() { return e.getID(); }
            @Override public String name() { return e.getImageName(); }
            @Override public ImageData<BufferedImage> read() throws Exception { return e.readImageData(); }
            @Override public void save(ImageData<BufferedImage> data) throws Exception { e.saveImageData(data); }
        }).toList();
    }

    /** {@code Project.getPath()} is the {@code project.qpproj} file; its folder holds {@code flowpath/}. */
    static Path projectDir(Project<?> project) {
        return project.getPath().getParent();
    }
}
