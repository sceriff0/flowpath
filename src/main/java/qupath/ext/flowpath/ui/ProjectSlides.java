package qupath.ext.flowpath.ui;

import qupath.ext.flowpath.batch.BatchSlide;
import qupath.ext.flowpath.batch.FlowPathBatch;
import qupath.ext.flowpath.cohort.CohortSession;
import qupath.ext.flowpath.cohort.SlideSource;
import qupath.lib.projects.Project;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code ProjectImageEntry} → the headless {@link SlideSource} / {@link BatchSlide} seams. The
 * adapters themselves live in {@link FlowPathBatch}, which a headless run uses too: one adapter each.
 */
final class ProjectSlides {

    private ProjectSlides() {}

    static List<CohortSession.SlideRef> refs(Project<BufferedImage> project) {
        return project.getImageList().stream().map(e -> new CohortSession.SlideRef(e.getID(), e.getImageName())).toList();
    }

    static List<SlideSource> sources(Project<BufferedImage> project) {
        return FlowPathBatch.slideSources(project);
    }

    static List<BatchSlide> batchSlides(Project<BufferedImage> project) {
        return FlowPathBatch.batchSlides(project);
    }

    /** {@code Project.getPath()} is the {@code project.qpproj} file; its folder holds {@code flowpath/}. */
    static Path projectDir(Project<?> project) {
        return project.getPath().getParent();
    }
}
