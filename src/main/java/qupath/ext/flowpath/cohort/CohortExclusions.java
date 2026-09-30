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

    /**
     * Whether {@code entry} is flagged excluded: the one reading of the metadata value, used by
     * {@link #of} and by a batch run's slides ({@code FlowPathBatch.batchSlides}).
     */
    static boolean flagged(ProjectImageEntry<?> entry) {
        return "true".equals(entry.getMetadataValue(KEY));
    }

    /**
     * The QuPath adapter: with {@link #flagged}, the only code that touches a
     * {@link ProjectImageEntry}'s exclusion flag.
     */
    static CohortExclusions of(Project<?> project) {
        return new CohortExclusions() {
            @Override public Set<String> excluded() {
                Set<String> out = new LinkedHashSet<>();
                for (ProjectImageEntry<?> e : project.getImageList()) {
                    if (flagged(e)) out.add(e.getID());
                }
                return out;
            }

            @Override public void setExcluded(String slideId, boolean excluded) throws IOException {
                for (ProjectImageEntry<?> e : project.getImageList()) {
                    if (!e.getID().equals(slideId)) continue;
                    write(new Flag() {
                        @Override public String get() { return e.getMetadataValue(KEY); }
                        @Override public void set(String value) {
                            if (value == null) e.removeMetadataValue(KEY); else e.putMetadataValue(KEY, value);
                        }
                    }, excluded, project::syncChanges);
                    return;
                }
                throw new IOException("No image with id " + slideId + " in this project");
            }
        };
    }

    /** One entry's exclusion value; {@code null} means absent. The seam {@link #write} is tested through. */
    interface Flag {
        String get();

        void set(String value);
    }

    /** Saving the project; may fail. */
    @FunctionalInterface
    interface Sync {
        void run() throws IOException;
    }

    /**
     * Set the flag and save, all or nothing: when the save fails the entry's previous value is
     * restored before the failure is rethrown, so a refused write (a read-only project) cannot
     * still take effect in memory on the next refresh.
     */
    static void write(Flag flag, boolean excluded, Sync sync) throws IOException {
        String previous = flag.get();
        flag.set(excluded ? "true" : null);
        try {
            sync.run();
        } catch (IOException | RuntimeException ex) {
            flag.set(previous);
            throw ex;
        }
    }
}
