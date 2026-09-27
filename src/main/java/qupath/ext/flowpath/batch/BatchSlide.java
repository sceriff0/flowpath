package qupath.ext.flowpath.batch;

import qupath.lib.images.ImageData;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;

import java.awt.image.BufferedImage;

/** One project image as the batch run sees it. The QuPath adapter is {@link FlowPathBatch#batchSlides}. */
public interface BatchSlide {
    String id();
    String name();
    ImageData<BufferedImage> read() throws Exception;
    void save(ImageData<BufferedImage> data) throws Exception;

    /**
     * The objects alone, without the image server: what a resumed slide's fingerprint and the
     * cohort sample need. A project entry reads it far more cheaply than {@link #read()}.
     */
    default PathObjectHierarchy readHierarchy() throws Exception {
        return read().getHierarchy();
    }
}
