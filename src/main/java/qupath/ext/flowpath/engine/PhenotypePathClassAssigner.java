package qupath.ext.flowpath.engine;

import qupath.ext.flowpath.model.CellPhenotype;
import qupath.ext.flowpath.model.ColorUtils;
import qupath.ext.flowpath.model.PhenotypeNode;
import qupath.ext.flowpath.model.PhenotypeOutcome;
import qupath.ext.flowpath.model.PhenotypeTree;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Assigns exactly one committed {@link PathClass} per cell (spec §7.1, Decision 11). */
public final class PhenotypePathClassAssigner {

    private PhenotypePathClassAssigner() {}

    public static void assign(List<PathObject> objects, List<CellPhenotype> phenotypes, PhenotypeTree tree) {
        Map<String, PathClass> cache = new HashMap<>();
        int n = Math.min(objects.size(), phenotypes.size());
        for (int i = 0; i < n; i++) {
            CellPhenotype c = phenotypes.get(i);
            String name;
            int packed;
            if (c.getOutcome() == PhenotypeOutcome.PHENOTYPE) {
                name = c.getCommitted();
                PhenotypeNode node = tree.findByName(name);
                packed = node != null ? node.getColor() : 0x808080;
            } else {
                name = c.getOutcome().reservedName();
                packed = tree.reservedColor(name);
            }
            PathClass pc = cache.computeIfAbsent(name, k -> {
                int quColor = ColorUtils.toQuPathColor(packed);
                PathClass created = PathClass.fromString(k, quColor);
                created.setColor(quColor);
                return created;
            });
            objects.get(i).setPathClass(pc);
        }
    }
}
