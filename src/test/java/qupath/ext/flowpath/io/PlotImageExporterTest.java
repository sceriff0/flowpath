package qupath.ext.flowpath.io;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.testing.FxTestSupport;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

class PlotImageExporterTest {

    // The FX-toolkit idiom every real-control test in this suite follows (see
    // AnalysisPaneFxTest): started once per JVM, not skipped, since writePng() and the
    // canvas fixture below both need a live FX Application Thread to run on.
    @BeforeAll
    static void toolkit() throws InterruptedException {
        FxTestSupport.startToolkit();
    }

    @Test
    void svgIsWrittenAsUtf8WithAnXmlDeclaration() throws Exception {
        File f = File.createTempFile("flowpath-plot", ".svg");
        f.deleteOnExit();
        PlotImageExporter.writeSvg(f, "<svg xmlns=\"http://www.w3.org/2000/svg\"><text>µm CD3⁺</text></svg>");
        String read = Files.readString(f.toPath(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(read.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"), read);
        assertTrue(read.contains("µm CD3⁺"), "non-ASCII survives the round trip");
    }

    @Test
    void pngIsARealPngAtTheRequestedScale() throws Exception {
        // toolkit started in @BeforeAll, per the idiom in AnalysisPaneFxTest
        javafx.scene.canvas.Canvas canvas = FxTestSupport.onFx(() -> {
            javafx.scene.canvas.Canvas c = new javafx.scene.canvas.Canvas(100, 50);
            c.getGraphicsContext2D().setFill(javafx.scene.paint.Color.RED);
            c.getGraphicsContext2D().fillRect(0, 0, 100, 50);
            return c;
        });
        File f = File.createTempFile("flowpath-plot", ".png");
        f.deleteOnExit();
        FxTestSupport.onFxRun(() -> {
            try { PlotImageExporter.writePng(f, canvas, 2.0); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(f);
        assertNotNull(img, "ImageIO could not read what we wrote");
        assertEquals(200, img.getWidth(), "scale 2.0 doubles the pixels");
        assertEquals(100, img.getHeight());
    }
}
