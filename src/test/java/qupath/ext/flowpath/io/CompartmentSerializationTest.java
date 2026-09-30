package qupath.ext.flowpath.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.flowpath.model.Compartment;
import qupath.ext.flowpath.model.GateNode;
import qupath.ext.flowpath.model.GateTree;
import qupath.ext.flowpath.model.QuadrantGate;
import qupath.ext.flowpath.model.Statistic;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** F5: per-channel compartment + statistic persist (v2), v1 files load with defaults. */
class CompartmentSerializationTest {

    @Test
    void thresholdCompartmentRoundTrip(@TempDir Path dir) throws IOException {
        GateNode g = new GateNode("CD3", 1.5);
        g.setCompartment(Compartment.NUCLEAR);
        g.setStatistic(Statistic.MEDIAN);
        GateTree tree = new GateTree();
        tree.addRoot(g);

        File f = dir.resolve("t.json").toFile();
        FlowPathSerializer.save(tree, f);
        GateNode loaded = FlowPathSerializer.load(f).getRoots().get(0);

        assertEquals(Compartment.NUCLEAR, loaded.getCompartment());
        assertEquals(Statistic.MEDIAN, loaded.getStatistic());
    }

    @Test
    void quadrantPerAxisRoundTrip(@TempDir Path dir) throws IOException {
        QuadrantGate q = new QuadrantGate("CD3", "CD8");
        q.setCompartmentX(Compartment.NUCLEAR);
        q.setStatisticX(Statistic.SUM);
        q.setCompartmentY(Compartment.CYTOPLASMIC);
        q.setStatisticY(Statistic.MEAN);
        GateTree tree = new GateTree();
        tree.addRoot(q);

        File f = dir.resolve("q.json").toFile();
        FlowPathSerializer.save(tree, f);
        QuadrantGate loaded = (QuadrantGate) FlowPathSerializer.load(f).getRoots().get(0);

        assertEquals(Compartment.NUCLEAR, loaded.getCompartmentX());
        assertEquals(Statistic.SUM, loaded.getStatisticX());
        assertEquals(Compartment.CYTOPLASMIC, loaded.getCompartmentY());
        assertEquals(Statistic.MEAN, loaded.getStatisticY());
    }

}
