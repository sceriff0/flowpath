package qupath.ext.flowpath.engine;

import qupath.ext.flowpath.model.PhenotypeNode;
import qupath.ext.flowpath.model.PhenotypeTree;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The lineage markers whose signature differs across a candidate set (spec §7.3 step 3). */
public final class DecisiveChannels {

    private DecisiveChannels() {}

    public static List<String> forCandidates(List<String> candidateNames, PhenotypeTree tree) {
        Set<String> markers = new LinkedHashSet<>();
        List<Map0> sigs = new ArrayList<>();
        for (String name : candidateNames) {
            PhenotypeNode node = tree.findByName(name);
            if (node == null) continue;
            sigs.add(new Map0(node.getSignature()));
            markers.addAll(node.getSignature().keySet());
        }
        List<String> decisive = new ArrayList<>();
        for (String m : markers) {
            Integer first = null;
            boolean differs = false;
            for (Map0 s : sigs) {
                Integer v = s.map().get(m); // null = marker absent in this signature
                if (first == null && !differs) first = v;
                else if (!java.util.Objects.equals(first, v)) differs = true;
            }
            if (differs) decisive.add(m);
        }
        decisive.sort(String::compareTo);
        return decisive;
    }

    private record Map0(java.util.Map<String, Integer> map) {}
}
