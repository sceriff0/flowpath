package qupath.ext.flowpath.batch;

import qupath.lib.images.ImageData;

import java.awt.image.BufferedImage;

/** One project image as the batch run sees it. The QuPath adapter lives in {@code ui/ProjectSlides}. */
public interface BatchSlide {
    String id();
    String name();
    ImageData<BufferedImage> read() throws Exception;
    void save(ImageData<BufferedImage> data) throws Exception;
}
