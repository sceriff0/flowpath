package qupath.ext.flowpath.cohort;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The review items of one gate (spec §6 "Reviewing by gate"), keyed by value — {@code (rootIndex,
 * gatePath)} — so a group survives rescoring onto fresh nodes and two same-channel roots are two
 * groups (CLAUDE.md "anything the user selects is keyed on a value").
 */
public record ReviewGroup(int rootIndex, String gatePath, List<ReviewItem> items) {

    public record Key(int rootIndex, String gatePath) {}

    public Key key() {
        return new Key(rootIndex, gatePath);
    }

    /** The flagged slides' ids, in item order. */
    public Set<String> slideIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (ReviewItem i : items) ids.add(i.key().slideId());
        return ids;
    }

    /** One group per gate, in first-seen order — top-down, because the scorer lists items so. */
    public static List<ReviewGroup> of(List<ReviewItem> items) {
        Map<Key, List<ReviewItem>> byKey = new LinkedHashMap<>();
        for (ReviewItem i : items) {
            byKey.computeIfAbsent(new Key(i.key().rootIndex(), i.key().gatePath()), k -> new ArrayList<>()).add(i);
        }
        List<ReviewGroup> out = new ArrayList<>();
        byKey.forEach((k, v) -> out.add(new ReviewGroup(k.rootIndex(), k.gatePath(), List.copyOf(v))));
        return out;
    }
}
