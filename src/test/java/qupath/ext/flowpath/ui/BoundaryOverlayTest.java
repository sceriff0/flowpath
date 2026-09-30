package qupath.ext.flowpath.ui;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.testing.Cells;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.regions.ImageRegion;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Overlays paint, never write: the review visuals must not touch the hierarchy. */
class BoundaryOverlayTest {

    @Test
    void itPaintsTheVeilAndOutlinesWithoutWritingAClassOrFiringAnEvent() {
        Cells cells = Cells.of(3).at(new double[]{20, 50, 80}, new double[]{20, 50, 80}).marker("CD3", 1.0);
        PathObjectHierarchy hierarchy = new PathObjectHierarchy();
        hierarchy.addObjects(cells.detections());
        AtomicInteger events = new AtomicInteger();
        hierarchy.addListener(e -> events.incrementAndGet());
        CellIndex index = cells.build();

        BoundaryOverlay overlay = new BoundaryOverlay();
        overlay.setCells(index, new boolean[]{true, false, true}, new int[]{0x00C800, 0x00C800, 0x808080});
        overlay.setVisible(true);
        assertEquals(2, overlay.cellCount());

        BufferedImage img = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        overlay.paintOverlay(g, ImageRegion.createInstance(0, 0, 100, 100, 0, 0), 1.0, null, true);
        g.dispose();

        assertNotEquals(0, img.getRGB(5, 5) >>> 24, "the veil darkens the tissue");
        int atCell = img.getRGB(20, 20) & 0xFFFFFF;
        int green = (atCell >> 8) & 0xFF;
        assertTrue(green > ((atCell >> 16) & 0xFF) && green > (atCell & 0xFF),
                "a point-ROI boundary cell is marked in its branch colour");
        for (PathObject o : cells.detections()) assertNull(o.getPathClass());
        assertEquals(0, events.get(), "no hierarchy event, so IngestCoordinator never re-reads");

        overlay.toggle();
        assertFalse(overlay.isVisible());
        BufferedImage hidden = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
        Graphics2D h = hidden.createGraphics();
        overlay.paintOverlay(h, ImageRegion.createInstance(0, 0, 100, 100, 0, 0), 1.0, null, true);
        h.dispose();
        assertEquals(0, hidden.getRGB(5, 5) >>> 24, "a hidden overlay paints nothing");
    }
}
