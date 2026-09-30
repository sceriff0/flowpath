package qupath.ext.flowpath.testing;

import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerBuilder;
import qupath.lib.images.servers.WrappedBufferedImageServer;

import java.awt.image.BufferedImage;
import java.net.URI;

/**
 * A 10×10 blank image for any URI, so a test can build a real QuPath {@code Project} — entries,
 * {@code .qpdata} files, {@code readHierarchy}/{@code saveImageData} — without an image file or
 * an image-reading library. Registered for {@code ServiceLoader} in the test resources'
 * {@code META-INF/services}, which is how a project entry finds its builder again when it reads.
 */
public final class InMemoryImageServerBuilder implements ImageServerBuilder<BufferedImage> {

    public static ServerBuilder<BufferedImage> serverBuilder(URI uri) {
        return DefaultImageServerBuilder.createInstance(InMemoryImageServerBuilder.class, uri);
    }

    @Override
    public UriImageSupport<BufferedImage> checkImageSupport(URI uri, String... args) {
        return UriImageSupport.createInstance(InMemoryImageServerBuilder.class, 1f, serverBuilder(uri));
    }

    @Override
    public ImageServer<BufferedImage> buildServer(URI uri, String... args) {
        String path = uri.getPath();
        // Its own builder, so a saved data file can name how to rebuild it (QuPath warns otherwise).
        return new WrappedBufferedImageServer(path.substring(path.lastIndexOf('/') + 1),
                new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB)) {
            @Override protected ServerBuilder<BufferedImage> createServerBuilder() { return serverBuilder(uri); }
        };
    }

    @Override public String getName() { return "FlowPath test images"; }
    @Override public String getDescription() { return "Blank in-memory images for tests"; }
    @Override public Class<BufferedImage> getImageType() { return BufferedImage.class; }
}
