package qupath.ext.flowpath.cohort;

import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Hand-picked negative peaks (UniFORM's landmark mode), stored on the project's image entries as
 * metadata {@code flowpath.cohort.peak.<columnKey>} = a RAW intensity, like
 * {@link CohortExclusions}: a fact about a slide, not about a gate tree. Raw, so the value survives
 * a change of log scale.
 */
public interface CohortPeaks {

    String PREFIX = "flowpath.cohort.peak.";

    /** slideId -&gt; columnKey -&gt; raw intensity; a slide with no peak is absent. */
    Map<String, Map<String, Double>> peaks();

    /** Set (or, with {@code null}, clear) one slide x column peak. */
    void setPeak(String slideId, String columnKey, Double raw) throws IOException;

    static CohortPeaks inMemory() {
        Map<String, Map<String, Double>> held = new LinkedHashMap<>();
        return new CohortPeaks() {
            @Override public Map<String, Map<String, Double>> peaks() {
                Map<String, Map<String, Double>> out = new LinkedHashMap<>();
                held.forEach((k, v) -> out.put(k, Map.copyOf(v)));
                return out;
            }
            @Override public void setPeak(String slideId, String columnKey, Double raw) {
                if (raw == null) {
                    Map<String, Double> m = held.get(slideId);
                    if (m == null) return;
                    m.remove(columnKey);
                    if (m.isEmpty()) held.remove(slideId);
                } else {
                    held.computeIfAbsent(slideId, k -> new HashMap<>()).put(columnKey, raw);
                }
            }
        };
    }

    /** The one reading of an entry's peak keys. */
    static Map<String, Double> read(ProjectImageEntry<?> entry) {
        return parse(entry.getMetadata());
    }

    /** The peak keys of a metadata map; a non-numeric or non-finite value is ignored. */
    static Map<String, Double> parse(Map<String, String> metadata) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : metadata.entrySet()) {
            if (!e.getKey().startsWith(PREFIX) || e.getKey().length() == PREFIX.length()) continue;
            try {
                double v = Double.parseDouble(e.getValue().trim());
                if (Double.isFinite(v)) out.put(e.getKey().substring(PREFIX.length()), v);
            } catch (RuntimeException ignored) {
                // not a number: not a peak
            }
        }
        return out;
    }

    /** The QuPath adapter: with {@link #read}, the only code touching an entry's peak metadata. */
    static CohortPeaks of(Project<?> project) {
        return new CohortPeaks() {
            @Override public Map<String, Map<String, Double>> peaks() {
                Map<String, Map<String, Double>> out = new LinkedHashMap<>();
                for (ProjectImageEntry<?> e : project.getImageList()) {
                    Map<String, Double> m = read(e);
                    if (!m.isEmpty()) out.put(e.getID(), m);
                }
                return out;
            }

            @Override public void setPeak(String slideId, String columnKey, Double raw) throws IOException {
                for (ProjectImageEntry<?> e : project.getImageList()) {
                    if (!e.getID().equals(slideId)) continue;
                    String key = PREFIX + columnKey;
                    write(new CohortExclusions.Flag() {
                        @Override public String get() { return e.getMetadataValue(key); }
                        @Override public void set(String value) {
                            if (value == null) e.removeMetadataValue(key); else e.putMetadataValue(key, value);
                        }
                    }, raw, project::syncChanges);
                    return;
                }
                throw new IOException("No image with id " + slideId + " in this project");
            }
        };
    }

    /** Set the value and save, all or nothing: a failed save restores the previous value. */
    static void write(CohortExclusions.Flag flag, Double raw, CohortExclusions.Sync sync) throws IOException {
        String previous = flag.get();
        flag.set(raw == null ? null : Double.toString(raw));
        try {
            sync.run();
        } catch (IOException | RuntimeException ex) {
            flag.set(previous);
            throw ex;
        }
    }
}
