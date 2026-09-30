package qupath.ext.flowpath.cohort;

import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Slides set aside from the cohort (spec 2026-09-30 section 5): stored on the project's image entries as
 * metadata, not in {@code flowpath.json} - exclusion is a fact about a slide, not about a gate tree,
 * and a tree field would force a format version. An excluded slide is never sampled, ranked or
 * reviewed; a batch run still gates it, uncorrected.
 */
public interface CohortExclusions {

    String KEY = "flowpath.cohort.excluded";

    Set<String> excluded();

    default boolean isExcluded(String slideId) {
        return excluded().contains(slideId);
    }

    void setExcluded(String slideId, boolean excluded) throws IOException;

    static CohortExclusions inMemory() {
        Set<String> ids = new LinkedHashSet<>();
        return new CohortExclusions() {
            @Override public Set<String> excluded() { return Set.copyOf(ids); }
            @Override public void setExcluded(String slideId, boolean excluded) {
                if (excluded) ids.add(slideId); else ids.remove(slideId);
            }
        };
    }

    /** The QuPath adapter: the only code that touches a {@link ProjectImageEntry}'s exclusion flag. */
    static CohortExclusions of(Project<?> project) {
        return new CohortExclusions() {
            @Override public Set<String> excluded() {
                Set<String> out = new LinkedHashSet<>();
                for (ProjectImageEntry<?> e : project.getImageList()) {
                    if ("true".equals(e.getMetadataValue(KEY))) out.add(e.getID());
                }
                return out;
            }

            @Override public void setExcluded(String slideId, boolean excluded) throws IOException {
                for (ProjectImageEntry<?> e : project.getImageList()) {
                    if (!e.getID().equals(slideId)) continue;
                    if (excluded) e.putMetadataValue(KEY, "true"); else e.removeMetadataValue(KEY);
                    project.syncChanges();
                    return;
                }
                throw new IOException("No image with id " + slideId + " in this project");
            }
        };
    }
}
