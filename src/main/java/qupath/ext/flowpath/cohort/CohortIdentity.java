package qupath.ext.flowpath.cohort;

import qupath.ext.flowpath.model.GateTree;

import java.util.Map;

/**
 * Whether a gate tree's per-slide state belongs to this project. QuPath's project entry ids are
 * a per-project counter — every project has an image "1" — so a slide setting or a reference id
 * carried into another project would silently name a different image there. The tree records the
 * image name each id stood for ({@link GateTree#getSlideNames()}); this is the one check of those
 * names against a project, shared by the live view and the batch run.
 */
public final class CohortIdentity {

    private CohortIdentity() {}

    /**
     * True when the tree recorded no names (nothing to contradict), or when every recorded id that
     * exists in the project carries the name it was recorded with. Ids the project lacks are not
     * a contradiction: a slide removed from a project leaves its settings behind harmlessly.
     *
     * @param projectNames the project's images, id → name
     */
    public static boolean matches(GateTree tree, Map<String, String> projectNames) {
        for (var recorded : tree.getSlideNames().entrySet()) {
            String name = projectNames.get(recorded.getKey());
            if (name != null && !name.equals(recorded.getValue())) return false;
        }
        return true;
    }

    /**
     * The slide id a tree is resolved for: {@code openSlideId} when the tree belongs to this
     * project, else null — every gate on its reference numbers, no slide setting honoured.
     */
    public static String resolutionSlideId(GateTree tree, Map<String, String> projectNames, String openSlideId) {
        return matches(tree, projectNames) ? openSlideId : null;
    }
}
