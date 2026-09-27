package qupath.ext.flowpath.cohort;

import qupath.lib.objects.hierarchy.PathObjectHierarchy;

/** One project image as the sampler sees it. The QuPath adapter lives in {@code ui/ProjectSlides}. */
public interface SlideSource {
    String id();
    String name();
    PathObjectHierarchy readHierarchy() throws Exception;
}
