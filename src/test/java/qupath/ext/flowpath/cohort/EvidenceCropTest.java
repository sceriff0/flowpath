package qupath.ext.flowpath.cohort;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.GateValues;
import qupath.ext.flowpath.model.MarkerStats;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.model.cohort.Alignment;
import qupath.ext.flowpath.model.cohort.Landmarks;
import qupath.ext.flowpath.testing.Cells;
import qupath.lib.images.servers.WrappedBufferedImageServer;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class EvidenceCropTest {

    /** 600 × 600 RGB: green 200 on the left half, 0 on the right; red 100 everywhere. */
    static WrappedBufferedImageServer server() {
        BufferedImage img = new BufferedImage(600, 600, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 600; y++) for (int x = 0; x < 600; x++) img.setRGB(x, y, (100 << 16) | ((x < 300 ? 200 : 0) << 8));
        return new WrappedBufferedImageServer("crop-test", img);
    }

    static int green(int argb) { return (argb >> 8) & 0xff; }
    static int red(int argb) { return (argb >> 16) & 0xff; }

    @Test
    void theMarkerIsGreenOnItsPerSlideRangeAndBoundaryCellsAreOutlined() {
        var outline = new EvidenceCrop.Outline(ROIs.createRectangleROI(100, 100, 100, 100, ImagePlane.getDefaultPlane()), 0xFF0000);
        EvidenceCrop.Crop crop = EvidenceCrop.render(server(),
                new EvidenceCrop.Spec(300, 300, 512, "Green", 0, 200, List.of(outline)));
        assertTrue(crop.ok(), crop.error());
        assertEquals(512, crop.width());
        assertEquals(512, crop.height());
        int x0 = 300 - 256;                                        // the crop's level-0 origin
        assertEquals(255, green(crop.argb()[256 * 512 + (150 - x0)]), "left half at the top of the range");
        assertEquals(0, green(crop.argb()[256 * 512 + (450 - x0)]), "right half at the bottom");
        assertTrue(red(crop.argb()[(100 - x0) * 512 + (150 - x0)]) > 200, "the outline's top edge");
        assertEquals(0, red(crop.argb()[(150 - x0) * 512 + (150 - x0)]), "the outline is an outline, not a fill");
    }

    @Test
    void aMissingMarkerChannelIsAnErrorNotAnException() {
        EvidenceCrop.Crop crop = EvidenceCrop.render(server(), new EvidenceCrop.Spec(300, 300, 512, "CD99", 0, 1, List.of()));
        assertFalse(crop.ok());
        assertEquals("Channel CD99 is not in this image", crop.error());
    }

    /** Pixel-space split between the two peaks: negatives sit in tile (0, 0), positives in tile (2, 2). */
    static final double SPLIT = 100 * Math.sinh(2.5);

    /**
     * As {@code AlignmentModelTest.slide}, but with each cell placed by its value, so a band at the
     * negative peak and a band at the positive peak land in different 512 px tiles.
     */
    static SlideSample placedSlide(String id, long seed, double shift) {
        Random r = new Random(seed);
        double[] raw = new double[3000];
        for (int i = 0; i < raw.length; i++) {
            double mu = (r.nextDouble() < 0.3 ? 4.0 : 1.0) + shift;
            raw[i] = 100 * Math.sinh(mu + 0.3 * r.nextGaussian());
        }
        CellIndex index = Cells.of(raw.length).marker("CD8", raw)
                .at(i -> raw[i] > SPLIT ? 1500 : 10, i -> raw[i] > SPLIT ? 1500 : 10).build();
        boolean[] clean = Cells.allTrue(raw.length);
        return new SlideSample(id, id + ".tif", index, clean, MarkerStats.compute(index, clean), raw.length, "f-" + id);
    }

    static List<SlideSample> placedCohort() {
        List<SlideSample> out = new ArrayList<>();
        out.add(placedSlide("ref", 1, 0.0));
        out.add(placedSlide("s1", 2, 0.05));
        out.add(placedSlide("s2", 3, -0.05));
        out.add(placedSlide("s3", 4, 0.1));
        return out;
    }

    /** Two enabled roots on CD8: root 0 cuts on the negative peak, root 1 on the positive one. */
    static GateTree twoRootsOnCd8() {
        GateTree tree = new GateTree();
        tree.setReferenceSlideId("ref");
        for (double u : new double[]{1.0, 4.0}) {
            GateNode g = new GateNode("CD8", 100 * Math.sinh(u));
            g.setStatistic(Statistic.MEAN);
            tree.addRoot(g);
        }
        GateNode second = tree.getRoots().get(1);
        second.setPositiveColor(0x00C800);
        second.setNegativeColor(0x0000C8);
        return tree;
    }

    static ReviewItem itemFor(GateTree tree, int root) {
        GateNode gate = tree.getRoots().get(root);
        return new ReviewItem(new ReviewItem.Key("s3", root, "CD8"), "s3.tif", gate,
                List.of(ReviewItem.Flag.ON_PEAK), List.of("x"), GateValues.read(gate));
    }

    @Test
    void theSpecCentresOnTheItemsOwnGatesHotspotAndMapsTheReferenceLandmarks() {
        List<SlideSample> samples = placedCohort();
        GateTree tree = twoRootsOnCd8();
        AlignmentModel model = AlignmentModel.build("ref", samples, AlignmentModel.columnsOf(tree), AlignmentModel.Cache.empty());

        EvidenceCrop.Spec second = EvidenceCrop.spec(itemFor(tree, 1), tree, samples.get(3), samples.get(0), model,
                model::alignment, 512);
        assertNotNull(second);
        assertEquals(1280.0, second.centerX(), "root 1 cuts the positive peak: tile (2, 2)");
        assertEquals(1280.0, second.centerY());
        assertFalse(second.outlines().isEmpty());
        for (EvidenceCrop.Outline o : second.outlines()) {
            assertTrue(o.rgb() == 0x00C800 || o.rgb() == 0x0000C8, "outlined in root 1's own branch colours");
        }

        EvidenceCrop.Spec first = EvidenceCrop.spec(itemFor(tree, 0), tree, samples.get(3), samples.get(0), model,
                model::alignment, 512);
        assertNotNull(first);
        assertEquals(256.0, first.centerX(), "root 0, same channel, cuts the negative peak: tile (0, 0)");

        Landmarks ref = model.referenceLandmarks("CD8");
        Alignment a = model.alignment("s3", "CD8");
        assertEquals(a.apply(Landmarks.sinh(ref.l1(), ref.cofactor())), second.markerLo(), 1e-9);
        assertEquals(a.apply(Landmarks.sinh(ref.l2(), ref.cofactor())), second.markerHi(), 1e-9);
        assertEquals("CD8", second.markerChannel());
    }

    @Test
    void withCorrectionOffTheRangeIsTheReferenceLandmarksUnmapped() {
        List<SlideSample> samples = placedCohort();
        GateTree tree = twoRootsOnCd8();
        tree.getRoots().get(1).setCorrectStaining(false);
        AlignmentModel model = AlignmentModel.build("ref", samples, AlignmentModel.columnsOf(tree), AlignmentModel.Cache.empty());

        EvidenceCrop.Spec spec = EvidenceCrop.spec(itemFor(tree, 1), tree, samples.get(3), samples.get(0), model,
                model::alignment, 512);
        Landmarks ref = model.referenceLandmarks("CD8");
        assertEquals(Landmarks.sinh(ref.l1(), ref.cofactor()), spec.markerLo(), 1e-9,
                "the range is corrected exactly when the gate is (TreeResolver.correctionFor)");
    }

    @Test
    void aSampleWithNoBoundaryCellHasNoSpec() {
        List<SlideSample> samples = placedCohort();
        GateTree tree = twoRootsOnCd8();
        tree.getRoots().get(1).setThreshold(1e9);
        AlignmentModel model = AlignmentModel.build("ref", samples, AlignmentModel.columnsOf(tree), AlignmentModel.Cache.empty());
        assertNull(EvidenceCrop.spec(itemFor(tree, 1), tree, samples.get(3), samples.get(0), model, model::alignment, 512));
    }
}
