package qupath.ext.flowpath.ui;

import qupath.ext.flowpath.cohort.CohortSession;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The labels a slide-choice dialog offers, each unique: an image name, or {@code "name (id)"} when
 * two slides share that name, mapped back to the slide id by the label. Mapping a picked name back
 * with a first-match lookup would pick the wrong slide whenever names collide. Toolkit-free.
 */
final class SlideChoices {

    private SlideChoices() {}

    /** Label → slide id, in the given order. */
    static Map<String, String> labels(List<CohortSession.SlideRef> refs) {
        Map<String, Integer> counts = new HashMap<>();
        for (CohortSession.SlideRef r : refs) counts.merge(r.name(), 1, Integer::sum);
        Map<String, String> out = new LinkedHashMap<>();
        for (CohortSession.SlideRef r : refs) {
            String label = counts.get(r.name()) > 1 ? r.name() + " (" + r.id() + ")" : r.name();
            out.put(label, r.id());
        }
        return Collections.unmodifiableMap(out);
    }

    /** The label naming {@code slideId}, or null when it is not offered. */
    static String labelOf(Map<String, String> labels, String slideId) {
        if (slideId == null) return null;
        for (Map.Entry<String, String> e : labels.entrySet()) if (e.getValue().equals(slideId)) return e.getKey();
        return null;
    }
}
