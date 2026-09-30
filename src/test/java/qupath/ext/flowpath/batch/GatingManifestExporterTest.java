package qupath.ext.flowpath.batch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.flowpath.engine.TreeResolver;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.GateValues;
import qupath.ext.flowpath.model.GateWalk;
import qupath.ext.flowpath.model.QuadrantGate;
import qupath.ext.flowpath.model.SlideSetting;
import qupath.ext.flowpath.model.Statistic;
import qupath.ext.flowpath.model.cohort.Alignment;
import qupath.ext.flowpath.model.cohort.Landmarks;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GatingManifestExporterTest {

    static final Alignment SHIFT = Alignment.between(new Landmarks(1.0, 1.0, Double.NaN), new Landmarks(1.0, 1.3, Double.NaN));

    static GateTree tree() {
        GateTree tree = new GateTree();
        tree.setReferenceSlideId("ref");
        GateNode a = new GateNode("CD8", 400.0);
        GateNode b = new GateNode("CD8", 600.0);
        a.setStatistic(Statistic.MEAN);
        b.setStatistic(Statistic.MEAN);
        QuadrantGate q = new QuadrantGate("CD3", "CD4", 5, 6);
        q.setStatisticX(Statistic.MEAN);
        q.setStatisticY(Statistic.MEAN);
        b.getBranches().get(0).getChildren().add(q);
        tree.addRoot(a);
        tree.addRoot(b);
        return tree;
    }

    static BatchResult result(GateTree tree, String slideId, List<String> markers) {
        return new BatchResult(slideId, slideId + ".tif", 10, markers, null,
                TreeResolver.resolve(tree, slideId, (s, c) -> SHIFT), null,
                BatchResult.WriteBack.NOT_REQUESTED, null, null);
    }

    @Test
    void gateWalkIsTopDownWithEnabledRootIndices() {
        GateTree tree = tree();
        GateNode disabled = new GateNode("CD20", 1.0);
        disabled.setEnabled(false);
        tree.getRoots().add(0, disabled);
        List<GateWalk.Entry> walk = GateWalk.enabled(tree);
        assertEquals(List.of("CD8", "CD8", "CD8+/CD3 vs CD4"), walk.stream().map(GateWalk.Entry::gatePath).toList());
        assertEquals(List.of(0, 1, 1), walk.stream().map(GateWalk.Entry::rootIndex).toList());
        assertSame(tree.getRoots().get(2), walk.get(2).parentGate());
    }

    /** Review Focus 1. */
    @Test
    void sameChannelRootsAreSeparatedByRootIndex(@TempDir Path dir) throws Exception {
        GateTree tree = tree();
        GateNode a = tree.getRoots().get(0);
        GateNode b = tree.getRoots().get(1);
        a.setSlideSetting("s1", new SlideSetting.Skip());
        b.setSlideSetting("s1", new SlideSetting.Reviewed(GateValues.of(new double[]{SHIFT.apply(600.0)})));

        File file = dir.resolve(GatingManifestExporter.FILE).toFile();
        GatingManifestExporter.write(file, tree, List.of(result(tree, "s1", List.of("CD8", "CD3", "CD4")),
                BatchResult.failed("bad", "bad.tif", "no server")), GatingManifestExporter.Annotations.NONE);
        List<String> lines = Files.readAllLines(file.toPath());

        assertEquals(GatingManifestExporter.HEADER, lines.get(0));
        assertEquals("image_id,image_name,root_index,gate_path,axis,column,reference_value,applied_value,source,"
                + "ref_L1,ref_L2,slide_L1,slide_L2,review,flags", lines.get(0));
        assertEquals("s1,s1.tif,0,CD8,x,CD8,400.0,,skipped,,,,,ok,", lines.get(1), "a Skip is an answer");
        assertEquals("s1,s1.tif,1,CD8,x,CD8,600.0," + SHIFT.apply(600.0) + ",corrected,,,,,ok,", lines.get(2));
        assertTrue(lines.get(3).startsWith("s1,s1.tif,1,CD8+/CD3 vs CD4,x,CD3,5.0,"));
        assertTrue(lines.get(4).startsWith("s1,s1.tif,1,CD8+/CD3 vs CD4,y,CD4,6.0,"));
        assertEquals(5, lines.size(), "the failed slide writes no rows");
    }

    /**
     * Final review M4: the review column is {@code ReviewScorer.answered} — a Skip, a Manual or a
     * Reviewed still matching the applied value read ok; a Reviewed the value has moved past, or
     * no setting, reads blank.
     */
    @Test
    void theReviewColumnIsTheOneAnsweredRule(@TempDir Path dir) throws Exception {
        GateTree tree = tree();
        GateNode a = tree.getRoots().get(0);
        GateNode b = tree.getRoots().get(1);
        a.setSlideSetting("s1", new SlideSetting.Manual(GateValues.of(new double[]{420.0})));
        b.setSlideSetting("s1", new SlideSetting.Reviewed(GateValues.of(new double[]{600.0})));   // stale: now corrected
        a.setSlideSetting("s2", new SlideSetting.Reviewed(GateValues.of(new double[]{SHIFT.apply(400.0)})));

        File file = dir.resolve(GatingManifestExporter.FILE).toFile();
        GatingManifestExporter.write(file, tree, List.of(result(tree, "s1", List.of("CD8", "CD3", "CD4")),
                result(tree, "s2", List.of("CD8", "CD3", "CD4"))), GatingManifestExporter.Annotations.NONE);
        List<String> lines = Files.readAllLines(file.toPath());
        assertTrue(lines.get(1).startsWith("s1,s1.tif,0,CD8,x,CD8,400.0,420.0,manual,") && lines.get(1).endsWith(",ok,"),
                lines.get(1));
        assertTrue(lines.get(2).startsWith("s1,s1.tif,1,CD8,") && lines.get(2).endsWith(",,"), lines.get(2));
        assertTrue(lines.get(5).startsWith("s2,s2.tif,0,CD8,") && lines.get(5).endsWith(",ok,"), lines.get(5));
        assertTrue(lines.get(6).startsWith("s2,s2.tif,1,CD8,") && lines.get(6).endsWith(",,"), lines.get(6));
    }

    /** Review Focus 2. */
    @Test
    void aChannelTheSlideLacksHasABlankAppliedValue(@TempDir Path dir) throws Exception {
        GateTree tree = tree();
        File file = dir.resolve(GatingManifestExporter.FILE).toFile();
        GatingManifestExporter.write(file, tree, List.of(result(tree, "s2", List.of("CD8", "CD3"))),
                new GatingManifestExporter.Annotations() {
                    @Override public Landmarks reference(String column) { return new Landmarks(1.0, 1.25, Double.NaN); }
                    @Override public Landmarks slide(String slideId, String column) { return new Landmarks(1.0, 1.5, 4.5); }
                    @Override public String flags(String slideId, int rootIndex, String gatePath) { return "unusual-staining"; }
                });
        List<String> lines = Files.readAllLines(file.toPath());
        String yRow = lines.get(4);
        assertTrue(yRow.startsWith("s2,s2.tif,1,CD8+/CD3 vs CD4,y,CD4,6.0,,corrected,"), yRow);
        assertTrue(lines.get(1).endsWith(",1.25,,1.5,4.5,,unusual-staining"), lines.get(1));
    }
}
