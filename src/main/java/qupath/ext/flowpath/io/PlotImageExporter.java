package qupath.ext.flowpath.io;

import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.transform.Transform;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/**
 * Writes an Analysis plot's own {@link Canvas} out as an image — to an SVG or PNG file, or
 * straight to the system clipboard — for {@code AnalysisPane}'s "Export ▾" menu (Task 12).
 * <p>
 * <b>SVG is not rendered here.</b> {@code PlotCanvas.toSvg()} already produces a complete
 * document through the same {@code draw(PlotSurface, PlotTheme)} routine that paints the
 * screen — see that class's javadoc for why a second SVG-writing path would risk the export
 * silently disagreeing with what was on screen. {@link #writeSvg} only adds the XML
 * declaration a standalone {@code .svg} file needs and writes the result as UTF-8.
 * <p>
 * <b>PNG is written without {@code javafx.embed.swing}.</b> That module is not on this
 * project's classpath, and adding it would be a new dependency, so the usual
 * {@code SwingFXUtils.fromFXImage} shortcut is unavailable. {@link #render} takes a
 * {@link Canvas} snapshot into a {@link WritableImage} instead, and {@link #writePng} copies
 * that image's pixels through its own {@link PixelReader} into a {@code java.awt.image}
 * {@link BufferedImage} before handing it to {@link ImageIO}. {@code java.desktop} —
 * {@code BufferedImage} and {@code ImageIO}'s home — is part of the JDK, so this needs nothing
 * new in {@code build.gradle.kts}.
 * <p>
 * <b>{@code scale} is a snapshot parameter, never a layout change.</b> {@link #render} asks for
 * a {@link WritableImage} sized {@code width * scale} by {@code height * scale} and hands
 * {@link SnapshotParameters} a matching {@link Transform#scale}; it never touches the canvas's
 * own {@code width}/{@code height} properties, and never calls back into the canvas's paint
 * routine at a different size. {@code PlotCanvas}'s own javadoc records this as the invariant a
 * higher-resolution export must preserve — the canvas is asked to rasterise the pixels it has
 * already laid out at a higher pixel density, not to lay itself out again at a different size.
 */
public final class PlotImageExporter {

    private PlotImageExporter() {
        // static utility class
    }

    /**
     * Write {@code svg} to {@code file} as a standalone document: UTF-8, with the XML
     * declaration line every {@code .svg} file needs prepended before the {@code <svg>} root
     * that {@code PlotCanvas.toSvg()} already produced.
     */
    public static void writeSvg(File file, String svg) throws IOException {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            w.write(svg);
        }
    }

    /**
     * Write {@code canvas} to {@code file} as a PNG, rendered at {@code scale} — see the class
     * javadoc for why {@code scale} changes only the snapshot's pixel density, never the
     * canvas's own layout.
     */
    public static void writePng(File file, Canvas canvas, double scale) throws IOException {
        BufferedImage image = toBufferedImage(render(canvas, scale));
        if (!ImageIO.write(image, "png", file)) {
            // Every JRE ships a PNG writer, so reaching this means no ImageWriter for "png"
            // was found on THIS classpath -- worth its own message rather than a bare false a
            // caller would have to already know to go looking for.
            throw new IOException("No PNG writer available for " + file);
        }
    }

    /**
     * Snapshot {@code canvas} at {@code scale}: a {@link WritableImage} sized
     * {@code (width * scale, height * scale)}, rendered through a {@link SnapshotParameters}
     * whose {@link Transform} does the scaling. Must run on the JavaFX application thread, the
     * same requirement {@link Canvas#snapshot} itself carries.
     */
    public static WritableImage render(Canvas canvas, double scale) {
        SnapshotParameters params = new SnapshotParameters();
        params.setTransform(Transform.scale(scale, scale));
        int width = (int) Math.round(canvas.getWidth() * scale);
        int height = (int) Math.round(canvas.getHeight() * scale);
        return canvas.snapshot(params, new WritableImage(width, height));
    }

    /**
     * "Copy plot to clipboard" — the same {@link #render} snapshot every other export in this
     * class uses, put on the system clipboard as an image rather than written to a file.
     */
    public static void copyToClipboard(Canvas canvas, double scale) {
        ClipboardContent content = new ClipboardContent();
        content.putImage(render(canvas, scale));
        Clipboard.getSystemClipboard().setContent(content);
    }

    /**
     * Copy {@code image}'s pixels, one at a time, through its own {@link PixelReader} into a
     * {@code TYPE_INT_ARGB} {@link BufferedImage} — see the class javadoc for why this replaces
     * {@code SwingFXUtils.fromFXImage} rather than calling it.
     */
    private static BufferedImage toBufferedImage(WritableImage image) {
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        BufferedImage buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        PixelReader reader = image.getPixelReader();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                buffered.setRGB(x, y, reader.getArgb(x, y));
            }
        }
        return buffered;
    }
}
