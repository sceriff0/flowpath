package qupath.ext.flowpath.ui;

import org.junit.jupiter.api.Test;
import qupath.ext.flowpath.cohort.SlideSample;
import qupath.ext.flowpath.engine.GatingEngine;
import qupath.ext.flowpath.model.CellIndex;
import qupath.ext.flowpath.model.QualityFilter;
import qupath.ext.flowpath.testing.Cells;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Final ruling I1: the open slide's resync ({@link GatingSession#derive}) and a cohort sample
 * ({@link SlideSample#scopedTo}) build their clean mask through the one helper, so on the same
 * cells, filter and annotations they cannot disagree about which cells are clean.
 */
class CleanMaskAgreementTest {

    @Test
    void theLiveSessionAndACohortSampleAgreeOnTheCleanCells() {
        CellIndex index = Cells.of(200).atGrid(1, 0).marker("CD3", i -> i).marker("CD8", i -> 2.0 * i)
                .area(i -> i % 3 == 0 ? 50 : 100).build();
        PathObject box = PathObjects.createAnnotationObject(
                ROIs.createRectangleROI(-0.5, -1, 120, 2, ImagePlane.getDefaultPlane()));
        QualityFilter filter = new QualityFilter();
        filter.setMinArea(60);

        for (QualityFilter f : new QualityFilter[]{null, filter}) {
            for (boolean roi : new boolean[]{false, true}) {
                GatingSession.Derived live = GatingSession.derive(
                        new GatingSession.DerivationInputs(index, f, roi, List.of(box)));
                boolean[] liveClean = combined(live, index.size());
                SlideSample sample = new SlideSample("s", "s.tif", index, null, null, index.size(), "f",
                        List.of(box), null).scopedTo(f, roi);
                assertArrayEquals(liveClean, sample.clean(), "filter " + (f != null) + ", roi " + roi);
            }
        }
    }

    /** The live session's combined mask, every cell when nothing filters (its null). */
    private static boolean[] combined(GatingSession.Derived d, int n) {
        boolean[] roi = d.regions() == null ? null : d.regions().included();
        boolean[] q = d.qualityMask();
        if (q != null && roi != null) return GatingEngine.combineMasks(q, roi);
        if (q != null) return q;
        if (roi != null) return roi;
        return Cells.allTrue(n);
    }
}
